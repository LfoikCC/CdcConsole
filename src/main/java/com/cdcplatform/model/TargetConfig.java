package com.cdcplatform.model;

import com.cdcplatform.model.Types.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Map;

public record TargetConfig(Long id, @NotBlank String name, @NotNull TargetType targetType,
                           @NotNull Map<String, Object> config, boolean enabled, OffsetDateTime createdAt) {}
