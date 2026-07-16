-- 秒杀安全升级脚本
-- Run this once for an existing LocalLife database.
-- The query must return no rows before the unique index is added.
SELECT user_id, voucher_id, COUNT(*) AS order_count -- 取出 用户 ID、优惠券 ID、该组合出现的次数，给次数起别名 order_count
FROM tb_voucher_order -- 从 tb_voucher_order 表中查询数据
GROUP BY user_id, voucher_id -- 按用户 ID、优惠券 ID 组合
HAVING COUNT(*) > 1; -- 过滤出出现次数大于 1 的组

-- Final database guard for the "one user, one voucher order" rule.
ALTER TABLE tb_voucher_order -- 修改 tb_voucher_order 表
    ADD UNIQUE INDEX uk_user_voucher (user_id, voucher_id); -- 按用户 ID、优惠券 ID 添加唯一索引 uk_user_voucher
