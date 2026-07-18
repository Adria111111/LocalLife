package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.cache.ShopBloomFilter;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    /* Caffeine 是运行在当前 JVM 内存中的本地缓存，主要用于缓存热点商户，命中后不需要访问 Redis，因此可以降低网络开销和 Redis QPS。
     * 但它不能在多个应用实例间共享，所以更新时需要结合 Redis Pub/Sub 通知其他实例清理各自的本地缓存。
     *
     * Redis 互斥锁和 Redis 分布式锁并不是完全不同的概念：
     * 互斥锁强调同一时刻只有一个执行者，分布式锁强调它能够在多个 JVM 或应用实例之间生效。
     * Redisson 分布式锁则是 Redisson 对 Redis 分布式锁的成熟封装，它提供了可重入、原子解锁、线程标识和看门狗自动续期等能力。
     * 项目中缓存重建逻辑简单，使用手写 Redis 互斥锁（锁粒度：shopid，同一个商户缓存的重建过程必须互斥：商户 1 重建缓存时，不应该阻止商户 2 查询数据库。）；
     * 秒杀一人一单对正确性要求更高，因此使用 Redisson 分布式锁（锁粒度：用户id+voucherid，一人一单的请求必须互斥，有一个不同的订单必须并行）。
     *
     * Bloom Filter：一个利用多个Hash函数组成的大位数组
     * false一定准确：多次hash之后有一个是0说明：这个ID以前绝对没有加入过。
     * 又存在误判：多次hash之后的结果刚好都已经因为别人变成1了，所以这个ID被判为可能被加入过，但其实没有。
     */

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 商户详情的一级本地缓存；Redis 是多个应用实例共享的二级缓存。 */
    @Resource(name = "shopLocalCache")
    private Cache<Long, Shop> shopLocalCache;

    @Resource
    private ShopBloomFilter shopBloomFilter;

    @Value("${shop.nearby-radius-meters:15000}")
    private double nearbyShopRadiusMeters;

    private final Random random = new Random();

    /**
     * 根据 id 查询店铺 queryById () + queryWithMutex ()，高并发场景下解决经典缓存问题：
     * 缓存穿透：查不存在的数据，大量请求打数据库，用缓存空值/布隆过滤器
     * 缓存击穿：一个超高并发的热点 Key 刚好过期瞬间大量并发请求打数据库，用Redis分布式锁互斥/逻辑过期
     * 缓存雪崩：大量 key 同时过期，导致大量请求打数据库，用随机 TTL，打散过期时间
     */
    @Override
    public Result queryById(Long id) {
        /*
         * 1. 布隆过滤器返回 false 代表这个 ID 一定没有被加入，可以直接拦截恶意或无效 ID。
         * 返回 true 只表示“可能存在”，仍要继续查询缓存和数据库确认。
         */
        if (shopBloomFilter.isDefinitelyAbsent(id)) {
            return Result.fail("店铺不存在");
        }

        // 2. 再查 Caffeine 本地内存，不需要网络请求，速度比 Redis 更快。
        Shop localShop = shopLocalCache.getIfPresent(id);
        if (localShop != null) {
            return Result.ok(localShop);
        }

        // 3. 一级缓存未命中，再查询 Redis；Redis 未命中时才会通过互斥锁查询 MySQL。
        Shop shop;
        try {
            shop = queryWithMutex(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("查询失败");
        }
        // queryWithMutex方法里的shop一开始就初始化为null，只有查到了才是赋了值的shop
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 4. Redis 或 MySQL 查询成功后回填一级缓存，后续热点请求可以直接读取本机内存。
        shopLocalCache.put(id, shop);
        return Result.ok(shop);
    }

    /**
     * 新增商户后同步布隆过滤器。
     * 必须等数据库事务提交成功再添加 ID，避免数据库回滚但布隆过滤器仍误以为商户存在。
     */
    @Override
    @Transactional
    public boolean save(Shop shop) {
        boolean saved = super.save(shop);
        if (saved) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    shopBloomFilter.add(shop.getId());
                }
            });
        }
        return saved;
    }

    public Shop queryWithMutex(Long id) throws InterruptedException {
        String cacheKey = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;

        while (true) {
            String shopJson = stringRedisTemplate.opsForValue().get(cacheKey); // get(cacheKey)---根据 key 去 Redis 中查询对应的值

            // 分支 1：缓存有有效数据（命中正常店铺）
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                break;
            }
            // 分支 2：缓存是空字符串 ""（缓存穿透解决方案）
            // shopJson = null---Redis 完全没存过这条店铺记录，无任何标记。
            // shopJson = ""---Redis 存在这条店铺记录，但记录为空字符串，表示该店铺不存在。
            if (shopJson != null) {
                break;
            }
            // 分支 3：缓存中没有数据（shopJson == null），既没有店铺数据，也没有空缓存标记，需要抢锁查库。
            // 尝试获取锁
            boolean isLock = tryLock(lockKey);
            // 获取锁失败，线程休眠后重试
            if (!isLock) {
                Thread.sleep(50);
                continue;
            }
            // 获取锁成功，进行数据库查询
            try {
                // 双重检查缓存
                shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(shopJson)) {
                    shop = JSONUtil.toBean(shopJson, Shop.class);
                    break;
                }
                if (shopJson != null) {
                    break;
                }

                // 确实没缓存再查询数据库
                Shop dbShop = getById(id);
                Thread.sleep(200);
                // 数据库无店铺，存入空字符串""，设置短期过期时间，同时加上随机值 TTL。
                // 解决缓存穿透：后续请求命中空字符串，不再访问数据库；随机 TTL：防止大量不存在店铺 key 同时过期，引发雪崩
                if (dbShop == null) {
                    stringRedisTemplate.opsForValue().set(
                            cacheKey,
                            "",
                            CACHE_NULL_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                            TimeUnit.MINUTES
                    );
                } else {
                    // 数据库有店铺，存入缓存，设置较长过期时间，同时加上随机值 TTL。
                    stringRedisTemplate.opsForValue().set(
                            cacheKey,
                            JSONUtil.toJsonStr(dbShop),
                            CACHE_SHOP_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                            TimeUnit.MINUTES
                    );
                    shop = dbShop;
                }
                break;
            } finally {
                // 释放锁
                unLock(lockKey);
            }
        }
        return shop;
    }

    /**
     * queryShopByType：根据店铺分类 id查询店铺列表，支持两种模式：
     * 不传坐标 (x,y)：普通分页，直接查 MySQL 按分类分页；
     * 传入坐标 (x,y)：查询当前用户附近同类型店铺，基于 Redis GEO 地理位置实现，按距离由近到远排序。
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 未传入坐标 (x,y)：
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 传入坐标 (x,y)：
        // 计算分页参数，确定查询范围
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String geoKey = SHOP_GEO_KEY + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                geoKey,
                new Circle(new Point(x, y), new Distance(nearbyShopRadiusMeters)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance() // 返回每个店铺到用户的距离
                        .sortAscending() // 按距离由近到远排序
                        .limit(end) // 一次性查出前end条（比如第2页end=10，查前10条）
        );
        // 判断查询结果是否为空或查询结果数量是否小于分页参数from
        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 封装数据：距离 distanceMap + 当前页店铺 ids 集合
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = results.getContent();
        List<Long> ids = new ArrayList<>(geoResults.size());
        // distanceMap：建立映射关系 店铺id → 距离用户多远。
        // 方便后面从数据库查出来店铺后，需要把距离塞到 Shop 实体里返回前端，数据库没有实时计算的距离字段，只能从这个 Map 取。
        Map<String, Distance> distanceMap = geoResults.stream()
                .collect(Collectors.toMap(
                        result -> result.getContent().getName(),
                        GeoResult::getDistance,
                        (oldValue, newValue) -> oldValue
                ));
        // 获取当前页数据存ids里，前端只显示当前页
        geoResults.stream()
                .skip(from) // 跳过前 from 条
                .forEach(result -> ids.add(Long.valueOf(result.getContent().getName())));
        // 根据 ids 里的 id 批量查数据库，保证距离排序
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        // 给 Shop 实体赋值距离
        shops.forEach(shop -> {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        });
        // 返回数据
        return Result.ok(shops);
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);

        /*
         * 当前方法有 @Transactional，数据库要在方法结束后才真正提交。
         * 因此把两层缓存删除放到 afterCommit，避免事务尚未提交时其他请求查到旧数据并重新写入缓存。
         *
         * redis订阅发布机制：
         * 应用实例 A 更新商户->数据库事务提交成功->A 删除 Redis 缓存->A 删除自己的 Caffeine--
         * ->A 向 Redis Channel 发布商户 id->B、C 收到消息->B、C 删除各自 Caffeine 中的商户
         */
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 先删除所有应用实例共享的 Redis 二级缓存，下一次查询才能读取数据库新值。
                stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

                // 当前实例立即删除一级缓存，不必等待自己收到 Redis 广播。
                shopLocalCache.invalidate(id);

                // 通知其他应用实例删除各自的 Caffeine 一级缓存，避免多实例之间长时间读取旧数据。
                // Redis Pub/Sub 消息的发布者：向 SHOP_CACHE_INVALIDATE_CHANNEL 频道发布 shopId，通知其他实例删除缓存
                stringRedisTemplate.convertAndSend(CACHE_SHOP_INVALIDATE_CHANNEL, id.toString());
            }
        });
        return Result.ok();
    }
}
