package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.seckillOrderKey;
import static com.hmdp.utils.RedisConstants.seckillStockKey;

/**
 * <p>
 *  服务实现类
 * </p>
 */

@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //  根据店铺id查询全部优惠券，普通查询，给前端展示
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    //  添加秒杀优惠券，管理员后台发布券
    @Override
    @Transactional
    /* @Transactional 管控范围：只拦截数据库操作
     * 加在方法上后，执行流程：
     * 进入方法 → Spring 创建数据库事务连接；
     * 方法里所有 save() / update() / remove() / mapper.xxx() 生成的 SQL，全部存入事务缓冲区，不会立刻提交到数据库磁盘；
     * 分两种结局：
     * 方法正常跑完、无运行时异常：Spring 自动 commit，缓冲区所有 SQL 永久落地数据库；
     * 方法抛出受检 / 运行时异常：Spring 自动 rollback，缓冲区所有 SQL 全部撤销，数据库无任何新增 / 修改。
     */
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券，把前端填写的优惠券通用基础数据插入 voucher 主表
        save(voucher);
        // 封装并保存秒杀优惠券信息，插入 seckill_voucher 表
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        /*
         * 数据库事务提交成功以后，再把秒杀库存写入 Redis。
         * 为什么不能一开始就写 Redis：如果数据库保存失败并回滚，而 Redis 已经写入库存，
         * 用户就会看到一张数据库中不存在、Redis 中却可以秒杀的“幽灵优惠券”。
         *
         * Runnable 是 Java 内置函数式接口，专门用来「包装一段可执行代码」。
         * 接口只有一个抽象方法：void run()，里面写要执行的逻辑。只有调用 .run()，盒子里的代码才会真正跑。
         *
         * ()：对应 run() 的参数列表，run 无参数，所以括号空着；
         * ->：Lambda 固定箭头符号，分隔参数和方法体；
         * {}：就是原来 run() 方法大括号，里面放要执行的逻辑。
         */
        Runnable cacheSeckillStock = () -> {
            // 设置秒杀库存
            stringRedisTemplate.opsForValue().set(
                    seckillStockKey(voucher.getId()),
                    voucher.getStock().toString()
            );
            // 新发布的秒杀券还没有用户下单，删除旧集合可以确保名单从空状态开始，即清空该券已下单用户集合
            stringRedisTemplate.delete(seckillOrderKey(voucher.getId()));
        };

        // 事务同步判断 & 注册 afterCommit 回调
        /*
         * TransactionSynchronizationManager.isActualTransactionActive()：判断当前代码有没有正在运行的数据库事务，有返回 true，否则返回 false
         * TransactionSynchronizationManager.registerSynchronization()：注册事务同步器，传入一个实现 TransactionSynchronization 接口的对象
         * afterCommit()：只有事务完整提交成功之后，才执行这里面的代码，且只执行一次
         */
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    // 等这条事务全部 commit 成功了，再去跑 cacheSeckillStock.run()，执行Redis
                    // 出现异常事务回滚时：afterCommit 不会触发，Redis 代码完全不跑
                    cacheSeckillStock.run();
                }
            });
        } else {
            // 进入 else 的条件：当前没有激活的数据库事务，不存在 “数据库回滚导致 Redis 错乱” 的风险，那就直接立刻执行 Redis 初始化
            // 为了兼容「无事务调用」的场景
            cacheSeckillStock.run();
        }
    }
}
