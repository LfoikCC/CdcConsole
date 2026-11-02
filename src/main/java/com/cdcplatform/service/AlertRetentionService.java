package com.cdcplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 告警保留策略。
 *
 * <p>只清理已确认/已解决的告警；OPEN 永远保留，避免把还没人处理的待办静默删掉。
 */
@Service
public class AlertRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AlertRetentionService.class);

    private final JdbcClient jdbc;
    private final RuntimeSettingsService settings;

    public AlertRetentionService(JdbcClient jdbc, RuntimeSettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        int days = Math.max(1, settings.current().alertRetentionDays());
        int deleted = jdbc.sql("DELETE FROM alert_event WHERE created_at<:cutoff AND status<>'OPEN'")
                .param("cutoff", OffsetDateTime.now().minusDays(days)).update();
        if (deleted > 0) {
            log.info("清理 {} 天前的已处理告警 {} 条", days, deleted);
        }
    }
}
