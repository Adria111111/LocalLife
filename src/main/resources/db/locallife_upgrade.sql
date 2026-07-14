-- LocalLife POI upgrade script.
-- Run this after the original hmdp.sql when upgrading the teaching dataset.

ALTER TABLE `tb_shop`
    ADD COLUMN `phone` varchar(64) NULL DEFAULT NULL COMMENT '联系电话' AFTER `address`,
    ADD COLUMN `poi_id` varchar(64) NULL DEFAULT NULL COMMENT '外部POI id' AFTER `phone`,
    ADD COLUMN `poi_source` varchar(32) NULL DEFAULT NULL COMMENT 'POI数据来源' AFTER `poi_id`;

CREATE INDEX `idx_shop_type_location` ON `tb_shop` (`type_id`, `x`, `y`);
CREATE UNIQUE INDEX `uk_shop_poi_source_id` ON `tb_shop` (`poi_source`, `poi_id`);

-- Demo POI rows. Replace these with data imported from Amap POI in real use.
INSERT INTO `tb_shop`
(`name`, `type_id`, `images`, `area`, `address`, `phone`, `poi_id`, `poi_source`, `x`, `y`, `avg_price`, `sold`, `comments`, `score`, `open_hours`)
VALUES
('星巴克臻选上海人民广场店', 1, '/imgs/shops/coffee01.jpg', '人民广场', '上海市黄浦区南京西路人民广场商圈', '021-63220001', 'amap-sh-0001', 'amap', 121.473667, 31.230525, 42, 2618, 932, 46, '07:00-22:00'),
('瑞幸咖啡静安寺店', 1, '/imgs/shops/coffee02.jpg', '静安寺', '上海市静安区南京西路静安寺商圈', '021-62480002', 'amap-sh-0002', 'amap', 121.445352, 31.223112, 28, 3120, 1086, 45, '07:30-21:30'),
('Manner Coffee陆家嘴中心店', 1, '/imgs/shops/coffee03.jpg', '陆家嘴', '上海市浦东新区陆家嘴环路', '021-58880003', 'amap-sh-0003', 'amap', 121.502032, 31.238142, 31, 1986, 644, 47, '08:00-20:30'),
('海底捞上海南京东路店', 1, '/imgs/shops/food01.jpg', '南京东路', '上海市黄浦区南京东路步行街', '021-63510004', 'amap-sh-0004', 'amap', 121.482021, 31.238027, 116, 4188, 2051, 48, '10:00-03:00'),
('全季酒店上海外滩店', 3, '/imgs/shops/hotel01.jpg', '外滩', '上海市黄浦区中山东一路外滩', '021-63290005', 'amap-sh-0005', 'amap', 121.490317, 31.239701, 398, 1210, 506, 44, '00:00-24:00'),
('纯K上海淮海路店', 2, '/imgs/shops/ktv01.jpg', '淮海路', '上海市黄浦区淮海中路', '021-63860006', 'amap-sh-0006', 'amap', 121.468938, 31.222322, 88, 2631, 721, 43, '12:00-06:00');
