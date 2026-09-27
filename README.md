# Feature Store Java（本地可运行的机器学习特征存储）

一个可在本地直接运行的 Spring Boot 特征存储。目标是让同一组特征**定义**贯穿
写入、回填、在线读取与离线训练回溯，保证：

- **同一份定义**：特征集按版本注册，语义 / 默认值 / 缺失规则随版本固化；
- **写入即校验**：字段缺失、类型不符、时间倒序、重复写入一律整批拒绝，不留半成品；
- **在线离线等价**：相同版本 + 实体 + 时间点，两条路径走同一个解析算法，结果必然一致；
- **时间点正确（point-in-time correct）**：离线样本只能看到该时间点“当时已知”的值，
  未来数据、迟到写入、回填修正都不会造成标签泄漏；
- **回填可重算且不抖动**：重算以追加修正点的方式进行，在线视图按批原子切换；
- **多团队工作区隔离**：通过 `X-Workspace` 在命名空间间隔离注册表、数据、
  修正、审计与统计，支持每工作区容量上限；不带工作区的旧调用全部走
  `default` 工作区，行为不变（见第 8 节）；
- **可解释、可观测**：每个判定都进审计日志（含工作区、实体、时间点、原因），过期 / 缺失显式上报，
  绝不静默返回默认值。

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

多个团队 / 环境可在同一进程里共用本服务，彼此完全隔离。

### 8.1 选择工作区：`X-Workspace` 请求头

除工作区管理接口外，**所有**数据接口（注册、演进、写入、在线 / 离线读、回填、
一致性、新鲜度 / 覆盖率、审计）都接受可选请求头：

```
X-Workspace: team-a
```

- 带头时，操作严格发生在该工作区内；
- **不带头（或为空）时，一律落到内置的 `default` 工作区**，因此升级前的
  所有旧调用（不传工作区）行为与返回完全不变。

每个工作区各自独立拥有：特征集注册表与版本链、全部历史点（离线）、在线链、
回填修正关系（`supersedes`）、correlationId 幂等表、审计记录以及新鲜度 /
覆盖率统计。不同工作区之间不共享锁、缓存或任何数据。

**同名特征集可以在不同工作区注册成完全不同的定义**（不同实体键、特征、类型），
互不冲突、互不可见。

### 8.2 创建 / 列出 / 查看用量

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/workspaces` | 创建工作区，可带容量上限 |
| GET  | `/api/workspaces` | 列出全部工作区（元数据 + 上限） |
| GET  | `/api/workspaces/current` | `X-Workspace` 当前选中工作区的元数据 |
| GET  | `/api/workspaces/usage` | 当前工作区的用量与上限 |

创建请求体：

```json
{
  "name": "team-a",
  "limits": { "maxFeatureSets": 50, "maxPoints": 1000000 }
}
```

- 名字必填，规则为 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`；非法名字返回 `400 BAD_REQUEST`；
- 重名创建返回 `409 WORKSPACE_CONFLICT`，已有配置与数据原样保留；
- `limits` 的两个维度均可省略 / 为 `null`，表示该维度不限；
- `default` 工作区始终存在，无需创建。

### 8.3 容量约束（整批拒绝，不留部分改动）

每个工作区有两个独立维度的上限：

- `maxFeatureSets`：可注册的**不同特征集名称数**（版本演进不新增特征集，不受限）；
- `maxPoints`：该工作区**落库历史点总数**（所有特征集 / 版本的写入点与回填修正点之和）。

判定时机都在对应工作区的写锁临界区内、**任何变更之前**：

- 注册：当 `当前特征集数 + 1 > maxFeatureSets` 时拒绝；
- 写入：先完成全部行校验，再当 `当前点数 + 本批行数 > maxPoints` 时**整批拒绝**；
- 回填：幂等重放不计数、不拒绝；新作业当 `当前点数 + 修正条数 > maxPoints`
  时**整批拒绝**。

超限返回 `422 CAPACITY_EXCEEDED`，响应体给出判定所需的全部信息：
`workspace / featureSet / version / dimension(FEATURE_SETS|POINTS) / limit /
currentUsage / requested / basis`，写入 / 回填场景还带首个 `entity / eventTime`。
超限时没有任何行、点或版本被部分写入；其他工作区不受任何影响。

查询用量：

```bash
curl -s localhost:8080/api/workspaces/usage -H 'X-Workspace: team-a'
# {"workspace":"team-a","featureSets":2,"points":137,"limits":{"maxFeatureSets":50,"maxPoints":1000000}}
```

### 8.4 跨工作区访问与错误区分

- 操作一个**不存在的工作区**：`404 WORKSPACE_NOT_FOUND`（区别于工作区内
  特征集不存在的 `404 NOT_FOUND` / 写入侧 `FEATURE_SET_NOT_FOUND`）；
- 回填的 `targetPointId` 指向**别的工作区**拥有的点：整批拒绝
  `422 BATCH_REJECTED`，该行原因是 `CROSS_WORKSPACE_ACCESS`，
  evidence 里同时给出 `ownerWorkspace` 与 `requestedWorkspace`；
- 点 id 在任何工作区都不存在：`TARGET_POINT_NOT_FOUND`，与跨空间访问明确区分。

### 8.5 并发与重启语义

- 每个工作区有独立的读写锁：多工作区并发写入 / 回填与在线 / 离线读取时，
  任一次读取只会看到某次提交**前或后的完整状态**，看不到半更新，也不会串到
  别的工作区；
