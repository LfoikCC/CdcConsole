package com.cdcplatform.service.delivery;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.DeliveryBatchRepository;
import com.cdcplatform.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TargetWriterRegistry {
    private final ConfigRepository configs;
    private final TaskRepository tasks;
    private final DeliveryPayloadMapper mapper;
    private final DeliveryBatchRepository batches;
    private final ObjectMapper json;
    private final CdcProperties properties;
    private final Map<Long, TargetBatchWriter> writers = new ConcurrentHashMap<>();

    public TargetWriterRegistry(ConfigRepository configs, TaskRepository tasks, DeliveryPayloadMapper mapper,
                                DeliveryBatchRepository batches, ObjectMapper json, CdcProperties properties) {
        this.configs = configs; this.tasks = tasks; this.mapper = mapper; this.batches = batches;
        this.json = json; this.properties = properties;
    }

    public TargetBatchWriter writer(long mappingId) {
        return writers.computeIfAbsent(mappingId, this::create);
    }

    private TargetBatchWriter create(long mappingId) {
        var mapping = configs.mapping(mappingId);
        var target = configs.target(mapping.targetId(), true);
        var table = tasks.tableForMapping(mappingId);
        return switch (target.targetType()) {
            case MYSQL -> new MySqlBatchWriter(target, mapping, table, mapper);
            case SQLSERVER -> new SqlServerBatchWriter(target, mapping, table, mapper);
            case STARROCKS_STREAM_LOAD -> new StarRocksBatchWriter(target, mapping, table, mapper, batches, json, properties);
            case KAFKA -> new KafkaBatchWriter(target, mapping, mapper, json);
        };
    }

    public void invalidate(long mappingId) {
        var writer = writers.remove(mappingId);
        if (writer != null) try { writer.close(); } catch (Exception ignored) {}
    }

    @PreDestroy void shutdown() { writers.keySet().forEach(this::invalidate); }
}
