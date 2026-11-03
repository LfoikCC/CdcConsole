-- 数据流文件夹：多级嵌套，用于按库/分层归类数据流。
-- 用 parent_id 自关联而不是嵌套集：层级浅、增删频繁，自关联更好维护。
CREATE TABLE IF NOT EXISTS dev_flow_folder (
  id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  parent_id BIGINT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dev_flow_folder_parent ON dev_flow_folder(parent_id);

-- 数据流归属的文件夹；NULL 表示留在根目录
ALTER TABLE dev_flow ADD COLUMN IF NOT EXISTS folder_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_dev_flow_folder ON dev_flow(folder_id);
