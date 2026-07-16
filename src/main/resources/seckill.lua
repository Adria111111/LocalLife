-- 调用脚本时传入的参数：KEYS[1]、KEYS[2]、ARGV[1]
-- KEYS[1]：当前优惠券的库存 Key，例如 seckill:stock:{1}=1; string类型
-- KEYS[2]：当前优惠券的已下单用户集合 Key，例如 seckill:order:{1}=1,2,3; set类型
-- ARGV[1]：当前登录用户的 ID
-- 注意：1、Redis Cluster 规则：集群共16384个哈希槽，Key通过CRC16算法分配至对应主节点；
--      Lua脚本存在硬性限制：同一段脚本不能操作分属不同哈希槽/不同主节点的Key，否则抛出 CROSSSLOT 跨槽异常。
--      2、Hash Tag {voucherId} 作用：
--      Redis计算槽位时仅读取大括号{}内的内容，忽略括号前后字符；
--      库存Key seckill:stock:{voucherId}、用户集合Key seckill:order:{voucherId}
--      共用相同Tag，强制两个Key计算出同一哈希槽，存储在同一台主节点，保证Lua脚本可原子操作两个Key，规避集群跨槽报错。

-- 第一步：先确认库存已经被预热到 Redis。
-- 如果库存 Key 不存在，不能把它当成 0，因为这通常代表管理员还没有初始化秒杀库存，需要提前把库存写入 Redis。
local stock = redis.call('GET', KEYS[1])
if not stock then
    return 3
end

-- 第二步：库存必须大于 0。tonumber 用来把 Redis 返回的字符串转成数字。
if tonumber(stock) <= 0 then
    return 1
end

-- 第三步：SISMEMBER 判断当前用户是否已经存在于“已下单用户集合”中。
-- SISMEMBER key member 作用：判断 member 元素是否存在于 key 对应的 Set 集合中。
-- 返回值只有两种： 1：元素存在集合里  0：元素不存在集合里
-- 返回 2 表示已经购买过，不能重复下单。
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 第四步：资格检查全部通过后，扣减一份 Redis 库存，并记录当前用户。
-- 整段 Lua 由 Redis 一次执行完，中间不会被其他秒杀请求插队。
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
