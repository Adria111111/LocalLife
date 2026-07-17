package com.hmdp.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单消息，也就是请求线程交给 RabbitMQ 的“订单任务单”。
 * 消费者只需要这三个编号，就能在后台扣减数据库库存并创建订单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提前生成的全局唯一订单 ID，同时用于消费者幂等判断。 */
    private Long orderId;

    /** 当前登录用户 ID。消费者线程没有 HTTP 登录上下文，所以必须通过消息传递。 */
    private Long userId;

    /** 被秒杀的优惠券 ID。 */
    private Long voucherId;
}
