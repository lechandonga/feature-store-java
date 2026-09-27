# Feature Store Java（本地可运行的机器学习特征存储）

一个可在本地直接运行的 Spring Boot 特征存储。目标是让同一组特征**定义**贯穿
写入、回填、在线读取与离线训练回溯，保证：

- **同一份定义**：特征集按版本注册，语义 / 默认值 / 缺失规则随版本固化；
- **写入即校验**：字段缺失、类型不符、时间倒序、重复写入一律整批拒绝，不留半成品；
- **在线离线等价**：相同版本 + 实体 + 时间点，两条路径走同一个解析算法，结果必然一致；
- **时间点正确（point-in-time correct）**：离线样本只能看到该时间点“当时已知”的值，
  未来数据、迟到写入、回填修正都不会造成标签泄漏；
- **回填可重算且不抖动**：重算以追加修正点的方式进行，在线视图按批原子切换；
- **可解释、可观测**：每个判定都进审计日志（含实体、时间点、原因），过期 / 缺失显式上报，
  绝不静默返回默认值；
- **多工作区隔离**：同一进程内按工作区（命名空间）隔离特征集、数据、审计与统计，
  支持按工作区配置容量上限；不传工作区的旧调用一律落在默认工作区，行为不变。

---

## 1. 快速开始

需要 JDK 17+（项目目标字节码 21）与 Maven。

```bash
mvn spring-boot:run
# 服务默认监听 http://localhost:8080
```

数据默认落盘到 `./data/feature-store-snapshot.json`（原子写），重启后自动重放恢复，
包括历史回填。可用 `feature-store.data-dir` 自定义目录。

运行全部测试：

```bash
mvn test
```

---

## 2. 核心概念与特征定义

### 2.1 特征集（Feature Set）与版本

一个特征集由 `name + entityKey + ttlMillis + features[]` 组成，注册后生成 **版本 1**。
之后每次兼容演进生成严格单调递增的 `latest + 1` 版本。版本一经注册**不可修改**，
因此历史读取永远基于当时的定义，结果可复现。

### 2.2 单个特征（FeatureDef）

| 字段 | 含义 |
| --- | --- |
| `name` | 特征名，特征集内唯一、顺序固定 |
| `type` | `STRING` / `LONG` / `DOUBLE` / `BOOLEAN` |
| `required` | 写入行是否必须包含该字段 |
| `defaultValue` | 缺失策略为 `DEFAULT` 时返回的值（可空），注册时校验类型 |
| `missingPolicy` | 缺失如何呈现（见下） |
| `description` | 人类可读的语义说明 |

**类型规则**：JSON 进入时，`Integer/Long` 都可写入 `LONG`，任何 `Number` 可写入
`DOUBLE`（数值提升），但字符串 / 布尔不会跨类型转换；非整型数不能写入 `LONG`。
入库时统一归一化（`LONG→Long`、`DOUBLE→Double`）。

### 2.3 缺失值规则（明确，不静默）

| `missingPolicy` | 行为 | 读取状态 |
| --- | --- | --- |
| `DEFAULT` | 返回声明的 `defaultValue` | `DEFAULTED` |
| `NULL` | 返回 `null` | `MISSING` |
| `REJECT` | 不提供默认，显式标记拒绝 | `REJECTED` |

任何读取结果的每个特征都带 `status`（`PRESENT/DEFAULTED/MISSING/EXPIRED/REJECTED`）
与来源点（`pointId/eventTime/ingestTime`），调用方一眼能区分“真实值”和“兜底值”。

---

## 3. 版本演进规则（不兼容即拒绝）

新版本通过对比“已有前缀”逐字段校验，**一次性收集全部冲突**并返回，冲突原因可区分：

- `ALREADY_EXISTS`：重复注册同名特征集；
- `FEATURE_SET_NOT_FOUND`：对未注册的特征集做演进；
- `DUPLICATE_FEATURE_NAME` / `INVALID_DEFINITION`：定义本身非法；
- `FEATURE_RENAMED_OR_REORDERED`：同位置改名或调整顺序（特征只能追加，不能移动）；
- `FEATURE_REMOVED`：删除已有特征；
- `FEATURE_TYPE_CHANGED`：类型变更；
- `REQUIRED_FLAG_CHANGED`：必填标志变更；
- `DEFAULT_VALUE_CHANGED`：默认值变更；
- `MISSING_POLICY_CHANGED`：缺失策略变更；
- `TTL_SHORTENED`：TTL 只能引入或加长，不能缩短（缩短会把历史读“回溯性地变成过期”）。

