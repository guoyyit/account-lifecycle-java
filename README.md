# 本地账号生命周期治理服务

单进程内完成全部存储与调度的账号生命周期治理：休眠检测、凭据过期宽限、
回收处置、验证恢复、分组策略、并发与重启安全、全流程审计。
不依赖任何外部服务，也不处理真实凭据（验证码仅为本地演示）。

- Java 17 / Spring Boot 3.4 / Maven
- 持久化：本地追加式事件日志（JSON Lines），启动时重放重建全部状态
- 运行：`mvn spring-boot:run`（默认 `http://localhost:8080`）
- 测试：`mvn test`

## 1. 生命周期状态机

账号任一时刻都由 `(status, statusReason, statusEnteredAt)` 描述，
状态可解释、进入原因可解释、进入时刻可解释。

| 当前状态 | 可迁移到 | 触发条件 / 原因 |
| --- | --- | --- |
| `ACTIVE` 正常 | `DORMANT` | 最后活动时间严格早于 `now - dormantThreshold`（休眠阈值） |
| `ACTIVE` 正常 | `GRACE` | 当前时刻严格晚于凭据到期时刻（凭据过期） |
| `ACTIVE` 正常 | `DISABLED` | 管理员禁用 |
| `DORMANT` 休眠 | `ACTIVE` | 休眠后收到新的活动上报（立即回醒） |
| `DORMANT` 休眠 | `RECYCLED` | 进入休眠后严格超过 `recycleBuffer`（回收缓冲期） |
| `DORMANT` 休眠 | `DISABLED` | 管理员禁用 |
| `GRACE` 宽限 | `ACTIVE` | 宽限期内完成凭据更新 |
| `GRACE` 宽限 | `RECYCLED` | 严格超过宽限期仍未更新凭据 |
| `GRACE` 宽限 | `DISABLED` | 管理员禁用 |
| `RECYCLED` 回收 | `ACTIVE` | 通过验证码恢复流程 |
| `DISABLED` 禁用 | （终态） | 不允许迁出 |

表外迁移一律抛出 `IllegalTransitionException`（HTTP 400）。

### 判定边界（确定性语义）

所有“到期”判定统一采用**严格晚于**，临界时刻本身不算到期：

- 休眠边界：`lastActivity == now - dormantThreshold` 时仍 `ACTIVE`；
  `lastActivity < now - dormantThreshold` 才进入 `DORMANT`。
- 凭据边界：`now == credentialExpiresAt` 时凭据仍有效；
  `now > credentialExpiresAt` 才进入 `GRACE`。
- 宽限边界：`now == graceEnteredAt + gracePeriod` 时仍可更新凭据；
  再晚 1 秒才回收。
- 回收缓冲：`now == dormantEnteredAt + recycleBuffer` 时仍保持 `DORMANT`。

上述边界均由 `DormancyBoundaryTest`、`CredentialGraceTest`、
`RecoveryFlowTest` 锁定。

## 2. 休眠检测与并发误判防护

- 活动信号通过 `POST /api/accounts/{id}/activity` 并发上报。
- 仓储为每个账号维护一把条纹锁（64 段）。账号的“判定 + 迁移”全过程都在
  该账号的锁内完成，活动上报与休眠扫描因此**串行交错**：
  - 扫描先拿锁：按旧活动时间判休眠；随后上报拿锁，看到账号已休眠，按同一阈值
    立即把它判回 `ACTIVE`。
  - 上报先拿锁：活动事件先落盘；扫描拿锁后看到新活动时间，不会判休眠。
- 交错结束后再跑一次扫描即可收敛：任何活动时间仍在阈值内的账号都不会停留在
  `DORMANT`。对应并发测试：`ActivityConcurrencyTest`（200 账号 × 上报/扫描交错）。

## 3. 凭据与账号双重过期

注册即发放首期凭据（有效期来自所在分组的 `credentialValidity`）：

1. 凭据过期 → 账号进入 `GRACE`，原因 `CREDENTIAL_EXPIRED`。
2. 宽限期内调用凭据更新 → 发放新凭据并回到 `ACTIVE`，
   原因 `CREDENTIAL_RENEWED`；同一幂等键的重复更新不重复生效。
