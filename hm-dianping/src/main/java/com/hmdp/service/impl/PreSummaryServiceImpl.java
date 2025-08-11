package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.AiProperties;
import com.hmdp.entity.Blog;
import com.hmdp.entity.PreSummary;
import com.hmdp.mapper.PreSummaryMapper;
import com.hmdp.service.IPreSummaryService;
import com.hmdp.service.IBlogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.security.MessageDigest;
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
    @Resource
    private PreSummaryMapper preSummaryMapper;

    // 预摘要与关键词的内存存储（简洁实现）
    private final Map<Long, String> idToPreview = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> idToKeywords = new ConcurrentHashMap<>();

    /**
     * 线程安全地重建博客的“预览文本”和“关键词”缓存，并持久化到数据库
     */
    @Override
    @Async
    public synchronized void rebuild() {
        // 1. 清空内存中的预览和关键词缓存
        idToPreview.clear();
        idToKeywords.clear();

        // 2. 从数据库中查询所有博客，只取 id、title、content 三列
        List<Blog> blogs = blogService.list(new QueryWrapper<Blog>().select("id","title","content"));
        if (CollUtil.isEmpty(blogs)) return;

        // 3. 获取最大预览长度配置（默认 300 字符）
        int maxLen = Optional.ofNullable(aiProperties.getSnippetMaxChars()).orElse(300);

        for (Blog b : blogs) {
            // 标题和内容为空时用空字符串替代
            String title = StrUtil.nullToEmpty(b.getTitle());
            String content = StrUtil.nullToEmpty(b.getContent());

            // 拼接标题和正文（去掉 HTML 标签、多余空格）
            String raw = title + "\n" + content.replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+"," ").trim();

            // 截取前 maxLen*3 个字符作为生成摘要的输入
            String input = StrUtil.sub(raw, 0, Math.min(maxLen * 3, raw.length()));

            // 调用 AI 或算法生成预览文本
            String preview = genPreview(input);

            // 提取关键词集合
            Set<String> kws = extractKeywords(input);
            if (StrUtil.isBlank(preview)) preview = StrUtil.sub(input, 0, Math.min(maxLen, input.length()));
            idToPreview.put(b.getId(), StrUtil.sub(preview, 0, maxLen));
            idToKeywords.put(b.getId(), kws);
            // 持久化到DB（upsert）
            upsertPreSummary(b.getId(), 0, sha1(raw), StrUtil.sub(preview,0,maxLen), String.join(",",kws));
        }
        log.info("PreSummary built: previews={}, keywords={}", idToPreview.size(), idToKeywords.size());
    }

    @Override
    public int countPreviews() {
        if (!idToPreview.isEmpty()) return idToPreview.size();
        return preSummaryMapper.selectCount(null).intValue();
    }

    @Override
    public int countKeywordSets() {
        if (!idToKeywords.isEmpty()) return idToKeywords.size();
        return preSummaryMapper.selectCount(null).intValue();
    }

    @Override
    public Set<Long> searchByKeywords(List<String> keywords, int limit) {
        // 1. 如果传入关键词列表为空或为 null，直接返回空集合
        if (keywords == null || keywords.isEmpty()) return Collections.emptySet();

        // 2. 处理关键词：
        //    - 去掉首尾空格
        //    - 过滤掉空字符串
        //    - 放入 Set 去重
        Set<String> q = keywords.stream()
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        if (q.isEmpty()) return Collections.emptySet(); // 全部是空词则直接返回

        // 3. 如果内存中的关键词索引还没加载，就从数据库加载一次
        ensureIndexLoadedFromDb();

        // 4. 将 idToKeywords（Map<Long, Set<String>>）的条目复制成 list
        //    每个条目是 {笔记ID -> 该笔记的关键词集合}
        List<Map.Entry<Long, Set<String>>> list = new ArrayList<>(idToKeywords.entrySet());

        // 5. 排序：
        //    - matchCount() 计算该笔记关键词集合与查询关键词集合的交集数量
        //    - 按交集数量从大到小排序（命中数多的排前面）
        list.sort((a, b) -> Long.compare(
                matchCount(b.getValue(), q),
                matchCount(a.getValue(), q)
        ));

        // 6. 取前 limit 个笔记 ID，保持顺序并去重（LinkedHashSet）
        return list.stream()
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String getLightContext(Long blogId) {
        String pv = idToPreview.get(blogId);
        if (pv != null) return pv;
        PreSummary ps = preSummaryMapper.selectOne(new QueryWrapper<PreSummary>()
                .eq("blog_id", blogId)
                .eq("chunk_id", 0)
                .last("limit 1"));
        return ps == null ? "" : ps.getSummary();
    }

    @Override
    public void refreshExpired() {
        // 简化：定时被调用时全量重建（可按 expires_at 条件改造为增量）
        rebuild();
    }

    // 已按需移除单条增量方法

    @Override
    @Async
    public void rebuildIncremental() {
        // 简化版：找出 pre_summary 中不存在的 blog 作为“新增”，只为它们生成
        List<Blog> all = blogService.list(new QueryWrapper<Blog>().select("id","title","content")); // 仅查询必要列以减少 IO
        if (all == null || all.isEmpty()) return; // 没有博客则无需处理，直接返回

        // 查询预摘要表已有的 blog_id 集合，用于判断哪些 Blog 已经处理过（做“增量”判断）
        Set<Long> existed = preSummaryMapper.selectList(new QueryWrapper<PreSummary>().select("blog_id")).stream()
                .map(PreSummary::getBlogId).collect(Collectors.toSet());

        // 遍历所有 Blog
        for (Blog b : all) {
            if (existed.contains(b.getId())) continue; // 若该 Blog 已有预摘要记录，则跳过（只处理“缺失”的）

            // inline 单条增量逻辑（避免依赖已移除的方法）
            String title = StrUtil.nullToEmpty(b.getTitle()); // 标题空值保护
            String content = StrUtil.nullToEmpty(b.getContent()).replaceAll("<[^>]+>", " ") // 去除 HTML 标签
                    .replaceAll("\\s+"," ").trim(); // 归一化空白字符，去首尾空格

            // 读取配置中的预览最大长度（默认 300），用于控制生成摘要与截断长度
            int maxLen = Optional.ofNullable(aiProperties.getSnippetMaxChars()).orElse(300);

            // 原始文本：标题 + 正文（已清洗）
            String raw = title + "\n" + content;

            // 为模型/算法准备的输入：截断到 maxLen*3，权衡摘要质量与开销
            String input = StrUtil.sub(raw, 0, Math.min(maxLen * 3, raw.length()));

            // 调用摘要生成（可能是大模型或算法）得到预览文本
            String preview = genPreview(input);

            // 提取关键词集合（用于后续关键词检索）
            Set<String> kws = extractKeywords(input);

            // 兜底：若未成功生成摘要，则退化为原文前 maxLen 个字符
            if (StrUtil.isBlank(preview)) preview = StrUtil.sub(input, 0, Math.min(maxLen, input.length()));

            // 将结果写入内存索引（便于快速搜索与拼接“轻上下文”）
            idToPreview.put(b.getId(), StrUtil.sub(preview, 0, maxLen)); // 预览内容再做一层长度保护
            idToKeywords.put(b.getId(), kws); // 关键词集合

            // 持久化到 DB：使用 UPSERT 语义（存在则更新，不存在则插入）
            // sourceHash 用 raw 的 SHA1 指纹，便于后续判断内容是否发生变化
            upsertPreSummary(b.getId(), 0, sha1(raw), StrUtil.sub(preview,0,maxLen), String.join(",",kws));
        }
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

    /**
     * 调用 OpenAI Chat Completions API，返回 AI 生成的内容
     *
     * @param systemPrompt 系统提示（system role），用于设定 AI 的背景/规则
     * @param userPrompt   用户提示（user role），用于输入实际的问题或内容
     * @return AI 回复的文本（可能为 null 表示调用失败）
     */
    private String callChat(String systemPrompt, String userPrompt) {
        try {
            // 1. 读取 API 基础配置（baseUrl / model / apiKey）
            String baseUrl = Optional.ofNullable(aiProperties.getBaseUrl())
                    .orElse("https://api.openai.com/v1");
            String model = Optional.ofNullable(aiProperties.getModel())
                    .orElse("gpt-3.5-turbo");
            String key = aiProperties.getApiKey();

            // 如果 API Key 为空，直接返回 null
            if (StrUtil.isBlank(key)) return null;

            // 2. 拼接完整的 API 地址
            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions"
                    : baseUrl + "/chat/completions";

            // 3. 设置 HTTP 请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + key);

            // 4. 组织请求体
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);

            // OpenAI Chat API 需要一个 messages 数组
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(mapOf("role", "system", "content", systemPrompt)); // 系统角色
            messages.add(mapOf("role", "user", "content", userPrompt));     // 用户角色
            body.put("messages", messages);

            // 可选参数：温度、最大生成 token 数
            Double temp = aiProperties.getTemperature();
            if (temp != null) body.put("temperature", temp);
            Integer maxTok = aiProperties.getMaxTokens();
            if (maxTok != null) body.put("max_tokens", maxTok);

            // 封装成 HttpEntity
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

            // 5. 发送 POST 请求
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    new URI(url), HttpMethod.POST, req,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            // 6. 检查响应状态和数据
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return null;

            // 7. 从响应中取出 choices 数组
            Object choices = resp.getBody().get("choices");
            if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) return null;

            // 8. 获取第一个 choice
            Object first = ((List<?>) choices).get(0);
            if (!(first instanceof Map)) return null;

            // 9. 取出 message 对象
            Object msg = ((Map<?, ?>) first).get("message");
            if (!(msg instanceof Map)) return null;

            // 10. 取出 content（AI 返回的正文）
            Object content = ((Map<?, ?>) msg).get("content");

            // 返回 AI 文本结果（可能为 null）
            return content == null ? null : content.toString();

        } catch (Exception e) {
            // 出现异常时记录日志并返回 null
            log.warn("callChat error", e);
            return null;
        }
    }


    private static Map<String,String> mapOf(String k1,String v1,String k2,String v2){
        Map<String,String> m=new HashMap<>();
        m.put(k1,v1);m.put(k2,v2);return m;
    }

    // 工具方法：生成字符串的 SHA1 哈希值（用于判断内容是否变化）
    // 返回 40 位十六进制小写字符串
    private String sha1(String text) {
        try {
            // 创建 SHA-1 摘要算法实例
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            // 将字符串转为 UTF-8 编码的字节数组（null 会转为空字符串）
            byte[] bytes = md.digest(StrUtil.nullToEmpty(text)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 出现异常时返回空字符串（避免中断业务）
            return "";
        }
    }

    // 持久化预览数据（有则更新，无则插入） —— UPSERT
    private void upsertPreSummary(Long blogId, int chunkId, String sourceHash,
                                  String summary, String keywords) {
        // 1. 查询是否已存在该 blogId + chunkId 的记录
        PreSummary exist = preSummaryMapper.selectOne(
                new QueryWrapper<PreSummary>()
                        .eq("blog_id", blogId)
                        .eq("chunk_id", chunkId)
                        .last("limit 1")
        );

        if (exist == null) {
            // 2. 不存在 → 新建记录并插入
            PreSummary ps = new PreSummary();
            ps.setBlogId(blogId);
            ps.setChunkId(chunkId);
            ps.setSourceHash(sourceHash); // 原文哈希，用于判断是否需要重新生成
            ps.setSummary(summary);       // 摘要
            ps.setKeywords(keywords);     // 关键词（逗号分隔）
            ps.setModel(aiProperties.getModel()); // 生成摘要用的模型名称
            ps.setUpdatedAt(java.time.LocalDateTime.now());
            preSummaryMapper.insert(ps);
        } else {
            // 3. 已存在 → 更新记录
            exist.setSourceHash(sourceHash);
            exist.setSummary(summary);
            exist.setKeywords(keywords);
            exist.setModel(aiProperties.getModel());
            exist.setUpdatedAt(java.time.LocalDateTime.now());
            preSummaryMapper.updateById(exist);
        }
    }

    private void ensureIndexLoadedFromDb() {
        // 1. 如果内存中的两个索引 Map（关键词索引、摘要索引）都不为空，就直接返回（说明已经加载过）
        if (!idToKeywords.isEmpty() && !idToPreview.isEmpty()) return;

        // 2. 从数据库中查询 chunk_id = 0 的所有 PreSummary 记录
        //    这里 chunk_id=0 通常代表“整篇内容”的预摘要（不是分片摘要）
        List<PreSummary> list = preSummaryMapper.selectList(
                new QueryWrapper<PreSummary>().eq("chunk_id", 0)
        );
        if (list == null || list.isEmpty()) return; // 数据库里没查到则直接返回

        // 3. 遍历数据库结果，将关键词和摘要加载到内存 Map 中
        for (PreSummary ps : list) {
            if (ps.getBlogId() == null) continue; // 没有 blogId 的记录跳过

            // 3.1 处理关键词字段
            //     - 空值转空串
            //     - 按中英文逗号分割
            //     - 去掉空白
            //     - 过滤空字符串
            //     - 使用 LinkedHashSet 去重且保持顺序
            String kw = StrUtil.nullToEmpty(ps.getKeywords());
            Set<String> set = Arrays.stream(kw.split("[，,]"))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // 3.2 填充内存索引
            idToKeywords.put(ps.getBlogId(), set);                          // 关键词索引
            idToPreview.put(ps.getBlogId(), StrUtil.nullToEmpty(ps.getSummary())); // 摘要索引
        }
    }
}


