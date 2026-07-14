package com.hmdp.vo;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 店铺视图对象
 * 后端 → 前端，包含页面需要的所有字段，甚至有数据库不存在的临时字段
 */

@Data
public class ShopVO {
    // 店铺基础ID
    private Long id;
    // 店铺名称
    private String name;
    // 店铺图片（多张逗号分隔）
    private String images;
    // 人均均价
    private BigDecimal avgPrice;
    // 营业时间
    private String openHours;
    // 用户到店铺距离（单位：米）
    private Double distance;
    // 评论数量（可选，页面展示）
    private Integer commentCount;
}