3. 宽限期超时 → 账号进入 `RECYCLED`，原因 `GRACE_TIMEOUT_RECYCLED`，
   此后只能走恢复流程。

## 4. 回收与验证恢复

- 休眠账号经过可配置的 `recycleBuffer` 缓冲期后才回收（不是一休眠就回收）。
- 恢复是独立的会话流程：
  1. `POST /api/recovery/{accountId}`（仅 `RECYCLED` 可发起），返回
     `sessionId` 与 6 位数字 `verificationCode`，会话有效期 15 分钟。
  2. `POST /api/recovery/sessions/{sessionId}:complete` 提交验证码。
- 验证失败（验证码错误/会话过期/账号已不在回收态）只记录 `RECOVERY_FAILED`
  审计，账号保持 `RECYCLED`，会话标记 `FAILED`/`EXPIRED`，需重新发起——
  **不留下半完成状态**。
- 恢复成功通过**单个 `RECOVERY_COMPLETED` 事件**原子提交
  （新凭据 + `RECYCLED -> ACTIVE` + 活动时间刷新）：
  - 事件 fsync 前崩溃：事件不存在，重启后仍是 `PENDING + RECYCLED`，可整体重试；
  - 事件 fsync 后崩溃：重启重放一次性还原，绝不半成品。
- 同一 `idempotencyKey` 的重复恢复请求返回同一会话；对已完成会话重复提交
  验证幂等返回成功，不重复迁移。

## 5. 分组策略

- 内置 `default` 分组；可创建任意命名分组，每组四个参数：
  `dormantThreshold`、`credentialValidity`、`gracePeriod`、`recycleBuffer`。
- 账号注册时若分组不存在，固化为 `default`。
- 策略变更只**追加新版本**（`version` 递增、带 `effectiveFrom`），
  判定任意历史时刻都解析当时生效的版本：变更**只影响之后的判定**，
  已发生的状态与历史事件不会被追溯改写。历史版本可通过
  `GET /api/policies/{group}/versions` 查询。

### 默认策略

| 参数 | 默认值 |
| --- | --- |
| 休眠阈值 `dormantThreshold` | 90 天 |
| 凭据有效期 `credentialValidity` | 365 天 |
| 宽限期 `gracePeriod` | 7 天 |
| 回收缓冲期 `recycleBuffer` | 30 天 |
| 扫描周期 | 30 秒 |

## 6. 配置项（`account-lifecycle.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `account-lifecycle.data-dir` | `${user.dir}/data/lifecycle` | 事件日志目录（`lifecycle-events.log`） |
| `account-lifecycle.scheduler-enabled` | `false` | 是否开启进程内周期扫描调度 |
| `account-lifecycle.scan-interval` | `30s` | 周期扫描间隔（同时也是重启后首扫延迟） |
| `account-lifecycle.default-dormant-threshold` | `90d` | default 组休眠阈值（仅首次启动播种） |
| `account-lifecycle.default-credential-validity` | `365d` | default 组凭据有效期（仅首次启动播种） |
| `account-lifecycle.default-grace-period` | `7d` | default 组宽限期（仅首次启动播种） |
| `account-lifecycle.default-recycle-buffer` | `30d` | default 组回收缓冲期（仅首次启动播种） |

默认策略事件只在日志中不存在 `default` 组时播种一次；之后修改配置不会
覆盖历史，需要通过策略 API 显式变更。

## 7. 并发与重启安全

- **并发扫描**：扫描可被调度器和 `POST /api/admin/scan` 并发触发。
  每账号一把锁串行化迁移；状态迁移以事件为单位幂等（事件 ID 去重），
  同一时刻重复扫描不会让版本号多增。
- **重启恢复**：所有状态（账号、策略版本、恢复会话、幂等键索引）都由
  事件日志重放得到，无任何额外数据库。日志逐行整行写入并 `fsync`，
  崩溃残留的不完整尾行在重放时被跳过，对应迁移等同于未提交，可安全重试；
  已提交迁移“不丢、不重复应用”。停机期间到期的迁移在重启后首次扫描
  （或手动扫描）时幂等补做。

