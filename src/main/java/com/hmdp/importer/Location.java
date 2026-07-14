package com.hmdp.importer;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 坐标封装实体。
 * 用来存放经纬度（lon 经度、lat 纬度），封装搜索用的中心点，要么用来解析 POI 里的 location 字符串，拆分出经度和纬度。
 */

@Data
@AllArgsConstructor
public class Location {

    private String name; // 搜索中心，例如：钟楼

    private double longitude;

    private double latitude;

}