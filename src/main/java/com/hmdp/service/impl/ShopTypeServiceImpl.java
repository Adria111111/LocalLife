package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 *  查询店铺分类列表，用 Redis List 做缓存优化，减少数据库查询压力。
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // Redis 是键值数据库，key 永远是字符串；value 支持多种数据结构：String、List、Set、Hash（filed-value）、ZSet

        // 1.从redis查商铺类型缓存（用list实践）
        List<String> shopTypeListJson = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1); // 查整张表
        // 2.判断是否存在
        if (CollUtil.isNotEmpty(shopTypeListJson)) {
            // 3.存在，直接返回shopTypeList信息
            List<ShopType> shopTypeList = shopTypeListJson.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        // 4.不存在，查数据库，无筛选，查整张 shop_type 表全部数据
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();
        if (CollUtil.isEmpty(shopTypeList)){
            // 5.不存在，返回错误
            return Result.fail("暂无商铺分类数据");
        }
        // 6.存在，存入redis
        // 6.1 实体列表转字符串列表（每条ShopType单独一个json字符串）
        List<String> jsonStrList = shopTypeList.stream()
                .map(item -> JSONUtil.toJsonStr(item))
                .collect(java.util.stream.Collectors.toList());

        // 6.2 批量尾插存入Redis List
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, jsonStrList);
        // 单独设置key过期时间
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 7.返回结果
        return Result.ok(shopTypeList);
    }
}
