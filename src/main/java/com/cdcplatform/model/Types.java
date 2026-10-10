package com.cdcplatform.model;

public final class Types {
    private Types() {}
    public enum DatabaseType { MYSQL, SQLSERVER, STARROCKS }
    public enum TargetType { MYSQL, SQLSERVER, KAFKA, STARROCKS_STREAM_LOAD }
    public enum Operation { CREATE, UPDATE, DELETE, READ }
    public enum SyncMode { FULL_INCREMENTAL, INCREMENTAL_ONLY }
    public enum TaskStatus { DRAFT, STARTING, SNAPSHOTTING, RUNNING, PAUSED, STOPPED, DEGRADED, FAILED }
    public enum TableStatus { DRAFT, STARTING, SNAPSHOTTING, RUNNING, RETRYING, PAUSED, STOPPED, FAILED }
    public enum DdlPolicy { DETECT_AND_BLOCK }
    public enum SnapshotProfile { LIGHT, HEAVY }
    public enum IncrementalProfile { STEADY, FREQUENT }
    public enum AlertStatus { OPEN, ACKNOWLEDGED, RESOLVED }
    public enum AlertType { TASK_FAILURE, TABLE_FAILURE, DDL_CHANGE, CHECKPOINT_EXPIRED, DATA_DELAY, RECONCILIATION_MISMATCH }
    public enum ReconciliationMode { INCREMENTAL, RANGE, FULL }
    public enum ReconciliationTrigger { MANUAL, SCHEDULED, AFTER_SNAPSHOT }
    public enum ReconciliationLogicalType { NUMBER, STRING, DATETIME, BOOLEAN, OTHER }
    public enum ReconciliationMetric { ROW_COUNT, SUM, MIN, MAX, NULL_RATE, TRUE_COUNT, FALSE_COUNT }
    public enum ReconciliationDiscrepancyType { TARGET_MISSING, TARGET_REDUNDANT, FIELD_MISMATCH, DELETE_RESIDUE }
}
