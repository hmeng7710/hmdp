package com.hmdp.config;

import com.hmdp.service.IPreSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PreSummaryScheduler {

    private final IPreSummaryService preSummaryService;

    // 每7天凌晨3点执行一次（CRON：秒 分 时 日 月 周）
    @Scheduled(cron = "0 0 3 */7 * ?")
    public void refreshEvery7Days() {
        log.info("[PreSummaryScheduler] start 7-day refresh");
        // 仅处理新增/缺失（过去任意时段新增都会在此被补齐）
        preSummaryService.rebuildIncremental();
        log.info("[PreSummaryScheduler] finish 7-day refresh");
    }
}


