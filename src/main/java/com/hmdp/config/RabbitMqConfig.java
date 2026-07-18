package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.mq.RabbitMqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑配置，创建交换机、队列和绑定关系
 * 拓扑可以理解为快递系统：
 * | 寄包裹的人 | Producer 生产者 |
 * | 包裹 | Message 消息（队列里面排的订单id和userid） |
 * | 分拣中心 | Exchange 交换机 |
 * | 快递地址 | Routing Key 路由键 |
 * | 等待取件的货架 | Queue 队列 |
 * | 取走并处理包裹的人 | Consumer 消费者 |
 * | 分拣说明书 | Binding 绑定关系（交换机-路由键-队列） |
 * | 快递公司总部（顺丰、京东物流） | Broker RabbitMQ服务器（RabbitMQ 服务器，负责管理消息生命周期，包括接收、路由、存储、投递、ACK 确认、重试、死信等） |
 * Producer 并不是直接把消息发送给 Queue，
 * 而是先发送到 Broker，由 Broker 内部的 Exchange
 * 根据 Routing Key 和 Binding 将消息路由到对应 Queue，
 * 最终由 Consumer 消费。
 */
@Configuration
public class RabbitMqConfig {

    /** 正常秒杀订单交换机，durable=true 表示 RabbitMQ 重启后仍然存在。 */
    @Bean // 创建并声明对象，可以重复使用
    public DirectExchange seckillExchange() {
        /* RabbitMQ 有几种常见交换机类型：Direct，Fanout，Topic，Headers
        Direct 的规则；消息的 Routing Key 必须和绑定使用的 Routing Key 完全匹配，消息才会进入对应队列。
         */
        return new DirectExchange(RabbitMqConstants.SECKILL_EXCHANGE, true, false);
        // durable(true) 表示：RabbitMQ 服务重启后，这个交换机定义仍然保留，不等于所有消息绝对不会丢。
    }

    /** 死信交换机，专门接收多次处理失败的订单消息。 */
    @Bean
    public DirectExchange seckillDeadLetterExchange() {
        return new DirectExchange(RabbitMqConstants.SECKILL_DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * 正常订单队列。
     * x-dead-letter-* 参数规定：消息最终消费失败时，转发到哪个死信交换机和路由键。
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(RabbitMqConstants.SECKILL_ORDER_QUEUE)
                .deadLetterExchange(RabbitMqConstants.SECKILL_DEAD_LETTER_EXCHANGE) // 配置死信交换机
                .deadLetterRoutingKey(RabbitMqConstants.SECKILL_DEAD_LETTER_ROUTING_KEY) // 配置死信路由键
                .build();
    }

    /** 死信队列不会让失败消息凭空消失，后续可以人工检查或自动补偿。 */
    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(RabbitMqConstants.SECKILL_DEAD_LETTER_QUEUE).build();
    }

    /** 正常交换机通过正常路由键绑定正常订单队列。 */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder.bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(RabbitMqConstants.SECKILL_ORDER_ROUTING_KEY);
    }

    /** 死信交换机通过死信路由键绑定死信队列。 */
    @Bean
    public Binding seckillDeadLetterBinding() {
        return BindingBuilder.bind(seckillDeadLetterQueue())
                .to(seckillDeadLetterExchange())
                .with(RabbitMqConstants.SECKILL_DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 使用 JSON 保存消息，RabbitMQ 管理页面里可以直接看懂内容，
     * 也避免使用 Java 原生序列化产生安全和跨版本兼容问题。
     */
    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
