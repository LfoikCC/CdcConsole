CREATE TABLE IF NOT EXISTS cdc_snapshot_stage (
  mapping_id BIGINT NOT NULL,
  generation BIGINT NOT NULL,
  row_key VARCHAR(2000) NOT NULL,
  row_json CLOB NOT NULL,
  PRIMARY KEY (mapping_id, generation, row_key),
  CONSTRAINT fk_snapshot_stage_mapping FOREIGN KEY (mapping_id) REFERENCES table_mapping(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_snapshot_stage_generation ON cdc_snapshot_stage(mapping_id, generation);
