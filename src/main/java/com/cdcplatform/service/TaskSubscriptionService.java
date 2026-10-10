package com.cdcplatform.service;

import com.cdcplatform.model.*;
import com.cdcplatform.model.Types.*;
import com.cdcplatform.repository.ConfigRepository;
import com.cdcplatform.repository.TaskRepository;
import com.cdcplatform.repository.TaskSubscriptionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Makes target delivery a first-class child of a task while preserving the
 * established mapping and delivery engines underneath. Each extra target gets
 * an internal runtime mapping, never a second user-visible task.
 */
@Service
public class TaskSubscriptionService {
    private final TaskRepository tasks;
    private final TaskSubscriptionRepository subscriptions;
    private final ConfigRepository configs;
    private final CaptureGroupService captureGroups;
    private final ConnectionProbeService connections;
    private final DeliveryService delivery;
    private final SegmentCache cache;
    private final RuntimeEventService events;
    private final ObjectMapper json;
    private final Set<Long> activeInitializations = ConcurrentHashMap.newKeySet();

    public TaskSubscriptionService(TaskRepository tasks, TaskSubscriptionRepository subscriptions, ConfigRepository configs,
                                   CaptureGroupService captureGroups, ConnectionProbeService connections,
                                   DeliveryService delivery, SegmentCache cache, ObjectMapper json, RuntimeEventService events) {
        this.tasks=tasks; this.subscriptions=subscriptions; this.configs=configs; this.captureGroups=captureGroups;
        this.connections=connections; this.delivery=delivery; this.cache=cache; this.json=json; this.events=events;
    }

    @PostConstruct
    void migrateLegacyTasks() {
        for (var task : tasks.tasks()) {
            try {
                ensureDefaultSubscription(task.id());
                subscriptions.subscriptions(task.id()).forEach(subscription -> subscription.tables().forEach(binding -> {
                    if ("ACTIVE".equalsIgnoreCase(binding.status()))
                        subscriptions.updateRuntimeStatus(binding.runtimeTaskTableId(), TableStatus.RUNNING);
                }));
            } catch (Exception ignored) { }
        }
    }

    public List<TaskTargetSubscription> list(long taskId) { ensureDefaultSubscription(taskId); return subscriptions.subscriptions(taskId); }
    public TaskTargetSubscription get(long taskId, long subscriptionId) {
        var value=subscriptions.subscription(subscriptionId);
        if(value.taskId()!=taskId) throw new IllegalArgumentException("target subscription is not attached to this task");
        return value;
    }

    public Map<String,Object> options(long taskId) {
        var task=tasks.task(taskId);
        var source=configs.source(task.sourceId(),true);
        var metadata=new HashMap<String,Map<String,Object>>();
        try {
            for(var database:task.tables().stream().map(SyncTaskTable::sourceDatabase).distinct().toList())
                for(var item:connections.tables(source,database)) metadata.put(tableKey(database,string(item.get("schema")),string(item.get("table"))),item);
        } catch(Exception ignored) { }
        var rows=task.tables().stream().map(table -> {
            Map<String,Object> row=json.convertValue(table,new TypeReference<LinkedHashMap<String,Object>>(){});
            var item=metadata.get(tableKey(table.sourceDatabase(),table.sourceSchema(),table.sourceTable()));
            if(item!=null){row.put("sourceColumns",item.getOrDefault("columns",List.of()));row.put("sourceColumnDetails",item.getOrDefault("columnDetails",List.of()));}
            return row;
        }).toList();
        return Map.of("task",task,"source",source,"tables",rows,"targets",configs.targets());
    }

    public Map<String,Object> reconciliationOptions(long taskId, long subscriptionId) {
        var value=new LinkedHashMap<String,Object>(options(taskId));
        value.put("subscription", get(taskId, subscriptionId));
        return value;
    }

