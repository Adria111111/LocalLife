package com.hmdp.mq;

import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 秒杀订单消息消费者，监听正常队列和死信队列，接受消息后调用业务方法。
 * HTTP 请求已经结束后，它在 RabbitMQ 工作线程中异步扣减数据库库存并保存订单。
 */
@Slf4j
@Component
public class VoucherOrderMessageListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 正常订单消费者。
     * 方法正常结束后 Spring 自动 ACK；抛异常时按配置重试，最终失败则进入死信队列。
     */
    @RabbitListener(queues = RabbitMqConstants.SECKILL_ORDER_QUEUE)
    // 说明：监听：正常订单队列，只要出现：{orderId,userId,voucherId}，Spring：自动反序列化：JSON->VoucherOrderMessage，然后调用：handleSeckillOrder(message)
    public void handleSeckillOrder(VoucherOrderMessage message) {
        log.info("开始消费秒杀订单消息，orderId={}", message.getOrderId());
        voucherOrderService.createVoucherOrder(message);
        log.info("秒杀订单消息消费成功，orderId={}", message.getOrderId());
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
