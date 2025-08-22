package com.hmdp.service;

import java.util.Set;

public interface IPreSummaryService {
    void rebuild();
    int countPreviews();
    int countKeywordSets();
    Set<Long> searchByKeywords(java.util.List<String> keywords, int limit);
    String getLightContext(Long blogId);
    void refreshExpired();

    // 增量重建：仅处理 DB 中新增的或内容已变化/过期的数据
    void rebuildIncremental();
}


