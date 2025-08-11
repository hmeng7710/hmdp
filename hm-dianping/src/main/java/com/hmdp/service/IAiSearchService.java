package com.hmdp.service;

import com.hmdp.dto.Result;

public interface IAiSearchService {
    /**
     * 使用向量检索（FAISS思路的本地内存版）进行 RAG 召回，再做总结。
     */
    Result vectorSearchAndSummarize(String query);
}


