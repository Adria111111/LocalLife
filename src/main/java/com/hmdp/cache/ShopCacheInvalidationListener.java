package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 是 Redis 的发布/订阅机制：发布者 Publisher->Redis 频道 Channel->订阅者 Subscriber
 * 这个类是 Redis Pub/Sub 消息的订阅者，它负责接收其他实例发布的缓存失效通知。
 * 接收 Redis 广播并删除当前应用实例中的商户一级缓存。
 * 这样一台服务器更新商户后，其他服务器也不会一直读取各自内存中的旧数据。
 */
@Slf4j
@Component
public class ShopCacheInvalidationListener implements MessageListener {

    @Resource(name = "shopLocalCache")
    private Cache<Long, Shop> shopLocalCache;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String shopIdText = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            Long shopId = Long.valueOf(shopIdText);
            shopLocalCache.invalidate(shopId);
            log.debug("收到商户缓存失效通知，已删除 Caffeine 缓存，shopId={}", shopId);
        } catch (NumberFormatException e) {
            // 非法消息不应中断 Redis 监听线程，只记录日志供后续排查。
            log.warn("收到无法识别的商户缓存失效通知，message={}", shopIdText);
        }
    }
}
