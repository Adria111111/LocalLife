package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private final Random random = new Random();

    @Override
    public Result queryById(Long id) {
    /* 缓存穿透：查询数据库一定不存在的数据，请求绕过缓存直接打到底层数据库---设置缓存空值
       缓存雪崩：同一时间段大量的缓存key同时失效或redis服务宕机，导致大量请求打到数据库---为不同的key的TTL添加随机值
       缓存击穿（热点key问题）：一个被高并发访问并且缓存重建业务较复杂的key忽然失效，多个请求同时访问数据库---互斥锁or逻辑过期 */

 /*       // 给商铺的缓存添加超时剔除和主动更新（改数据就删缓存）策略
        // 普通业务 CRUD（商铺、商户）：先更新 MySQL → 删除 Redis 缓存 + TTL 兜底（主力）

        // 1. 从redis查商铺缓存（String结构）
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 2. 判断是否命中“正常缓存数据”
        // 只要不是 null 且不是 ""，说明是正常JSON数据
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中正常数据：直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // ================================
        // 4. 走到这里说明：
        // ① shopJson == null → Redis未命中（第一次查）
        // ② shopJson == "" (即空字符串)  → 命中空值缓存（表示之前查过DB，该数据不存在）
        // ================================

        // 4.1 判断是否命中“空值缓存”
        // 注意：这里只处理 Redis 有 key，值 ="" 的情况
        if (shopJson != null) {
            // 那shopJson就是""---空值缓存了
            return Result.fail("店铺不存在");
        }

        // ================================
        // 4.2 走到这里说明 Redis 未命中（shopJson == null）
        // 需要查数据库
        // ================================

        Shop shop = getById(id);

        if (shop == null) {
            // 4.2.1 DB也没有 → 解决缓存穿透
            // 写入空值缓存，避免后续请求继续打数据库
            stringRedisTemplate.opsForValue().set(
                    CACHE_SHOP_KEY + id,
                    "",
                    CACHE_NULL_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                    TimeUnit.MINUTES
            );
            return Result.fail("店铺不存在");
        }

        // 4.2.2 DB有数据 → 写入Redis缓存（正常缓存）
        stringRedisTemplate.opsForValue().set(
                CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                TimeUnit.MINUTES // 空缓存会过期消失，过期后 Redis 变回无 key (null) 状态
        );

        // 5. 返回数据库查询结果
        return Result.ok(shop);*/

        // 基于互斥锁解决缓存击穿
        Shop shop = null; // 引用类型局部变量，涉及 try-catch 多分支时，统一初始化为 null
        try {
            shop = queryWithMutex(id);
        } catch (InterruptedException e) {  // 捕获线程休眠被打断的异常（用sleep的时候就要考虑）
            Thread.currentThread().interrupt(); // 把当前线程的中断标记重新设为true，如果后面再遇到sleep也会直接中断，防止死机
            return Result.fail("查询失败");
        }

        // 这里判断内层方法返回的是不是null
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        String cacheKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;

        // 最终返回结果容器，引用局部变量必须初始化
        Shop shop = null;

        // 自旋死循环：直到拿到有效结果才退出，比递归好
        while (true) {
            // 1. 查Redis缓存
            String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

            // 1.1：缓存存在有效JSON数据
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                break; // 跳出while循环，直接return
            }

            // 1.2：key存在但值是空字符串""（缓存空值，库无店铺）
            if (shopJson != null) {
                break;
            }

            // 1.3 shopJson == null：key不存在，缓存失效/未初始化，必须抢锁重建
            // 尝试获取锁
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 抢锁失败，休眠，下一轮重新走完整流程
                Thread.sleep(50);
                continue; // 跳回到 while(true) 的循环条件开头
            }

            // 抢到锁，进入重建逻辑
            try {
                // 关键二次查缓存
                // 排队等锁的间隙，别的线程可能已经完成缓存重建，避免重复查库
                shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(shopJson)) {
                    shop = JSONUtil.toBean(shopJson, Shop.class);
                    break;
                }
                if (shopJson != null) {
                    break;
                }

                // 缓存依旧为空，真正执行查库
                Shop dbShop = getById(id);
                // 模拟重建的延时
                Thread.sleep(200);

                if (dbShop == null) {
                    // 数据库无店铺：存入空字符串短TTL，防缓存穿透；加随机时间防雪崩
                    stringRedisTemplate.opsForValue().set(
                            cacheKey,
                            "",
                            CACHE_NULL_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                            TimeUnit.MINUTES
                    );
                } else {
                    // 数据库查到店铺：序列化dbShop写入Redis，随机过期打散，防雪崩
                    stringRedisTemplate.opsForValue().set(
                            cacheKey,
                            JSONUtil.toJsonStr(dbShop),
                            CACHE_SHOP_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                            TimeUnit.MINUTES
                    );
                    shop = dbShop;
                }
                break; // 重建完成，跳出自旋循环
            } finally {
                // 无论正常、异常、空数据，锁强制释放
                unLock(lockKey);
            }
        }
        // 两种返回：shop实例(存在) / null(不存在)
        return shop;
    }


    // 尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS); // setnx
        return BooleanUtil.isTrue(flag); // 防止包装类拆箱 null 报错
    }

    // 释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional // 添加事务
    public Result update(Shop shop) {
        // 给商铺的缓存添加超时剔除和主动更新（改数据就删缓存）策略
        // 普通业务 CRUD（商铺、商户）：先更新 MySQL → 删除 Redis 缓存 + TTL 兜底（主力）

        Long id = shop.getId();
        if (id == null) {
            // 1.校验
            return Result.fail("店铺id不能为空");  // 这里抛异常的话就会回滚
        }
        // 2.更新数据库（数据库根据id的操作）
        updateById(shop);
        // 3.删除 Redis 缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id); // 主动更新
        return Result.ok();
    }
}
