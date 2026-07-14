package com.hmdp.importer;

import lombok.Data;

/**
 * 对应高德 pois 里面的 photos 数组。
 * 专门映射图片对象，封装图片的 url、title 字段，接收商家所有的图片地址，属于 POI 内部的子对象。
 */

@Data
public class Photo {

    /**
     * 图片地址
     */
    private String url;
}