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
        // 1) 查询前使用预摘要索引（若为空可手动重建）
        if (preSummaryService.countPreviews() == 0) {
            preSummaryService.rebuild();
        }
        // 2) 简单切词（按空白和标点拆分），并筛去空串
        String norm = q == null ? "" : q.trim();
        String[] arr = norm.replaceAll("[，,。.!?；;]"," ").split("\\s+");
        java.util.List<String> terms = new java.util.ArrayList<>();
        for (String s : arr) if (s != null && s.length()>0) terms.add(s);
        java.util.Set<Long> ids = preSummaryService.searchByKeywords(terms, 10);
        if (ids.isEmpty()) return Result.ok("未检索到相关内容");
        // 3) 轻上下文拼接：优先使用预摘要，必要时带少量原文片段
        StringBuilder ctx = new StringBuilder();
        int limitChars = 1200; // 轻上下文限长
        for (Long id : ids) {
            String pv = preSummaryService.getLightContext(id);
            String piece = "[笔记#"+id+"] " + pv + "\n\n";
            if (ctx.length()+piece.length()>limitChars) break;
            ctx.append(piece);
        }
        // 4) 调用大模型生成最终凝练总结
        return aiSearchService.vectorSearchAndSummarize(q + "\n以下为离线预摘要：\n" + ctx.toString());
    }
}


