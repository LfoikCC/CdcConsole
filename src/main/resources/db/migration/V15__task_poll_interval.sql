-- 任务级轮询间隔：NULL 表示沿用账户配置
ALTER TABLE sync_task ADD COLUMN IF NOT EXISTS poll_interval_ms INT;
