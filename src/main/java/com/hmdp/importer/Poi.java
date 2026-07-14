package com.hmdp.importer;

import lombok.Data;
import java.util.List;

/**
 * 高德单条商铺的实体类。
 * 对应 JSON 数组里的每一家门店，包含店名、地址、电话、adcode 行政区划、biz_ext 营业时间评分、photos 图片集合等全部商铺信息，是解析后的单条原始商户数据。
 */

@Data
public class Poi {
    // poi是高德返回的数据，地图点位结构化数据对象

    /**
     * 高德POI唯一ID，每个商户的唯一标识
     */
    private String id;

    /**
     * 名称
     */
    private String name;

    /**
     * 地址
     */
    private String address;


    /**
     * 经纬度
     * 格式：108.94,34.26
     */
    private String location;

    /**
     * 电话
     */
    private Object tel;

    /**
     * 评分
     */
    private String rating;

    /**
     * 营业时间
     */
    private String opentime_today;

    /**
     * 图片
     */
    private List<Photo> photos;

}