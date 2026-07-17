package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.mq.VoucherOrderMessage;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    /** RabbitMQ 消费者调用：在数据库事务中扣减库存并创建订单。 */
    void createVoucherOrder(VoucherOrderMessage message);

    /** RabbitMQ 发送失败或消息最终进入死信队列时，撤销 Redis 预扣。 */
    void rollbackRedisReservation(VoucherOrderMessage message);
}
