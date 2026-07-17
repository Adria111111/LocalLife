package com.hmdp.mq;

/**
 * RabbitMQ 名称常量。
 * 把交换机、队列和路由键集中管理，可以避免生产者和消费者手写字符串时拼错名称。
 */
public final class RabbitMqConstants {

    private RabbitMqConstants() {
    // 私有构造方法，这个类不允许外部创建对象，只允许通过类名读取常量。
    }

    /** 秒杀订单正常交换机：生产者把订单消息发送到这里。 */
    public static final String SECKILL_EXCHANGE = "locallife.seckill.exchange";

    /** 秒杀订单正常队列：消费者从这里取得订单任务。 */
    public static final String SECKILL_ORDER_QUEUE = "locallife.seckill.order.queue";

    /** 正常订单路由键。DirectExchange 根据它把消息送进正常队列。 */
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    /** 死信交换机：多次消费失败的消息会被转发到这里。 */
    public static final String SECKILL_DEAD_LETTER_EXCHANGE = "locallife.seckill.dlx";

    /** 死信队列：保存最终处理失败的订单，避免消息静默丢失。 */
    public static final String SECKILL_DEAD_LETTER_QUEUE = "locallife.seckill.order.dlq";

    /** 死信路由键。 */
    public static final String SECKILL_DEAD_LETTER_ROUTING_KEY = "seckill.order.dead";
}
