-- 当Redis预扣库存成功，但后续MySQL下单失败时，需要撤销Redis的预扣操作
-- 先判断：用户是否真的在已下单集合，防止重复调用补偿脚本导致库存无限上涨
-- 只有用户确实存在于已下单集合中时才补回库存，避免重复补偿造成库存越补越多。
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    -- SREM：从已抢购用户集合移除当前用户ID，取消抢购标记
    redis.call('SREM', KEYS[2], ARGV[1])
    -- INCR：库存数值+1，归还刚才预扣掉的一份库存
    redis.call('INCR', KEYS[1])
    -- 返回 1 表示补偿成功，库存已补回
    return 1
end
-- 用户不在集合内，无需补偿，直接返回0
return 0
