package com.hmdp.controller;

import com.hmdp.dto.Result;
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

    /**
     * GET /ai/search?q=关键词  向量检索RAG（方案二）
     */
    @GetMapping("/search")
    public Result vectorSearch(@RequestParam("q") String q) {
        return aiSearchService.vectorSearchAndSummarize(q);
    }
}


