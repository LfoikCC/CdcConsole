# CDC Platform

Spring Boot 3 / Java 21 CDC 服务，Vue 3 管理台。一个同步任务绑定一个源连接、一个目标连接，并可配置多张表。源端支持 MySQL、SQL Server 和 StarRocks；目标端支持 MySQL、SQL Server、StarRocks Stream Load 和 Kafka。

## 启动与构建

需要 JDK 21、Maven 3.9+。执行 `mvn package` 会安装前端依赖、构建 Vue 页面并打入 Spring Boot JAR。运行 `java -jar target/cdc-platform-0.1.0-SNAPSHOT.jar` 后访问 `http://localhost:8080`。前端开发可在 `frontend/` 执行 `npm ci && npm run dev`；Vite 默认代理 `/api` 到 `127.0.0.1:8081`。

如果通过 Docker 容器运行 Maven 并挂载源码，请额外挂载独立 volume 到 `/workspace/frontend/node_modules`，隔离 Linux 原生模块与宿主机的 macOS `node_modules`；宿主机开发依赖仍用宿主机 `npm ci` 安装。

默认 H2 数据库及事件缓存位于 `./data`。通过 `CDC_DATA_DIR` 修改路径，通过 `SERVER_PORT` 修改端口。首次启动由 Flyway 建表；旧配置库会 baseline 到 V1 并迁移到任务模型。原表映射保留，但写入 API 已废弃（HTTP 410）。迁移后的任务处于停止状态，不复用旧 CDC checkpoint。

## 任务配置

从“同步任务”创建任务，依次选择连接与同步模式、源数据库和多张表及目标对象、字段映射和性能档位，最后做连接、主键、类型、目标表和 CDC 前置条件校验。目标表通过元数据选择；Kafka 从 broker 的 Topic 列表逐表选择。源/目标表均需有主键（Kafka 目标例外）；一个任务内不能将两张源表映射到同一目标表。

- `FULL_INCREMENTAL`：Debezium 一致性快照 Upsert 到目标，再从快照位点消费变更；不清空目标表，也不删除目标端独有的历史行。
- `INCREMENTAL_ONLY`：首次启动从当前日志位点消费，不复制已有行。
- MySQL 须启用 `log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`，并授予所需复制权限。SQL Server 需要数据库和每张源表启用原生 CDC。
- StarRocks 作为源端仅支持主键快照对比轮询，不保证严格一致；其目标端使用 Stream Load。

暂停保留采集位点、投递位点和待投递批次，恢复时先清空历史批次，再继续采集。停止会清除源端采集器位点，并将当前未完成批次标记为 `CANCELLED`；批次文件保留用于审计，但下次启动不会重放。运行期间只能改任务名称和描述；改连接、模式或字段映射需先停止。单表失败时其余表继续运行，任务进入 `DEGRADED`；全部失败则为 `FAILED`。抓取时间由后端生成 `Instant`，按任务时区（默认 `Asia/Shanghai`）格式化。

## 可靠投递

任务内每张表采用独立的顺序投递队列，并维护两个互不混用的位点：

- `capturePosition`：CDC 批次写入本地不可变 `.ready` 文件、执行 `fsync`、原子重命名并登记成功后推进。Debezium 只等待可靠落盘，不等待远程目标恢复。
- `deliveryPosition`：目标端确认整个批次成功后推进，用于表示业务数据实际同步进度。

服务重启时会先恢复中断的 `DELIVERING` 批次和数据库中尚未登记的 `.ready` 文件，再允许对应表继续采集。目标端不可用时从本地批次重试，不重新扫描源表或重跑全量快照。同一 mapping 同时最多投递一个批次，失败的头部批次不会被后续批次越过；不同 mapping 可以并行。

关系型目标按主键实现幂等写入：MySQL 使用批量 `INSERT ... ON DUPLICATE KEY UPDATE`，SQL Server 使用带 `HOLDLOCK` 的参数化 `MERGE`，DELETE 使用完整映射主键。连接和 `PreparedStatement` 在同一 mapping 的连续批次间复用，每个批次使用一个事务，任意语句失败时整批回滚。

StarRocks 使用 Stream Load。`STEADY` 每个请求最多 500 行，`FREQUENT` 最多 2000 行，同时受 10 MB 请求体限制；一个本地批次可以拆成多个 part，所有 part 成功后才推进投递位点。part 使用稳定 label，重试时先确认既有 label 的状态，DELETE 通过 `__op=1` 写入主键表。

可重试错误默认最多执行 6 次（首次加 5 次重试），退避约为 1、2、4、8、16 秒并带抖动。超过次数后只停止失败表，保留批次文件和双位点。待投递数据默认上限为 10 GB；达到上限时对应采集回调失败且不提交采集位点，以形成背压而不是丢弃事件。成功批次文件默认保留 7 天。

## 运维与 API

