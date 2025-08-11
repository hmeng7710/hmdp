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

    // 增量：仅处理DB中的新增（或缺失）
    @GetMapping("/rebuild-incremental")
    public Result rebuildIncremental() {
        preSummaryService.rebuildIncremental();
        return Result.ok("incremental rebuild started (async)");
    }

    // 已按需移除单条增量接口

    @GetMapping("/index-stats")
    public Result indexStats() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("previews", preSummaryService.countPreviews());
        data.put("keywordSets", preSummaryService.countKeywordSets());
        return Result.ok(data);
    }
}


