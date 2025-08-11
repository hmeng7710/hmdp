package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    /**
     * OpenAI 兼容的 Base URL，例如：https://api.openai.com/v1
     */
    private String baseUrl;

    /**
     * 模型名称，例如：gpt-3.5-turbo 或 gpt-4o-mini 等
     */
    private String model;

    /**
     * API Key，形如：sk-***
     */
    private String apiKey;

    /** 温度（0-2） */
    private Double temperature = 0.2;

    /** 聊天响应最大 tokens */
    private Integer maxTokens = 512;

    /** 每条笔记内容截断长度（字符数） */
    private Integer snippetMaxChars = 300;

    /** 检索返回的候选笔记条数 */
    private Integer topK = 10;

    /** 构造 RAG 上下文的最大长度（字符数） */
    private Integer contextMaxChars = 4000;

    /** 缓存有效期（秒） */
    private Long cacheTtlSeconds = 300L;
}


