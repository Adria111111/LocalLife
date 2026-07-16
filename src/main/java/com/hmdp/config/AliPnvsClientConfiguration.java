package com.hmdp.config;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.teaopenapi.models.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Spring 配置工厂类，拿实体里的账号信息，创建阿里云短信 SDK 客户端（对象生产器）
 * 创建阿里云 SDK 的 Client 客户端，交给 Spring 容器管理，供业务注入使用，不管理签名、模板、有效期这类业务参数
 */
@Configuration
public class AliPnvsClientConfiguration {

    @Bean
    public Client dypnsClient(AliPnvsConfig properties) throws Exception {
        if (!StringUtils.hasText(properties.getAccessKeyId())
                || !StringUtils.hasText(properties.getAccessKeySecret())) {
            throw new IllegalStateException(
                    "未配置阿里云 AccessKey，请设置 ALIBABA_CLOUD_ACCESS_KEY_ID 和 ALIBABA_CLOUD_ACCESS_KEY_SECRET");
        }
        Config config = new Config()
                .setAccessKeyId(properties.getAccessKeyId())
                .setAccessKeySecret(properties.getAccessKeySecret())
                .setEndpoint(properties.getEndpoint());
        return new Client(config);
    }
}
