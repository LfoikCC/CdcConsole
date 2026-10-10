package com.cdcplatform.model;

import com.cdcplatform.model.Types.Operation;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ChangeEvent(UUID id, @NotNull Long mappingId, @NotNull Operation operation,
                          @NotNull Map<String, Object> before, @NotNull Map<String, Object> after,
                          Instant capturedAt) {
    public ChangeEvent normalized() {
        return new ChangeEvent(id == null ? UUID.randomUUID() : id, mappingId, operation,
                before == null ? Map.of() : before, after == null ? Map.of() : after,
                capturedAt == null ? Instant.now() : capturedAt);
    }
}
