package com.hmdp.service;

import com.hmdp.dto.RagChunk;

import java.util.List;

public interface IVectorRagService {
    /** 初始化或刷新内存索引（从 tb_blog 读取、分块、嵌入） */
    void rebuildIndex();

    /** 基于查询语句，检索最相似的若干块 */
    List<RagChunk> searchTopK(String query, int topK);
}


