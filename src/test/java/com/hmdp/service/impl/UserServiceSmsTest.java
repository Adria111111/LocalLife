package com.hmdp.service.impl;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody.SendSmsVerifyCodeResponseBodyModel;
import com.hmdp.config.AliPnvsConfig;
import com.hmdp.dto.Result;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_SEND_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class UserServiceSmsTest {

    private static final String PHONE = "13800138000";

    @Mock
    private Client dypnsClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserServiceImpl userService;

    @Before
    public void setUp() {
        AliPnvsConfig config = new AliPnvsConfig();
        config.setSignName("恒创联众");
        config.setTemplateCode("100001");
        config.setValidTimeSeconds(120);
        config.setSendIntervalSeconds(60);

        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "aliPnvsConfig", config);
        ReflectionTestUtils.setField(userService, "dypnsClient", dypnsClient);
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    public void sendsWithConfiguredSignatureAndStoresCodeAfterSuccess() throws Exception {
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_SEND_KEY + PHONE, "1", 60, TimeUnit.SECONDS)).thenReturn(true);

        SendSmsVerifyCodeResponseBody body = new SendSmsVerifyCodeResponseBody()
                .setSuccess(true)
                .setCode("OK")
                .setRequestId("request-id")
                .setModel(new SendSmsVerifyCodeResponseBodyModel()
                        .setVerifyCode("654321")
                        .setBizId("biz-id"));
        when(dypnsClient.sendSmsVerifyCode(any(SendSmsVerifyCodeRequest.class)))
                .thenReturn(new SendSmsVerifyCodeResponse().setBody(body));

        Result result = userService.sendCode(PHONE, null);

        assertTrue(result.getSuccess());
        ArgumentCaptor<SendSmsVerifyCodeRequest> captor =
                ArgumentCaptor.forClass(SendSmsVerifyCodeRequest.class);
        verify(dypnsClient).sendSmsVerifyCode(captor.capture());
        SendSmsVerifyCodeRequest request = captor.getValue();
        assertEquals("恒创联众", request.getSignName());
        assertEquals("100001", request.getTemplateCode());
        assertEquals(PHONE, request.getPhoneNumber());
        assertEquals("86", request.getCountryCode());
        assertEquals("{\"code\":\"##code##\",\"min\":\"2\"}", request.getTemplateParam());
        assertEquals(Long.valueOf(6), request.getCodeLength());
        assertEquals(Boolean.TRUE, request.getReturnVerifyCode());

        verify(valueOperations).set(
                eq(LOGIN_CODE_KEY + PHONE), eq("654321"), eq(120L), eq(TimeUnit.SECONDS));
    }

    @Test
    public void doesNotStoreCodeWhenAliyunRejectsRequest() throws Exception {
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_SEND_KEY + PHONE, "1", 60, TimeUnit.SECONDS)).thenReturn(true);
        SendSmsVerifyCodeResponseBody body = new SendSmsVerifyCodeResponseBody()
                .setSuccess(false)
                .setCode("INVALID_PARAMETERS")
                .setMessage("parameter is not valid");
        when(dypnsClient.sendSmsVerifyCode(any(SendSmsVerifyCodeRequest.class)))
                .thenReturn(new SendSmsVerifyCodeResponse().setBody(body));

        Result result = userService.sendCode(PHONE, null);

        assertEquals(Boolean.FALSE, result.getSuccess());
        verify(valueOperations, never()).set(
                eq(LOGIN_CODE_KEY + PHONE), anyString(), anyLong(), eq(TimeUnit.SECONDS));
        verify(redisTemplate).delete(LOGIN_CODE_SEND_KEY + PHONE);
    }

    @Test
    public void keepsLocalCooldownWhenAliyunReportsFrequencyLimit() throws Exception {
        when(valueOperations.setIfAbsent(
                LOGIN_CODE_SEND_KEY + PHONE, "1", 60, TimeUnit.SECONDS)).thenReturn(true);
        SendSmsVerifyCodeResponseBody body = new SendSmsVerifyCodeResponseBody()
                .setSuccess(false)
                .setCode("biz.FREQUENCY")
                .setMessage("check frequency failed");
        when(dypnsClient.sendSmsVerifyCode(any(SendSmsVerifyCodeRequest.class)))
                .thenReturn(new SendSmsVerifyCodeResponse().setBody(body));

        Result result = userService.sendCode(PHONE, null);

        assertEquals(Boolean.FALSE, result.getSuccess());
        verify(redisTemplate, never()).delete(LOGIN_CODE_SEND_KEY + PHONE);
    }
}
