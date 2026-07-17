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
     */
    @RabbitListener(queues = RabbitMqConstants.SECKILL_ORDER_QUEUE)
    // 说明：监听：正常订单队列，只要出现：{orderId,userId,voucherId}，Spring：自动反序列化：JSON->VoucherOrderMessage，然后调用：handleSeckillOrder(message)
    public void handleSeckillOrder(VoucherOrderMessage message) {
        log.info("开始消费秒杀订单消息，orderId={}", message.getOrderId());

        // 锁的粒度是“用户 + 优惠券”，只阻止同一用户重复创建同一张券的订单。
        String lockKey = seckillOrderLockKey(message.getVoucherId(), message.getUserId());
        RLock lock = redissonClient.getLock(lockKey);

        /*
         * lock() 没有手写固定过期时间，Redisson 看门狗会在业务未完成时自动续期。
         * 如果同一订单消息被重复投递，后来的线程会等待前一个事务提交，再执行幂等检查。
         */
        lock.lock();
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
