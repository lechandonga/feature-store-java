# Feature Store (Java / Spring Boot)

一个**本地可运行**的机器学习特征存储。特征的「定义、写入、回填、在线读取、离线训练回溯」共享同一份定义和同一条读取代码路径，保证语义一致、结果可复现、每次判定可解释。

内存存储、零外部依赖，`mvn spring-boot:run` 即可启动。

---

## 1. 构建与运行

```bash
mvn -o package -DskipTests
java -jar target/feature-store-java-1.0-SNAPSHOT.jar          # 默认 8080
mvn -o test                                                   # 全部测试
```

要求 JDK 21+。

---

## 2. 特征定义（Feature Definition）

每个特征由**显式整数版本**的不可变定义组成：

| 字段 | 含义 |
|---|---|
| `name` / `version` | 特征名与显式版本号 |
| `valueType` | `INT` / `DOUBLE` / `BOOLEAN` / `STRING` |
| `missingPolicy` | 无可见值时的策略：`DEFAULT` / `NULL` / `ERROR` |
| `defaultValue` | 仅 `missingPolicy=DEFAULT` 时使用；类型必须与 `valueType` 一致 |
| `ttlMs` | 新鲜度 TTL（毫秒），`0` 表示不跟踪；超过 TTL 的值仍返回但显式标记为 `EXPIRED` |
| `description` | 描述 |

读取结果 `FeatureValue` 永远带一个明确的 `status`，**不会静默返回默认值**：

- `RECORDED`：命中真实记录
- `DEFAULTED`：无可见值，按策略返回注册默认值（并说明判定依据）
- `MISSING`：无可见值，策略为 NULL，显式返回 null
- `MISSING_ERROR`：无可见值，策略为 ERROR，不伪造值，交给调用方处理
- `EXPIRED`：命中真实值但超过 TTL，原值保留、仅标记过期

每个服务值还带有 `eventTsMs / ingestTsMs / revisionTsMs / origin / explanation`，可完整追溯「这个值在该时间点为什么可见」。

### 版本演进规则

- `valueType` 跨版本**不可变**，变更被拒绝：`TYPE_CHANGED`
- `missingPolicy` 跨版本**不可变**，变更被拒绝：`MISSING_POLICY_CHANGED`
- `defaultValue` 只能演进为**同类型**的新值，否则：`DEFAULT_VALUE_TYPE_MISMATCH`
- 已存在的版本号不能重复注册：`VERSION_ALREADY_EXISTS`
- 未注册特征：`FEATURE_NOT_FOUND`；指定版本不存在：`VERSION_NOT_FOUND`
- 兼容变更：`description`、`ttlMs`、同类型的 `defaultValue`
- 不传 `version` 时，注册自动取 `max(version)+1`

---

## 3. 数据模型与时间点语义（核心）

每条记录 `FeatureRecord` 带三个时间：

| 时间 | 含义 |
|---|---|
| `eventTsMs` | 业务/事件时间（特征值描述的时刻） |
| `ingestTsMs` | 该观测进入管道的墙钟时间（写入默认取当前时间；写入行可用 `ingestTs` 显式提供，用于真实历史数据回溯） |
| `revisionTsMs` | 该修订版本的提交墙钟时间（普通写入=摄入时间；回填/重算=提交时刻） |

在线与离线读取由**同一个** `FeatureLookupService.lookup` 实现，只有模式不同：

- **AS_OF（离线训练/回溯）**：记录可见当且仅当
  `eventTs <= asOf` 且 `ingestTs <= asOf` 且 `revisionTs <= asOf`。
  - 未来数据（事件时间在 asOf 之后）不可见；
  - 迟到写入（事件时间早、摄入时间晚于 asOf）不可见；
  - 回填/重算修正（revisionTs 晚于 asOf）不可见——**杜绝标签泄漏**，且历史回溯结果与未来写入无关、**逐位可复现**。
- **ONLINE（在线服务）**：所有已提交修订均可见（asOf=读取时刻）。

两种模式下选取规则一致：取 `eventTs` 最大的记录；同一 eventTs 存在多个修订时取 `revisionTs` 最新的修订（`RECOMPUTE > BACKFILL > INGEST`）。

因此，在**相同版本、相同实体、相同时间点**下，在线与离线结果必然等价；若有差异，一致性检查会明确归类：
`VALUE_MISMATCH` / `PRESENCE_MISMATCH` / `STATUS_MISMATCH` / `VERSION_MISMATCH`。

### 回填与重算（Backfill / Recompute）

- 修正**绝不覆盖或删除**旧记录，而是追加一条 `origin=BACKFILL|RECOMPUTE` 的新修订；
- 其 `ingestTs = eventTs`、`revisionTs = now`，所以：
  - 历史时间点（asOf < revisionTs）的回溯结果保持不变、可解释；
  - 在线与 asOf ≥ revisionTs 的离线读取看到修正值，并可看到修订来源；
- 回填通道允许迟到的旧 eventTs（在线写入通道不允许，见下）。

---

## 4. 写入时模式校验（整批原子）

`/api/ingest/write`（在线写入）逐行校验，**任一行违规则整批拒绝，零行生效**：

| RejectReason | 触发条件 |
|---|---|
| `MISSING_ENTITY_ID` / `MISSING_EVENT_TIME` / `MISSING_VALUE` | 必填缺失 |
| `INVALID_TIME` | eventTs 非正 |
| `TYPE_MISMATCH` | 值不能赋给声明类型（BOOLEAN/STRING 不做隐式转换） |
| `FUTURE_EVENT_TIME` | eventTs 晚于写入墙钟 |
| `OUT_OF_ORDER` | 在线通道 eventTs 早于该实体已有高水位（迟到数据必须走 `/backfill`） |
| `DUPLICATE_WRITE` | 同一 (特征,版本,实体,eventTs) 已有 INGEST 记录 |
| `UNKNOWN_FEATURE` / `VERSION_NOT_FOUND` / `INVALID_BATCH` | 定义或批次本身非法 |

