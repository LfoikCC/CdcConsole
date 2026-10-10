package com.cdcplatform.model;

import jakarta.validation.Valid;

import java.util.List;

public record ReconciliationConfig(
        boolean enabled,
        String sourceTimeColumn,
        String targetTimeColumn,
        @Valid List<ReconciliationFieldConfig> fields) {

    public ReconciliationConfig {
        if (fields == null) fields = List.of();
        else fields = List.copyOf(fields);
    }

    public static ReconciliationConfig disabled() {
        return new ReconciliationConfig(false, null, null, List.of());
    }
}
