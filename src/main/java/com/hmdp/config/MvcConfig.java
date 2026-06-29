package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // InterceptorRegistry是SpringMVC提供的拦截器注册注册器对象，把写好的Interceptor告诉Spring容器，让框架在请求时自动执行拦截逻辑

        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())  // stringRedisTemplate放参数里---手动把 Redis 塞进去了，前面写了带参数的构造方法
                // 拦截全局所有请求，除了下面exclude里写的地址全部要登录校验
                .addPathPatterns("/**")
                /*  通配符含义：
                /**：匹配当前层级下所有子孙路径（最深层级全部放行，比如/shop/1、/shop/detail/2都放行）
                /*：只匹配一级子路径，/blog/query/* 能匹配 /blog/query/1，匹配不到 /blog/query/1/2  */
                 .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                         "/user/me",
                         "/voucher/**"
                ).order(1);

         // 刷新token拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        // order()方法：设置拦截器的执行顺序，数字越小越先执行
    }
}
