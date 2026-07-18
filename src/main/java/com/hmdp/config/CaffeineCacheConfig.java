package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.hmdp.cache.ShopCacheInvalidationListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_INVALIDATE_CHANNEL;

/**
 * Caffeine 一级缓存配置。
 * Caffeine 数据保存在当前 Java 进程内，读取很快；Redis 继续作为多个应用实例共享的二级缓存。
 */
@Configuration
public class CaffeineCacheConfig {

    @Value("${shop.cache.caffeine-max-size:1000}")
    private long maximumSize;

    @Value("${shop.cache.caffeine-expire-minutes:5}")
    private long expireMinutes;

    /** 创建专门保存商户详情的本地缓存，Key 是商户 ID，Value 是商户对象。 */
    @Bean("shopLocalCache")
    public Cache<Long, Shop> shopLocalCache() {
        return Caffeine.newBuilder()
                // 最大条目数限制可以防止缓存持续增长并耗尽 JVM 内存。由 Caffeine 自动执行缓存淘汰。
                .maximumSize(maximumSize)
                // 写入一段时间后自动过期，使单机缓存能够定期回到 Redis 获取较新的数据。
                .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 订阅商户缓存失效频道。
     * 每个应用实例都有自己的 Caffeine，因此每个实例都要监听广播并删除自己的本地缓存。
     */
    @Bean
    public RedisMessageListenerContainer shopCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            ShopCacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(CACHE_SHOP_INVALIDATE_CHANNEL));
        return container;
    }
}
