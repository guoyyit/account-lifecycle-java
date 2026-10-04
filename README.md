# 本地账号生命周期治理服务

进程内的账号生命周期治理组件：休眠检测、凭据/账号双重过期约束、回收处置、验证恢复、
分组策略、并发与重启安全、全流程审计。所有状态保存在本地 WAL 日志文件中，
不依赖数据库、消息队列或任何外部服务，也不处理真实凭据。

## 状态机

账号任一时刻处于五种状态之一，且当前状态与进入原因（`stateReason`）始终可查询。

| 状态 | 含义 |
| --- | --- |
| `ACTIVE` | 正常 |
| `DORMANT` | 休眠（最近活动时间超过分组休眠阈值） |
| `GRACE` | 宽限期（凭据已过期，等待更新） |
| `DISABLED` | 禁用（账号整体到期或管理员禁用） |
| `RECYCLED` | 已回收（宽限超时或休眠缓冲期结束） |

合法迁移（其它迁移一律抛出 `InvalidTransitionException`，HTTP 409）：

```
ACTIVE    -> DORMANT | GRACE | DISABLED
DORMANT   -> ACTIVE（收到活动） | GRACE（凭据过期） | DISABLED | RECYCLED（缓冲期结束）
GRACE     -> ACTIVE（宽限期内更新凭据） | DISABLED | RECYCLED（宽限期超时）
DISABLED  -> ACTIVE（仅管理员手动启用）
RECYCLED  -> ACTIVE（仅恢复流程完成）
```

同状态迁移视为幂等空操作。

### 时间边界约定（闭区间，确定性）

所有判定使用注入的 `Clock`，扫描时刻记为 `now`：

- 休眠：`now - lastActivityAt >= dormancyThreshold` 时进入 `DORMANT`；差 1ms 不进入。
- 凭据过期：`now >= credentialExpiresAt` 时进入 `GRACE`；临界时刻即触发。
- 宽限截止：`now - graceEnteredAt >= credentialGracePeriod` 时宽限期截止（截止时刻为
  `graceEnteredAt + credentialGracePeriod`，闭区间，临界时刻即截止）。
- 休眠缓冲：`now - dormantEnteredAt >= dormantBufferPeriod` 时回收。
- 账号到期：`now >= accountExpiresAt` 时进入 `DISABLED`，之后扫描保持稳定。

休眠判定与活动上报通过同一把账号级锁串行；交错执行下仍活跃的账号不会被误判，
见 `ConcurrentActivityScanTest`。休眠账号上报活动会原子地回到 `ACTIVE`。

#### 宽限期截止的凭据更新拒绝规则

截止时刻由状态进入时间与当前策略的 `credentialGracePeriod` 唯一确定，凭据更新与扫描
共用账号级锁，因此截止时刻的结果与两者执行顺序无关，只有一种结果：

- 截止**之前**（`now < deadline`）提交凭据更新：恢复为 `ACTIVE`，凭据有效期顺延，
  行为与历史一致。
- 截止**时刻及之后**（`now >= deadline`）提交凭据更新：一律拒绝，账号状态、进入原因、
  凭据有效期与版本号都不发生任何改变；随后的扫描必然回收。
- 扫描先执行时账号已变为 `RECYCLED`，更新按"状态不允许"拒绝；更新先执行时按
  "宽限期已截止"拒绝。两条路径互斥且最终都是"未更新 + 被回收"。
- 拒绝原因通过异常错误码/HTTP `errorCode` 区分：
  `GRACE_PERIOD_EXPIRED`（宽限期截止）、`INVALID_STATE_TRANSITION`（状态不允许）、
  账号不存在为通用 `LIFECYCLE_ERROR`。
- 每次被拒绝的更新都追加一条 `CREDENTIAL_RENEWAL_REJECTED` 审计事件，`detail` 内含
  机器可判定的拒绝原因（`ACCOUNT_NOT_FOUND` / `STATE_NOT_ALLOWED` /
  `GRACE_PERIOD_EXPIRED`）与说明。
- 宽限期内成功的凭据更新是**单个原子 WAL 事件** `CREDENTIAL_RENEWED`，
  "凭据有效期变更"与"`GRACE -> ACTIVE` 状态恢复"要么完整生效、要么完整未生效，
  不存在两个事件之间崩溃导致的半完成结果。见 `GraceDeadlineTest`、
  `ConcurrentGraceRenewTest`、`RenewalAtomicityTest`。

## 凭据与账号双重过期

- 注册时可分别指定 `credentialTtl` 与 `accountTtl`（也可只指定其一或都不指定）。
- 凭据到期先进入 `GRACE`；宽限期内调用凭据更新即回到 `ACTIVE`，否则回收。
- 账号整体到期直接 `DISABLED`，不会被扫描自动回收，只能由管理员重新启用。

## 回收与恢复

恢复是显式三阶段流程，仅 `RECYCLED` 账号可发起。验证结果由**服务端**判定，
调用方不能自行声明验证成功：

