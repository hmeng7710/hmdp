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
        preSummaryService.refreshExpired();
        log.info("[PreSummaryScheduler] finish 7-day refresh");
    }
}


