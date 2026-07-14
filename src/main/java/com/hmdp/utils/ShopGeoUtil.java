package com.hmdp.utils;

import com.hmdp.mapper.ShopMapper;
import org.springframework.data.geo.*;

import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Shop;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 专门封装Redis GEO 地理位置相关所有工具方法。
 * 把重复写的 Redis Geo 代码抽离出来，统一管理，Service/Controller 直接调用。
 */

@Component
public class ShopGeoUtil {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    /**
     * 构建分类GEO key
     */
    public String getGeoKey(Long typeId) {
        return "shop:geo:" + typeId;
    }

    /**
     * 单个店铺写入GEO
     */
    public void addShopGeo(Long typeId, Long shopId, Double lon, Double lat) {
        if (lon == null || lat == null) return;
        String key = getGeoKey(typeId);
        GeoOperations<String, String> geoOps = stringRedisTemplate.opsForGeo();
        geoOps.add(key, new Point(lon, lat), shopId.toString());
    }

    /**
     * 删除店铺GEO坐标（更新/删除店铺时调用）
     */
    public void removeShopGeo(Long typeId, Long shopId) {
        String key = getGeoKey(typeId);
        stringRedisTemplate.opsForGeo().remove(key, shopId.toString());
    }

    /**
     * 根据用户坐标、分类、半径查询周边商户（返回店铺ID+距离）
     *
     * @param typeId  分类id
     * @param userLon 用户经度
     * @param userLat 用户纬度
     * @param radius  搜索半径 单位：米
     * @param limit   最多返回条数
     */
    public List<RedisGeoCommands.GeoLocation<String>> searchNearShop(Long typeId, Double userLon, Double userLat, Integer radius, Integer limit) {
        String key = getGeoKey(typeId);
        GeoOperations<String, String> geoOps = stringRedisTemplate.opsForGeo();
        // 配置参数：返回距离、按距离升序、限制条数
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance() // 返回每个店铺到你的距离
                .sortAscending() // 由近到远排序
                .limit(limit); // 最多返回limit条
        // 以用户坐标为中心，半径米查询
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoOps.radius(
                key,
                new Circle(new Point(userLon, userLat), new Distance(radius)),
                args // args 查询配置
        );

        // 转换为标准 GeoLocation 返回
        return results.getContent().stream()
                .map(GeoResult::getContent)
                .collect(Collectors.toList());
        // GeoLocation 全称：RedisGeoCommands.GeoLocation<String>，是 SpringDataRedis 封装的单个地理位置元素。
        // 存了两个核心信息：
        // name：存入 GEO 时给这个坐标绑定的标识（这里存的是 shopId 转的字符串）
        // point：该点的经纬度 Point(经度,纬度)
    }

    /**
     * 批量加载某一类所有店铺坐标进Redis GEO
     *
     * @param typeId 店铺分类id
     * 1  | 美食
     * 2  | KTV
     * 3  | 酒店
     * 4  | 美甲
     * 5  | 美发
     * 6  | 足疗
     * 7  | 健身房
     * 8  | 电影院
     * 9  | 咖啡馆
     * 10 | 奶茶店
     */
    public void loadShopGeoByTypeId(Long typeId) {
        // 1. 先清空该分类旧数据，防止重复
        clearTypeGeo(typeId);
        String key = getGeoKey(typeId);
        GeoOperations<String, String> geoOps = stringRedisTemplate.opsForGeo();

        // 2. 查询该分类全部店铺
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Shop::getTypeId, typeId);
        List<Shop> shopList = shopMapper.selectList(wrapper);

        // 3. 组装批量map
        Map<String, Point> pointMap = new HashMap<>();
        for (Shop shop : shopList) {
            Double lon = shop.getX();
            Double lat = shop.getY();
            if (lon == null || lat == null) {
                continue;
            }
            pointMap.put(shop.getId().toString(), new Point(lon, lat));
        }

        // 4. 批量写入redis geo
        if (!pointMap.isEmpty()) {
            geoOps.add(key, pointMap);
        }
    }

    /**
     * 清空某分类下所有GEO数据（重新加载前使用）
     */
    public void clearTypeGeo(Long typeId) {
        String key = getGeoKey(typeId);
        stringRedisTemplate.delete(key);
    }
}