    @Transactional
    public void updateReconciliation(long taskId, long subscriptionId, Map<String,Object> body) {
        var subscription=get(taskId, subscriptionId);
        var task=tasks.task(taskId);
        Object raw=body.get("tables");
        if(!(raw instanceof List<?> list)) throw new IllegalArgumentException("tables is required");
        var byBinding=new HashMap<Long,Map<String,Object>>();
        for(Object item:list) if(item instanceof Map<?,?> map) {
            var row=new LinkedHashMap<String,Object>(); map.forEach((key,value)->row.put(String.valueOf(key),value));
            long bindingId=number(row,"subscriptionTableId");
            if(byBinding.put(bindingId,row)!=null) throw new IllegalArgumentException("同一订阅表不能重复提交核对配置");
        }
        if(byBinding.size()!=subscription.tables().size()) throw new IllegalArgumentException("必须提交目标订阅下全部表的核对配置");
        for(var binding:subscription.tables()) {
            var row=byBinding.get(binding.id());
            if(row==null) throw new IllegalArgumentException("缺少订阅表核对配置");
            var reconciliation=readRecon(row.get("reconciliation"), ReconciliationConfig.disabled());
            validateReconciliation(task,binding.mapping(),reconciliation);
            subscriptions.updateRuntimeReconciliation(binding.runtimeTaskTableId(),reconciliation);
        }
        tasks.addLog(taskId,null,"INFO","目标订阅数据核对配置已更新："+subscription.name());
        publishStatus(subscriptionId);
    }

    @Transactional
    public void updateMappings(long taskId, long subscriptionId, Map<String,Object> body) {
        var subscription=get(taskId,subscriptionId);
        var task=tasks.task(taskId);
        Object raw=body.get("tables");
        if(!(raw instanceof List<?> list)) throw new IllegalArgumentException("tables is required");
        var requested=new HashMap<Long,Map<String,Object>>();
        for(Object item:list) if(item instanceof Map<?,?> map) {
            var row=new LinkedHashMap<String,Object>(); map.forEach((key,value)->row.put(String.valueOf(key),value));
            long bindingId=number(row,"subscriptionTableId");
            if(requested.put(bindingId,row)!=null) throw new IllegalArgumentException("同一订阅表不能重复提交");
        }
        if(requested.size()!=subscription.tables().size()) throw new IllegalArgumentException("必须提交目标订阅下全部表的投递映射");
        for(var binding:subscription.tables()) {
            var row=requested.get(binding.id());
            if(row==null) throw new IllegalArgumentException("缺少订阅表投递映射");
            var source=task.tables().stream().filter(table -> table.id()==binding.sourceTaskTableId()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("订阅源表不存在"));
            var next=targetMapping(task,source,subscription.targetId(),row);
            validateTargetMapping(source,next);
            long mappingId=binding.mappingId();
            delivery.cancel(mappingId);
            var persisted=new TableMapping(mappingId,next.sourceId(),next.targetId(),next.sourceDatabase(),next.sourceSchema(),next.sourceTable(),
                    next.targetSchema(),next.targetTable(),next.columns(),next.includeOperation(),next.includeCapturedAt(),next.operationColumn(),
                    next.capturedAtColumn(),next.capturedAtFormat(),next.operationValues(),true);
            configs.updateMapping(mappingId,persisted);
            subscriptions.updateRuntimeMapping(binding.runtimeTaskTableId(),persisted,binding.mapping().reconciliation());
            subscriptions.restartTable(binding.id());
            captureGroups.setMappingSubscriptionStatus(mappingId,"INITIALIZING_SNAPSHOT",null);
        }
        subscriptions.updateSubscriptionState(subscriptionId,"INITIALIZING_SNAPSHOT",null,false);
        tasks.addLog(taskId,null,"INFO","目标订阅投递映射已更新，正在重新初始化："+subscription.name());
        publishStatus(subscriptionId);
        initialize(subscriptionId);
    }

