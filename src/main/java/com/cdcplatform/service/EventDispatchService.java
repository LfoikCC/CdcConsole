package com.cdcplatform.service;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.TargetType;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.ReconciliationRepository;
import com.cdcplatform.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class EventDispatchService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private final ConfigRepository repository;
    private final SegmentCache cache;
    private final ObjectMapper json;
    private final ZoneId timeZone;
    private final TaskRepository tasks;
    private final ReconciliationRepository reconciliations;
    private final DeliveryService delivery;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    public EventDispatchService(ConfigRepository repository, SegmentCache cache, ObjectMapper json, CdcProperties properties,
                                TaskRepository tasks, ReconciliationRepository reconciliations, DeliveryService delivery) {
        this.repository = repository; this.cache = cache; this.json = json;
        this.timeZone = ZoneId.of(properties.getTimeZone());
        this.tasks = tasks;
        this.reconciliations = reconciliations;
        this.delivery = delivery;
    }

    public EventDispatchService(ConfigRepository repository, SegmentCache cache, ObjectMapper json, CdcProperties properties,
                                TaskRepository tasks) {
        this(repository, cache, json, properties, tasks, null, null);
    }

    EventDispatchService(ConfigRepository repository, SegmentCache cache, ObjectMapper json, CdcProperties properties) {
        this(repository, cache, json, properties, null, null, null);
    }

    public DispatchResult accept(ChangeEvent raw) {
        var event = raw.normalized();
        if (delivery != null && tasks != null && tasks.taskIdForMapping(event.mappingId()) != null) {
            var outcome = delivery.stageAndAwait(List.of(event), event.id().toString(), event.id().toString(), 0,
                    Duration.ofMinutes(5));
            return new DispatchResult(event.id(), "batch:" + outcome.batchId(), outcome.delivered(), outcome.error());
        }
        var file = cache.append(event);
        Long deleteAuditId = null;
        try {
            var mapping = repository.mapping(event.mappingId());
            if (!mapping.enabled()) throw new IllegalStateException("Mapping is disabled");
            var target = repository.target(mapping.targetId(), true);
            if (!target.enabled()) throw new IllegalStateException("Target is disabled");
            if (tasks != null && reconciliations != null) {
                var table = tasks.tableForMapping(event.mappingId());
                var taskId = tasks.taskIdForMapping(event.mappingId());
                if (table != null && taskId != null && !table.keyColumns().isEmpty()) {
                    var sourceRow = event.operation() == Types.Operation.DELETE ? event.before() : event.after();
                    var key = sourceKey(table, sourceRow);
                    String fingerprint = fingerprint(key);
                    if (event.operation() == Types.Operation.DELETE)
                        deleteAuditId = reconciliations.createDeleteAudit(taskId, table.id(), event.mappingId(), fingerprint,
                                key, OffsetDateTime.ofInstant(event.capturedAt(), ZoneId.of(tasks.task(taskId).timeZone())));
                    else reconciliations.supersedeDelete(event.mappingId(), fingerprint);
                }
            }
            var payload = transform(event, mapping);
            switch (target.targetType()) {
                case MYSQL, SQLSERVER -> dispatchJdbc(target, mapping, event, payload);
                case KAFKA -> dispatchKafka(target, mapping, event, payload);
                case STARROCKS_STREAM_LOAD -> dispatchStarRocks(target, mapping, event, payload);
            }
            if (deleteAuditId != null) reconciliations.markDeleteDelivered(deleteAuditId);
            if (tasks != null) tasks.recordDelivery(event.mappingId(), Duration.between(event.capturedAt(), java.time.Instant.now()).toMillis());
            return new DispatchResult(event.id(), file.toString(), true, null);
        } catch (Exception e) {
            if (deleteAuditId != null && reconciliations != null) reconciliations.markDeleteFailed(deleteAuditId, rootMessage(e));
            var taskId = tasks == null ? null : tasks.taskIdForMapping(event.mappingId());
            if (taskId != null) {
                tasks.recordFailure(event.mappingId(), rootMessage(e));
                repository.setMappingEnabled(event.mappingId(), false);
                var table = tasks.tableForMapping(event.mappingId());
                tasks.addLog(taskId, table == null ? null : table.id(), "ERROR", "目标写入失败：" + rootMessage(e));
                tasks.createAlert(taskId, table == null ? null : table.id(), Types.AlertType.TABLE_FAILURE,
                        "目标写入失败", rootMessage(e));
            }
            return new DispatchResult(event.id(), file.toString(), false, rootMessage(e));
        }
    }

    public DispatchResult acceptBatch(List<ChangeEvent> rawEvents, String sourceStart, String sourceEnd, long generation) {
        if (rawEvents == null || rawEvents.isEmpty()) throw new IllegalArgumentException("delivery batch is empty");
        if (delivery == null) {
            DispatchResult last = null;
            for (var event : rawEvents) {
                last = accept(event);
                if (!last.delivered()) return last;
            }
            return last;
        }
        var outcome = delivery.stageAndAwait(rawEvents, sourceStart, sourceEnd, generation, Duration.ofMinutes(10));
        return new DispatchResult(rawEvents.getLast().id(), "batch:" + outcome.batchId(), outcome.delivered(), outcome.error());
    }

    private Map<String, Object> sourceKey(SyncTaskTable table, Map<String, Object> row) {
        var key = new LinkedHashMap<String, Object>();
        for (String column : table.keyColumns()) key.put(column, row.get(column));
        return key;
    }

    private String fingerprint(Map<String, Object> key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(key));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("无法生成删除主键指纹", e); }
    }

    Map<String, Object> transform(ChangeEvent event, TableMapping mapping) {
        var source = event.operation() == Types.Operation.DELETE ? event.before() : event.after();
        var output = new LinkedHashMap<String, Object>();
        if (mapping.columns().isEmpty()) output.putAll(source);
        else mapping.columns().forEach((from, to) -> output.put(to, source.get(from)));
        if (mapping.includeOperation()) output.put(mapping.operationColumn(), operationValue(event, mapping));
        if (mapping.includeCapturedAt()) {
            var taskId = tasks == null ? null : tasks.taskIdForMapping(mapping.id());
            ZoneId zone = taskId == null ? timeZone : ZoneId.of(tasks.task(taskId).timeZone());
            output.put(mapping.capturedAtColumn(), capturedAt(event, mapping.capturedAtFormat(), zone));
        }
        return output;
    }

    private Object capturedAt(ChangeEvent event, String format, ZoneId zone) {
        if ("TIMESTAMP".equalsIgnoreCase(format)) return LocalDateTime.ofInstant(event.capturedAt(), zone);
        if ("ISO8601".equalsIgnoreCase(format)) return event.capturedAt().toString();
        try { return DateTimeFormatter.ofPattern(format).withZone(zone).format(event.capturedAt()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid captured-at time format: " + format, e); }
    }

    private Object operationValue(ChangeEvent event, TableMapping mapping) {
        String value = mapping.operationValues().getOrDefault(event.operation().name(), event.operation().name());
        if (value.matches("-?\\d+")) {
            try { return Long.parseLong(value); } catch (NumberFormatException ignored) { }
        }
        return value;
    }

    private void dispatchJdbc(TargetConfig target, TableMapping mapping, ChangeEvent event, Map<String, Object> row) throws Exception {
        var c = target.config();
        String host = required(c, "host"), database = required(c, "database"), user = required(c, "username"), password = required(c, "password");
        int port = Integer.parseInt(String.valueOf(c.getOrDefault("port", target.targetType() == TargetType.MYSQL ? 3306 : 1433)));
        String url = target.targetType() == TargetType.MYSQL
                ? "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC".formatted(host, port, database)
                : "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true".formatted(host, port, database);
        String table = qualified(mapping.targetSchema(), mapping.targetTable(), target.targetType());
        try (var connection = DriverManager.getConnection(url, user, password)) {
            if (event.operation() == Types.Operation.DELETE) {
                var keys = configuredKeys(c, row, mapping);
                String sql = "DELETE FROM " + table + " WHERE " + String.join(" AND ", keys.stream().map(k -> quote(k, target.targetType()) + "=?").toList());
                try (var ps = connection.prepareStatement(sql)) { bind(ps, keys, row); ps.executeUpdate(); }
            } else {
                var columns = new ArrayList<>(row.keySet());
                var keys = configuredKeys(c, row, mapping);
                var updates = columns.stream().filter(column -> !keys.contains(column)).toList();
                int changed = 0;
                if (!updates.isEmpty()) {
                    String update = "UPDATE " + table + " SET " + String.join(",", updates.stream().map(x -> quote(x, target.targetType()) + "=?").toList()) +
                            " WHERE " + String.join(" AND ", keys.stream().map(k -> quote(k, target.targetType()) + "=?").toList());
                    try (var ps = connection.prepareStatement(update)) {
                        var bindings = new ArrayList<>(updates); bindings.addAll(keys); bind(ps, bindings, row); changed = ps.executeUpdate();
                    }
                }
                if (changed == 0) {
                    String insert = "INSERT INTO " + table + " (" + String.join(",", columns.stream().map(x -> quote(x, target.targetType())).toList()) + ") VALUES (" +
                            String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
                    try (var ps = connection.prepareStatement(insert)) {
                        bind(ps, columns, row);
                        ps.executeUpdate();
                    } catch (java.sql.SQLException e) {
                        if (!Set.of(1062, 2601, 2627).contains(e.getErrorCode())) throw e;
                        if (!updates.isEmpty()) {
                            String update = "UPDATE " + table + " SET " + String.join(",", updates.stream()
                                    .map(x -> quote(x, target.targetType()) + "=?").toList()) + " WHERE " +
                                    String.join(" AND ", keys.stream().map(k -> quote(k, target.targetType()) + "=?").toList());
                            try (var ps = connection.prepareStatement(update)) {
                                var bindings = new ArrayList<>(updates); bindings.addAll(keys);
                                bind(ps, bindings, row); ps.executeUpdate();
                            }
                        }
                    }
                }
            }
        }
    }

    private void dispatchKafka(TargetConfig target, TableMapping mapping, ChangeEvent event, Map<String, Object> row) throws Exception {
        var c = target.config(); var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, required(c, "bootstrapServers"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all"); props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        String topic = mapping.targetTable();
        String key = event.id().toString();
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(topic, key, json.writeValueAsString(row))).get();
        }
    }

    private void dispatchStarRocks(TargetConfig target, TableMapping mapping, ChangeEvent event, Map<String, Object> row) throws Exception {
        var c = target.config(); String endpoint = required(c, "endpoint").replaceAll("/$", "");
        String db = required(c, "database"), table = mapping.targetTable();
        var payload = new LinkedHashMap<>(row);
        payload.replaceAll((column, value) -> value instanceof LocalDateTime dateTime
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(dateTime) : value);
        if (event.operation() == Types.Operation.DELETE) payload.put("__op", 1);
        var body = json.writeValueAsString(List.of(payload));
        var auth = Base64.getEncoder().encodeToString((required(c, "username") + ":" + required(c, "password")).getBytes(StandardCharsets.UTF_8));
        var uri = URI.create(endpoint + "/api/" + db + "/" + table + "/_stream_load");
        var response = sendStreamLoad(uri, auth, body);
        if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307 || response.statusCode() == 308) {
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IllegalStateException("StarRocks Stream Load redirect has no location"));
            response = sendStreamLoad(uri.resolve(location), auth, body);
        }
        if (response.statusCode() / 100 != 2 || !response.body().contains("Success"))
            throw new IllegalStateException("StarRocks Stream Load failed: HTTP " + response.statusCode() + " " + response.body());
    }

    private HttpResponse<String> sendStreamLoad(URI uri, String auth, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth).header("format", "json")
                .header("strip_outer_array", "true").expectContinue(true)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> configuredKeys(Map<String, Object> config, Map<String, Object> row, TableMapping mapping) {
        if (tasks != null) {
            var taskTable = tasks.tableForMapping(mapping.id());
            if (taskTable != null && !taskTable.keyColumns().isEmpty()) {
                var mapped = taskTable.keyColumns().stream().map(key -> mapping.columns().getOrDefault(key, key)).toList();
                if (mapped.stream().allMatch(row::containsKey)) return mapped;
            }
        }
        Object raw = config.get("keyColumns");
        if (raw instanceof List<?> list && !list.isEmpty()) return list.stream().map(String::valueOf).toList();
        var id = row.keySet().stream().filter(k -> "id".equalsIgnoreCase(k)).findFirst();
        if (id.isPresent()) return List.of(id.get());
        throw new IllegalArgumentException("DELETE requires target config keyColumns (or an id column)");
    }
    private void bind(java.sql.PreparedStatement ps, List<String> columns, Map<String, Object> row) throws Exception { for (int i = 0; i < columns.size(); i++) ps.setObject(i + 1, row.get(columns.get(i))); }
    private String qualified(String schema, String table, TargetType type) { return schema == null || schema.isBlank() ? quote(table, type) : quote(schema, type) + "." + quote(table, type); }
    private String quote(String value, TargetType type) { if (!IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException("Invalid SQL identifier: " + value); return type == TargetType.MYSQL ? "`" + value + "`" : "[" + value + "]"; }
    private String required(Map<String, Object> map, String key) { var value = map.get(key); if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing target setting: " + key); return value.toString(); }
    private String rootMessage(Throwable e) { var t = e; while (t.getCause() != null) t = t.getCause(); return t.getMessage(); }
    public record DispatchResult(UUID eventId, String cacheFile, boolean delivered, String error) {}
}
