package com.cdcplatform.service;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.Operation;
import com.cdcplatform.model.Types.TargetType;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventDispatchServiceTest {
    @Test
    void timestampUsesConfiguredBusinessTimeZone() {
        var properties = new CdcProperties();
        properties.setTimeZone("Asia/Shanghai");
        var service = new EventDispatchService(null, null, new ObjectMapper(), properties);
        var mapping = new TableMapping(1L, 1L, 2L, null, "orders", null, "orders",
                Map.of("id", "id"), false, true, null, "captured_at", "TIMESTAMP", Map.of(), true);
        var event = new ChangeEvent(null, 1L, Operation.CREATE, Map.of(), Map.of("id", 7),
                Instant.parse("2026-09-19T15:57:17.888Z"));

        assertThat(service.transform(event, mapping).get("captured_at"))
                .isEqualTo(LocalDateTime.parse("2026-09-19T23:57:17.888"));
    }

    @Test
    void starRocksStreamLoadFormatsLocalTimestampAndDeleteMarker() throws Exception {
        var payload = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/test_db/orders/_stream_load", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = "{\"Status\":\"Success\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var repository = mock(ConfigRepository.class);
            var cache = mock(SegmentCache.class);
            var tasks = mock(TaskRepository.class);
            var properties = new CdcProperties();
            var mapping = new TableMapping(1L, 1L, 2L, null, "orders", null, "orders",
                    Map.of("id", "id"), false, true, null, "captured_at", "TIMESTAMP", Map.of(), true);
            when(repository.mapping(1L)).thenReturn(mapping);
            when(repository.target(2L, true)).thenReturn(new TargetConfig(2L, "sr-test", TargetType.STARROCKS_STREAM_LOAD,
                    Map.of("endpoint", "http://127.0.0.1:" + server.getAddress().getPort(), "database", "test_db",
                            "username", "test", "password", "test"), true, null));
            when(cache.append(any())).thenReturn(Path.of("test-events.ndjson"));
            when(tasks.taskIdForMapping(1L)).thenReturn(null);
            var service = new EventDispatchService(repository, cache, new ObjectMapper(), properties, tasks);
            var captured = Instant.parse("2026-09-19T15:57:17.888Z");

            var result = service.accept(new ChangeEvent(null, 1L, Operation.READ, Map.of(), Map.of("id", 7), captured));
            assertThat(result.delivered()).as(result.error()).isTrue();
            assertThat(payload.get()).contains("2026-09-19 23:57:17.888");
            assertThat(service.accept(new ChangeEvent(null, 1L, Operation.DELETE, Map.of("id", 7), Map.of(), captured)).delivered()).isTrue();
            assertThat(payload.get()).contains("\"__op\":1");
        } finally { server.stop(0); }
    }
}
