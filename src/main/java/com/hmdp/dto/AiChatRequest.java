package com.hmdp.dto;

import lombok.Data;

/** 前端发送给 AI 客服的请求，前端发给后端。 */
@Data
public class AiChatRequest {

    /**
     * 会话 ID。第一次提问可以不传，由后端生成；后续提问带回同一个 ID 才能延续上下文。
     */
    private String conversationId;

    /** 用户本次输入的问题。 */
    private String message;

    /** 可选商户 ID；传入后，客服会读取该商户和优惠券的真实数据作为回答依据。 */
    private Long shopId;
}
