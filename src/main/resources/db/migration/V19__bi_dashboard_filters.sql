-- 看板级筛选器：同一数据集的所有卡片都会自动带上这些条件。
--
-- 存 CLOB JSON 而不是拆表：筛选器是「跟着看板走的一份配置」，
-- 不参与卡片布局校验，也没有跨表查询需求，和 config_json 一样读写都走 Jackson。
ALTER TABLE bi_dashboard ADD COLUMN IF NOT EXISTS filter_json CLOB;