## 8. 审计

每一次状态迁移、活动上报、凭据发放、恢复请求/成功/失败、策略变更都追加为
一条不可变事件，字段包括：`eventId, type, accountId, occurredAt, fromStatus,
toStatus, reason, detail, idempotencyKey`。

- 按账号查询：`GET /api/accounts/{id}/audit`
- 按账号 + 时间范围：同接口加 `from` / `to` 查询参数
- 全量审计：`GET /api/admin/audit`
- 策略变更审计：事件的 `accountId` 为 `policy:{分组名}`

事件按日志顺序返回，相邻状态事件的 `toStatus/fromStatus` 首尾相接，
足以完整还原每个账号的状态演进，顺序与最终状态一致
（见 `AuditTrailTest`）。

## 9. HTTP API 一览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/accounts` | 注册账号，body：`accountId`、`groupName?`、`idempotencyKey?` |
| `GET /api/accounts` | 列出全部账号快照 |
| `GET /api/accounts/{id}` | 查询单个账号（含状态、原因、进入时刻、版本） |
| `POST /api/accounts/{id}/activity` | 上报活动，body：`at?`（缺省为当前时刻）、`idempotencyKey?` |
| `POST /api/accounts/{id}/credential:renew?idempotencyKey=` | 宽限期内更新凭据 |
| `POST /api/accounts/{id}:disable?reason=` | 禁用账号 |
| `GET /api/accounts/{id}/audit?from=&to=` | 账号审计 |
| `POST /api/recovery/{id}` | 发起回收恢复，body：`idempotencyKey?` |
| `POST /api/recovery/sessions/{sessionId}:complete` | 提交验证码，body：`code` |
| `GET /api/recovery/sessions/{sessionId}` | 查询恢复会话 |
| `GET /api/policies/{group}` | 当前生效策略 |
| `GET /api/policies/{group}/versions` | 策略历史版本 |
| `POST /api/policies/{group}` | 变更策略（四个 Duration 参数，ISO-8601，如 `P30D`） |
| `POST /api/admin/scan` | 立即触发一次扫描，返回本次发生迁移的账号 |
| `GET /api/admin/audit` | 全量审计事件 |

### 快速示例

```bash
# 注册
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
  -d '{"accountId":"alice"}'
# 上报活动
curl -X POST localhost:8080/api/accounts/alice/activity \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"beat-1"}'
# 手动扫描（测试临界时刻时很有用）
curl -X POST localhost:8080/api/admin/scan
# 调整某分组休眠阈值为 30 天
curl -X POST localhost:8080/api/policies/vip -H 'Content-Type: application/json' \
  -d '{"dormantThreshold":"P30D","credentialValidity":"P365D","gracePeriod":"P7D","recycleBuffer":"P30D"}'
```

## 10. 测试覆盖

| 测试类 | 覆盖场景 |
| --- | --- |
| `StateMachineTest` | 合法/非法状态迁移 |
| `DormancyBoundaryTest` | 休眠临界时刻、阈值后回醒 |
| `ActivityConcurrencyTest` | 并发上报与扫描交错的误判防护、并发上报顺序 |
| `CredentialGraceTest` | 凭据临界、进入宽限、临界更新、超时回收、更新幂等 |
| `RecoveryFlowTest` | 回收缓冲临界、验证失败不半成品、成功恢复、请求幂等 |
| `PolicyGroupTest` | 分组差异、未知分组回退、策略变更不追溯 |
| `ConcurrentScannerTest` | 多扫描器并发下迁移不重复 |
| `RestartRecoveryTest` | 重启重建状态/会话/幂等键、重启后扫描不重复 |
| `FileEventLogTest` | 崩溃残行跳过、后续追加正常 |
| `AuditTrailTest` | 演进链可还原、失败验证可审计、按账号/时间查询 |
| `WebApiIntegrationTest` | Spring Bean 装配与端到端 HTTP 流程 |
