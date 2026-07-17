package com.hmdp.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消息生产者。
 * 它只负责把订单任务可靠地交给 RabbitMQ，不负责修改 MySQL。
 */
@Slf4j // log.info（）
@Component
public class SeckillOrderPublisher {

    @Resource
    private RabbitTemplate rabbitTemplate; // 操作 RabbitMQ

    /**
     * 发送订单消息并等待 RabbitMQ Broker 确认。
     * CorrelationData 使用订单 ID 关联消息和确认结果，方便排查某一笔订单。
     */
    public boolean publish(VoucherOrderMessage message) {
        CorrelationData correlationData = new CorrelationData(message.getOrderId().toString());
        // 订单全局唯一id当作CorrelationData的消息id，方便核对：订单id=消息id
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConstants.SECKILL_EXCHANGE,
                    RabbitMqConstants.SECKILL_ORDER_ROUTING_KEY,
                    message,
                    correlationData
            );

            // 最多等待 5 秒。ack=true 表示 RabbitMQ Broker 已接收该消息。
            CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                log.error("RabbitMQ 拒绝秒杀订单消息，orderId={}, reason={}",
                        message.getOrderId(), confirm.getReason());
                return false; // 属于「业务预期内的发送失败」
            }

            // mandatory=true 时，如果交换机找不到匹配队列，消息会被退回生产者。
            /* mandatory = false（默认）：交换机找不到任何匹配队列 → 直接丢弃这条消息，生产者完全无感知，不会回调、不会返回退回消息，消息直接丢失。
               mandatory = true（yaml文件里配置的模式）：
               交换机找不到匹配队列，不会丢消息，会把这条消息退回生产者，存入 correlationData.getReturnedMessage()，靠这个判断路由失败。
             */
            if (correlationData.getReturnedMessage() != null) {
                log.error("秒杀订单消息无法路由到队列，orderId={}, returned={}",
                        message.getOrderId(), correlationData.getReturnedMessage());
                return false;
            }

            log.info("秒杀订单消息已被 RabbitMQ 接收，orderId={}", message.getOrderId());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待 RabbitMQ 确认时线程被中断", e); // 属于「通信意外故障」
        } catch (Exception e) {
            throw new AmqpException("发送秒杀订单消息失败", e);  // 属于「通信意外故障」
        }
    }
}
