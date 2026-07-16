package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置属性实体类，专门读取 yml 配置文件，存所有短信参数（数据载体）
 * 纯数据模型，没有任何业务逻辑，只存配置值，项目里可以到处注入
 */

@ConfigurationProperties(prefix = "aliyun.pnvs")
@Data
public class AliPnvsConfig {
    private String accessKeyId;
    private String accessKeySecret;
    private String endpoint = "dypnsapi.aliyuncs.com";
    private String signName;
    private String templateCode;
    private Integer validTimeSeconds = 120;
    private Integer sendIntervalSeconds = 60;
}
