package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    /* Spring规则：
         1. Spring = 对象工厂，它管理的对象统称为 Bean
         2. 只有 Spring 管理的 Bean 才能使用自动注入功能
         3. 自己 new 的对象，Spring 不管理，注入会为 null
         4. 类上添加 @Component(通用组件（拦截器、工具类）)/@Service(业务逻辑类)/@Controller(接口控制器)/@Repository(数据库操作类) 注解，Spring 启动时会自动创建该类对象并纳入容器管理
         5. @Autowired 作用：从 Spring 容器中自动获取匹配的 Bean，给成员变量赋值，无需手动 new 对象；
            默认按类型注入，从容器找同类型 Bean 赋值；
            同类型多个 Bean 时，配合 @Qualifier("bean名称") 按名称注入；
         6. @Resource：默认按名称注入，找不到再按类型
         7. 构造器注入，不用任何注入注解，最安全、最常用
         8. @Bean 手动注入
    */
    // 声明Redis对象
    private StringRedisTemplate stringRedisTemplate;


/*    // 构造器注入：Spring 自动传参，有参构造
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }*/
    //  LoginInterceptor这个类是自己写的，它的对象不是spring创建管理的
    //  看谁用了stringRedisTemplate，就往谁里注入


    @Override
    // Controller 执行之前
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        /* session：
        // 1.获取session
        HttpSession session = request.getSession(); // request是变量名
        // 2.获取session中的用户，并强转为User
        User user = (User) session.getAttribute("user");
        // 3.判断用户是否存在
        if (user == null){
            // 4.不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        // 5.存在，保存用户信息到ThreadLocal---线程隔离，贯穿整个请求，用完必须 remove ()，不删会内存泄漏
        // 把user转为UserDTO
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());
        UserHolder.saveUser(userDTO);
        // 6.放行
        return true;*/

        /*
        // redis:
        // 1.获取请求头中的token
        String token = request.getHeader("Authorization"); // request是变量名
        if (StrUtil.isBlank(token)) {
            // 4.不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        // 2.获取redis中根据token查到的用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        // stringRedisTemplate---操作redis的工具
        // entries(key)---根据 key 获取 Hash 里的全部键值对，返回Map，自己已经判断过null了
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            // 4.不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }

        // 5.存在，保存用户信息到ThreadLocal---线程隔离，贯穿整个请求，用完必须 remove ()，不删会内存泄漏
        // 5.1 在redis中查到的数据是hash数据结构，所以要转为UserDTO再保存
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 从redis取数据存到threadlocal时 → 完全不需要再做任何string和Long转换
        // 5.2 存到ThreadLocal
        UserHolder.saveUser(userDTO);

        // 6.刷新token有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 7.放行
        return true;
        */

        // 这里完全就剩下只检查登录需不需要放行的拦截器了，放在在RefreshTokenInterceptor之后的
        // 1.判断是否需要拦截（threadlocal里是否有user信息）
        if (UserHolder.getUser() == null) {
            // 2.没有，需要拦截
            response.setStatus(401);
            return false;
        }
        // 3.有，放行
        return true;
    }

    @Override
    // 整个请求完全结束之后（视图渲染完、响应发给浏览器之后）
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求收尾，强制清空当前线程存储的用户
        UserHolder.removeUser();
    }
}
