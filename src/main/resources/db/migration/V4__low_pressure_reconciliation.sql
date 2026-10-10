ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS reconciliation_delay_minutes INT NOT NULL DEFAULT 120;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS reconciliation_window_minutes INT NOT NULL DEFAULT 60;
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS reconcile_after_snapshot BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE sync_task_table ADD COLUMN IF NOT EXISTS reconciliation_json CLOB NOT NULL DEFAULT '{"enabled":false,"fields":[]}';

ALTER TABLE reconciliation_run ADD COLUMN IF NOT EXISTS mode VARCHAR(30) NOT NULL DEFAULT 'FULL';
ALTER TABLE reconciliation_run ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE reconciliation_run ADD COLUMN IF NOT EXISTS window_start TIMESTAMP WITH TIME ZONE;
ALTER TABLE reconciliation_run ADD COLUMN IF NOT EXISTS window_end TIMESTAMP WITH TIME ZONE;
ALTER TABLE reconciliation_run ADD COLUMN IF NOT EXISTS requested_table_ids_json CLOB NOT NULL DEFAULT '[]';

ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'MATCHED';
ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS target_missing BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS target_redundant BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS field_mismatch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS delete_residue BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS message VARCHAR(4000);

CREATE TABLE IF NOT EXISTS reconciliation_field_result (
  id BIGINT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  task_table_id BIGINT NOT NULL,
  source_column VARCHAR(255) NOT NULL,
  target_column VARCHAR(255) NOT NULL,
  logical_type VARCHAR(30) NOT NULL,
  metric VARCHAR(30) NOT NULL,
  source_value VARCHAR(4000),
  target_value VARCHAR(4000),
  matched BOOLEAN NOT NULL,
  CONSTRAINT fk_recon_field_run FOREIGN KEY (run_id) REFERENCES reconciliation_run(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_recon_field_run ON reconciliation_field_result(run_id,task_table_id);

CREATE TABLE IF NOT EXISTS reconciliation_discrepancy (
  id BIGINT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  task_table_id BIGINT NOT NULL,
  discrepancy_type VARCHAR(40) NOT NULL,
  key_json CLOB NOT NULL,
  field_name VARCHAR(255),
  source_value CLOB,
  target_value CLOB,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_recon_discrepancy_run FOREIGN KEY (run_id) REFERENCES reconciliation_run(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_recon_discrepancy_run ON reconciliation_discrepancy(run_id,discrepancy_type,id);

CREATE TABLE IF NOT EXISTS cdc_delete_audit (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  task_table_id BIGINT NOT NULL,
  mapping_id BIGINT NOT NULL,
  key_fingerprint VARCHAR(64) NOT NULL,
  key_json CLOB NOT NULL,
  status VARCHAR(30) NOT NULL,
  source_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
  delivered_at TIMESTAMP WITH TIME ZONE,
  verified_at TIMESTAMP WITH TIME ZONE,
  last_error VARCHAR(2000),
  CONSTRAINT fk_delete_audit_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_delete_audit_pending ON cdc_delete_audit(task_id,task_table_id,status,source_event_at);
CREATE INDEX IF NOT EXISTS idx_delete_audit_key ON cdc_delete_audit(mapping_id,key_fingerprint,status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_reconciliation_window
  ON reconciliation_run(task_id,mode,trigger_source,window_start,window_end);
