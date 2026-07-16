package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody.SendSmsVerifyCodeResponseBodyModel;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.AliPnvsConfig;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private AliPnvsConfig aliPnvsConfig; // 阿里云短信配置（签名、模板、有效期、限流时间）

    @Autowired
    private Client dypnsClient; // 阿里云短信SDK客户端，调用发送验证码接口

    @Autowired
    private StringRedisTemplate stringRedisTemplate; // Redis模板，用于缓存短信验证码和登录用户信息

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // Redis 限流防刷
        Boolean canSend = stringRedisTemplate.opsForValue().setIfAbsent(
                LOGIN_CODE_SEND_KEY + phone,
                "1",
                aliPnvsConfig.getSendIntervalSeconds(),
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(canSend)) {
            return Result.fail("验证码发送过于频繁，请稍后重试！");
        }

        try {
            // 组装阿里云短信请求参数
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    // 1. 国家码，中国固定86
                    .setCountryCode("86")
                    // 2. 接收短信的用户手机号（前端传过来的phone）
                    .setPhoneNumber(phone)
                    // 3. 短信签名，阿里云短信平台申请的签名名称，配置在yml
                    .setSignName(aliPnvsConfig.getSignName())
                    // 4. 短信模板ID，阿里云后台创建的验证码模板编号
                    .setTemplateCode(aliPnvsConfig.getTemplateCode())
                    // 5. 模板变量参数，短信模板里有占位符##code##，用来填充验证码
                    .setTemplateParam("{\"code\":\"##code##\",\"min\":\"2\"}")
                    // 6. 验证码长度：6位数字
                    .setCodeLength(6L)
                    // 7. 验证码类型 1=纯数字（固定规则）
                    .setCodeType(1L)
                    // 8. 让阿里云接口把生成的验证码返回，方便存入Redis
                    .setReturnVerifyCode(true)
                    // 9. 阿里云侧验证码有效时长（和本地Redis缓存过期时间保持一致）
                    .setValidTime(aliPnvsConfig.getValidTimeSeconds().longValue())
                    // 10. 阿里云侧同一号码发送间隔限制（防刷，和本地Redis限流双重防护）
                    .setInterval(aliPnvsConfig.getSendIntervalSeconds().longValue())
                    // 11. 重复发送策略 1=重新生成新验证码
                    .setDuplicatePolicy(1L)
                    // 12. 发送失败自动重试开关 1=开启自动重试
                    .setAutoRetry(1L);
            // 调用阿里云短信接口发送验证码
            SendSmsVerifyCodeResponse response = dypnsClient.sendSmsVerifyCode(request);
            SendSmsVerifyCodeResponseBody body = response == null ? null : response.getBody();
            if (body == null || !Boolean.TRUE.equals(body.getSuccess()) || !"OK".equals(body.getCode())) {
                return handleSendFailure(phone, body);
            }
            // 提取阿里云返回的验证码，存入 Redis
            SendSmsVerifyCodeResponseBodyModel model = body.getModel();
            String verifyCode = model == null ? null : model.getVerifyCode();
            if (!StringUtils.hasText(verifyCode)) {
                stringRedisTemplate.delete(LOGIN_CODE_SEND_KEY + phone);
                log.error("阿里云返回发送成功，但未返回验证码，requestId={}", getRequestId(body));
                return Result.fail("短信服务返回数据异常");
            }
            stringRedisTemplate.opsForValue().set(
                    LOGIN_CODE_KEY + phone,
                    verifyCode,
                    aliPnvsConfig.getValidTimeSeconds(),
                    TimeUnit.SECONDS
            );
            log.info("短信验证码已发送，requestId={}, bizId={}",
                    getRequestId(body), model.getBizId());
            return Result.ok();
        } catch (Exception e) {
            stringRedisTemplate.delete(LOGIN_CODE_SEND_KEY + phone);
            log.error("调用阿里云短信认证接口失败", e);
            return Result.fail("短信发送失败");
        }
    }

    private Result handleSendFailure(String phone, SendSmsVerifyCodeResponseBody body) {
        String errorCode = body == null ? "EMPTY_RESPONSE" : body.getCode();
        String message = body == null ? "阿里云未返回响应内容" : body.getMessage();
        log.warn("短信发送被拒绝，code={}, message={}, requestId={}",
                errorCode, message, getRequestId(body));

        if ("biz.FREQUENCY".equalsIgnoreCase(errorCode)
                || "FREQUENCY_FAIL".equalsIgnoreCase(errorCode)) {
            return Result.fail("验证码发送过于频繁，请稍后重试！");
        }

        // 非限流错误允许用户修正配置后立即重试。
        stringRedisTemplate.delete(LOGIN_CODE_SEND_KEY + phone);
        return Result.fail("短信发送失败（" + errorCode + "）");
    }

    private String getRequestId(SendSmsVerifyCodeResponseBody body) {
        if (body == null) {
            return null;
        }
        if (StringUtils.hasText(body.getRequestId())) {
            return body.getRequestId();
        }
        return body.getModel() == null ? null : body.getModel().getRequestId();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
