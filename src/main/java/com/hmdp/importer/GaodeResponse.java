package com.hmdp.importer;

import lombok.Data;
import java.util.List;

/**
 * 高德接口最外层的响应实体类。
 * 用来映射高德返回的整包 JSON，对应里面的 status、infocode、count、pois 数组、suggestion 这些顶层字段，是 Jackson 反序列化的载体。
 */

@Data
public class GaodeResponse {

    /**
     * 是否成功
     */
    private String status;

    /**
     * 返回数量
     */
    private String count;

    /**
     * POI列表
     */
    private List<Poi> pois;

}