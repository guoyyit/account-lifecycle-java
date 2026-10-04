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
- 宽限超时：`now - graceEnteredAt >= credentialGracePeriod` 时回收。
- 休眠缓冲：`now - dormantEnteredAt >= dormantBufferPeriod` 时回收。
- 账号到期：`now >= accountExpiresAt` 时进入 `DISABLED`，之后扫描保持稳定。

休眠判定与活动上报通过同一把账号级锁串行；交错执行下仍活跃的账号不会被误判，
见 `ConcurrentActivityScanTest`。休眠账号上报活动会原子地回到 `ACTIVE`。

### 宽限期截止判定与凭据更新拒绝规则

凭据更新的受理与否由**提交时刻**（进入服务方法时读取的时钟值 `submittedAt`）决定，
与扫描是否已执行、与请求何时获得账号锁无关：

- 账号处于 `GRACE` 时，记宽限截止时刻 `deadline = graceEnteredAt + credentialGracePeriod`。
- `submittedAt < deadline`：更新被接受，账号原子恢复为 `ACTIVE`
  （单个 `CREDENTIAL_RENEWED` 事件同时承载有效期变更与状态恢复）。
- `submittedAt >= deadline`：更新被拒绝，抛出 `GracePeriodExpiredException`（HTTP 409，
  `reason=GRACE_PERIOD_EXPIRED`），账号状态与凭据信息不发生任何改变。
- 因此在截止时刻，更新与扫描的结果唯一确定：更新必然被拒绝、扫描必然回收，
  与两者执行顺序无关（见 `GraceDeadlineRenewalTest`）。
- 并发提交多个更新时，判定随提交时刻单调：提交更早的请求被拒绝，
  则提交更晚的请求必然也被拒绝（见 `ConcurrentRenewalConsistencyTest`）。
- 所有被拒绝的更新都会追加 `CREDENTIAL_RENEWAL_REJECTED` 审计记录，
  原因可区分：`GRACE_PERIOD_EXPIRED` / `ACCOUNT_NOT_FOUND` / `STATE_NOT_ALLOWED`。

## 凭据与账号双重过期

- 注册时可分别指定 `credentialTtl` 与 `accountTtl`（也可只指定其一或都不指定）。
- 凭据到期先进入 `GRACE`；宽限期截止前提交凭据更新即回到 `ACTIVE`，截止后提交被拒绝，
  账号由扫描回收。
- 账号整体到期直接 `DISABLED`，不会被扫描自动回收，只能由管理员重新启用。

## 回收与恢复

恢复是显式三阶段流程，仅 `RECYCLED` 账号可发起。验证结果由服务端判定，
调用方不能声明验证是否通过：

1. `POST /api/lifecycle/accounts/{id}/recoveries` 发起，可带幂等 `requestId`。
   服务端为每个新请求发放一次性验证凭据 `verificationToken`（随机生成），
   并给出其过期时刻 `tokenExpiresAtMillis`。
2. `POST /api/lifecycle/recoveries/{requestId}/verification`，请求体提交
   `{"token":"<verificationToken>"}`，服务端比对凭据后判定：
   - 凭据正确且未过期：`VERIFIED`。
   - 凭据缺失/错误（含把其它请求的凭据拿来复用）：本次验证失败，抛出
     `VerificationFailedException`（HTTP 403，`reason=TOKEN_MISSING`/`TOKEN_MISMATCH`），
     请求保持 `PENDING`，账号保持 `RECYCLED`，可持正确凭据重试。
   - 凭据已过期：请求终结为 `FAILED`（`reason=TOKEN_EXPIRED`），账号不被激活。
3. `POST /api/lifecycle/recoveries/{requestId}/completion` 完成，账号原子激活。

- 验证凭据只能用于签发它的恢复请求；有效期与恢复请求挂起 TTL 一致
  （`tokenExpiresAt = createdAt + recoveryPendingTtl`），过期后不得再通过验证。
