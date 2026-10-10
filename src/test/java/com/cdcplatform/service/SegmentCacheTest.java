package com.cdcplatform.service;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.Types.Operation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentCacheTest {
    @Test void appendsEventToLocalSegment() throws Exception {
        Path temp = Path.of("target", "test-cache", String.valueOf(System.nanoTime()));
        var properties = new CdcProperties(); properties.getCache().setDirectory(temp); properties.getCache().setSegmentSizeMb(1);
        var cache = new SegmentCache(properties, new ObjectMapper().findAndRegisterModules()); cache.init();
        var path = cache.append(new ChangeEvent(null, 1L, Operation.CREATE, Map.of(), Map.of("id", 7), null));
        assertThat(path).exists(); assertThat(cache.totalBytes()).isPositive(); assertThat(cache.segments()).hasSize(1);
    }

    @Test void persistsAndReadsChecksummedBatchDeterministically() throws Exception {
        Path temp = Path.of("target", "test-batch-cache", String.valueOf(System.nanoTime()));
        var properties = new CdcProperties(); properties.getCache().setDirectory(temp);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var cache = new SegmentCache(properties, mapper); cache.init();
        var event = new ChangeEvent(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                9L, Operation.CREATE, Map.of(), Map.of("id", 7), Instant.parse("2026-09-29T00:00:00Z"));

        var first = cache.appendBatch(9L, 3L, "p1", "p2", List.of(event));
        var second = cache.appendBatch(9L, 3L, "p1", "p2", List.of(event));

        assertThat(first.batchId()).isEqualTo(second.batchId());
        assertThat(first.path()).exists();
        assertThat(cache.readBatch(first.path(), first.checksum())).containsExactly(event);
        assertThat(cache.readyBatches()).extracting(SegmentCache.BatchDescriptor::batchId).contains(first.batchId());
    }
}
