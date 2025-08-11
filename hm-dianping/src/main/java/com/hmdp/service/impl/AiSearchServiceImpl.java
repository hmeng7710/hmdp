package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.Result;
import com.hmdp.service.IAiSearchService;
import com.hmdp.service.IBlogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.net.URI;
import java.util.*;
 

@Service
@Slf4j
@RequiredArgsConstructor
public class AiSearchServiceImpl implements IAiSearchService {

    // 保留依赖以便后续扩展（当前未使用）
    @SuppressWarnings("unused")
    private final IBlogService blogService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties aiProperties;
    private final com.hmdp.service.IVectorRagService vectorRagService;

    @Resource
    private RestTemplate restTemplate;

    // 删除方案一（like/BM25）逻辑，改为仅保留方案二

    @Override
    public Result vectorSearchAndSummarize(String query) {
        if (StrUtil.isBlank(query)) {
            return Result.fail("关键词不能为空");
        }
        String cacheKey = "ai:vsearch:" + query.trim();
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(cached);
        }

        int topK = Optional.ofNullable(aiProperties.getTopK()).orElse(10);
        List<com.hmdp.dto.RagChunk> hits = vectorRagService.searchTopK(query, topK);
        if (hits == null || hits.isEmpty()) {
            return Result.ok("未检索到相关内容");
        }

        int contextMax = Optional.ofNullable(aiProperties.getContextMaxChars()).orElse(4000);
        StringBuilder contextBuilder = new StringBuilder();
        for (com.hmdp.dto.RagChunk c : hits) {
            String piece = "标题：" + (c.getTitle()==null?"":c.getTitle()) + "\n内容片段：" + c.getText() + "\n\n";
            if (contextBuilder.length() + piece.length() > contextMax) break;
            contextBuilder.append(piece);
        }

        String systemPrompt = "你是一个专业的笔记总结助手。基于向量检索到的片段，输出中文凝练要点（5-10条），如信息不足则说明。";
        String userPrompt = "用户搜索关键词：" + query + "\n以下为相关片段：\n\n" + contextBuilder.toString();
        String summary = callChatCompletions(systemPrompt, userPrompt);
        if (StrUtil.isBlank(summary)) {
            return Result.fail("AI 总结失败");
        }
        long ttl = Optional.ofNullable(aiProperties.getCacheTtlSeconds()).orElse(300L);
        stringRedisTemplate.opsForValue().set(cacheKey, summary, ttl, java.util.concurrent.TimeUnit.SECONDS);
        return Result.ok(summary);
    }
    // 方案一关键词扩展已删除

    private String callChatCompletions(String systemPrompt, String userPrompt) {
        try {
            String baseUrl = Optional.ofNullable(aiProperties.getBaseUrl()).orElse("https://api.openai.com/v1");
            String model = Optional.ofNullable(aiProperties.getModel()).orElse("gpt-3.5-turbo");
            String apiKey = aiProperties.getApiKey();
            if (StrUtil.isBlank(apiKey)) {
                log.error("AI API Key 未配置");
                return null;
            }

            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(mapOf("role", "system", "content", systemPrompt));
            messages.add(mapOf("role", "user", "content", userPrompt));
            body.put("messages", messages);
            body.put("temperature", Optional.ofNullable(aiProperties.getTemperature()).orElse(0.2));
            body.put("max_tokens", Optional.ofNullable(aiProperties.getMaxTokens()).orElse(512));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    new URI(url),
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.error("AI 调用失败，status={} body={}", resp.getStatusCode(), resp.getBody());
                return null;
            }
            Map<?, ?> responseBody = resp.getBody();
            Object choicesObj = responseBody.get("choices");
            if (!(choicesObj instanceof List)) {
                return null;
            }
            List<?> choices = (List<?>) choicesObj;
            if (choices.isEmpty()) return null;
            Object first = choices.get(0);
            if (!(first instanceof Map)) return null;
            Map<?, ?> firstMap = (Map<?, ?>) first;
            Object messageObj = firstMap.get("message");
            if (!(messageObj instanceof Map)) return null;
            Map<?, ?> message = (Map<?, ?>) messageObj;
            Object content = message.get("content");
            return content == null ? null : content.toString();
        } catch (Exception e) {
            log.error("调用 AI API 异常", e);
            return null;
        }
    }

    private static Map<String, String> mapOf(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}