- 验证成功后持同一凭据重复验证是幂等的；验证失败不会留下半完成状态，
  账号状态与恢复请求状态始终一致。
- 每次验证尝试都留下审计记录：成功为 `RECOVERY_VERIFIED`，失败为
  `RECOVERY_VERIFY_REJECTED`（携带 `TOKEN_MISSING`/`TOKEN_MISMATCH` 等原因），
  过期终结为 `RECOVERY_FAILED`。
- 恢复中的请求超过 `recoveryPendingTtl` 未完成，下一次扫描将其置为 `FAILED`。
- 同一进行中的请求重复发起/验证/完成均幂等；失败后可用新的 `requestId`
  重试（会签发新的验证凭据）。
- 完成动作对应单个原子 WAL 事件，进程在其落盘前后崩溃都不会产生半完成状态。
- 兼容说明：重启重放旧日志时，历史 `RECOVERY_STARTED` 记录没有验证凭据，
  对应的遗留请求无法通过验证（任何提交都会按凭据错误处理），
  待 TTL 到期后由扫描终结，不影响其它数据的使用。

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
- 宽限期内的凭据更新是单个原子 WAL 事件（`CREDENTIAL_RENEWED`，同时携带新有效期
  与状态恢复），不存在"有效期已更新而状态仍停留在宽限期"的中间态：
  落盘前崩溃 = 完整未生效，落盘后崩溃 = 重启重放后完整生效
  （见 `AtomicRenewalCrashTest`）。
- 启动时按序号顺序重放日志重建内存；落盘前崩溃 = 事件不存在，可安全重试；
  落盘后崩溃 = 重放后状态已生效，重试不会重复应用（见 `RestartRecoveryTest`）。
- 崩溃/断电可能在日志尾部留下一条不完整记录。重启时只顺序解码到第一条无法解析
  （或未以换行终结）的记录为止：其前的完整历史全部重放，尾部残缺记录被丢弃并把
  日志文件截断到最后一条完整记录之后，不会因此放弃整份日志，重启后扫描与日常
  操作不再反复报错（见 `CorruptTailRecoveryTest`）。
- 日志文件：`${account-lifecycle.data-dir}/lifecycle.log`（JSON Lines）。

## 审计

每次账号注册、活动上报、状态迁移、凭据更新、策略变更、恢复发起/验证/完成/失败
都追加一条带全局递增 `sequence` 与时间戳的审计记录。审计直接读取 WAL，
顺序与最终状态严格一致，支持按账号与毫秒时间范围查询：

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

本次修复未新增配置项；验证凭据有效期直接取自分组策略的 `recoveryPendingTtl`，
既有配置与数据无需迁移即可继续使用。

即使不开启内置调度，也可随时调用 `POST /api/lifecycle/scan` 手动触发扫描。

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

# 回收账号恢复三步（验证凭据由服务端发放）
curl -XPOST localhost:8080/api/lifecycle/accounts/u1/recoveries \
  -H 'Content-Type: application/json' -d '{"requestId":"r-1"}'
# 响应中包含 verificationToken 与 tokenExpiresAtMillis，下一步需原样提交
curl -XPOST localhost:8080/api/lifecycle/recoveries/r-1/verification \
  -H 'Content-Type: application/json' -d '{"token":"<verificationToken>"}'
curl -XPOST localhost:8080/api/lifecycle/recoveries/r-1/completion
```

## 测试

```bash
mvn test
```

覆盖：状态机非法迁移、休眠临界、宽限期临界、休眠缓冲回收、并发上报与扫描交错、
分组策略不追溯、恢复幂等与失败不留半成品、TTL 超时、WAL 重放与崩溃前后恢复、
审计顺序与时间窗查询、宽限期截止前后的更新/扫描交错与截止时刻唯一确定性、
并发更新的接受/拒绝一致性、日志尾部不完整记录的重启重建、凭据更新两效果间崩溃的
完整生效/完整未生效、验证凭据缺失/错误/过期/跨请求复用与失败恢复幂等。
