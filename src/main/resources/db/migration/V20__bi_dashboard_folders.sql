-- 数据看板文件夹：左侧导航按目录组织看板，支持多级嵌套。
-- 与数据流文件夹（V17）同一套做法：parent_id 自关联，层级浅、增删频繁。
CREATE TABLE IF NOT EXISTS bi_dashboard_folder (
  id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  parent_id BIGINT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bi_folder_parent ON bi_dashboard_folder(parent_id);

-- 看板归属的文件夹；NULL 表示留在根目录
ALTER TABLE bi_dashboard ADD COLUMN IF NOT EXISTS folder_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_bi_dashboard_folder ON bi_dashboard(folder_id);

-- 最近查看：一个用户对一个看板只留一条，重复查看只更新时间。
-- 单独一张表而不是在看板上记 last_viewed_by：最近查看是「按人」的，
-- 记成全局字段会让别人的浏览把自己的列表顶掉。
CREATE TABLE IF NOT EXISTS bi_dashboard_view (
  dashboard_id BIGINT NOT NULL,
  username VARCHAR(160) NOT NULL,
  viewed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (dashboard_id, username),
  CONSTRAINT fk_bi_view_dashboard FOREIGN KEY (dashboard_id) REFERENCES bi_dashboard(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bi_view_user ON bi_dashboard_view(username, viewed_at);
