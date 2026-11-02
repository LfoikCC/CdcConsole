-- 告警按时间倒序分页的覆盖索引
CREATE INDEX IF NOT EXISTS idx_alert_created ON alert_event(created_at DESC, id DESC);
