package com.cdcplatform.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:settings-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-settings-cache"
})
class RuntimeSettingsServiceTest {

    @Autowired
    RuntimeSettingsService settings;

    @Test
    void persistsAlertRetentionDays() {
        var updated = settings.update(new RuntimeSettingsService.Settings(128, 14, 45));

        assertThat(updated.alertRetentionDays()).isEqualTo(45);
        assertThat(settings.current().alertRetentionDays()).isEqualTo(45);
        assertThat(settings.current().segmentSizeMb()).isEqualTo(128);
        assertThat(settings.current().retentionDays()).isEqualTo(14);
    }
}
