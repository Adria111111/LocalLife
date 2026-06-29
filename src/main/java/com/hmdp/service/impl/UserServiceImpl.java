package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import cn.hutool.http.HttpUtil;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.*;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    // ServiceImpl是MyBatis-Plus 提供的通用业务层父实现类
    // 第一个泛型：Mapper 接口，负责数据库 CRUD 底层操作；第二个泛型：数据库实体类，映射表字段、主键、索引等表结构信息；
    // 继承该类，就可以直接使用 MyBatis-Plus 提供的通用业务层方法，如：save、update、delete、getById、list、page 等

    @Resource
    //@Resource---Java 原生注解；先按变量名匹配 Bean，无同名再按类型；不能构造注入、无可选注入。
    //@Autowired---Spring 专属；默认只按类型匹配；支持required=false可选注入、搭配@Qualifier指定 Bean、支持构造器注入。
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        /* session:
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到session---setAttribute：绑定属性到会话
        session.setAttribute("code", code);
        // 5.真实发送短信
        String appCode = "13f12588d376408ab6091fb1035d79cf";
        try {
            String url = "https://gyytz.market.alicloudapi.com/sms/smsSend?mobile=" + phone
                    + "&param=**code**:" + code + ",**minute**:1"
                    + "&smsSignId=2e65b1bb3d054466b82f0c9d125465e2"
                    + "&templateId=908e94ccf08b4476ba6c876d13f084ad";

            // 2. 直接用Hutool发送，不需要任何HttpUtils
            String result = HttpUtil.createPost(url) // 创建一个 HTTP POST 请求对象，指定请求地址为短信接口地址
                    .header("Authorization", "APPCODE " + appCode) // 往 HTTP 请求头（Header） 中添加身份认证信息
                    .execute() // 执行 HTTP 请求，建立网络连接，发送请求数据，等待服务器响应
                    .body(); // 获取 HTTP 响应体（ResponseBody），即服务器返回的 JSON 结果

            log.info("短信发送结果：{}", result);
        } catch (Exception e) {
            log.error("短信发送失败", e); // e 就是程序出错时，Java 自动给你打包好的错误信息对象
            return Result.fail("短信发送失败");
        }
        // 6.返回ok
        return Result.ok();*/

        // redis：
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 以string类型保存，key-手机号，value-验证码
        // "login:code:"---前缀，避免redis里其他人也用手机号当key区分不开(LOGIN_CODE_KEY)
        // 2---为key设置的有效期，不然每个验证码都会存(LOGIN_CODE_TTL)

        // 5.真实发送短信
        String appCode = "13f12588d376408ab6091fb1035d79cf";
        try {
            String url = "https://gyytz.market.alicloudapi.com/sms/smsSend?mobile=" + phone
                    + "&param=**code**:" + code + ",**minute**:1"
                    + "&smsSignId=2e65b1bb3d054466b82f0c9d125465e2"
                    + "&templateId=908e94ccf08b4476ba6c876d13f084ad";

            // 2. 直接用Hutool发送，不需要任何HttpUtils
            String result = HttpUtil.createPost(url) // 创建一个 HTTP POST 请求对象，指定请求地址为短信接口地址
                    .header("Authorization", "APPCODE " + appCode) // 往 HTTP 请求头（Header） 中添加身份认证信息
                    .execute() // 执行 HTTP 请求，建立网络连接，发送请求数据，等待服务器响应
                    .body(); // 获取 HTTP 响应体（ResponseBody），即服务器返回的 JSON 结果

            log.info("短信发送结果：{}", result);
        } catch (Exception e) {
            log.error("短信发送失败", e); // e 就是程序出错时，Java 自动给你打包好的错误信息对象
            return Result.fail("短信发送失败");
        }
        // 6.返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        /* session：
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 2.校验验证码
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute("code");
        if (cacheCode == null || !cacheCode.toString().equals(code)) { // 反向校验避免嵌套
            // 3.不一致，返回错误信息
            return Result.fail("验证码错误！");
        }
        // 4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // MyBatis-Plus 3.x 链式查询语法：query()---快速构建查询条件；one()---查出来一条；多条就转成list()；

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户，并返回到user
            user = createUserWithPhone(phone);
        }

        // 7.存在和不存在都要保存用户信息到session，返回用户信息
        session.setAttribute("user", user);

        return Result.ok();
        // 为什么不需要返回登陆凭证？
        // 执行 session.setAttribute("user", user) 时，服务端会生成一个唯一SessionId，存在服务端内存；
        // SpringMVC/Tomcat 会自动把 SessionId 写入响应头 Set-Cookie，下发给浏览器；
        // 浏览器收到后，自动把这个 Cookie 存到本地；
        // 之后浏览器每一次请求这个域名接口，都会自动带上这个 Cookie（里面包含 SessionId）；
        // 服务端拿到 Cookie 里的 SessionId，就能匹配到你存的user对象，识别登录状态。*/

        // redis：
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 2.从redis获取验证码并校验
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.toString().equals(code)) { // 反向校验避免嵌套
            // 3.不一致，返回错误信息
            return Result.fail("验证码错误！");
        }
        // 4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // MyBatis-Plus 3.x 链式查询语法：query()---快速构建查询条件；one()---查出来一条；多条就转成list()；

        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户，并返回到user
            user = createUserWithPhone(phone);
        }

        // 7.存在和不存在都要保存用户信息到redis
        // 7.1 生成token作为key
        String token = UUID.randomUUID().toString(true);

        // 7.2 将user对象转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class); // 把 User 对象里的数据，复制一份到 UserDTO 对象里
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,                // 参数1：要转换的源对象（用户DTO实体）
                new HashMap<>(),        // 参数2：目标Map容器，转换后数据放进这个HashMap
                CopyOptions.create()    // 参数3：复制规则配置对象
                        .setIgnoreNullValue(true)                  // 规则1：值为null的字段直接跳过，不存入Map
                        .setFieldValueEditor((fieldname, fieldvalue) -> fieldvalue.toString()) // 规则2：所有字段值统一转字符串
        );

        // 7.3 保存到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);

        // 7.4 设置token有效期
        // 解决：如果用户一直活跃，就需要一直隔30min验证token，影响用户访问别的页面
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES); //  Redis的Hash结构只支持expire设置有效期

        // 7.5 返回token（类似于sessionId存的user信息，随时携带）到客户端
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
