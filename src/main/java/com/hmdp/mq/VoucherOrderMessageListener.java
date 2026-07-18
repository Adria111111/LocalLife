package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.seckillOrderLockKey;

/**
 * 秒杀订单消息消费者，监听正常队列和死信队列，接受消息后调用业务方法。
 * HTTP 请求已经结束后，它在 RabbitMQ 工作线程中异步扣减数据库库存并保存订单。
 */
@Slf4j
@Component
public class VoucherOrderMessageListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 正常订单消费者。
     * 方法正常结束后 Spring 自动 ACK；抛异常时按配置重试，最终失败则进入死信队列。
     * 注意：消费者处理订单完成后的 ACK，不是发给生产者的，而是发给 RabbitMQ Broker。
     *      消息是否重新投递，也不是生产者决定的，而是 RabbitMQ Broker 决定的。当消费者连接或 Channel 关闭后，RabbitMQ 会把没有被确认的消息重新入队，并投递给原消费者恢复后的实例或其他消费者。
     */
    @RabbitListener(queues = RabbitMqConstants.SECKILL_ORDER_QUEUE)
    // 监听正常订单队列，只要出现：{orderId,userId,voucherId}，Spring：自动反序列化：JSON->VoucherOrderMessage，然后调用：handleSeckillOrder(message)
    public void handleSeckillOrder(VoucherOrderMessage message) {
        log.info("开始消费秒杀订单消息，orderId={}", message.getOrderId());

        // 锁的粒度是“用户 + 优惠券”，只阻止同一用户重复创建同一张券的订单。（业务唯一约束是什么，锁粒度就应该尽量与这个业务唯一约束保持一致）
        // 只锁优惠券id实际上还是一次只能处理一单，导致很多想买这个优惠券的人抢是这个优惠券id的一把锁，还是要排队等下单优惠券，失去高并发能力
        // 只锁用户id会导致用户下单另一张优惠券还得等这张下单完才能处理
        String lockKey = seckillOrderLockKey(message.getVoucherId(), message.getUserId());
        RLock lock = redissonClient.getLock(lockKey);

        /*
         * lock() 没有手写固定过期时间，Redisson 看门狗会在业务未完成时自动续期。
         * 如果同一订单消息被重复投递，后来的线程会等待前一个事务提交，再执行幂等检查。
         */
        lock.lock();
        /* lock.lock();没有指定锁的持有时间（leaseTime）。Redisson 会认为：
         * Redisson 会自动启用 WatchDog 机制，WatchDog 会在业务执行期间定期刷新锁的过期时间，防止因为事务执行时间过长导致锁提前释放；
         * 当业务完成后，在 finally 中调用 unlock()，此时 WatchDog 停止续期并立即释放锁。
         */
        try {
            // 这里调用的是 Spring 代理对象，方法返回时 @Transactional 事务已经提交，再进入 finally 解锁。
            voucherOrderService.createVoucherOrder(message);
            log.info("秒杀订单消息消费成功，orderId={}", message.getOrderId());
        } finally {
            // 只允许持有锁的当前线程解锁，避免误删其他线程后来获得的锁。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 死信消费者。
     * 一条消息连续失败后，说明数据库订单没有可靠创建，需要撤销 Redis 的预扣库存和购买标记。
     */
    @RabbitListener(queues = RabbitMqConstants.SECKILL_DEAD_LETTER_QUEUE)
    public void handleDeadLetter(VoucherOrderMessage message) {
        log.error("秒杀订单进入死信队列，开始补偿 Redis，orderId={}", message.getOrderId());
        voucherOrderService.rollbackRedisReservation(message);
    }
}
