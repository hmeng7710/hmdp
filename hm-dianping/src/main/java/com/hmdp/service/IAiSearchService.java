package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IAiSearchService {
    /**
     * 使用离线预摘要作为轻上下文，结合用户查询，生成凝练总结。
     * 传入的 query 可以包含已拼接的轻上下文。
     */
    Result vectorSearchAndSummarize(String query);
}


