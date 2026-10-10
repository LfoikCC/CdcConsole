CREATE TABLE IF NOT EXISTS sync_task (
  id BIGINT PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(2000) NOT NULL DEFAULT '',
  source_id BIGINT NOT NULL,
  target_id BIGINT NOT NULL,
  sync_mode VARCHAR(32) NOT NULL,
  ddl_policy VARCHAR(32) NOT NULL DEFAULT 'DETECT_AND_BLOCK',
  time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 0,
  alert_lag_seconds INT NOT NULL DEFAULT 60,
  reconciliation_cron VARCHAR(120),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_task_source FOREIGN KEY (source_id) REFERENCES cdc_source(id),
  CONSTRAINT fk_task_target FOREIGN KEY (target_id) REFERENCES cdc_target(id)
);

CREATE TABLE IF NOT EXISTS sync_task_table (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  mapping_id BIGINT NOT NULL UNIQUE,
  source_database VARCHAR(255) NOT NULL,
  source_schema VARCHAR(255),
  source_table VARCHAR(255) NOT NULL,
  target_schema VARCHAR(255),
  target_table VARCHAR(255) NOT NULL,
  columns_json CLOB NOT NULL,
  key_columns_json CLOB NOT NULL DEFAULT '[]',
  include_operation BOOLEAN NOT NULL DEFAULT FALSE,
  include_captured_at BOOLEAN NOT NULL DEFAULT FALSE,
  operation_column VARCHAR(255) NOT NULL DEFAULT '_cdc_operation',
  captured_at_column VARCHAR(255) NOT NULL DEFAULT '_cdc_captured_at',
  captured_at_format VARCHAR(255) NOT NULL DEFAULT 'TIMESTAMP',
  operation_values_json CLOB NOT NULL DEFAULT '{}',
  snapshot_profile VARCHAR(20) NOT NULL DEFAULT 'LIGHT',
  incremental_profile VARCHAR(20) NOT NULL DEFAULT 'STEADY',
  structure_fingerprint VARCHAR(128),
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  processed_rows BIGINT NOT NULL DEFAULT 0,
  error_count BIGINT NOT NULL DEFAULT 0,
  last_latency_ms BIGINT,
  last_event_at TIMESTAMP WITH TIME ZONE,
  last_error VARCHAR(2000),
  CONSTRAINT fk_task_table_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_table_mapping FOREIGN KEY (mapping_id) REFERENCES table_mapping(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS sync_checkpoint (
  task_table_id BIGINT PRIMARY KEY,
  phase VARCHAR(30) NOT NULL,
  position_json CLOB NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sync_checkpoint_table FOREIGN KEY (task_table_id) REFERENCES sync_task_table(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_instance (
  id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at TIMESTAMP WITH TIME ZONE, message VARCHAR(2000),
  CONSTRAINT fk_instance_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS table_instance (
  id BIGINT PRIMARY KEY, instance_id BIGINT NOT NULL, task_table_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL, started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at TIMESTAMP WITH TIME ZONE, message VARCHAR(2000),
  CONSTRAINT fk_table_instance_run FOREIGN KEY (instance_id) REFERENCES task_instance(id) ON DELETE CASCADE,
  CONSTRAINT fk_table_instance_table FOREIGN KEY (task_table_id) REFERENCES sync_task_table(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS metric_sample (
  id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL,
  sampled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_rows BIGINT NOT NULL DEFAULT 0, error_count BIGINT NOT NULL DEFAULT 0,
  lag_ms BIGINT NOT NULL DEFAULT 0, queue_depth BIGINT NOT NULL DEFAULT 0,
  read_rate DOUBLE PRECISION NOT NULL DEFAULT 0, write_rate DOUBLE PRECISION NOT NULL DEFAULT 0,
  CONSTRAINT fk_metric_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_metric_task_time ON metric_sample(task_id, sampled_at);
CREATE TABLE IF NOT EXISTS runtime_log (
  id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, table_id BIGINT,
  level VARCHAR(20) NOT NULL, message VARCHAR(4000) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_log_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_log_task_time ON runtime_log(task_id, created_at);
CREATE TABLE IF NOT EXISTS alert_event (
  id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, table_id BIGINT,
  alert_type VARCHAR(50) NOT NULL, status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  title VARCHAR(300) NOT NULL, message VARCHAR(4000) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_alert_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_alert_status ON alert_event(status, created_at);
CREATE TABLE IF NOT EXISTS reconciliation_run (
  id BIGINT PRIMARY KEY, task_id BIGINT NOT NULL, status VARCHAR(30) NOT NULL,
  source_rows BIGINT NOT NULL DEFAULT 0, target_rows BIGINT NOT NULL DEFAULT 0,
  mismatch_count BIGINT NOT NULL DEFAULT 0, sample_keys_json CLOB NOT NULL DEFAULT '[]',
  message VARCHAR(4000), started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_reconciliation_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS reconciliation_detail (
  id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL, task_table_id BIGINT NOT NULL,
  source_rows BIGINT NOT NULL DEFAULT 0, target_rows BIGINT NOT NULL DEFAULT 0,
  mismatch_count BIGINT NOT NULL DEFAULT 0, sample_keys_json CLOB NOT NULL DEFAULT '[]',
  CONSTRAINT fk_recon_detail_run FOREIGN KEY (run_id) REFERENCES reconciliation_run(id) ON DELETE CASCADE,
  CONSTRAINT fk_recon_detail_table FOREIGN KEY (task_table_id) REFERENCES sync_task_table(id) ON DELETE CASCADE
);

INSERT INTO sync_task(id,name,description,source_id,target_id,sync_mode,status,version)
SELECT m.id, '迁移任务-' || CAST(m.id AS VARCHAR), '由旧表映射自动迁移', m.source_id, m.target_id,
       'INCREMENTAL_ONLY', 'STOPPED', 0
FROM table_mapping m WHERE NOT EXISTS (SELECT 1 FROM sync_task t WHERE t.id=m.id);

INSERT INTO sync_task_table(id,task_id,mapping_id,source_database,source_schema,source_table,target_schema,target_table,
 columns_json,include_operation,include_captured_at,operation_column,captured_at_column,captured_at_format,
 operation_values_json,status)
SELECT m.id,m.id,m.id,COALESCE(NULLIF(m.source_database,''),'default'),m.source_schema,m.source_table,m.target_schema,m.target_table,
 m.columns_json,m.include_operation,m.include_captured_at,m.operation_column,m.captured_at_column,m.captured_at_format,
 m.operation_values_json,'STOPPED'
FROM table_mapping m WHERE NOT EXISTS (SELECT 1 FROM sync_task_table st WHERE st.mapping_id=m.id);

UPDATE table_mapping SET enabled=FALSE;
