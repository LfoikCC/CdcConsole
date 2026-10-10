package com.cdcplatform.service;

import com.cdcplatform.repository.ConfigRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ColumnMappingService {
    private final ConfigRepository repository;
    private final ConnectionProbeService metadata;

    public ColumnMappingService(ConfigRepository repository, ConnectionProbeService metadata) {
        this.repository = repository; this.metadata = metadata;
    }

    public Suggestion suggest(long sourceId, long targetId, String sourceDatabase, String sourceSchema, String sourceTable,
                              String targetSchema, String targetTable) {
        var sourceConfig = repository.source(sourceId, true);
        String database = sourceDatabase == null || sourceDatabase.isBlank() ? sourceConfig.databaseName() : sourceDatabase;
        var sourceColumns = columns(metadata.tables(sourceConfig, database), sourceSchema, sourceTable, "source");
        var targetColumns = columns(metadata.tables(repository.target(targetId, true)), targetSchema, targetTable, "target");
        var targetByLower = new LinkedHashMap<String, String>();
        targetColumns.forEach(column -> targetByLower.putIfAbsent(column.toLowerCase(Locale.ROOT), column));
        var mappings = new ArrayList<ColumnMatch>();
        for (var source : sourceColumns) {
            String target = targetByLower.get(source.toLowerCase(Locale.ROOT));
            mappings.add(new ColumnMatch(source, target, target != null));
        }
        return new Suggestion(sourceColumns, targetColumns, mappings,
                mappings.stream().filter(ColumnMatch::matched).count());
    }

    @SuppressWarnings("unchecked")
    private List<String> columns(List<Map<String, Object>> tables, String schema, String table, String label) {
        String expectedSchema = schema == null ? "" : schema.trim();
        return tables.stream().filter(item -> table.equalsIgnoreCase(String.valueOf(item.get("table"))) &&
                        (expectedSchema.isEmpty() || expectedSchema.equalsIgnoreCase(String.valueOf(item.get("schema")))))
                .findFirst().map(item -> (List<String>) item.get("columns"))
                .orElseThrow(() -> new IllegalArgumentException("The " + label + " table was not found: " +
                        (expectedSchema.isEmpty() ? table : expectedSchema + "." + table)));
    }

    public record ColumnMatch(String sourceColumn, String targetColumn, boolean matched) {}
    public record Suggestion(List<String> sourceColumns, List<String> targetColumns,
                             List<ColumnMatch> mappings, long matchedCount) {}
}
