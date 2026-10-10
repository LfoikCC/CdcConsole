ALTER TABLE sync_checkpoint ADD COLUMN IF NOT EXISTS capture_position_json CLOB;
ALTER TABLE sync_checkpoint ADD COLUMN IF NOT EXISTS delivery_position_json CLOB;
ALTER TABLE sync_checkpoint ADD COLUMN IF NOT EXISTS generation BIGINT NOT NULL DEFAULT 0;
UPDATE sync_checkpoint SET capture_position_json=position_json WHERE capture_position_json IS NULL;
UPDATE sync_checkpoint SET delivery_position_json=position_json WHERE delivery_position_json IS NULL;

CREATE TABLE IF NOT EXISTS cdc_delivery_batch (
  batch_id VARCHAR(96) PRIMARY KEY,
  task_id BIGINT NOT NULL,
  task_table_id BIGINT NOT NULL,
  mapping_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  source_start_position CLOB,
  source_end_position CLOB,
  segment_path VARCHAR(2000) NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  event_count INT NOT NULL,
  payload_bytes BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP WITH TIME ZONE,
  last_error VARCHAR(4000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  delivered_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_delivery_batch_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_delivery_batch_table FOREIGN KEY (task_table_id) REFERENCES sync_task_table(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_delivery_batch_head ON cdc_delivery_batch(mapping_id,status,created_at,batch_id);
CREATE INDEX IF NOT EXISTS idx_delivery_batch_task ON cdc_delivery_batch(task_id,created_at);

CREATE TABLE IF NOT EXISTS cdc_delivery_part (
  batch_id VARCHAR(96) NOT NULL,
  part_no INT NOT NULL,
  label VARCHAR(160) NOT NULL,
  status VARCHAR(30) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  response_body CLOB,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (batch_id,part_no),
  CONSTRAINT fk_delivery_part_batch FOREIGN KEY (batch_id) REFERENCES cdc_delivery_batch(batch_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cdc_extraction_cycle (
  mapping_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  checkpoint_value VARCHAR(1000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (mapping_id,generation),
  CONSTRAINT fk_extraction_cycle_mapping FOREIGN KEY (mapping_id) REFERENCES table_mapping(id) ON DELETE CASCADE
);

ALTER TABLE cdc_delete_audit ADD COLUMN IF NOT EXISTS event_id VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS uq_delete_audit_event ON cdc_delete_audit(mapping_id,event_id);
