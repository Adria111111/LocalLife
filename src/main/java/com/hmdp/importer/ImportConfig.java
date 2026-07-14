package com.hmdp.importer;

/**
 * 导入功能的常量配置类。
 * 存放固定不变的参数：高德的 Key、搜索半径 radius、每页条数 offset，把硬编码的配置统一收拢在这里，方便修改。
 */

public class ImportConfig {
    /**
     * 查询半径（单位：米）
     */
    public static final int RADIUS = 5000;

    /**
     * 每页数量（高德最大25）
     */
    public static final int OFFSET = 25;
}
