package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
        return Result.ok(shop);
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
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