- 快照升级为 **v2（workspaces）** 信封：工作区清单、容量配置、每个工作区的
  定义 / 历史点 / 修正关系、correlationId 幂等结果都会在重启后恢复；
- **升级兼容**：旧版本写出的 v1 快照（`{"definitions":...,"points":...}`）
  在启动时自动整体归入 `default` 工作区，旧数据照常可读；在其他工作区注册
  同名特征集不会与这些旧数据冲突（也不会误判为重复）。

---

## 9. HTTP API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/workspaces` | 创建工作区（可带 `limits` 容量上限） |
| GET  | `/api/workspaces` | 列出工作区 |
| GET  | `/api/workspaces/current` | 当前（`X-Workspace`）工作区元数据 |
| GET  | `/api/workspaces/usage` | 当前工作区用量 / 上限 |
| POST | `/api/features` | 注册特征集 v1（可带 `X-Workspace`） |
| POST | `/api/features/{name}/versions?ttlMillis=` | 兼容演进，生成下一版本 |
| GET  | `/api/features/{name}/versions/{version}` | 查看某版本定义 |
| POST | `/api/write` | 原子批量写入 |
| GET  | `/api/read/online/{set}/entities/{entity}?version=&asOf=` | 在线点查 |
| POST | `/api/read/offline/{set}?version=` | 批量时间点历史读取 |
| POST | `/api/read/consistency/{set}?version=` | 在线/离线一致性校验 |
| POST | `/api/backfill` | 回填 / 重算（可带 correlationId） |
| GET  | `/api/ops/freshness/{set}?version=&asOf=` | 新鲜度 |
| GET  | `/api/ops/coverage/{set}?version=&asOf=` | 覆盖率 |
| GET  | `/api/ops/audit?limit=` | 当前工作区最近审计条目 |

错误以结构化 JSON 返回：

- 定义冲突 `409 DEFINITION_CONFLICT`，工作区重名 `409 WORKSPACE_CONFLICT`；
- 批次拒绝 `422 BATCH_REJECTED`（含每行 `reason/detail/evidence`，
  跨工作区回填为 `CROSS_WORKSPACE_ACCESS`）；
- 容量超限 `422 CAPACITY_EXCEEDED`（含维度 / 上限 / 当前用量 / 申请量 / 判定依据）；
- 工作区不存在 `404 WORKSPACE_NOT_FOUND`，特征集 / 版本 / 点不存在 `404 NOT_FOUND`；
- 非法工作区名或容量值 `400 BAD_REQUEST`。

---

## 10. 端到端示例

```bash
# 0) 创建带容量上限的工作区
curl -s -X POST localhost:8080/api/workspaces -H 'Content-Type: application/json' -d '{
  "name":"team-a","limits":{"maxFeatureSets":50,"maxPoints":1000000}}'

# 1) 注册（注意 X-Workspace 头；不带头则写入 default 工作区）
curl -s -X POST localhost:8080/api/features -H 'X-Workspace: team-a' \
  -H 'Content-Type: application/json' -d '{
  "name":"user","entityKey":"uid","ttlMillis":null,
  "features":[
    {"name":"age","type":"LONG","required":true,"defaultValue":0,"missingPolicy":"DEFAULT","description":""},
    {"name":"score","type":"DOUBLE","required":false,"defaultValue":null,"missingPolicy":"NULL","description":""}
  ],"description":""}'

# 2) 写入
curl -s -X POST localhost:8080/api/write -H 'X-Workspace: team-a' \
  -H 'Content-Type: application/json' -d '{
  "featureSet":"user","version":1,
  "rows":[{"eventTime":1000,"fields":{"uid":"u1","age":30,"score":0.9}}]}'

# 3) 在线读（asOf 给一个足够晚的毫秒时间）
curl -s -H 'X-Workspace: team-a' \
  "localhost:8080/api/read/online/user/entities/u1?version=1&asOf=9999999999999"

# 4) 离线时间点读
curl -s -X POST -H 'X-Workspace: team-a' \
  "localhost:8080/api/read/offline/user?version=1" \
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
- `WorkspaceIsolationTest`：同名特征集跨工作区注册 / 读写 / 观测隔离、
  工作区不存在、重名 409、`FEATURE_SETS` 与 `POINTS` 两个维度超限后的
  **整批拒绝与状态不变**、跨工作区回填 `CROSS_WORKSPACE_ACCESS`
  （与未知点 `TARGET_POINT_NOT_FOUND` 区分）、默认工作区旧调用形态兼容；
- `WorkspaceConcurrencyTest`：两个工作区并发写入 / 回填 / 在线离线读取，
  持续校验一致性、值域不串空间、实体集合各自独立、无半更新；
- `WorkspaceRecoveryTest`：重启后工作区清单 / 容量配置 / 数据 / 修正关系 /
  correlationId 幂等结果恢复，且**旧版 v1 快照自动归入 `default` 工作区、
  与新工作区同名特征集不冲突**；
- `WorkspaceApiIntegrationTest`：HTTP 端到端验证 `X-Workspace` 选择、
  创建 / 列表 / 用量、404/409/422 错误体中的维度与判定依据。

快速验证命令：

```bash
mvn test                       # 全量 58 个用例
mvn test -Dtest=WorkspaceIsolationTest
mvn test -Dtest=WorkspaceConcurrencyTest
mvn test -Dtest=WorkspaceRecoveryTest
mvn test -Dtest=WorkspaceApiIntegrationTest
```
