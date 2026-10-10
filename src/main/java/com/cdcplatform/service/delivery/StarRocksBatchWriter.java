package com.cdcplatform.service.delivery;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.DeliveryBatch;
import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.model.TargetConfig;
import com.cdcplatform.model.Types.IncrementalProfile;
import com.cdcplatform.model.Types.Operation;
import com.cdcplatform.repository.DeliveryBatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StarRocksBatchWriter implements TargetBatchWriter {
    private final TargetConfig target;
    private final TableMapping mapping;
    private final SyncTaskTable table;
    private final DeliveryPayloadMapper mapper;
    private final DeliveryBatchRepository batches;
    private final ObjectMapper json;
    private final int maxBytes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public StarRocksBatchWriter(TargetConfig target, TableMapping mapping, SyncTaskTable table,
                                DeliveryPayloadMapper mapper, DeliveryBatchRepository batches,
                                ObjectMapper json, CdcProperties properties) {
        this.target = target; this.mapping = mapping; this.table = table; this.mapper = mapper;
        this.batches = batches; this.json = json; this.maxBytes = properties.getDelivery().getStreamLoadMaxBytes();
    }

    @Override
    public BatchWriteResult write(DeliveryBatch batch, List<ChangeEvent> events) throws Exception {
        int maxRows = table != null && table.incrementalProfile() == IncrementalProfile.FREQUENT ? 2000 : 500;
        var parts = new ArrayList<List<Map<String, Object>>>();
        var current = new ArrayList<Map<String, Object>>();
        int currentBytes = 2;
        for (var event : events) {
            var payload = new LinkedHashMap<>(mapper.transform(event, mapping));
            payload.replaceAll((column, value) -> {
                Object normalized = TemporalValueNormalizer.normalize(column, value);
                return normalized instanceof LocalDateTime dateTime
                        ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(dateTime) : normalized;
            });
            if (event.operation() == Operation.DELETE) payload.put("__op", 1);
            int bytes = json.writeValueAsBytes(payload).length + 1;
            if (!current.isEmpty() && (current.size() >= maxRows || currentBytes + bytes > maxBytes)) {
                parts.add(current); current = new ArrayList<>(); currentBytes = 2;
            }
            current.add(payload); currentBytes += bytes;
        }
        if (!current.isEmpty()) parts.add(current);
        for (int index = 0; index < parts.size(); index++) writePart(batch, index, parts.get(index));
        return new BatchWriteResult(events.size(), parts.size());
    }

    private void writePart(DeliveryBatch batch, int partNo, List<Map<String, Object>> rows) throws Exception {
        String baseLabel = "cdc_" + batch.mappingId() + "_" + batch.batchId() + "_" + partNo;
        String label = baseLabel.length() <= 120 ? baseLabel : baseLabel.substring(0, 120);
        byte[] body = json.writeValueAsBytes(rows);
        HttpResponse<String> response = send(label, body);
        String responseBody = response.body() == null ? "" : response.body();
        var parsed = parse(responseBody);
        String status = parsed.status;
        String existing = parsed.existingStatus;
        if (response.statusCode() / 100 == 2 && "SUCCESS".equals(status)) {
            batches.upsertPart(batch.batchId(), partNo, label, "SUCCEEDED", batch.attemptCount(), responseBody); return;
        }
        if ("LABEL ALREADY EXISTS".equals(status) || "PUBLISH TIMEOUT".equals(status)) {
            String resolved = existing.isBlank() ? queryLabelState(label) : existing;
            if ("FINISHED".equals(resolved) || "VISIBLE".equals(resolved)) {
                batches.upsertPart(batch.batchId(), partNo, label, "SUCCEEDED", batch.attemptCount(), responseBody); return;
            }
            if ("CANCELLED".equals(resolved) || "ABORTED".equals(resolved)) {
                label = (baseLabel + "_r" + Math.max(1, batch.attemptCount()));
                if (label.length() > 120) label = label.substring(0, 120);
                response = send(label, body);
                responseBody = response.body() == null ? "" : response.body();
                parsed = parse(responseBody); status = parsed.status;
                if (response.statusCode() / 100 == 2 && "SUCCESS".equals(status)) {
                    batches.upsertPart(batch.batchId(), partNo, label, "SUCCEEDED", batch.attemptCount(), responseBody); return;
                }
            }
            if ("RUNNING".equals(resolved) || "LOADING".equals(resolved) || resolved.isBlank())
                throw new DeliveryException("StarRocks label 尚未完成: " + label, true);
        }
        boolean retryable = response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500;
        batches.upsertPart(batch.batchId(), partNo, label, retryable ? "RETRY_WAIT" : "FAILED",
                batch.attemptCount(), responseBody);
        throw new DeliveryException("StarRocks Stream Load failed: HTTP " + response.statusCode() + " " + responseBody,
                retryable);
    }

    private HttpResponse<String> send(String label, byte[] body) throws Exception {
        var config = target.config();
        String endpoint = required(config, "endpoint").replaceAll("/$", "");
        String database = required(config, "database");
        String auth = Base64.getEncoder().encodeToString((required(config, "username") + ":" + required(config, "password"))
                .getBytes(StandardCharsets.UTF_8));
        URI uri = URI.create(endpoint + "/api/" + database + "/" + mapping.targetTable() + "/_stream_load");
        var request = request(uri, auth, label, body);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 301 || response.statusCode() == 302 || response.statusCode() == 307 || response.statusCode() == 308) {
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new DeliveryException("StarRocks redirect missing location", true));
            response = http.send(request(uri.resolve(location), auth, label, body), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private HttpRequest request(URI uri, String auth, String label, byte[] body) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).expectContinue(true)
                .header("Authorization", "Basic " + auth).header("label", label).header("format", "json")
                .header("strip_outer_array", "true").header("strict_mode", "true").header("max_filter_ratio", "0")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private ParsedResponse parse(String body) {
        try {
            var node = json.readTree(body);
            return new ParsedResponse(node.path("Status").asText("").toUpperCase(Locale.ROOT),
                    node.path("ExistingJobStatus").asText("").toUpperCase(Locale.ROOT));
        } catch (Exception e) { return new ParsedResponse("", ""); }
    }

    private String queryLabelState(String label) {
        var config = target.config();
        try {
            URI endpoint = URI.create(required(config, "endpoint"));
            int port = Integer.parseInt(String.valueOf(config.getOrDefault("queryPort", 9030)));
            String url = "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC".formatted(endpoint.getHost(), port,
                    required(config, "database"));
            try (var connection = DriverManager.getConnection(url, required(config, "username"), required(config, "password"));
                 var statement = connection.createStatement();
                 var results = statement.executeQuery("SHOW LOAD WHERE LABEL = '" + label.replace("'", "''") + "'")) {
                if (!results.next()) return "";
                var metadata = results.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++)
                    if ("STATE".equalsIgnoreCase(metadata.getColumnLabel(i))) return results.getString(i).toUpperCase(Locale.ROOT);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String required(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing target setting: " + key);
        return value.toString();
    }

    private record ParsedResponse(String status, String existingStatus) {}
}
