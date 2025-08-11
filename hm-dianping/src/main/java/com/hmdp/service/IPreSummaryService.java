package com.hmdp.service;

import java.util.Set;

public interface IPreSummaryService {
    void rebuild();
    int countPreviews();
    int countKeywordSets();
    Set<Long> searchByKeywords(java.util.List<String> keywords, int limit);
    String getLightContext(Long blogId);
}


