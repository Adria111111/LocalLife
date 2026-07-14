package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
