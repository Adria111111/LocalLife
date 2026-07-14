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

    @Override
    public Result queryById(Long id) {
        Shop shop;
        try {
            shop = queryWithMutex(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("查询失败");
        }

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
            String shopJson = stringRedisTemplate.opsForValue().get(cacheKey);

            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                break;
            }

            if (shopJson != null) {
                break;
            }

            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(50);
                continue;
            }

            try {
                shopJson = stringRedisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(shopJson)) {
                    shop = JSONUtil.toBean(shopJson, Shop.class);
                    break;
                }
                if (shopJson != null) {
                    break;
                }

                Shop dbShop = getById(id);
                Thread.sleep(200);

                if (dbShop == null) {
                    stringRedisTemplate.opsForValue().set(
                            cacheKey,
                            "",
                            CACHE_NULL_TTL + random.nextInt(TTL_RAND_RANGE + 1),
                            TimeUnit.MINUTES
                    );
                } else {
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
                unLock(lockKey);
            }
        }
        return shop;
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String geoKey = SHOP_GEO_KEY + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                geoKey,
                new Circle(new Point(x, y), new Distance(nearbyShopRadiusMeters)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(end)
        );

        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResults = results.getContent();
        List<Long> ids = new ArrayList<>(geoResults.size());
        Map<String, Distance> distanceMap = geoResults.stream()
                .collect(Collectors.toMap(
                        result -> result.getContent().getName(),
                        GeoResult::getDistance,
                        (oldValue, newValue) -> oldValue
                ));

        geoResults.stream()
                .skip(from)
                .forEach(result -> ids.add(Long.valueOf(result.getContent().getName())));

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();

        shops.forEach(shop -> {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        });
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
