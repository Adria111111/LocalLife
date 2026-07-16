package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Web通用工具Bean配置类
 * 功能：创建RestTemplate单例对象存入Spring容器（只造对象交给 Spring 容器，不修改 MVC 运行流程）
 * RestTemplate用于后端发起HTTP请求，调用第三方远程接口、外部API
 * 业务代码直接@Autowired注入复用，无需重复创建实例
 */

@Configuration
public class WebConfig {
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
