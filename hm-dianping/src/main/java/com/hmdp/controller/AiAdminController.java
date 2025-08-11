package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IPreSummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiAdminController {

    @Resource
    private IPreSummaryService preSummaryService;

    @GetMapping("/rebuild-index")
    public Result rebuildIndex() {
        preSummaryService.rebuild();
        return Result.ok("pre-summaries rebuilt");
    }

    @GetMapping("/index-stats")
    public Result indexStats() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("previews", preSummaryService.countPreviews());
        data.put("keywordSets", preSummaryService.countKeywordSets());
        return Result.ok(data);
    }
}


