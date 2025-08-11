package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVectorRagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai")
public class AiAdminController {

    @Resource
    private IVectorRagService vectorRagService;

    @GetMapping("/rebuild-index")
    public Result rebuildIndex() {
        vectorRagService.rebuildIndex();
        return Result.ok("rebuild started/finished");
    }

    @GetMapping("/index-stats")
    public Result indexStats() {
        try {
            java.lang.reflect.Method m1 = vectorRagService.getClass().getMethod("chunkSize");
            java.lang.reflect.Method m2 = vectorRagService.getClass().getMethod("embeddingSize");
            Object cs = m1.invoke(vectorRagService);
            Object es = m2.invoke(vectorRagService);
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("chunks", cs);
            data.put("embeddings", es);
            return Result.ok(data);
        } catch (Exception e) {
            return Result.ok("stats unavailable");
        }
    }
}


