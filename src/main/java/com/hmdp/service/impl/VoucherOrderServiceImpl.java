package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.SeckillOrderPublisher;
import com.hmdp.mq.VoucherOrderMessage;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.seckillOrderKey;
import static com.hmdp.utils.RedisConstants.seckillStockKey;

/**
 * <p>
 * 服务实现类
 * </p>
 */

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** 秒杀资格检查与 Redis 预扣库存脚本。脚本只在应用启动时读取一次。 */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    /** 数据库下单失败时，用来撤销 Redis 预扣结果的补偿脚本。 */
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        // 加载resources下seckill.lua抢购脚本
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        // 加载resources下seckill_rollback.lua补偿脚本
        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker; // 生成全局唯一分布式订单ID
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderPublisher seckillOrderPublisher;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        // 4. 判断库存是否充足。
        // 这只是快速失败，因为 Java 中查询到的库存可能很快过期。真正的并发安全由后面的 stock > 0 条件保证。
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        // 5. 一人一单的业务检查。数据库唯一索引是最终防线，这里用于尽早给用户友好提示。
         /*
        数据库唯一索引 uk_user_voucher (user_id, voucher_id) 在高并发情况下强制保证数据不能重复
        Java 查询：第一层友好判断
        数据库索引：最后一道硬性防线
         */
        Long userId = UserHolder.getUser().getId();
        long orderCount = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count(); // 统计userid的用户买了voucherid的优惠券的数量
        if (orderCount > 0) {
            return Result.fail("不能重复购买同一张优惠券");
        }

        // 6. 准备 Lua 需要操作的两个 Redis Key。
        String stockKey = seckillStockKey(voucherId);
        String orderKey = seckillOrderKey(voucherId);

        // 7. 执行 Lua：检查 Redis 库存、检查重复购买、预扣库存、记录用户一次完成。
        Long redisResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, orderKey),
                userId.toString()
        );
        if (redisResult == null) {
            return Result.fail("Redis 执行秒杀脚本失败");
        }
        if (redisResult == 1L) {
            return Result.fail("库存不足");
        }
        if (redisResult == 2L) {
            return Result.fail("不能重复购买同一张优惠券");
        }
        if (redisResult == 3L) {
            return Result.fail("秒杀库存尚未初始化，请重新发布优惠券");
        }

        // 8. Redis 预扣成功后生成全局唯一订单 ID。下一阶段接入 RabbitMQ 时，这个 ID 会随消息一起发送。
        // 雪花算法生成的长数字，作为这一条 voucher_order 订单表的主键 id（表主键）
        long orderId = redisIdWorker.nextId("order");

        // 9. 把订单任务交给 RabbitMQ。请求线程不再同步等待 MySQL 扣库存和插入订单。
        VoucherOrderMessage message = new VoucherOrderMessage(orderId, userId, voucherId);
        try {
            boolean published = seckillOrderPublisher.publish(message);
            if (!published) {
                rollbackRedisReservation(message);
                return Result.fail("订单消息发送失败，秒杀资格已退回");
            }
        } catch (RuntimeException e) {
            // RabbitMQ 连接失败或确认失败时，撤销刚才的 Redis 预扣，用户稍后可以重试。
            rollbackRedisReservation(message);
            return Result.fail("订单服务繁忙，请稍后重试");
        }

        // 10. RabbitMQ 已确认接收消息，立即把订单 ID 返回前端；数据库订单由消费者后台创建。
        return Result.ok(orderId);
    }

    /**
     * RabbitMQ 消费者调用的数据库下单方法。
     * @Transactional 保证“数据库扣库存”和“保存订单”要么一起成功，要么一起回滚。
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrderMessage message) {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        // 消息可能因网络或重试被投递多次。订单 ID 已存在时直接返回，避免重复扣库存。
        if (getById(orderId) != null) {
            return;
        }

        // 一人一单的消费者幂等检查；数据库 uk_user_voucher 唯一索引仍是最终防线。
        long orderCount = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (orderCount > 0) {
            return;
        }

        // 虽然 Redis 已预扣，MySQL 仍保留 stock > 0 条件，防止缓存异常导致数据库库存为负。
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1") // 执行
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // greater than，AND stock > 0
                .update();
        if (!success) {
            // 抛出异常会让事务回滚，同时触发 RabbitMQ 重试；连续失败后消息进入死信队列。
            throw new IllegalStateException("数据库秒杀库存不足，voucherId=" + voucherId);
        }

        // 创建订单并保存到数据库。
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        if (!save(voucherOrder)) {
            throw new IllegalStateException("秒杀订单保存失败，orderId=" + orderId);
        }
    }

    /**
     * 使用补偿 Lua 原子地移除用户购买标记，并把 Redis 库存加回去。
     * 补偿前先查数据库：如果订单已经成功落库，说明可能只是 ACK 丢失，不能错误地补回库存。
     */
    @Override
    public void rollbackRedisReservation(VoucherOrderMessage message) {
        Long orderCount = query()
                .eq("user_id", message.getUserId())
                .eq("voucher_id", message.getVoucherId())
                .count();
        if (getById(message.getOrderId()) != null || orderCount > 0) {
            return;
        }

        String stockKey = seckillStockKey(message.getVoucherId());
        String orderKey = seckillOrderKey(message.getVoucherId());
        stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                Arrays.asList(stockKey, orderKey),
                message.getUserId().toString()
        );
    }
}