    /** Adds one complete target across every source table in the task. */
    @Transactional
    public long create(long taskId, Map<String,Object> body) {
        var task=tasks.task(taskId);
        long targetId=number(body,"targetId");
        var target=configs.target(targetId,true);
        if(!target.enabled()) throw new IllegalArgumentException("目标连接已停用");
        List<Map<String,Object>> rows=tableRows(task,body);
        if(rows.size()!=task.tables().size()) throw new IllegalArgumentException("每个目标订阅必须为任务中的全部源表配置目标映射");
        var bySource=new HashMap<Long,Map<String,Object>>();
        for(var row:rows) {
            long sourceId=number(row,"sourceTaskTableId");
            if(bySource.put(sourceId,row)!=null) throw new IllegalArgumentException("同一源表不能重复配置目标映射");
        }
        for(var source:task.tables()) if(!bySource.containsKey(source.id()))
            throw new IllegalArgumentException("缺少源表目标映射："+source.sourceTable());
        String name=text(body,"name");
        if(name==null||name.isBlank()) name=target.name();
        long subscriptionId=subscriptions.createSubscription(taskId,targetId,name.trim(),textOr(body,"description",""),"INITIALIZING_SNAPSHOT");
        for(var source:task.tables()) {
            var row=bySource.get(source.id());
            var mapping=targetMapping(task,source,targetId,row);
            var reconciliation=readRecon(row.get("reconciliation"),source.reconciliation());
            validateTargetMapping(source,mapping);
            long mappingId=configs.addMapping(mapping);
            var persisted=configs.mapping(mappingId);
            long runtimeTableId=subscriptions.createRuntimeTable(subscriptionId,taskId,source,persisted,reconciliation);
            long groupId=captureGroups.ensureForMapping(source.mappingId());
            captureGroups.ensureForMapping(mappingId);
            captureGroups.setMappingSubscriptionStatus(mappingId,"INITIALIZING_SNAPSHOT",null);
            subscriptions.createTable(subscriptionId,source.id(),runtimeTableId,mappingId,groupId,"INITIALIZING_SNAPSHOT");
        }
        tasks.addLog(taskId,null,"INFO","目标订阅已创建："+name+"，正在自动初始化");
        if(isActive(task.status())) initialize(subscriptionId);
        else updateSubscriptionState(subscriptionId,"PAUSED","任务尚未启动，启动后将自动初始化",false);
        return subscriptionId;
    }

    public void initialize(long subscriptionId) {
        if(!activeInitializations.add(subscriptionId)) return;
        Thread.startVirtualThread(() -> {
            try { runInitialization(subscriptionId); } finally { activeInitializations.remove(subscriptionId); }
        });
    }

    public void initializeForTask(long taskId) {
        for(long id:subscriptions.pendingInitializations(taskId)) initialize(id);
    }

    public void action(long taskId,long subscriptionId,String action) {
        var subscription=get(taskId,subscriptionId);
        switch(action.toLowerCase(Locale.ROOT)) {
            case "pause" -> updateSubscriptionState(subscriptionId,"PAUSED",null,false);
            case "resume","retry-initialization" -> {
                updateSubscriptionState(subscriptionId,"INITIALIZING_SNAPSHOT",null,false); initialize(subscriptionId);
            }
            case "cancel" -> {
                updateSubscriptionState(subscriptionId,"CANCELLED",null,false);
                subscription.tables().forEach(table -> captureGroups.setMappingSubscriptionStatus(table.mappingId(),"CANCELLED",null));
            }
            default -> throw new IllegalArgumentException("unsupported target subscription action: "+action);
        }
    }

    private void ensureDefaultSubscription(long taskId) {
        if(subscriptions.hasSubscriptions(taskId)) return;
        var task=tasks.task(taskId);
        long id=subscriptions.createSubscription(taskId,task.targetId(),"默认目标订阅","由历史单目标任务自动迁移","ACTIVE");
        for(var table:task.tables()) {
            long groupId=captureGroups.ensureForMapping(table.mappingId());
            subscriptions.createTable(id,table.id(),table.id(),table.mappingId(),groupId,"ACTIVE");
        }
    }

