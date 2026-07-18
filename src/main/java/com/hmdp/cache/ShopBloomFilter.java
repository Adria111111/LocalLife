package com.hmdp.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_BLOOM_FILTER_KEY;

/**
 * 商户 ID 布隆过滤器。
 * 它位于 Caffeine、Redis 和 MySQL 之前，用很小的内存拦截大量一定不存在的商户 ID。
 */
@Slf4j
@Component
public class ShopBloomFilter implements ApplicationRunner {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ShopMapper shopMapper;

    @Value("${shop.bloom-filter.enabled:true}")
    private boolean enabled;

    @Value("${shop.bloom-filter.expected-insertions:100000}")
    private long expectedInsertions;

    @Value("${shop.bloom-filter.false-probability:0.01}")
    private double falseProbability;

    /** 只有现有数据库 ID 全部加载完成后才设为 true，防止初始化期间产生假阴性。 */
    private volatile boolean ready;

    /**
     * Spring Boot 启动完成前，把数据库现有商户 ID 加入 Redis 中的布隆过滤器。
     * 多个应用实例重复执行是安全的，因为添加同一个 ID 是幂等的。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("商户布隆过滤器已关闭，查询将使用原有缓存链路");
            return;
        }

        try {
            RBloomFilter<Long> bloomFilter = bloomFilter();
            // 仅第一次创建时初始化容量和误判率；过滤器已存在时 tryInit 会安全地返回 false。
            bloomFilter.tryInit(expectedInsertions, falseProbability);

            // 只查询主键，不加载商户的名称、图片等无关字段，减少初始化时的数据库和内存开销。
            List<Object> shopIds = shopMapper.selectObjs(
                    new QueryWrapper<Shop>().select("id")
            );
            for (Object shopId : shopIds) {
                if (shopId instanceof Number) {
                    bloomFilter.add(((Number) shopId).longValue());
                }
            }

            // 必须最后设置 ready；此前任何查询都会自动降级，不会错误拦截真实商户。
            ready = true;
            log.info("商户布隆过滤器初始化完成，加载商户数量={}", shopIds.size());
        } catch (RuntimeException e) {
            ready = false;
            // 布隆过滤器属于性能保护层，初始化失败不能阻止整个应用提供基础查询服务。
            log.error("商户布隆过滤器初始化失败，已降级为原有缓存查询链路", e);
        }
    }

    /**
     * 判断商户 ID 是否一定不存在。
     * 只有过滤器准备完成且 contains=false 时才能直接拒绝；其他情况必须继续查缓存和数据库。
     */
    public boolean isDefinitelyAbsent(Long shopId) {
        if (!enabled || !ready || shopId == null) {
            return false;
        }
        try {
            return !bloomFilter().contains(shopId);
        } catch (RuntimeException e) {
            // Redis 临时故障时放行请求，保证不会把真实商户误判为不存在。
            log.warn("查询商户布隆过滤器失败，已放行到缓存链路，shopId={}", shopId);
            return false;
        }
    }

    /** 数据库新增商户成功后，把新 ID 加入布隆过滤器。 */
    public void add(Long shopId) {
        if (!enabled || !ready || shopId == null) {
            return;
        }
        try {
            bloomFilter().add(shopId);
        } catch (RuntimeException e) {
            // 数据库已经提交，过滤器同步失败只能记录告警，不能反向宣称数据库保存失败。
            log.error("新增商户 ID 写入布隆过滤器失败，shopId={}", shopId, e);
        }
    }

    private RBloomFilter<Long> bloomFilter() {
        return redissonClient.getBloomFilter(SHOP_BLOOM_FILTER_KEY);
    }
}