**唯一允许的兼容变更**：在特征列表末尾**新增**特征（TTL 可加长）。
不兼容的演进整体被拒绝，不会创建半个新版本。

---

## 4. 双时间模型与时间点语义（防泄漏的关键）

每个存储点带两个时间：

- `eventTime`：业务事件发生时间（写入方提供，不可被回填改写）；
- `ingestTime`：该值在系统中变为可见的墙钟时间（提交时刻）。

在某个查询时间 `T`，一个点**可见当且仅当**：

```
eventTime <= T  AND  ingestTime <= T
```

并在可见点中选择“最新”的一个；若存在回填修正，则被修正的旧点在修正可见后被隐藏。

由此天然杜绝三类泄漏：

1. **未来数据**：`eventTime > T` 的点在业务时间到达前不可见；
2. **迟到数据**：尽管 `eventTime` 很老，只要 `ingestTime > T`，在过去的时间点 `T`
   仍然不可见——“当时根本还不知道”；
3. **回填修正**：修正以**新点追加**（`supersedes` 指向旧点，`ingestTime` 为当下）。
   - 在过去的 `T`：旧值继续被返回 → **历史快照可逐字节复现**；
   - 在当下 `T`：返回修正值，且 `pointId/supersedes/source=BACKFILL` 可解释来源。

### 在线与离线为什么等价

`ReadService.online(...)` 与 `ReadService.offline(...)` 对每个特征调用**同一个**
`resolve(...)`，底层是同一份追加式数据。唯一刻意差异：**在线读会施加 TTL**，
过期值标记为 `EXPIRED`（保留原值，不用默认值顶替）；离线读返回该时间点有效的值。
一致性校验把这种 TTL 状态差异显式归类为 `STATUS_MISMATCH`，而不是吞掉或误报成数值分歧。

---

## 5. 写入校验（原子、拒绝不留痕）

一个批次要么全部生效，要么全部拒绝。校验在**同一把写锁临界区**内完成并提交，
避免“校验后、提交前被其他写入插入”的竞态。拒绝原因（`RejectionReason`）可区分：

- `FEATURE_SET_NOT_FOUND` / `VERSION_MISMATCH`；
- `EMPTY_BATCH`；
- `ROW_MISSING_ENTITY_KEY`：缺实体键；
- `UNKNOWN_FIELD`：写入了未声明字段；
- `MISSING_REQUIRED_FIELD`：必填缺失；
- `TYPE_MISMATCH`：类型不符（证据含 expected/actual）；
- `DUPLICATE_WITHIN_BATCH` / `REVERSE_ORDER_WITHIN_BATCH`：批内重复 / 同实体时间倒序；
- `DUPLICATE_ALREADY_STORED`：与已存点完全相同（幂等冲突，拒绝且状态不变）；
- `SAME_TIME_DIFFERENT_VALUE`：同 `(实体,eventTime)` 已有不同值——
  普通写入不能覆盖，必须走 `/api/backfill` 修正。

> 迟到写入（eventTime 早于已有点）是**允许**的；时间点过滤保证它不会污染过去的视图。

---

## 6. 回填 / 重算

回填请求引用一个已有点 `targetPointId`，为其追加修正点：

- 校验实体一致、`eventTime` **不可改写**（否则 `EVENT_TIME_IMMUTABLE`）；
- 目标点不存在 `TARGET_POINT_NOT_FOUND`，属于别的特征集 / 实体 `TARGET_POINT_MISMATCH`；
- 不能对一个“本身就是修正”的点再修正（保持修正链线性），同批不能重复修正同一目标；
- 修正值按与写入完全相同的模式校验；
- 全部通过后，在一次写锁临界区内追加离线点并**原子重建在线链**，
  并发读取只可能看到“重算前”或“重算后”，不会看到半成品；
- `correlationId` 提供作业级幂等：重放返回首次结果，不产生第二层修正。

---

## 7. 一致性校验与可观测性

- **一致性校验** `/api/read/consistency/{featureSet}`：在**同一个读锁快照**内同时解析
  在线 / 离线两条路径并逐特征比对（避免并发提交恰好插在两次独立读取之间造成假分歧）。
  差异归类：`VALUE_MISMATCH / STATUS_MISMATCH / POINT_MISMATCH / ONLY_ONLINE / ONLY_OFFLINE`。
- **新鲜度** `/api/ops/freshness/{featureSet}`：按 TTL 列出每个过期实体及年龄；
  无 TTL 时显式表示“不会过期”。