    private void runInitialization(long subscriptionId) {
        var subscription=subscriptions.subscription(subscriptionId);
        var task=tasks.task(subscription.taskId());
        if(!isActive(task.status())) { updateSubscriptionState(subscriptionId,"PAUSED","任务尚未运行",false); return; }
        updateSubscriptionState(subscriptionId,"INITIALIZING_SNAPSHOT",null,false);
        try {
            for(var table:subscription.tables()) initializeTable(task,subscription,table);
            updateSubscriptionState(subscriptionId,"ACTIVE",null,true);
            tasks.addLog(task.id(),null,"INFO","目标订阅初始化完成："+subscription.name());
        } catch(Exception e) {
            String error=root(e);
            updateSubscriptionState(subscriptionId,"FAILED",error,false);
            tasks.addLog(task.id(),null,"ERROR","目标订阅初始化失败："+subscription.name()+"："+error);
        }
    }

    private void initializeTable(SyncTask task, TaskTargetSubscription subscription, TaskTargetSubscriptionTable binding) throws Exception {
        var runtime=binding.mapping();
        var source=configs.source(task.sourceId(),true);
        OffsetDateTime barrier=OffsetDateTime.now();
        updateTableState(subscription.id(),binding,"INITIALIZING_SNAPSHOT",null,false);
        captureGroups.setMappingSubscriptionStatus(binding.mappingId(),"INITIALIZING_SNAPSHOT",null);
        // Live fan-out stays disabled while snapshot and catch-up batches are delivered.
        // Mapping edits cancel the old writer and leave delivery suspended.
        delivery.resume(binding.mappingId());
        scanSnapshot(source,runtime);
        updateTableState(subscription.id(),binding,"CATCHING_UP",null,false);
        updateSubscriptionState(subscription.id(),"CATCHING_UP",null,false);
        // Replay all shared source batches created since the snapshot boundary.
        // Re-reading immutable ready files avoids a second CDC consumer.
        for(int pass=0; pass<3; pass++) {
            int replayed=replaySince(binding.captureGroupId(),runtime.sourceTable(),runtime.mappingId(),barrier);
            if(replayed==0) break;
        }
        captureGroups.setMappingSubscriptionStatus(binding.mappingId(),"ACTIVE",null);
        subscriptions.updateRuntimeStatus(binding.runtimeTaskTableId(),TableStatus.RUNNING);
        updateTableState(subscription.id(),binding,"ACTIVE",null,true);
    }

