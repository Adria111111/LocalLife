package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final String LOGIN_CODE_SEND_KEY = "login:code:send:";
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final int TTL_RAND_RANGE = 5;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type:list";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /** 秒杀券在 Redis 中的库存前缀。最终格式示例：seckill:stock:{1} */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    /** 秒杀券已经下单的用户集合前缀。最终格式示例：seckill:order:{1} */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";

    /** RabbitMQ 消费端的一人一单分布式锁前缀。 */
    public static final String SECKILL_ORDER_LOCK_KEY = "lock:seckill:order:";

    /**
     * 生成某张秒杀券的 Redis 库存 Key。
     * 大括号是 Redis Cluster 的 hash tag，可以保证库存 Key 和用户集合 Key 落在同一个槽位，
     * 这样 Lua 脚本将来在 Redis 集群中也能同时操作这两个 Key。
     */
    public static String seckillStockKey(Long voucherId) {
        return SECKILL_STOCK_KEY + "{" + voucherId + "}";
    }

    /** 生成某张秒杀券的“已下单用户集合”Key。 */
    public static String seckillOrderKey(Long voucherId) {
        return SECKILL_ORDER_KEY + "{" + voucherId + "}";
    }

    /**
     * 生成“一位用户购买一张优惠券”的锁 Key。
     * 锁的范围只覆盖同一用户和同一优惠券，不同用户之间仍然可以并行创建订单。
     */
    public static String seckillOrderLockKey(Long voucherId, Long userId) {
        return SECKILL_ORDER_LOCK_KEY + "{" + voucherId + "}:" + userId;
    }
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
