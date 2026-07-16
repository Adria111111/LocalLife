package com.hmdp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 */

@Configuration
public class MybatisConfig {

    /**
     * 配置分页插件
     * 功能：注册分页插件，开启MySQL分页查询能力
     * 不加此配置，MP分页Page对象无法生效，查询会返回全量数据
     * 适配MySQL数据库分页语法，全局所有分页接口统一生效
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