1. `POST /api/lifecycle/accounts/{id}/recoveries` 发起，可带幂等 `requestId`。
   服务端生成一次性验证凭据，随响应返回：
   `verificationToken`（仅本次返回，后续不再披露）与 `verificationTokenExpiresAtMillis`。
   凭据以 SHA-256 摘要持久化，WAL 与审计中不出现明文。
2. `POST /api/lifecycle/recoveries/{requestId}/verification`，请求体
   `{"verificationToken":"..."}`，由服务端校验：
   - 凭据正确且未过期：`VERIFIED`（账号仍保持 `RECYCLED`，直到完成阶段才激活）。
   - 缺失/空白：错误码 `RECOVERY_TOKEN_MISSING`；错误：`RECOVERY_TOKEN_MISMATCH`；
     凭据属于其它恢复请求：`RECOVERY_TOKEN_FOREIGN`；已过期：`RECOVERY_TOKEN_EXPIRED`。
   - 凭据缺失、错误、跨请求复用：请求保持 `PENDING`、账号保持 `RECYCLED`，可更正后重试。
   - 凭据过期：请求置为 `FAILED`、账号保持 `RECYCLED`，需以新的 `requestId` 重新发起。
   - 每次尝试都追加 `RECOVERY_VERIFICATION_FAILED` 审计事件，`detail` 内含可区分的
     失败原因；成功验证追加 `RECOVERY_VERIFIED`。
3. `POST /api/lifecycle/recoveries/{requestId}/completion` 完成，账号原子激活。

- 恢复中的请求超过 `recoveryPendingTtl` 未完成，下一次扫描将其置为 `FAILED`。
- 验证凭据只能用于发放它的那个恢复请求；把 A 请求的凭据用于 B 请求一律按
  `RECOVERY_TOKEN_FOREIGN` 拒绝。
- 验证凭据有效期由 `account-lifecycle.default-verification-ttl`（默认 `PT10M`）控制，
  过期后不再通过验证；该有效期与恢复请求整体的 `recoveryPendingTtl` 相互独立。
- 同一进行中的请求重复发起/验证/完成均幂等（验证成功后重复提交不再追加事件）；
  失败后可用新的 `requestId` 重试。
- 完成动作对应单个原子 WAL 事件，进程在其落盘前后崩溃都不会产生半完成状态。

见 `RecoveryVerificationTokenTest`、`FailedRecoveryIdempotencyTest`、
`RecoveryFlowTest`。

## 策略与分组

- 每个分组一条当前策略，带自增 `revision`；修改即追加新版本，历史完整保留。
- 未配置的分组回退到 `default` 分组。
- 策略变更只影响之后的判定：已发生的状态、进入原因与时间不被追溯改写
  （见 `PolicyGroupTest`）。

| 策略项 | 含义 | 默认值 |
| --- | --- | --- |
| `dormancyThreshold` | 无活动多久判定休眠 | `P90D` |
| `credentialGracePeriod` | 凭据过期后的宽限期 | `P7D` |
| `dormantBufferPeriod` | 休眠后保留多久才回收 | `P30D` |
| `accountMaxLifetime` | 账号默认整体有效期，`0` 表示不限制 | `0` |
| `recoveryPendingTtl` | 恢复请求最长挂起时间 | `PT24H` |

## 并发与重启安全

- 同一账号的上报、凭据更新、恢复与扫描迁移共用账号级锁，串行执行。
- 扫描可并发调用；`scanOnce()` 对全部账号逐个判定，每个账号最多发生一次幂等迁移。
- 所有状态变更是"先持久（WAL 追加 + `fsync`）后可见（内存物化）"。
- 启动时按序号顺序重放日志重建内存；落盘前崩溃 = 事件不存在，可安全重试；
  落盘后崩溃 = 重放后状态已生效，重试不会重复应用（见 `RestartRecoveryTest`）。
- 对日志尾部不完整记录容错：进程在追加过程中崩溃/断电，可能在尾部留下一条截断的
  JSON 行。重启时只丢弃**最后一条无法解码的尾部记录**，此前完整持久化的全部记录
  照常重放，内存状态、审计顺序与日志严格一致；已完成的迁移既不丢失也不重复应用。
  重启时会物理截断（必要时补全换行符并 `fsync`）尾部损坏字节，之后扫描与日常操作
  不再因该遗留问题报错。若损坏行之后仍存在可解码记录（即损坏发生在日志中部），
  属于不可安全判定的介质损坏，启动直接报错而不是静默忽略。
- 一次凭据更新的两个效果（有效期变更、状态恢复）合并在单个 `CREDENTIAL_RENEWED`
  事件里，因此在"两个效果之间崩溃"这一历史窗口已不存在；尾部恰好截断该事件时，
  重启结果是完整未生效（凭据与状态都不变，可安全重试）。见 `LogTornTailTest`、
  `RenewalAtomicityTest`。
- 日志文件：`${account-lifecycle.data-dir}/lifecycle.log`（JSON Lines）。

## 审计

