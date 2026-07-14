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
    private AliPnvsConfig aliPnvsConfig;

    @Autowired
    private Client dypnsClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

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
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setCountryCode("86")
                    .setPhoneNumber(phone)
                    .setSignName(aliPnvsConfig.getSignName())
                    .setTemplateCode(aliPnvsConfig.getTemplateCode())
                    // 使用阿里云生成验证码，参数与 OpenAPI 调试台保持一致。
                    .setTemplateParam("{\"code\":\"##code##\",\"min\":\"2\"}")
                    .setCodeLength(6L)
                    .setCodeType(1L)
                    .setReturnVerifyCode(true)
                    .setValidTime(aliPnvsConfig.getValidTimeSeconds().longValue())
                    .setInterval(aliPnvsConfig.getSendIntervalSeconds().longValue())
                    .setDuplicatePolicy(1L)
                    .setAutoRetry(1L);

            SendSmsVerifyCodeResponse response = dypnsClient.sendSmsVerifyCode(request);
            SendSmsVerifyCodeResponseBody body = response == null ? null : response.getBody();
            if (body == null || !Boolean.TRUE.equals(body.getSuccess()) || !"OK".equals(body.getCode())) {
                return handleSendFailure(phone, body);
            }

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
