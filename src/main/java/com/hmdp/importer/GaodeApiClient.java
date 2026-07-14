package com.hmdp.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 高德接口的请求客户端。
 * 专门封装 HTTP GET 请求、拼接 URL、调用高德周边搜索 API，把返回的 JSON 字符串解析成 Java 对象；
 * 同时做了接口状态判断、统一异常捕获，只负责 “从高德拿数据”，不和数据库打交道。
 */

@Component
public class GaodeApiClient {

    private final RestTemplate restTemplate;
    // Spring 提供的 HTTP 请求工具，用来发起 GET 请求调用高德外网接口，适合一次性导入脚本
    private final ObjectMapper objectMapper;
    private final String amapKey;
    // Jackson JSON 序列化工具，作用：把高德返回的 JSON 字符串，自动转换成我们自定义的实体类 GaodeResponse

    public GaodeApiClient(RestTemplate restTemplate,
                          ObjectMapper objectMapper,
                          @Value("${amap.web-api-key:}") String amapKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.amapKey = amapKey;
    }

    public GaodeResponse search(String location, String type, int page) {
        if (!StringUtils.hasText(amapKey)) {
            throw new IllegalStateException("未配置高德 Web API Key，请设置 AMAP_WEB_API_KEY 环境变量");
        }
        try {
            // 1. 拼接高德周边搜索接口完整URL
            String url = String.format(
                    "https://restapi.amap.com/v3/place/around"
                            + "?key=%s"
                            + "&location=%s"
                            + "&radius=%d"
                            + "&types=%s"
                            + "&page=%d"
                            + "&offset=%d",
                    amapKey,
                    location,
                    ImportConfig.RADIUS,
                    type,
                    page,
                    ImportConfig.OFFSET
            );

            // 2. 发起HTTP GET请求，并获取响应
            ResponseEntity<String> response =
                    restTemplate.getForEntity(url, String.class);
                    //对目标接口 url 发起 GET 网络请求，指定将接口返回的全部报文以字符串形式接收

            // 3. 解析响应结果为GaodeResponse对象
            GaodeResponse result = objectMapper.readValue(
                    //readValue(字符串, 目标类.class)：解析 JSON，根据类上属性名自动匹配 JSON key
                    response.getBody(),
                    GaodeResponse.class
            );

            // 4.1 检查接口调用是否成功
            if (!"1".equals(result.getStatus())) {
                throw new RuntimeException("高德接口调用失败");
            }

            // 4.2 处理POI为空的情况
            if (result.getPois() == null) {
                result.setPois(java.util.Collections.emptyList());
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("调用高德 POI 接口失败", e);
        }
    }
}
