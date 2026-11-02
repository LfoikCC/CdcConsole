-- 数据集：直连库表 / 自定义 SQL / Excel 上传
CREATE TABLE IF NOT EXISTS cdc_dataset (
  id BIGINT PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(1000),
  dataset_type VARCHAR(16) NOT NULL,
  account_id BIGINT,
  database_name VARCHAR(255),
  table_name VARCHAR(255),
  sql_text CLOB,
  storage_schema VARCHAR(64),
  storage_table VARCHAR(128),
  row_count BIGINT DEFAULT 0,
  column_count INT DEFAULT 0,
  status VARCHAR(16) DEFAULT 'READY',
  created_by VARCHAR(160),
  created_at TIMESTAMP WITH TIME ZONE,
  updated_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_dataset_account FOREIGN KEY (account_id) REFERENCES cdc_account(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS cdc_dataset_column (
  dataset_id BIGINT NOT NULL,
  ordinal INT NOT NULL,
  name VARCHAR(255) NOT NULL,
  data_type VARCHAR(64) NOT NULL,
  nullable BOOLEAN DEFAULT TRUE,
  PRIMARY KEY (dataset_id, ordinal),
  CONSTRAINT fk_dataset_column FOREIGN KEY (dataset_id) REFERENCES cdc_dataset(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cdc_dataset_file (
  dataset_id BIGINT PRIMARY KEY,
  file_name VARCHAR(255),
  size_bytes BIGINT,
  sha256 VARCHAR(64),
  storage_path VARCHAR(1000),
  CONSTRAINT fk_dataset_file FOREIGN KEY (dataset_id) REFERENCES cdc_dataset(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cdc_dataset_sheet (
  dataset_id BIGINT PRIMARY KEY,
  sheet_index INT,
  sheet_name VARCHAR(255),
  header_row INT,
  data_start_row INT,
  row_count BIGINT,
  storage_table VARCHAR(128),
  CONSTRAINT fk_dataset_sheet FOREIGN KEY (dataset_id) REFERENCES cdc_dataset(id) ON DELETE CASCADE
);
