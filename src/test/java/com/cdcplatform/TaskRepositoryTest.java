package com.cdcplatform;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task-repository-test;DB_CLOSE_DELAY=-1",
        "cdc.cache.directory=target/test-task-cache"
})
class TaskRepositoryTest {
    @Autowired TaskRepository tasks;
    @Autowired ConfigRepository configs;

    @Test void createsMultiTableTaskAndTransitionsThroughPauseAndStop() {
        long sourceId = configs.addSource(new SourceConfig(null, "task-source", DatabaseType.MYSQL,
                "127.0.0.1", 3306, "", "root", "password", 500, false, null));
        long targetId = configs.addTarget(new TargetConfig(null, "task-target", TargetType.STARROCKS_STREAM_LOAD,
                Map.of("endpoint", "http://127.0.0.1:18030", "database", "target_db", "username", "root", "password", "password"), false, null));
        var first = table("orders");
        var second = table("order_items");
        long id = tasks.create(new SyncTask(null, "orders CDC", "multi-table", sourceId, targetId,
                SyncMode.FULL_INCREMENTAL, null, "Asia/Shanghai", null, null, null, null,
                List.of(first, second), null, null));

        var created = tasks.task(id);
        assertThat(created.status()).isEqualTo(TaskStatus.DRAFT);
        assertThat(created.tables()).hasSize(2);
        assertThat(created.tables()).allMatch(table -> !configs.mapping(table.mappingId()).enabled());
        long instanceId = tasks.start(id, false);
        assertThat(tasks.task(id).status()).isEqualTo(TaskStatus.SNAPSHOTTING);
        assertThat(tasks.instances(id)).extracting(OperationsModels.TaskInstance::id).contains(instanceId);
        assertThat(tasks.task(id).tables()).allMatch(table -> configs.mapping(table.mappingId()).enabled());

        tasks.pause(id);
        assertThat(tasks.task(id).status()).isEqualTo(TaskStatus.PAUSED);
        assertThat(tasks.task(id).tables()).allMatch(table -> !configs.mapping(table.mappingId()).enabled());
        tasks.start(id, true);
        tasks.stop(id);
        assertThat(tasks.task(id).status()).isEqualTo(TaskStatus.STOPPED);
    }

    @Test void rejectsStaleVersionAndPreventsReferencedConnectionDeletion() {
        long sourceId = configs.addSource(new SourceConfig(null, "version-source", DatabaseType.MYSQL,
                "127.0.0.1", 3306, "", "root", "password", 500, false, null));
        long targetId = configs.addTarget(new TargetConfig(null, "version-target", TargetType.MYSQL,
                Map.of("host", "127.0.0.1", "port", 3306, "database", "target_db", "username", "root", "password", "password"), false, null));
        long id = tasks.create(new SyncTask(null, "version test", "", sourceId, targetId,
                SyncMode.INCREMENTAL_ONLY, null, "Asia/Shanghai", null, null, null, null,
                List.of(table("orders")), null, null));
        var stale = tasks.task(id);

        assertThatThrownBy(() -> configs.deleteSource(sourceId)).isInstanceOf(IllegalStateException.class).hasMessageContaining("同步任务");
        tasks.rename(id, "renamed", "changed");
        assertThatThrownBy(() -> tasks.update(id, stale)).isInstanceOf(IllegalStateException.class).hasMessageContaining("刷新");
        assertThat(tasks.task(id).tables()).hasSize(1);
    }

    @Test void pauseAndResumeLeaveFailedTableDisabled() {
        long sourceId = configs.addSource(new SourceConfig(null, "mixed-source", DatabaseType.MYSQL,
                "127.0.0.1", 3306, "", "root", "password", 500, false, null));
        long targetId = configs.addTarget(new TargetConfig(null, "mixed-target", TargetType.MYSQL,
                Map.of("host", "127.0.0.1", "database", "target_db", "username", "root", "password", "password"), false, null));
        long id = tasks.create(new SyncTask(null, "mixed task", "", sourceId, targetId,
                SyncMode.FULL_INCREMENTAL, null, "Asia/Shanghai", null, null, null, null,
                List.of(table("orders"), table("items")), null, null));
        tasks.start(id, false);
        var failed = tasks.task(id).tables().getFirst();
        var healthy = tasks.task(id).tables().get(1);
        tasks.recordFailure(failed.mappingId(), "DDL changed");
        configs.setMappingEnabled(failed.mappingId(), false);
        tasks.pause(id);
        assertThat(tasks.task(id).tables().getFirst().status()).isEqualTo(TableStatus.FAILED);
        tasks.start(id, true);
        assertThat(configs.mapping(failed.mappingId()).enabled()).isFalse();
        assertThat(configs.mapping(healthy.mappingId()).enabled()).isTrue();
        assertThat(tasks.task(id).tables().getFirst().lastError()).isEqualTo("DDL changed");
        tasks.stop(id);
    }

    private SyncTaskTable table(String name) {
        return new SyncTaskTable(null, null, "test_db", null, name, null, name,
                Map.of("id", "id"), List.of("id"), false, false, null, null, null,
                Map.of(), null, null, null, null, 0, 0, null, null, null);
    }
}
