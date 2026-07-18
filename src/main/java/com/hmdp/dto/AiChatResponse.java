package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** AI 客服成功回复给前端的数据，后端返回给前端。 */

@Data
@AllArgsConstructor
public class AiChatResponse {

    /** 前端后续提问时需要继续携带的会话 ID。 */
    private String conversationId;

    /** 阿里云百炼模型生成的客服回复。 */
    private String answer;

    /** 实际调用的模型名称，方便学习和排查配置。 */
    private String model;
}
