package com.hmdp.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保存在 Redis 中的简化会话消息。
 * 只保存角色和文本，不直接序列化 LangChain4j 对象，避免框架升级造成旧会话无法读取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatMemoryMessage {

    /** 取值为 user 或 assistant。 */
    private String role;

    private String content;
}