回填/重算批次使用相同的整批原子规则，但允许乱序 eventTs；同来源同键的重复修正也被拒绝。

### 并发安全

- 「校验 + 提交」在同一个全局写临界区内完成，杜绝校验后、提交前插入竞争写入；
- 存储采用**写时复制（copy-on-write）+ 单次引用切换**发布不可变快照；
- 读取**不拿写锁**，只读取不可变快照；
- 一次多特征读取（在线 / 离线 / 一致性检查）绑定**同一个快照句柄**，回填/重算正在进行时绝不会读到「A 特征已更新、B 特征未更新」的半更新状态；
- 被拒绝的批次不修改任何快照。

---

## 5. HTTP API

| 方法 & 路径 | 说明 |
|---|---|
| `POST /api/features` | 注册（或指定版本号） |
| `POST /api/features/{name}/evolve` | 兼容演进，自动产生下一版本 |
| `GET  /api/features/{name}` | 查看全部版本 |
| `POST /api/ingest/write` | 在线写入（整批原子，200 / 422） |
| `POST /api/ingest/backfill` | 回填修正（追加修订） |
| `POST /api/ingest/recompute` | 重算（语义同回填，origin=RECOMPUTE） |
| `POST /api/serving/online` | 在线读取 `{entityId, features[], versions[]}` |
| `POST /api/serving/offline` | 时间点离线读取 `{entityId, asOf, features[], versions[]}` |
| `POST /api/serving/consistency` | 在线 vs 同时间点离线逐特征比对 |
| `POST /api/ops/freshness` | 新鲜度 / 覆盖率报告（过期、缺失显式报告） |
| `GET  /api/ops/audit?limit=N` | 结构化判定日志（实体、时间点、判定依据） |

定义冲突返回 `409`，body 含可区分的 `reason`；写入拒绝返回 `422`，body 含全部 `WriteViolation` 明细。

### 快速示例

```bash
# 注册
curl -X POST localhost:8080/api/features -H 'Content-Type: application/json' \
  -d '{"name":"price","valueType":"DOUBLE","missingPolicy":"DEFAULT","defaultValue":0,"ttlMs":60000}'

# 写入（eventTs 用毫秒时间戳；可选 ingestTs）
curl -X POST localhost:8080/api/ingest/write -H 'Content-Type: application/json' \
  -d '{"batchId":"b1","rows":[{"feature":"price","version":1,"entityId":"sku-1","eventTs":1700000000000,"value":9.9}]}'

# 在线 / 离线
curl -X POST localhost:8080/api/serving/online  -H 'Content-Type: application/json' \
  -d '{"entityId":"sku-1","features":["price"],"versions":[1]}'
curl -X POST localhost:8080/api/serving/offline -H 'Content-Type: application/json' \
  -d '{"entityId":"sku-1","asOf":1700000001000,"features":["price"],"versions":[1]}'
```

---

## 6. 一致性、新鲜度与审计

- **一致性检查**：对同一 `(实体, 版本, asOf)` 同时计算 ONLINE 与 AS_OF 两个值并逐特征比较；任何差异归类报告，`status=MATCH/MISMATCH`，不被默认值掩盖。
- **新鲜度/覆盖率**：按特征 TTL 报告 `FRESH/STALE/MISSING/NOT_TRACKED`，给出最新 eventTs、age、ttl；返回 `staleCount / missingCount / coverageRatio`。
- **审计日志**：每次注册拒绝、批次提交/拒绝、读取判定、一致性判定都记录
  `action / feature / version / entity / eventTs / asOf / decision / basis`，同时输出到 SLF4J 与内存环形缓冲（`/api/ops/audit`）。日志包含实体、时间点与判定依据，回溯结果可复现、可审计。

---

## 7. 如何验证（测试组织）

```bash
mvn -o test
```

| 测试类 | 覆盖的需求重点 |
|---|---|
| `RegistryVersionTest` | 版本自增、重复版本、类型变更、缺失策略变更、默认值类型不匹配，各自冲突原因可区分 |
| `WriteValidationTest` | 缺失/类型错误/未来时间/乱序/重复写入拒绝；**一个坏行整批回滚，不留部分数据**；迟到数据走 backfill |
| `PointInTimeConsistencyTest` | 未来数据不可见；迟到写入与回填修正不泄漏进历史 asOf；回溯结果重复执行一致；在线/离线同时间点等价；差异被检测并归类 |
| `ConcurrentBackfillTest` | 回填/重算与在线读取、噪声写入、非法批次并发：同批次两特征始终同修订（无半更新/错读），被拒批次不落库 |
| `FreshnessCoverageTest` | STALE/MISSING 显式报告、覆盖率；EXPIRED 保留原值；三种缺失策略返回不同 status；审计日志含实体/时间点/依据 |
| `ApiIntegrationTest` | Spring MVC 端到端：注册→冲突 409→写入→422 明细（含整批回滚验证）→在线/离线/审计 |

---

## 8. 关键包结构

```
domain/      枚举与不可变值对象（定义、记录、状态、冲突/拒绝原因）
exception/   定义冲突、写入拒绝异常（携带可区分 reason）
registry/    FeatureRegistry：注册与兼容版本演进
storage/     FeatureStorage：写时复制原子快照 + 快照绑定读取
service/     Ingest/Backfill(Recompute)/Lookup(在线离线共用)/Consistency/Freshness/DecisionLogger
api/         REST 控制器与全局异常映射
```
