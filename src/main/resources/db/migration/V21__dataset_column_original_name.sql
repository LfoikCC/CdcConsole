-- 数据集字段的原始名称（来自数据库/B文件表头），与系统里可改的别名分开存
ALTER TABLE cdc_dataset_column ADD COLUMN IF NOT EXISTS original_name VARCHAR(255);
UPDATE cdc_dataset_column SET original_name = name WHERE original_name IS NULL;

-- 本地文件数据集的去重主键：追加数据时按该字段跳过已存在的行
ALTER TABLE cdc_dataset_sheet ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(255);
