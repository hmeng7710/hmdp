package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
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
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiSearchServiceImpl implements IAiSearchService {

    private final IBlogService blogService;
    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties aiProperties;

    @Resource
    private RestTemplate restTemplate;

    @Override
    public Result searchAndSummarize(String query) {
        if (StrUtil.isBlank(query)) {
            return Result.fail("关键词不能为空");
        }

        String cacheKey = "ai:search:" + query.trim();
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(cached);
        }

        // 1) 基于 title/content 的模糊查询，限制 topK
        int topK = Optional.ofNullable(aiProperties.getTopK()).orElse(10);
        List<Blog> candidates = blogService.list(new QueryWrapper<Blog>()
                .select("id", "title", "content")
                .and(w -> w.like("title", query).or().like("content", query))
                .last("limit " + topK));

        // 1.1 若无结果，使用大模型进行关键词扩展后再检索（简单、有效提升召回）
        if (CollUtil.isEmpty(candidates)) {
            List<String> expanded = expandKeywordsWithLLM(query);
            if (CollUtil.isNotEmpty(expanded)) {
                QueryWrapper<Blog> qw = new QueryWrapper<Blog>().select("id", "title", "content");
                // (title like kw1 or content like kw1) or (title like kw2 or content like kw2) ...
                boolean first = true;
                for (String kw : expanded) {
                    if (StrUtil.isBlank(kw)) continue;
                    if (first) {
                        qw.and(w -> w.like("title", kw).or().like("content", kw));
                        first = false;
                    } else {
                        qw.or(w -> w.like("title", kw).or().like("content", kw));
                    }
                }
                qw.last("limit " + Math.max(topK, 10));
                candidates = blogService.list(qw);
            }
        }

        if (CollUtil.isEmpty(candidates)) {
            return Result.ok("未检索到相关内容");
        }

        // 2) 截断、拼接为 RAG 上下文
        int snippetMax = Optional.ofNullable(aiProperties.getSnippetMaxChars()).orElse(300);
        int contextMax = Optional.ofNullable(aiProperties.getContextMaxChars()).orElse(4000);
        StringBuilder contextBuilder = new StringBuilder();
        for (Blog b : candidates) {
            String title = Optional.ofNullable(b.getTitle()).orElse("");
            String content = Optional.ofNullable(b.getContent()).orElse("");
            String snippet = StrUtil.sub(content, 0, Math.min(snippetMax, content.length()));
            String piece = "标题：" + title + "\n内容片段：" + snippet + "\n\n";
            if (contextBuilder.length() + piece.length() > contextMax) {
                break;
            }
            contextBuilder.append(piece);
        }

        String systemPrompt = "你是一个专业的笔记总结助手。基于给定的笔记片段，\n"
                + "- 用中文输出一个凝练的要点总结（5-10条要点即可），\n"
                + "- 保持客观中立，不要编造事实，\n"
                + "- 若信息不足请直说‘信息不足，无法得出可靠结论’。";

        String userPrompt = "用户搜索关键词：" + query + "\n以下为与之相关的笔记片段（可能不完整）：\n\n" + contextBuilder.toString()
                + "\n请输出精炼总结，适合展示在搜索结果顶部。";

        String summary = callChatCompletions(systemPrompt, userPrompt);
        if (StrUtil.isBlank(summary)) {
            return Result.fail("AI 总结失败");
        }

        // 3) 缓存
        long ttl = Optional.ofNullable(aiProperties.getCacheTtlSeconds()).orElse(300L);
        stringRedisTemplate.opsForValue().set(cacheKey, summary, ttl, TimeUnit.SECONDS);

        return Result.ok(summary);
    }

    /**
     * 使用大模型对查询进行关键词扩展，输出 3-8 个相关中文关键词。
     * 结果做短期缓存，避免重复生成。
     */
    private List<String> expandKeywordsWithLLM(String query) {
        try {
            String cacheKey = "ai:qexp:" + query.trim();
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                return Arrays.stream(cached.split(","))
                        .map(String::trim)
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toList());
            }

            String systemPrompt = "你是中文搜索助手。请将用户查询改写为更易检索的关键词。";
            String userPrompt = "请基于中文查询‘" + query + "’给出3-8个相关检索关键词：\n"
                    + "- 每个关键词不超过4个字\n"
                    + "- 只输出逗号分隔的关键词列表，例如：美食, 餐厅, 小吃, 周边\n"
                    + "- 不要输出解释";

            String raw = callChatCompletions(systemPrompt, userPrompt);
            if (StrUtil.isBlank(raw)) return Collections.emptyList();
            // 解析逗号分隔
            List<String> terms = Arrays.stream(raw.replace('\n', ' ').split("[，,]"))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .distinct()
                    .limit(10)
                    .collect(Collectors.toList());

            if (!terms.isEmpty()) {
                stringRedisTemplate.opsForValue().set(cacheKey, String.join(",", terms),
                        Optional.ofNullable(aiProperties.getCacheTtlSeconds()).orElse(300L), TimeUnit.SECONDS);
            }
            return terms;
        } catch (Exception e) {
            log.warn("关键词扩展失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

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


