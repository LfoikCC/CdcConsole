package com.cdcplatform.service;

import com.cdcplatform.config.CdcProperties;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RuntimeSettingsService {
    private final JdbcClient jdbc;
    private final CdcProperties properties;

    public RuntimeSettingsService(JdbcClient jdbc, CdcProperties properties) { this.jdbc = jdbc; this.properties = properties; }

    @PostConstruct
    void load() {
        value("cache.segment-size-mb").ifPresent(v -> properties.getCache().setSegmentSizeMb(Long.parseLong(v)));
        value("cache.retention-days").ifPresent(v -> properties.getCache().setRetentionDays(Integer.parseInt(v)));
    }

    public Settings current() {
        return new Settings(properties.getCache().getSegmentSizeMb(), properties.getCache().getRetentionDays());
    }

    public Settings update(Settings settings) {
        properties.getCache().setSegmentSizeMb(settings.segmentSizeMb());
        properties.getCache().setRetentionDays(settings.retentionDays());
        save("cache.segment-size-mb", String.valueOf(settings.segmentSizeMb()));
        save("cache.retention-days", String.valueOf(settings.retentionDays()));
        return current();
    }

    private java.util.Optional<String> value(String key) {
        return jdbc.sql("SELECT setting_value FROM app_setting WHERE setting_key=:key").param("key", key).query(String.class).optional();
    }

    private void save(String key, String value) {
        int changed = jdbc.sql("UPDATE app_setting SET setting_value=:value,updated_at=CURRENT_TIMESTAMP WHERE setting_key=:key")
                .param("key", key).param("value", value).update();
        if (changed == 0) jdbc.sql("INSERT INTO app_setting(setting_key,setting_value) VALUES(:key,:value)")
                .param("key", key).param("value", value).update();
    }

    public record Settings(@Min(1) @Max(10240) long segmentSizeMb, @Min(1) @Max(3650) int retentionDays) {}
}
