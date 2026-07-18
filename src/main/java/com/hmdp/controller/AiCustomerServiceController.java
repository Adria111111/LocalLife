package com.hmdp.controller;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiCustomerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/** AI 客服接口；没有加入登录白名单，因此必须登录后才能调用，避免匿名请求消耗模型费用。 */
@RestController
@RequestMapping("/ai/customer-service")
public class AiCustomerServiceController {

    @Resource
    private IAiCustomerService aiCustomerService;

    /** 发送问题并获取百炼模型回复。 */
    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        // Controller 只负责接收 HTTP JSON；登录校验、会话记忆和模型调用全部交给 Service。
        return aiCustomerService.chat(request);
    }

    /** 主动清除某个会话的上下文，下一次提问将从全新对话开始。 */
    @DeleteMapping("/conversation/{conversationId}")
    public Result clearConversation(@PathVariable("conversationId") String conversationId) {
        // 路径中的 conversationId 会传给 Service，并与当前登录用户 ID 一起组成 Redis Key。
        return aiCustomerService.clearConversation(conversationId);
    }
}