每次账号注册、活动上报、状态迁移、凭据更新（成功与被拒绝）、策略变更、
恢复发起/验证尝试（成功与各类失败）/完成/失败都追加一条带全局递增 `sequence`
与时间戳的审计记录。拒绝与失败类事件只用于审计，不改变账号或请求状态。
审计直接读取 WAL，顺序与最终状态严格一致，支持按账号与毫秒时间范围查询：

| 事件类型 | 说明 |
| --- | --- |
| `CREDENTIAL_RENEWED` | 宽限期/正常期凭据更新成功（原子更新有效期与状态恢复） |
| `CREDENTIAL_RENEWAL_REJECTED` | 凭据更新被拒，`detail` 含 `ACCOUNT_NOT_FOUND`/`STATE_NOT_ALLOWED`/`GRACE_PERIOD_EXPIRED` |
| `RECOVERY_VERIFICATION_FAILED` | 恢复验证尝试失败，`detail` 含 `RECOVERY_TOKEN_MISSING`/`RECOVERY_TOKEN_MISMATCH`/`RECOVERY_TOKEN_FOREIGN`/`RECOVERY_TOKEN_EXPIRED` |

```
GET /api/lifecycle/audit?accountId=u1&fromMillis=...&toMillis=...
GET /api/lifecycle/accounts/{id}/audit
```

## 配置项（application.properties）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `account-lifecycle.data-dir` | `data/account-lifecycle` | WAL 目录 |
| `account-lifecycle.scan-interval` | `PT1M` | 调度扫描间隔 |
| `account-lifecycle.scheduler-enabled` | `false` | 是否开启内置定时扫描 |
| `account-lifecycle.default-dormancy-threshold` | `P90D` | 默认休眠阈值 |
| `account-lifecycle.default-credential-grace-period` | `P7D` | 默认宽限期 |
| `account-lifecycle.default-dormant-buffer-period` | `P30D` | 默认休眠缓冲期 |
| `account-lifecycle.default-account-max-lifetime` | `PT0S` | 默认账号有效期 |
| `account-lifecycle.default-recovery-pending-ttl` | `PT24H` | 默认恢复 TTL |
| `account-lifecycle.default-verification-ttl` | `PT10M` | 恢复验证凭据默认有效期 |

即使不开启内置调度，也可随时调用 `POST /api/lifecycle/scan` 手动触发扫描。

错误响应统一为 `409 Conflict`（状态不允许、宽限期截止、验证凭据问题）或
`400 Bad Request`（参数/账号问题），响应体均含 `errorCode` 与 `error` 字段。

## HTTP 接口示例

```bash
# 注册账号（凭据 30 天有效）
curl -XPOST localhost:8080/api/lifecycle/accounts -H 'Content-Type: application/json' \
  -d '{"accountId":"u1","group":"default","credentialTtl":"P30D"}'

# 上报活动
curl -XPOST localhost:8080/api/lifecycle/accounts/u1/activity -H 'Content-Type: application/json' -d '{}'

# 宽限期内更新凭据
curl -XPOST localhost:8080/api/lifecycle/accounts/u1/credential-renewal \
  -H 'Content-Type: application/json' -d '{"ttl":"P30D"}'

# 修改某分组策略
curl -XPUT localhost:8080/api/lifecycle/groups/vip/policy -H 'Content-Type: application/json' \
  -d '{"dormancyThreshold":"P180D","credentialGracePeriod":"P14D",
       "dormantBufferPeriod":"P60D","recoveryPendingTtl":"PT48H","reason":"VIP 策略"}'

# 触发一次扫描
curl -XPOST localhost:8080/api/lifecycle/scan

# 回收账号恢复三步
curl -XPOST localhost:8080/api/lifecycle/accounts/u1/recoveries \
  -H 'Content-Type: application/json' -d '{"requestId":"r-1"}'
# 上一步响应中拿到 verificationToken（仅发放时返回一次）
curl -XPOST localhost:8080/api/lifecycle/recoveries/r-1/verification \
  -H 'Content-Type: application/json' -d '{"verificationToken":"<服务端发放的凭据>"}'
curl -XPOST localhost:8080/api/lifecycle/recoveries/r-1/completion
```

## 测试

```bash
mvn test
```

覆盖：状态机非法迁移、休眠临界、宽限期临界与截止后强制拒绝（`GraceDeadlineTest`）、
截止时刻更新/扫描交错与并发更新唯一结果（`ConcurrentGraceRenewTest`）、
休眠缓冲回收、并发上报与扫描交错、分组策略不追溯、
服务端验证凭据的缺失/错误/跨请求复用/过期与幂等（`RecoveryVerificationTokenTest`、
`FailedRecoveryIdempotencyTest`）、TTL 超时、
WAL 重放与崩溃前后恢复（`RestartRecoveryTest`）、
日志尾部不完整记录的重启重建（`LogTornTailTest`）、
凭据更新两效果的原子性（`RenewalAtomicityTest`）、审计顺序与时间窗查询。
测试全部使用注入的 `MutableClock`、临时目录与确定性令牌生成器，不依赖外部服务，
也不使用真实凭据。
