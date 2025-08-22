package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IPreSummaryService;
import com.hmdp.service.IAiSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiSearchController {

    @Resource
    private IAiSearchService aiSearchService;
    @Resource
    private IPreSummaryService preSummaryService;

    /**
     * GET /ai/search?q=关键词  方案D：预摘要+关键词检索+轻上下文
     */
    @GetMapping("/search")
    public Result vectorSearch(@RequestParam("q") String q) {
        // 1) 如果预摘要索引为空，则先重建一次（保证有可用数据）
        if (preSummaryService.countPreviews() == 0) {
            preSummaryService.rebuild();
        }

        // 2) 对用户查询 q 做简单分词（按空格、标点拆），去掉空词
        String norm = q == null ? "" : q.trim();
        String[] arr = norm.replaceAll("[，,。.!?；;]"," ").split("\\s+");
        java.util.List<String> terms = new java.util.ArrayList<>();
        for (String s : arr) if (s != null && s.length() > 0) terms.add(s);

        // 用关键词匹配的方式搜索预摘要，最多取 10 个匹配的 blogId
        java.util.Set<Long> ids = preSummaryService.searchByKeywords(terms, 10);
        if (ids.isEmpty()) return Result.ok("未检索到相关内容");

        // 3) 构建“轻上下文”：优先用预摘要，必要时加少量原文片段（限长 1200 字符）
        StringBuilder ctx = new StringBuilder();
        int limitChars = 1200;
        for (Long id : ids) {
            String pv = preSummaryService.getLightContext(id); // 根据 id 获取轻量上下文（通常是预摘要）
            String piece = "[笔记#" + id + "] " + pv + "\n\n";  // 给每段加标签，方便区分来源
            if (ctx.length() + piece.length() > limitChars) break; // 如果加上这段就超长，直接停止循环
            ctx.append(piece); // 否则追加到上下文
        }

        // 4) 把用户原始问题 + 离线预摘要上下文 一起送给大模型生成总结
        return aiSearchService.vectorSearchAndSummarize(
                q + "\n以下为离线预摘要：\n" + ctx.toString()
        );

    }
}


