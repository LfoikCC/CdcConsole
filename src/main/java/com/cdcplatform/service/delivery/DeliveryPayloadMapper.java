package com.cdcplatform.service.delivery;

import com.cdcplatform.config.CdcProperties;
import com.cdcplatform.model.ChangeEvent;
import com.cdcplatform.model.SyncTaskTable;
import com.cdcplatform.model.TableMapping;
import com.cdcplatform.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeliveryPayloadMapper {
    private final TaskRepository tasks;
    private final ZoneId defaultZone;

    public DeliveryPayloadMapper(TaskRepository tasks, CdcProperties properties) {
        this.tasks = tasks; this.defaultZone = ZoneId.of(properties.getTimeZone());
    }

    public Map<String, Object> transform(ChangeEvent event, TableMapping mapping) {
        var source = event.operation() == com.cdcplatform.model.Types.Operation.DELETE ? event.before() : event.after();
        var output = new LinkedHashMap<String, Object>();
        if (mapping.columns().isEmpty()) output.putAll(source);
        else mapping.columns().forEach((from, to) -> output.put(to, source.get(from)));
        if (mapping.includeOperation()) output.put(mapping.operationColumn(), operationValue(event, mapping));
        if (mapping.includeCapturedAt()) {
            Long taskId = tasks.taskIdForMapping(mapping.id());
            ZoneId zone = taskId == null ? defaultZone : ZoneId.of(tasks.task(taskId).timeZone());
            output.put(mapping.capturedAtColumn(), capturedAt(event, mapping.capturedAtFormat(), zone));
        }
        return output;
    }

    public List<String> targetKeys(TableMapping mapping, SyncTaskTable table, Map<String, Object> row) {
        if (table != null && !table.keyColumns().isEmpty()) {
            var mapped = table.keyColumns().stream().map(key -> mapping.columns().getOrDefault(key, key)).toList();
            if (mapped.stream().allMatch(row::containsKey)) return mapped;
        }
        var id = row.keySet().stream().filter(k -> "id".equalsIgnoreCase(k)).findFirst();
        if (id.isPresent()) return List.of(id.get());
        throw new IllegalArgumentException("目标批量写入需要完整主键映射");
    }

    private Object capturedAt(ChangeEvent event, String format, ZoneId zone) {
        if ("TIMESTAMP".equalsIgnoreCase(format)) return LocalDateTime.ofInstant(event.capturedAt(), zone);
        if ("ISO8601".equalsIgnoreCase(format)) return event.capturedAt().toString();
        return DateTimeFormatter.ofPattern(format).withZone(zone).format(event.capturedAt());
    }

    private Object operationValue(ChangeEvent event, TableMapping mapping) {
        String value = mapping.operationValues().getOrDefault(event.operation().name(), event.operation().name());
        if (value.matches("-?\\d+")) try { return Long.parseLong(value); } catch (NumberFormatException ignored) {}
        return value;
    }
}
