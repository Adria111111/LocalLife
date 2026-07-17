package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置。
 * Redisson 与 StringRedisTemplate 连接同一个 Redis，但 Redisson 额外提供了分布式锁等高级功能。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Value("${spring.redis.database:0}")
    private int redisDatabase;

    /** 创建全局唯一的 RedissonClient，并在 Spring 关闭时自动释放连接。 */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 当前项目使用单机 Redis；地址必须带 redis:// 协议前缀。
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setDatabase(redisDatabase);

        // 本地 Redis 通常没有密码；只有配置了密码时才传给 Redisson。
        if (StringUtils.hasText(redisPassword)) {
            serverConfig.setPassword(redisPassword);
        }

        return Redisson.create(config);
    }
}
