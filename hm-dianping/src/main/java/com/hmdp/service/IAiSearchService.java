package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IAiSearchService {
    /**
     * 基于关键词，从 tb_blog 检索 title/content，构造 RAG 上下文并请求大模型，返回中文凝练总结。
     */
    Result searchAndSummarize(String query);
}