    private void scanSnapshot(SourceConfig source, SyncTaskTable table) throws Exception {
        List<String> columns=new ArrayList<>(table.columns().keySet());
        for(String key:table.keyColumns()) if(!columns.contains(key)) columns.add(key);
        if(columns.isEmpty()) throw new IllegalArgumentException("目标订阅没有可读取的源字段");
        String sql="SELECT "+String.join(",",columns.stream().map(column -> quote(source.dbType(),column)).toList())+" FROM "+qualified(source.dbType(),table.sourceSchema(),table.sourceTable());
        try(var connection=DriverManager.getConnection(connections.streamingUrl(source,table.sourceDatabase()),source.username(),source.password())) {
            connection.setReadOnly(true); connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement(sql)) {
                statement.setFetchSize(1000); statement.setQueryTimeout(600);
                try(var rs=statement.executeQuery()) {
                    var events=new ArrayList<ChangeEvent>(1000); long row=0;
                    while(rs.next()) {
                        var after=new LinkedHashMap<String,Object>();
                        for(String column:columns) after.put(column,rs.getObject(column));
                        var eventId=UUID.nameUUIDFromBytes((table.mappingId()+"|snapshot|"+(++row)).getBytes(StandardCharsets.UTF_8));
                        events.add(new ChangeEvent(eventId,table.mappingId(),Operation.READ,Map.of(),after,java.time.Instant.now()));
                        if(events.size()==1000) { stageSnapshot(events); events.clear(); }
                    }
                    if(!events.isEmpty()) stageSnapshot(events);
                }
            } finally { try { connection.rollback(); } catch(Exception ignored) { } }
        }
    }

    private int replaySince(Long groupId, String sourceTable, long targetMappingId, OffsetDateTime after) {
        if(groupId==null) return 0; int count=0;
        for(var batch:captureGroups.captureBatches(groupId)) {
            if(batch.createdAt().isBefore(after)) continue;
            try {
                var events=cache.readBatch(Path.of(batch.segmentPath()),batch.checksum());
                if(events.isEmpty() || events.getFirst().mappingId()==targetMappingId) continue;
                var replay=events.stream().map(event -> new ChangeEvent(
                        UUID.nameUUIDFromBytes((targetMappingId+"|replay|"+event.id()).getBytes(StandardCharsets.UTF_8)),targetMappingId,
                        event.operation(),event.before(),event.after(),event.capturedAt())).toList();
                stageSnapshot(replay); count++;
            } catch(Exception e) { throw new IllegalStateException("无法回放共享采集批次："+root(e),e); }
        }
        return count;
    }

    private void stageSnapshot(List<ChangeEvent> events) {
        var outcome=delivery.stageAndAwait(events,"SUBSCRIPTION_SNAPSHOT","SUBSCRIPTION_SNAPSHOT",0,Duration.ofMinutes(10));
        if(!outcome.delivered()) throw new IllegalStateException(outcome.error());
    }

    private TableMapping targetMapping(SyncTask task, SyncTaskTable source, long targetId, Map<String,Object> row) {
        String schema=text(row,"targetSchema"); String table=required(row,"targetTable");
        Map<String,String> columns=stringMap(row.get("columns"));
        if(columns.isEmpty()) throw new IllegalArgumentException(source.sourceTable()+"：至少配置一个目标字段映射");
        return new TableMapping(null,task.sourceId(),targetId,source.sourceDatabase(),source.sourceSchema(),source.sourceTable(),schema,table,columns,
                bool(row,"includeOperation",true),bool(row,"includeCapturedAt",true),textOr(row,"operationColumn","_cdc_operation"),
                textOr(row,"capturedAtColumn","_cdc_captured_at"),textOr(row,"capturedAtFormat","TIMESTAMP"),
                stringMap(row.get("operationValues")),true);
    }

    private void validateTargetMapping(SyncTaskTable source, TableMapping mapping) {
        var target=configs.target(mapping.targetId(),true);
        if(target.targetType()==TargetType.KAFKA) return;
        var sourceConfig=configs.source(mapping.sourceId(),true);
        var targetMeta=connections.tables(target).stream().filter(item -> mapping.targetTable().equals(String.valueOf(item.get("table")))
                && Objects.equals(blank(mapping.targetSchema()),blank(item.get("schema")==null?null:String.valueOf(item.get("schema"))))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(source.sourceTable()+"：目标表不存在"));
        var sourceMeta=connections.tables(sourceConfig,mapping.sourceDatabase()).stream().filter(item -> mapping.sourceTable().equals(String.valueOf(item.get("table")))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(source.sourceTable()+"：源表元数据不可用"));
        var sourceColumns=strings(sourceMeta.get("columns")); var targetColumns=strings(targetMeta.get("columns"));
        if(!sourceColumns.containsAll(mapping.columns().keySet()) || !targetColumns.containsAll(mapping.columns().values()))
            throw new IllegalArgumentException(source.sourceTable()+"：字段映射包含不存在的字段");
        var sourceKeys=source.keyColumns(); var targetKeys=strings(targetMeta.get("primaryKeys"));
        if(sourceKeys.isEmpty()) throw new IllegalArgumentException(source.sourceTable()+"：源表缺少主键");
        var mapped=sourceKeys.stream().map(key -> mapping.columns().get(key)).collect(java.util.stream.Collectors.toSet());
        if(mapped.contains(null)||!mapped.equals(new LinkedHashSet<>(targetKeys))) throw new IllegalArgumentException(source.sourceTable()+"：源主键必须完整映射到目标主键");
    }

    private List<Map<String,Object>> tableRows(SyncTask task,Map<String,Object> body) {
        Object value=body.get("tables");
        if(value instanceof List<?> list) return list.stream().filter(Map.class::isInstance)
                .<Map<String,Object>>map(item -> new LinkedHashMap<>((Map<String,Object>)item)).toList();
        if(task.tables().size()!=1) throw new IllegalArgumentException("多表任务必须逐表配置目标映射");
        var row=new LinkedHashMap<String,Object>(body); row.put("sourceTaskTableId",task.tables().getFirst().id()); return List.of(row);
    }

    private void updateSubscriptionState(long subscriptionId, String status, String error, boolean done) {
        subscriptions.updateSubscriptionState(subscriptionId,status,error,done);
        publishStatus(subscriptionId);
    }

    private void updateTableState(long subscriptionId, TaskTargetSubscriptionTable table, String status, String error, boolean done) {
        subscriptions.updateTableState(table.id(),status,error,done);
        publishStatus(subscriptionId);
    }

    private void publishStatus(long subscriptionId) {
        try {
            var subscription=subscriptions.subscription(subscriptionId);
            events.publish("subscription",Map.of("taskId",subscription.taskId(),"subscriptionId",subscription.id(),
                    "status",subscription.status(),"at",OffsetDateTime.now().toString()));
        } catch (Exception ignored) { }
    }

    private void validateReconciliation(SyncTask task, SyncTaskTable mapping, ReconciliationConfig reconciliation) {
        if(reconciliation==null || !reconciliation.enabled()) return;
        var source=configs.source(task.sourceId(),true);
        var sourceMeta=connections.tables(source,mapping.sourceDatabase()).stream()
                .filter(item -> Objects.equals(normalize(item.get("table")),normalize(mapping.sourceTable()))
                        && Objects.equals(normalize(item.get("schema")),normalize(mapping.sourceSchema())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(mapping.sourceTable()+"：源表元数据不可用"));
        var types=columnTypes(sourceMeta);
        String time=reconciliation.sourceTimeColumn();
        if(time==null || !"TIME".equals(family(types.getOrDefault(time,""))))
            throw new IllegalArgumentException(mapping.sourceTable()+"：业务变更时间字段必须是日期时间类型");
        if(!Objects.equals(mapping.columns().get(time),reconciliation.targetTimeColumn()))
            throw new IllegalArgumentException(mapping.sourceTable()+"：业务变更时间字段必须存在有效字段映射");
        if(reconciliation.fields()==null || reconciliation.fields().isEmpty())
            throw new IllegalArgumentException(mapping.sourceTable()+"：请至少选择一个核数字段");
        for(var field:reconciliation.fields()) {
            if(!Objects.equals(mapping.columns().get(field.sourceColumn()),field.targetColumn()))
                throw new IllegalArgumentException(mapping.sourceTable()+"：核数字段未正确映射："+field.sourceColumn());
            String detected=family(types.getOrDefault(field.sourceColumn(),""));
            String expected=switch(field.logicalType()) {
                case NUMBER -> "NUMBER"; case STRING -> "TEXT"; case DATETIME -> "TIME";
                case BOOLEAN -> "BOOLEAN"; case OTHER -> detected;
            };
            if(!detected.isBlank() && !detected.equals(expected))
                throw new IllegalArgumentException(mapping.sourceTable()+"：核数字段逻辑类型不匹配："+field.sourceColumn());
            var allowed=switch(field.logicalType()) {
                case NUMBER -> Set.of(ReconciliationMetric.SUM,ReconciliationMetric.MIN,ReconciliationMetric.MAX,ReconciliationMetric.NULL_RATE);
                case STRING, OTHER -> Set.of(ReconciliationMetric.NULL_RATE);
                case DATETIME -> Set.of(ReconciliationMetric.MIN,ReconciliationMetric.MAX,ReconciliationMetric.NULL_RATE);
                case BOOLEAN -> Set.of(ReconciliationMetric.TRUE_COUNT,ReconciliationMetric.FALSE_COUNT,ReconciliationMetric.NULL_RATE);
            };
            if(field.metrics()==null || field.metrics().isEmpty() || field.metrics().stream().anyMatch(metric -> !allowed.contains(metric)))
                throw new IllegalArgumentException(mapping.sourceTable()+"：核数字段指标不兼容："+field.sourceColumn());
        }
    }

    private Map<String,String> columnTypes(Map<String,Object> metadata) {
        var result=new HashMap<String,String>();
        Object details=metadata.get("columnDetails");
        if(details instanceof List<?> list) for(Object item:list) if(item instanceof Map<?,?> column)
            result.put(String.valueOf(column.get("name")),String.valueOf(column.get("type")));
        return result;
    }

    private String family(String type) {
        String value=Objects.toString(type,"").toUpperCase(Locale.ROOT);
        if(value.matches(".*(INT|DECIMAL|NUMERIC|NUMBER|FLOAT|DOUBLE|REAL).*")) return "NUMBER";
        if(value.matches(".*(CHAR|TEXT|STRING|JSON).*")) return "TEXT";
        if(value.matches(".*(DATE|TIME|YEAR).*")) return "TIME";
        if(value.matches(".*(BOOL|BIT).*")) return "BOOLEAN";
        return value;
    }

    private String normalize(Object value) {
        String text=string(value);
        return text==null||text.isBlank()?"":text.toLowerCase(Locale.ROOT);
    }

    private boolean isActive(TaskStatus status){return Set.of(TaskStatus.STARTING,TaskStatus.SNAPSHOTTING,TaskStatus.RUNNING,TaskStatus.DEGRADED).contains(status);}
    private String quote(DatabaseType type,String id){return type==DatabaseType.SQLSERVER?"["+id.replace("]","]]")+"]":"`"+id.replace("`","``")+"`";}
    private String qualified(DatabaseType type,String schema,String table){return schema==null||schema.isBlank()?quote(type,table):quote(type,schema)+"."+quote(type,table);}
    private long number(Map<String,Object> map,String key){return Long.parseLong(required(map,key));}
    private String required(Map<String,Object> map,String key){String value=text(map,key);if(value==null||value.isBlank())throw new IllegalArgumentException(key+" is required");return value;}
    private String text(Map<String,Object> map,String key){Object value=map.get(key);return value==null?null:String.valueOf(value);}
    private String string(Object value){return value==null?null:String.valueOf(value);}
    private String textOr(Map<String,Object> map,String key,String fallback){String value=text(map,key);return value==null||value.isBlank()?fallback:value;}
    private boolean bool(Map<String,Object> map,String key,boolean fallback){Object value=map.get(key);return value==null?fallback:Boolean.parseBoolean(String.valueOf(value));}
    private String blank(String value){return value==null||value.isBlank()?null:value;}
    private Map<String,String> stringMap(Object value){if(!(value instanceof Map<?,?> input))return Map.of();var result=new LinkedHashMap<String,String>();input.forEach((key,item)->result.put(String.valueOf(key),String.valueOf(item)));return result;}
    private Set<String> strings(Object value){if(!(value instanceof List<?> list))return Set.of();var result=new LinkedHashSet<String>();for(Object item:list)result.add(String.valueOf(item));return result;}
    private ReconciliationConfig readRecon(Object value,ReconciliationConfig fallback){try{return value==null?fallback:json.convertValue(value,ReconciliationConfig.class);}catch(Exception e){throw new IllegalArgumentException("数据核对配置无效",e);}}
    private String root(Throwable error){var value=error;while(value.getCause()!=null)value=value.getCause();return Objects.toString(value.getMessage(),value.getClass().getSimpleName());}
    private String tableKey(String database,String schema,String table){return (database+"|"+Objects.toString(schema,"")+"|"+table).toLowerCase(Locale.ROOT);}
}
