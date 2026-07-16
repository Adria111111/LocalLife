package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private TransactionTemplate transactionTemplate; // 编程式事务，替代@Transactional注解

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

        try {
            /*
             * TransactionTemplate 中的代码处于同一个数据库事务：
             * 有异常抛出 -> 扣数据库库存和保存订单一起回滚；
             * 正常执行结束 -> 两个数据库操作一起提交。
             */
            Boolean databaseSuccess = transactionTemplate.execute(status ->
                    createVoucherOrder(voucherId, userId, orderId)
            );
            if (!Boolean.TRUE.equals(databaseSuccess)) {
                rollbackRedisReservation(stockKey, orderKey, userId);
                return Result.fail("数据库库存不足，请刷新后重试");
            }
        } catch (RuntimeException e) {
            // 数据库异常时必须撤销 Redis 预扣，否则用户没有订单，Redis 库存却永久少了一份。
            rollbackRedisReservation(stockKey, orderKey, userId);
            throw e;
        }

        // 9. Redis 与数据库都处理成功，返回订单 ID。
        return Result.ok(orderId);
    }

    /**
     * 真正修改数据库的方法。虽然 Redis 已经检查过一次，数据库仍保留 stock > 0 条件作为最终安全防线。
     */
    private Boolean createVoucherOrder(Long voucherId, Long userId, Long orderId) {
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1") // 执行
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // greater than，AND stock > 0
                .update();
        if (!success) {
            return false;
        }

        // 创建订单并保存到数据库。
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        return save(voucherOrder);
    }

    /** 使用补偿 Lua 原子地移除用户购买标记，并把 Redis 库存加回去。 */
    private void rollbackRedisReservation(String stockKey, String orderKey, Long userId) {
        stringRedisTemplate.execute(
                SECKILL_ROLLBACK_SCRIPT,
                Arrays.asList(stockKey, orderKey),
                userId.toString()
        );
    }
}