- **覆盖率** `/api/ops/coverage/{featureSet}`：只统计真正 `PRESENT` 的
  `(实体 × 特征)` 单元，`DEFAULTED/MISSING/EXPIRED` 不计为覆盖。
- **审计** `/api/ops/audit`：每条接受 / 拒绝记录都带 `featureSet/version/entity/
  eventTime/decision/reason/evidence`。被拒绝的批次除批次摘要外，还为每个违规行单独
  记录一条 `WRITE_ROW` / `BACKFILL_ROW`。审计同时通过名为 `feature-store-audit` 的
  SLF4J logger 输出，便于在日志系统中按 `entity=` / `eventTime=` 检索。

---

## 8. 工作区（命名空间）隔离

服务可同时给多个团队 / 环境共用。每个**工作区**是独立的命名空间：特征集定义与版本、
历史点、修正关系、回填幂等记录、审计条目、新鲜度 / 覆盖率统计都彼此隔离。
不同工作区可以注册**同名但定义不同**的特征集，互不冲突、互不可见。

### 8.1 默认工作区与兼容范围

- 名为 `default` 的工作区永远存在；
- 所有不接受 / 未传 `workspace` 参数的旧调用（注册、演进、写入、读取、回填、观测、审计）
  一律落在 `default`，行为与升级前完全一致；
- 升级前落盘的旧格式快照（顶层 `{definitions, points}`）在重启后自动加载进
  `default`，旧数据继续可读，且不会与新工作区里的同名特征集误判为冲突。

### 8.2 选择工作区

- 管理接口：`POST /api/workspaces` 创建、`GET /api/workspaces` 列出（含用量与上限）、
  `GET /api/workspaces/{name}` 查询单个工作区用量；
- 其余所有端点接受可选查询参数 `workspace=<名字>` 选择命名空间，缺省即 `default`。

### 8.3 容量上限

创建工作区时可配置两个维度（均可选，缺省不限）：

| 配置 | 含义 |
| --- | --- |
| `maxFeatureSets` | 该工作区可注册的不同特征集名数量（演进已有集合不占新额度） |
| `maxPoints` | 该工作区落库历史点（写入点 + 回填修正点）总数 |

达到上限时，新增注册、写入或回填**整批拒绝**：校验在提交前完成，已存在的数据与版本
不会被部分改动。`GET /api/workspaces/{name}` 返回 `featureSetCount/maxFeatureSets` 与
`pointCount/maxPoints` 的当前用量与上限。

### 8.4 可区分的错误

| 场景 | HTTP | error | 说明 |
| --- | --- | --- | --- |
| 工作区不存在 | 404 | `WORKSPACE_NOT_FOUND` | 带 `workspace` 名 |
| 工作区重名 | 409 | `WORKSPACE_CONFLICT` | 创建已存在的名字 |
| 容量超限 | 409 | `WORKSPACE_CAPACITY_EXCEEDED` | 带 `workspace/featureSet/dimension/limit/current/requested` |
| 跨工作区引用 | 422 | `BATCH_REJECTED`（行级 `reason=CROSS_WORKSPACE_ACCESS`） | 例如回填指向属于其他工作区的点，`evidence.ownerWorkspace` 标明归属 |

容量超限与跨工作区引用同样写入审计（含工作区、特征集、实体、时间点与判定依据），
可按工作区过滤查询（`GET /api/ops/audit?workspace=`）。

### 8.5 并发与重启

所有工作区共享同一把提交读写锁：任何一次读取只能看到某次提交前或提交后的完整状态，
不会串到别的工作区，也不会看到半更新。快照按工作区持久化（定义、历史点、容量配置、
回填幂等记录），重启后逐工作区重放恢复。

---

## 9. HTTP API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/features` | 注册特征集 v1 |
| POST | `/api/features/{name}/versions?ttlMillis=` | 兼容演进，生成下一版本 |
| GET  | `/api/features/{name}/versions/{version}` | 查看某版本定义 |
| POST | `/api/write` | 原子批量写入 |
| GET  | `/api/read/online/{set}/entities/{entity}?version=&asOf=` | 在线点查 |
| POST | `/api/read/offline/{set}?version=` | 批量时间点历史读取 |
| POST | `/api/read/consistency/{set}?version=` | 在线/离线一致性校验 |
| POST | `/api/backfill` | 回填 / 重算（可带 correlationId） |
| GET  | `/api/ops/freshness/{set}?version=&asOf=` | 新鲜度 |
| GET  | `/api/ops/coverage/{set}?version=&asOf=` | 覆盖率 |
| GET  | `/api/ops/audit?limit=&workspace=` | 最近审计条目（可按工作区过滤） |
| POST | `/api/workspaces` | 创建工作区（可配 `maxFeatureSets/maxPoints`） |
| GET  | `/api/workspaces` | 列出工作区及用量 / 上限 |
| GET  | `/api/workspaces/{name}` | 单个工作区用量与上限 |

