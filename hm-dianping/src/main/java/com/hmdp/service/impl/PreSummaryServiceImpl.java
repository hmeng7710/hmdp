package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.AiProperties;
import com.hmdp.entity.Blog;
import com.hmdp.service.IPreSummaryService;
import com.hmdp.service.IBlogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PreSummaryServiceImpl implements IPreSummaryService {

    private final IBlogService blogService;
    private final AiProperties aiProperties;

    @Resource
    private RestTemplate restTemplate;

    // 预摘要与关键词的内存存储（简洁实现）
    private final Map<Long, String> idToPreview = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> idToKeywords = new ConcurrentHashMap<>();

    @Override
    public synchronized void rebuild() {
        idToPreview.clear();
        idToKeywords.clear();
        List<Blog> blogs = blogService.list(new QueryWrapper<Blog>().select("id","title","content"));
        if (CollUtil.isEmpty(blogs)) return;
        int maxLen = Optional.ofNullable(aiProperties.getSnippetMaxChars()).orElse(300);
        for (Blog b : blogs) {
            String title = StrUtil.nullToEmpty(b.getTitle());
            String content = StrUtil.nullToEmpty(b.getContent());
            String raw = title + "\n" + content.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+"," ").trim();
            String input = StrUtil.sub(raw, 0, Math.min(maxLen * 3, raw.length()));
            String preview = genPreview(input);
            Set<String> kws = extractKeywords(input);
            if (StrUtil.isBlank(preview)) preview = StrUtil.sub(input, 0, Math.min(maxLen, input.length()));
            idToPreview.put(b.getId(), StrUtil.sub(preview, 0, maxLen));
            idToKeywords.put(b.getId(), kws);
        }
        log.info("PreSummary built: previews={}, keywords={}", idToPreview.size(), idToKeywords.size());
    }

    @Override
    public int countPreviews() { return idToPreview.size(); }

    @Override
    public int countKeywordSets() { return idToKeywords.size(); }

    @Override
    public Set<Long> searchByKeywords(List<String> keywords, int limit) {
        if (keywords == null || keywords.isEmpty()) return Collections.emptySet();
        Set<String> q = keywords.stream().map(String::trim).filter(StrUtil::isNotBlank).collect(Collectors.toSet());
        if (q.isEmpty()) return Collections.emptySet();
        // 简单 OR 匹配：命中任意关键词即入选，按命中数降序，取前 limit
        List<Map.Entry<Long, Set<String>>> list = new ArrayList<>(idToKeywords.entrySet());
        list.sort((a,b)-> Long.compare(matchCount(b.getValue(), q), matchCount(a.getValue(), q)));
        return list.stream().limit(limit).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String getLightContext(Long blogId) {
        return idToPreview.getOrDefault(blogId, "");
    }

    private long matchCount(Set<String> base, Set<String> q) {
        long n = 0;
        for (String s : q) if (base.contains(s)) n++;
        return n;
    }

    private String genPreview(String input) {
        String sys = "你是摘要助手。为以下文本生成不超过120字的中文简介，要求凝练、关键信息优先，不要客套。";
        String user = "文本：" + input;
        return callChat(sys, user);
    }

    private Set<String> extractKeywords(String input) {
        String sys = "你是关键词助手。请为文本提取6-12个中文关键词，2-4字/个，用逗号分隔，不要解释。";
        String user = input;
        String out = callChat(sys, user);
        if (StrUtil.isBlank(out)) return Collections.emptySet();
        return Arrays.stream(out.replace('\n',' ').split("[，,]"))
                .map(String::trim).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String callChat(String systemPrompt, String userPrompt) {
        try {
            String baseUrl = Optional.ofNullable(aiProperties.getBaseUrl()).orElse("https://api.openai.com/v1");
            String model = Optional.ofNullable(aiProperties.getModel()).orElse("gpt-3.5-turbo");
            String key = aiProperties.getApiKey();
            if (StrUtil.isBlank(key)) return null;
            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + key);
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(mapOf("role","system","content",systemPrompt));
            messages.add(mapOf("role","user","content",userPrompt));
            body.put("messages", messages);
            body.put("temperature", 0.2);
            body.put("max_tokens", 256);
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(new URI(url), HttpMethod.POST, req,
                    new ParameterizedTypeReference<Map<String, Object>>(){});
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody()==null) return null;
            Object choices = resp.getBody().get("choices");
            if (!(choices instanceof List) || ((List<?>)choices).isEmpty()) return null;
            Object first = ((List<?>)choices).get(0);
            if (!(first instanceof Map)) return null;
            Object msg = ((Map<?,?>)first).get("message");
            if (!(msg instanceof Map)) return null;
            Object content = ((Map<?,?>)msg).get("content");
            return content==null?null:content.toString();
        } catch (Exception e) {
            log.warn("callChat error", e);
            return null;
        }
    }

    private static Map<String,String> mapOf(String k1,String v1,String k2,String v2){
        Map<String,String> m=new HashMap<>();
        m.put(k1,v1);m.put(k2,v2);return m;
    }
}


