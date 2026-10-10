DROP INDEX IF EXISTS uq_reconciliation_window;

CREATE UNIQUE INDEX IF NOT EXISTS uq_reconciliation_window
  ON reconciliation_run(task_id,mode,window_start,window_end);
