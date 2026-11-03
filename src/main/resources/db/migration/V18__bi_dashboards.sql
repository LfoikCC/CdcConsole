-- 数据分析：看板与卡片。
--
-- 布局（x/y/w/h）用强类型列：服务端要校验 x+w<=12、列表要按 dashboard 排序、还要建索引。
-- 卡片配置整体存 CLOB JSON：柱状图要「1 维度 + N 度量」，文本卡一个字段都没有，指标卡只有 1 个度量，
-- 拆成强类型列会得到一张一半是 NULL 的宽表，每加一种图表就要一次迁移。
-- 与 dev_flow.graph_json（V12）同一套做法，读写都走 Jackson。
CREATE TABLE IF NOT EXISTS bi_dashboard (
  id BIGINT PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(1000),
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 1,
  created_by VARCHAR(160),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_bi_dashboard_status ON bi_dashboard(status);
CREATE INDEX IF NOT EXISTS idx_bi_dashboard_updated ON bi_dashboard(updated_at);

CREATE TABLE IF NOT EXISTS bi_dashboard_card (
  id BIGINT PRIMARY KEY,
  dashboard_id BIGINT NOT NULL,
  card_type VARCHAR(16) NOT NULL,
  title VARCHAR(160),
  dataset_id BIGINT,
  grid_x INT NOT NULL DEFAULT 0,
  grid_y INT NOT NULL DEFAULT 0,
  grid_w INT NOT NULL DEFAULT 6,
  grid_h INT NOT NULL DEFAULT 8,
  sort_order INT NOT NULL DEFAULT 0,
  config_json CLOB,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_bi_card_dashboard FOREIGN KEY (dashboard_id) REFERENCES bi_dashboard(id) ON DELETE CASCADE,
  -- 数据集被删时卡片保留但失效（前端显示「数据集已删除」），不静默毁掉别人的看板
  CONSTRAINT fk_bi_card_dataset FOREIGN KEY (dataset_id) REFERENCES cdc_dataset(id) ON DELETE SET NULL,
  CONSTRAINT ck_bi_card_grid CHECK (
    grid_x >= 0 AND grid_x <= 11 AND grid_w >= 1 AND grid_w <= 12
    AND grid_x + grid_w <= 12
    AND grid_y >= 0 AND grid_h >= 1 AND grid_h <= 40)
);

CREATE INDEX IF NOT EXISTS idx_bi_card_dashboard ON bi_dashboard_card(dashboard_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_bi_card_dataset ON bi_dashboard_card(dataset_id);
