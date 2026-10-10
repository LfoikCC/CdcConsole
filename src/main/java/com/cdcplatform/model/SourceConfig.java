package com.cdcplatform.model;

import com.cdcplatform.model.Types.DatabaseType;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public record SourceConfig(Long id, @NotBlank String name, @NotNull DatabaseType dbType,
                           @NotBlank String host, @Min(1) @Max(65535) int port,
                           String databaseName, @NotBlank String username,
                           String password, @Min(100) int pollIntervalMs,
                           boolean enabled, OffsetDateTime createdAt) {
    public SourceConfig {
        if (databaseName == null) databaseName = "";
        if (pollIntervalMs == 0) pollIntervalMs = 500;
    }
}
