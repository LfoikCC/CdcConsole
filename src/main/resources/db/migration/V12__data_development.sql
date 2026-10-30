-- 数据开发（可视化数据流）。
-- 图整体存 dev_flow.graph_json（画布是「整图保存」语义，服务端不按节点查询），
-- 节点运行记录用 node_key 字符串关联，不建 node/edge 表。
-- 运行时的中间结果落在 DEV_TMP schema，由 DevTempTableStore 在每次运行结束与启动时回收。

CREATE SCHEMA IF NOT EXISTS DEV_TMP;

CREATE TABLE IF NOT EXISTS dev_flow (
  id BIGINT PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(2000) NOT NULL DEFAULT '',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  graph_json CLOB NOT NULL,
  version INT NOT NULL DEFAULT 0,
  last_run_status VARCHAR(32),
  last_run_at TIMESTAMP WITH TIME ZONE,
  last_run_rows BIGINT NOT NULL DEFAULT 0,
  created_by VARCHAR(120),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dev_flow_updated ON dev_flow(updated_at DESC);

CREATE TABLE IF NOT EXISTS dev_flow_run (
  id BIGINT PRIMARY KEY,
  flow_id BIGINT NOT NULL,
  flow_version INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  trigger_type VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
  actor VARCHAR(120),
  started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at TIMESTAMP WITH TIME ZONE,
  duration_ms BIGINT,
  node_total INT NOT NULL DEFAULT 0,
  node_succeeded INT NOT NULL DEFAULT 0,
  rows_written BIGINT NOT NULL DEFAULT 0,
  message VARCHAR(2000),
  graph_snapshot CLOB,
  CONSTRAINT fk_dev_flow_run_flow FOREIGN KEY (flow_id) REFERENCES dev_flow(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dev_flow_run_flow ON dev_flow_run(flow_id, started_at DESC);

CREATE TABLE IF NOT EXISTS dev_node_run (
  id BIGINT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  node_key VARCHAR(64) NOT NULL,
  node_type VARCHAR(32) NOT NULL,
  node_name VARCHAR(160),
  status VARCHAR(32) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at TIMESTAMP WITH TIME ZONE,
  duration_ms BIGINT,
  rows_read BIGINT NOT NULL DEFAULT 0,
  rows_written BIGINT NOT NULL DEFAULT 0,
  message VARCHAR(2000),
  CONSTRAINT fk_dev_node_run_run FOREIGN KEY (run_id) REFERENCES dev_flow_run(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dev_node_run_run ON dev_node_run(run_id, id);
