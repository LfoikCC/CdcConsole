-- A task owns the source-side capture configuration. A target subscription is
-- the user-visible delivery destination and may contain one mapping per task table.
CREATE TABLE IF NOT EXISTS task_target_subscription (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  target_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(2000) NOT NULL DEFAULT '',
  status VARCHAR(40) NOT NULL DEFAULT 'INITIALIZING_SNAPSHOT',
  initialization_status VARCHAR(40) NOT NULL DEFAULT 'INITIALIZING_SNAPSHOT',
  initialized_at TIMESTAMP WITH TIME ZONE,
  last_error VARCHAR(4000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_task_target_subscription_task FOREIGN KEY (task_id) REFERENCES sync_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_target_subscription_target FOREIGN KEY (target_id) REFERENCES cdc_target(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_task_target_subscription_task ON task_target_subscription(task_id,id);

CREATE TABLE IF NOT EXISTS target_subscription_table (
  id BIGINT PRIMARY KEY,
  subscription_id BIGINT NOT NULL,
  source_task_table_id BIGINT NOT NULL,
  runtime_task_table_id BIGINT NOT NULL,
  capture_group_id BIGINT,
  mapping_id BIGINT NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'INITIALIZING_SNAPSHOT',
  initialization_started_at TIMESTAMP WITH TIME ZONE,
  initialization_completed_at TIMESTAMP WITH TIME ZONE,
  initialization_error VARCHAR(4000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_subscription_table_subscription FOREIGN KEY (subscription_id) REFERENCES task_target_subscription(id) ON DELETE CASCADE,
  CONSTRAINT fk_subscription_table_source FOREIGN KEY (source_task_table_id) REFERENCES sync_task_table(id) ON DELETE CASCADE,
  CONSTRAINT fk_subscription_table_runtime FOREIGN KEY (runtime_task_table_id) REFERENCES sync_task_table(id) ON DELETE CASCADE,
  CONSTRAINT fk_subscription_table_mapping FOREIGN KEY (mapping_id) REFERENCES table_mapping(id) ON DELETE CASCADE,
  CONSTRAINT fk_subscription_table_group FOREIGN KEY (capture_group_id) REFERENCES cdc_capture_group(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_table_source ON target_subscription_table(subscription_id,source_task_table_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_table_mapping ON target_subscription_table(mapping_id);

ALTER TABLE sync_task_table ADD COLUMN IF NOT EXISTS subscription_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_task_table_subscription ON sync_task_table(subscription_id);

ALTER TABLE reconciliation_run ADD COLUMN IF NOT EXISTS subscription_id BIGINT;
ALTER TABLE reconciliation_detail ADD COLUMN IF NOT EXISTS subscription_table_id BIGINT;
ALTER TABLE reconciliation_field_result ADD COLUMN IF NOT EXISTS subscription_table_id BIGINT;
ALTER TABLE reconciliation_discrepancy ADD COLUMN IF NOT EXISTS subscription_table_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_reconciliation_run_subscription ON reconciliation_run(subscription_id,started_at DESC);

-- Existing single-target tasks become a normal default subscription. The Java
-- bootstrap backfills the per-table rows because old mappings are authoritative.
