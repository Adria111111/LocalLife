package com.hmdp.service;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;

/** LocalLife AI 客服业务接口。 */
public interface IAiCustomerService {

    /** 完成一次多轮客服问答，并返回统一 Result。 */
    Result chat(AiChatRequest request);

    /** 删除当前登录用户指定会话的 Redis 历史。 */
    Result clearConversation(String conversationId);
}