任务详情包含概览、表配置、运行实例、指标、日志和数据核对。每分钟采样指标，默认保留 7 天；告警可确认或解决。数据核对按业务变更时间字段计算已闭合的半开窗口，先比较 `ROW_COUNT` 和用户选择字段的 `SUM`、`MIN`、`MAX`、空值数、布尔计数等指标；只有聚合不一致时才流式扫描窗口并按主键分批查询另一端，定位目标缺失、目标冗余和字段不一致。CDC DELETE 通过独立审计表验证目标端是否仍可见。每次运行统计全部差异，但最多保存 1000 条明细；只报告，不自动修复。Kafka 目标不支持数据核对。

每张表启用核对时必须选择已映射的日期时间字段和至少一个业务核数字段。建议为源端、目标端的核对时间字段建立索引。新任务默认延迟 120 分钟、窗口 60 分钟，并可选择定时核对和全量同步完成后自动核对。数据核对支持增量窗口、指定时间范围和全量三种模式；全量模式可能产生较高查询负载，应在低峰期或只读副本执行。旧任务升级后核对配置默认为关闭，不再运行旧式全表主键核对。

主要接口：

- `GET/POST /api/tasks`、`GET/PUT/DELETE /api/tasks/{id}`；更新配置提交 `version` 做乐观锁。
- `POST /api/tasks/validate`、`POST /api/tasks/{id}/actions/{start|pause|resume|stop}`。
- `GET /api/tasks/{id}/{instances|metrics|logs|reconciliations}`，`POST /api/tasks/{id}/reconciliations`。提交核对异步返回 HTTP 202 和运行 ID；请求可指定 `INCREMENTAL`、`RANGE` 或 `FULL`、表 ID、时间范围和是否下钻。
- `GET /api/tasks/{id}/reconciliations/{runId}` 返回每张表的指标和分类统计；`GET /api/tasks/{id}/reconciliations/{runId}/discrepancies` 使用 `page`/`size`/`type` 查询差异明细。
- `GET /api/tasks/{id}/delivery-batches` 使用 `status`/`page`/`size` 查询本地投递批次；`POST /api/tasks/{id}/delivery-batches/{batchId}/retry` 只重放指定失败批次，不重置源端位点或重新执行快照。
- 日志使用 `page`/`size` 服务端分页；指标可传 `page`/`size` 获取分页结果，不传时返回图表所需的时间范围序列。
- `GET /api/alerts`、`POST /api/alerts/{id}/{acknowledge|resolve}`、`GET /api/runtime/events`（SSE）。
- `GET/POST /api/sources`、`GET/POST /api/targets` 及各自的元数据、连接测试接口。

## 测试

`mvn test` 运行后端测试；`cd frontend && npm test` 运行前端单元测试。应用测试会验证 Flyway V7 可从空库和旧库升级，批次文件测试会验证稳定批次 ID、校验和与恢复读取；核对适配器测试覆盖 MySQL、SQL Server 和 StarRocks 的标识符引用、聚合表达式与批量参数限制。`MySqlTaskIntegrationTest` 使用 Testcontainers MySQL 8.4 验证全量、并发写入、增删改、暂停恢复以及目标端独有行保留。测试需要可访问的 Docker daemon；没有 Docker 时该测试会跳过，务必检查测试报告的 `skipped` 数。独立的 SQL Server、StarRocks Stream Load 和真实 MySQL 到 StarRocks 故障恢复验收仍需要可用环境；千万级数据上线前应在低峰期或只读副本压测，并覆盖“目标已提交但本地成功标记尚未写入”等进程终止点。
# CdcProject

## DBX 插件构建

独立 Web 构建使用 `cd frontend && npm run build`，输出到 Spring Boot 的 `target/generated-resources/static`。DBX 插件使用 `npm run build:dbx-plugin`，独立生成 `dist/cdc-console.dbxp` 和用于沙箱预验证的 `dist/cdc-sse-probe.dbxp`，不会覆盖独立 Web 资源。先安装探针验证主题、授权、SSE 连接与心跳；点击“连接并验证”可在断网恢复后重新建立连接并检查 `Last-Event-ID`。

插件模式要求宿主提供 `getHostInfo`、`getThemeTokens`、`onThemeChange`、`getBackendConfig` 和 `onBackendTokenChange`。`getBackendConfig` 返回后端 `baseUrl`、内存中的 `accessToken` 和可选 `expiresAt`；后端地址与令牌不能写入 Manifest 或浏览器持久化存储。宿主未提供后端能力时插件显示兼容性错误，不会按独立 Web 模式访问同源 API。

后端默认 `CDC_BACKEND_AUTH_MODE=NONE`，保持现有独立部署行为。插件部署建议设置 `CDC_BACKEND_AUTH_MODE=BEARER`、`CDC_BACKEND_AUTH_ISSUER`、`CDC_BACKEND_AUTH_AUDIENCE` 和精确的 `CDC_BACKEND_CORS_ALLOWED_ORIGINS`；启用 Bearer 后 `/api/**` 需要有效 JWT。SSE 使用同一 Bearer 令牌，服务端保留最近 256 条非心跳事件用于 `Last-Event-ID` 回放；超出窗口时客户端刷新页面数据。

正式安装前仍须在目标 DBX 版本中验证 Manifest/Host API 契约、沙箱 CSP `connect-src`、跨域预检、SSE 心跳与断线重连。仅本地打包成功不等于 DBX 宿主集成验收通过。
