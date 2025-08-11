package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.RagChunk;
import com.hmdp.entity.Blog;
import com.hmdp.service.IVectorRagService;
import com.hmdp.service.IBlogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
@RequiredArgsConstructor
public class VectorRagServiceImpl implements IVectorRagService {

    private final IBlogService blogService;
    private final AiProperties aiProperties;

    @Resource
    private RestTemplate restTemplate;

    // 简化：以内存结构保存分块与向量
    private final List<RagChunk> chunks = new CopyOnWriteArrayList<>();
    private final List<float[]> embeddings = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        // 启动不强制构建，首次调用时再构建，避免启动阻塞
    }

    public int chunkSize() { return chunks.size(); }
    public int embeddingSize() { return embeddings.size(); }

    @Override
    public synchronized void rebuildIndex() {
        chunks.clear();
        embeddings.clear();
        List<Blog> blogs = blogService.list(new QueryWrapper<Blog>().select("id","title","content"));
        if (CollUtil.isEmpty(blogs)) return;
        // 分块
        int blockSize = Optional.ofNullable(aiProperties.getSnippetMaxChars()).orElse(300);
        for (Blog b : blogs) {
            String title = Optional.ofNullable(b.getTitle()).orElse("");
            String content = Optional.ofNullable(b.getContent()).orElse("");
            if (StrUtil.isNotBlank(content)) {
                content = content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            }
            if (StrUtil.isBlank(title) && StrUtil.isBlank(content)) continue;
            List<String> pieces = splitByLength(title + "\n" + content, blockSize);
            for (String p : pieces) {
                chunks.add(new RagChunk(b.getId(), title, p));
            }
        }
        // 调用 embedding 接口
        List<String> texts = new ArrayList<>(chunks.size());
        for (RagChunk c : chunks) {
            texts.add(c.getText());
        }
        List<float[]> vecs = embedTexts(texts);
        embeddings.addAll(vecs);
        log.info("Vector index built: chunks={} dims={} embedModel={}", chunks.size(), vecs.isEmpty()?0:vecs.get(0).length, aiProperties.getEmbeddingModel());
    }

    @Override
    public List<RagChunk> searchTopK(String query, int topK) {
        if (chunks.isEmpty() || embeddings.isEmpty() || chunks.size() != embeddings.size()) {
            rebuildIndex();
        }
        if (chunks.isEmpty() || embeddings.isEmpty()) return Collections.emptyList();
        List<float[]> qvec = embedTexts(Collections.singletonList(query));
        if (qvec.isEmpty() || qvec.get(0) == null) return Collections.emptyList();
        float[] q = qvec.get(0);
        PriorityQueue<int[]> heap = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
        for (int i = 0; i < embeddings.size(); i++) {
            float score = cosineSim(q, embeddings.get(i));
            // 存 [index, score*1e6 转 int] 以简化
            int key = (int) Math.round(score * 1_000_000);
            if (heap.size() < topK) {
                heap.offer(new int[]{i, key});
            } else if (key > heap.peek()[1]) {
                heap.poll();
                heap.offer(new int[]{i, key});
            }
        }
        List<int[]> list = new ArrayList<>(heap);
        list.sort((a,b)->Integer.compare(b[1], a[1]));
        List<RagChunk> result = new ArrayList<>(list.size());
        for (int[] e : list) {
            result.add(chunks.get(e[0]));
        }
        return result;
    }

    private static List<String> splitByLength(String text, int maxLen) {
        List<String> list = new ArrayList<>();
        if (text == null) return list;
        String s = text.trim();
        for (int i = 0; i < s.length(); i += maxLen) {
            int end = Math.min(i + maxLen, s.length());
            list.add(s.substring(i, end));
        }
        return list;
    }

    private List<float[]> embedTexts(List<String> inputs) {
        String baseUrl = Optional.ofNullable(aiProperties.getBaseUrl()).orElse("https://api.openai.com/v1");
        String apiKey = aiProperties.getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            log.error("AI API Key 未配置");
            return Collections.emptyList();
        }
        String primaryModel = Optional.ofNullable(aiProperties.getEmbeddingModel()).orElse("text-embedding-v1");
        String fallbackModel = "text-embedding-v1";
        String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        int batchSize = 8;
        List<float[]> all = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, inputs.size());
            List<String> batch = inputs.subList(start, end);
            List<float[]> part = callEmbeddingOnce(url, headers, primaryModel, batch, true);
            if (part.isEmpty()) {
                log.warn("Primary embedding model '{}' returned empty, trying fallback '{}'", primaryModel, fallbackModel);
                part = callEmbeddingOnce(url, headers, fallbackModel, batch, false);
            }
            if (part.isEmpty()) {
                for (int i = 0; i < batch.size(); i++) all.add(new float[0]);
            } else {
                all.addAll(part);
            }
        }
        return all;
    }

    private List<float[]> callEmbeddingOnce(String url, HttpHeaders headers, String model, List<String> batch, boolean withDims) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("input", batch);
            if (withDims) {
                Integer dims = aiProperties.getEmbeddingDimensions();
                if (dims != null && dims > 0) body.put("dimensions", dims);
                String enc = aiProperties.getEmbeddingEncodingFormat();
                if (StrUtil.isNotBlank(enc)) body.put("encoding_format", enc);
            }
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    new URI(url), HttpMethod.POST, request,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.error("Embedding 调用失败，status={} body={}", resp.getStatusCode(), resp.getBody());
                return Collections.emptyList();
            }
            Map<?, ?> responseBody = resp.getBody();
            Object dataObj = responseBody.get("data");
            if (!(dataObj instanceof List)) {
                log.error("Embedding 返回格式异常: {}", responseBody);
                return Collections.emptyList();
            }
            List<?> data = (List<?>) dataObj;
            List<float[]> result = new ArrayList<>(data.size());
            for (Object item : data) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) item;
                Object emb = m.get("embedding");
                if (!(emb instanceof List)) continue;
                List<?> arr = (List<?>) emb;
                float[] vec = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    Object v = arr.get(i);
                    vec[i] = v instanceof Number ? ((Number) v).floatValue() : 0f;
                }
                result.add(vec);
            }
            return result;
        } catch (Exception e) {
            log.error("调用 Embedding API 异常(model={})", model, e);
            return Collections.emptyList();
        }
    }

    private static float cosineSim(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1f;
        double dot = 0d, na = 0d, nb = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return -1f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }
}


