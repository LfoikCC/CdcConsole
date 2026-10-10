package com.cdcplatform;

import com.cdcplatform.model.SourceConfig;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.DatabaseType;
import com.cdcplatform.model.Types.TargetType;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.service.ConnectionProbeService;
import com.cdcplatform.service.EventDispatchService;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.Types.Operation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-app-cache"
})
class CdcPlatformApplicationTest {
    @Autowired ConfigRepository repository;
    @Autowired ConnectionProbeService connections;
    @Autowired EventDispatchService dispatch;

    @Test void contextLoads() {}

    @Test void starRocksSourceUsesMysqlProtocolAndQueryPort() {
        var source = new SourceConfig(null, "starrocks-source-test", DatabaseType.STARROCKS,
                "starrocks-fe", 9030, "analytics", "root", "password", 500, false, null);

        assertThat(connections.url(source))
                .isEqualTo("jdbc:mysql://starrocks-fe:9030/analytics?useSSL=false&serverTimezone=UTC");
    }

    @Test void mysqlSourceUsesMysqlProtocol() {
        var source = new SourceConfig(null, "mysql-source-test", DatabaseType.MYSQL,
                "mysql", 3306, "test_db", "root", "password", 500, false, null);

        assertThat(connections.url(source))
                .isEqualTo("jdbc:mysql://mysql:3306/test_db?useSSL=false&serverTimezone=UTC");
    }

    @Test void mysqlConnectionDoesNotRequireDefaultDatabase() {
        var source = new SourceConfig(null, "mysql-connection-test", DatabaseType.MYSQL,
                "mysql", 3306, "", "root", "password", 500, false, null);

        assertThat(connections.url(source))
                .isEqualTo("jdbc:mysql://mysql:3306/?useSSL=false&serverTimezone=UTC");
    }

    @Test void deletingSourceAlsoDeletesMappingsAndCheckpoints() {
        long sourceId = repository.addSource(new SourceConfig(null, "source-delete-test", DatabaseType.SQLSERVER,
                "127.0.0.1", 1433, "source_db", "sa", "password", 500, false, null));
        long targetId = repository.addTarget(new TargetConfig(null, "target-delete-test", TargetType.SQLSERVER,
                Map.of("host", "127.0.0.1"), false, null));
        long mappingId = repository.addMapping(new TableMapping(null, sourceId, targetId, "dbo", "source_table",
                "dbo", "target_table", Map.of("id", "id"), false, false,
                null, null, null, Map.of(), false));
        repository.saveCheckpoint(mappingId, "0x01");

        repository.deleteSource(sourceId);

        assertThat(repository.sources()).noneMatch(source -> source.id() == sourceId);
        assertThat(repository.mappings()).noneMatch(mapping -> mapping.id() == mappingId);
        assertThat(repository.checkpoint(mappingId)).isNull();
        assertThat(repository.targets()).anyMatch(target -> target.id() == targetId);
    }

    @Test void deletingTargetAlsoDeletesMappingsAndCheckpoints() {
        long sourceId = repository.addSource(new SourceConfig(null, "source-target-delete-test", DatabaseType.SQLSERVER,
                "127.0.0.1", 1433, "source_db", "sa", "password", 500, false, null));
        long targetId = repository.addTarget(new TargetConfig(null, "target-target-delete-test", TargetType.SQLSERVER,
                Map.of("host", "127.0.0.1"), false, null));
        long mappingId = repository.addMapping(new TableMapping(null, sourceId, targetId, "dbo", "source_table",
                "dbo", "target_table", Map.of("id", "id"), false, false,
                null, null, null, Map.of(), false));
        repository.saveCheckpoint(mappingId, "0x02");

        repository.deleteTarget(targetId);

        assertThat(repository.targets()).noneMatch(target -> target.id() == targetId);
        assertThat(repository.mappings()).noneMatch(mapping -> mapping.id() == mappingId);
        assertThat(repository.checkpoint(mappingId)).isNull();
        assertThat(repository.sources()).anyMatch(source -> source.id() == sourceId);
    }

    @Test void starRocksStreamLoadUsesSupportedExpectContinue() throws Exception {
        var requestBody = new AtomicReference<String>();
        var expectHeader = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/target_db/target_table/_stream_load", exchange -> {
            expectHeader.set(exchange.getRequestHeaders().getFirst("Expect"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"Status\":\"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            long sourceId = repository.addSource(new SourceConfig(null, "stream-load-source", DatabaseType.STARROCKS,
                    "127.0.0.1", 9030, "source_db", "root", "password", 500, false, null));
            long targetId = repository.addTarget(new TargetConfig(null, "stream-load-target", TargetType.STARROCKS_STREAM_LOAD,
                    Map.of("endpoint", "http://127.0.0.1:" + server.getAddress().getPort(), "database", "target_db",
                            "username", "root", "password", "password"), true, null));
            long mappingId = repository.addMapping(new TableMapping(null, sourceId, targetId, null, "source_table",
                    null, "target_table", Map.of("id", "id"), false, false,
                    null, null, null, Map.of(), true));

            var result = dispatch.accept(new ChangeEvent(null, mappingId, Operation.CREATE, Map.of(), Map.of("id", 7), Instant.now()));

            assertThat(result.delivered()).isTrue();
            assertThat(expectHeader.get()).isEqualToIgnoringCase("100-Continue");
            assertThat(requestBody.get()).contains("\"id\":7");
        } finally {
            server.stop(0);
        }
    }

    @Test void snapshotRowsCanBeReplacedAndCleared() {
        long sourceId = repository.addSource(new SourceConfig(null, "snapshot-source", DatabaseType.STARROCKS,
                "127.0.0.1", 9030, "source_db", "root", "password", 500, false, null));
        long targetId = repository.addTarget(new TargetConfig(null, "snapshot-target", TargetType.STARROCKS_STREAM_LOAD,
                Map.of("endpoint", "http://127.0.0.1:8030", "database", "target_db", "username", "root", "password", "password"), false, null));
        long mappingId = repository.addMapping(new TableMapping(null, sourceId, targetId, null, "source_table",
                null, "target_table", Map.of("id", "id"), false, false,
                null, null, null, Map.of(), false));

        repository.replaceSnapshot(mappingId, Map.of("[1]", "{\"id\":1}"));
        assertThat(repository.snapshot(mappingId)).containsEntry("[1]", "{\"id\":1}");

        repository.replaceSnapshot(mappingId, Map.of("[2]", "{\"id\":2}"));
        assertThat(repository.snapshot(mappingId)).containsOnlyKeys("[2]");

        repository.clearSnapshot(mappingId);
        assertThat(repository.snapshot(mappingId)).isEmpty();
    }
}