以上所有业务端点都接受可选 `workspace` 查询参数选择命名空间（缺省 `default`）。

错误以结构化 JSON 返回：定义冲突 `409 DEFINITION_CONFLICT`，
批次拒绝 `422 BATCH_REJECTED`（含每行 `reason/detail/evidence`），未找到 `404 NOT_FOUND`，
工作区错误 `404 WORKSPACE_NOT_FOUND` / `409 WORKSPACE_CONFLICT` /
`409 WORKSPACE_CAPACITY_EXCEEDED`（见 8.4）。

---

## 10. 端到端示例

```bash
# 1) 注册
curl -s -X POST localhost:8080/api/features -H 'Content-Type: application/json' -d '{
  "name":"user","entityKey":"uid","ttlMillis":null,
  "features":[
    {"name":"age","type":"LONG","required":true,"defaultValue":0,"missingPolicy":"DEFAULT","description":""},
    {"name":"score","type":"DOUBLE","required":false,"defaultValue":null,"missingPolicy":"NULL","description":""}
  ],"description":""}'

# 2) 写入
curl -s -X POST localhost:8080/api/write -H 'Content-Type: application/json' -d '{
  "featureSet":"user","version":1,
  "rows":[{"eventTime":1000,"fields":{"uid":"u1","age":30,"score":0.9}}]}'

# 3) 在线读（asOf 给一个足够晚的毫秒时间）
curl -s "localhost:8080/api/read/online/user/entities/u1?version=1&asOf=9999999999999"

# 4) 离线时间点读
curl -s -X POST "localhost:8080/api/read/offline/user?version=1" \
  -H 'Content-Type: application/json' -d '[{"entity":"u1","asOfTime":9999999999999}]'
```

---

## 11. 如何验证（测试地图）

测试位于 `src/test/.../tests`，使用可手动控制的 `FixedTimeSource` 让时间线确定可复现：

- `RegistrationVersionTest`：版本单调、只允许追加、各类不兼容冲突被区分并整体拒绝；
- `WriteValidationTest`：缺字段 / 类型不符 / 倒序 / 重复 / 同时间不同值，
  并断言**被拒绝后存储点数不增、部分行不可见**；
- `PointInTimeTest`：未来 / 迟到 / 回填在统一时间线上的可见性，历史快照可复现，
  在线离线在多个时间点等价；
- `BackfillTest`：事件时间不可改、目标不匹配、整批原子、correlationId 幂等；
- `ConcurrencyTest`：多线程并发写入 + 回填 + 读取，不断做一致性校验，
  断言无异常、无半更新、拒绝写入不增点；
- `ConsistencyObservabilityTest`：一致性报告、TTL `EXPIRED` 显式上报、覆盖率统计；
- `AuditLogTest`：审计含实体 / 时间点 / 原因，可按实体过滤；
- `FeatureStoreApiIntegrationTest`：完整 HTTP 生命周期与 409/422/404 映射；
- `PersistenceRoundTripTest`：落盘后用全新实例重放，含修正值且历史视图一致；
- `WorkspaceIsolationTest`：同名特征集跨工作区定义 / 数据 / 审计隔离、默认工作区兼容、
  跨工作区点引用以 `CROSS_WORKSPACE_ACCESS` 区分拒绝；
- `WorkspaceCapacityTest`：特征集数与历史点数两维上限，超限整批拒绝且状态不变、
  其他工作区不受影响、用量 / 上限可查；
- `WorkspaceConcurrencyTest`：多工作区并发写入 / 回填 / 读取，按值域标记验证不串空间、
  无半更新；
- `WorkspacePersistenceTest`：重启后工作区清单、容量配置、各工作区数据与修正关系、
  回填幂等结果全部恢复；旧格式快照落入默认工作区；
- `WorkspaceApiIntegrationTest`：HTTP 层工作区生命周期、`workspace` 参数路由与
  `WORKSPACE_NOT_FOUND / WORKSPACE_CONFLICT / WORKSPACE_CAPACITY_EXCEEDED` 错误映射。
