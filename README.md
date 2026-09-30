# 本地闪电贷服务（flashloan-java）

一个基于 Spring Boot 3 / Java 17 的**本地无抵押闪电贷**服务。借款方可以在单个
**原子执行单元**内借出资金、在多个资金池/多币种之间流转，最后归还本金与手续费。
任一环节失败、归还不足（或超额）或执行超时，整个单元整体回滚，不留下任何状态变更。

## 1. 核心原子性语义

每个借款请求（一次 `POST /api/loans`）是一个原子单元，严格遵循：

1. **先拷贝、后提交（copy-on-write 事务）**：单元开始时对“已提交状态”做深拷贝，
   放款、用户操作、归还校验全部发生在工作副本上；提交前对外完全不可见。
2. **单一提交点**：只有当全部操作成功、归还足额且无残留资金时，才把新状态
   **原子落盘（临时文件 fsync + 原子 rename + 目录 fsync）**，落盘成功后才对外可见。
3. **失败即整体回滚**：操作失败、余额不足、归还不足、归还超额、残留外币、超过步数
   上限或执行超时，工作副本被直接丢弃；资金池余额、手续费、借款记录均无任何变化。
4. **失败不收费**：手续费只在成功提交的单元中扣收并随提交落盘，失败单元不产生手续费。
5. **写事务全局串行化 + 快照读**：所有写事务（借款 / 建池 / 注资 / 配置更新）串行执行，
   杜绝同一笔可用资金被并发重复借出；读请求读取已提交快照，不阻塞也不会看到中间状态。

## 2. 资金模型与操作

- **资金池（Pool）**：持有若干 `{币种: 余额}`，余额不允许为负。
- **借款方虚拟账户 `<borrower>`**：仅在单元执行期内存在、不持久化。放款时本金进入
  该虚拟账户，用户操作驱动资金流动，结束时由平台自动结算归还。
- **自动归还**：单元结束时，借款方必须在**借出币种**上恰好持有
  `本金 + 手续费`，平台将其贷记回借出池（手续费沉淀在借出池中）；
  - 少于该数额 → `INSUFFICIENT_REPAYMENT`；
  - 多于该数额 → `REPAYMENT_SURPLUS`（资金不允许滞留给借款方）；
  - 其他币种余额非零 → `INSUFFICIENT_REPAYMENT`。

支持的资金操作（`operations` 数组，按序执行）：

| type | 含义 | 关键字段 |
| --- | --- | --- |
| `TRANSFER` | 同币种在池与池、池与借款方之间划转 | `fromPool`、`toPool`、`currency`、`amount`（`fromPool`/`toPool` 可用 `<borrower>`） |
| `SWAP` | 在指定池 `pool` 内按固定数量兑换币种 | `pool`、`fromPool`（`<borrower>` 表示借款方与池兑换，填池 ID 表示池内自兑）、`fromCurrency`/`toCurrency`、`fromAmount`/`toAmount` |

所有数量均使用 `BigDecimal` 精确十进制运算，避免浮点误差。

## 3. HTTP 接口

### 资金池管理
- `POST /api/pools`：建池并可选初始注资。请求：`{"id","name","balances":{"USDT":1000}}`
- `POST /api/pools/{poolId}/deposits`：追加流动性。请求体使用 `{"balances":{...}}`
- `GET  /api/pools`：列出全部池
- `GET  /api/pools/{poolId}`：查询单个池

### 借款执行与审计
- `POST /api/loans`：原子执行借款单元
- `GET  /api/loans`：列出全部**已提交**单元（失败单元不产生记录）
- `GET  /api/loans/{id}`：查询单元详情，含逐步资金流转路径、手续费与所用配置版本

借款请求示例：

```json
{
  "poolId": "A",
  "currency": "USDT",
  "amount": 1000,
  "operations": [
    {"type":"TRANSFER","fromPool":"<borrower>","toPool":"B","currency":"USDT","amount":1000},
    {"type":"SWAP","pool":"B","fromPool":"B","fromCurrency":"USDT","toCurrency":"ETH","fromAmount":1000,"toAmount":1}
  ]
}
```

### 配置
- `GET /api/config`：查询当前配置（含单调递增 `version`）
- `PUT /api/config`：更新配置，字段为空表示该项不变；返回新版本

失败响应统一形如：

```json
{ "status":"FAILED", "failureReason":"INSUFFICIENT_REPAYMENT",
  "failureMessage":"归还不足: 应还本金+手续费 1001 ...", "timestamp":"..." }
```

失败原因（`failureReason`，确定性枚举）与典型 HTTP 状态：
`INVALID_REQUEST`/`INVALID_AMOUNT`/`INVALID_OPERATION`/`LOAN_LIMIT_EXCEEDED`/
`STEP_LIMIT_EXCEEDED` → 400；`POOL_NOT_FOUND`/`CURRENCY_NOT_FOUND` → 404；
`INSUFFICIENT_POOL_BALANCE`/`INSUFFICIENT_REPAYMENT`/`REPAYMENT_SURPLUS` → 422；
`EXECUTION_TIMEOUT` → 408。

## 4. 配置项

可在 `application.properties` 配置，也可经 `PUT /api/config` 在运行中更新：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `flashloan.data-dir` | `data/flashloan` | 持久化快照目录，崩溃恢复的唯一事实来源 |
| `flashloan.default-fee-rate` | `0.001` | 默认手续费率（占本金比例，0.001 = 0.1%） |
| `flashloan.default-max-loan-amount` | `1000000` | 默认单笔借款本金上限 |
| `flashloan.default-max-steps` | `50` | 默认单单元资金操作步数上限 |
| `flashloan.execution-timeout-ms` | `5000` | 单单元执行超时（毫秒），超时整体回滚 |
| `flashloan.test-step-delay-ms` | `0` | 仅测试用：每步注入固定延时，生产保持 0 |

**运行时更新的生效时点（确定且可解释）**：写事务全局串行化。配置更新本身是一个
原子写事务，更新成功提交即产生新的单调递增 `version`；**只有在该提交点之后开始**
的借款单元使用新参数，已开始（进行中）的单元自始至终持有其开始时刻的配置快照。
每条借款记录都保存 `configVersion`、`feeRate`、`fee`，可事后审计其适用的参数。

## 5. 并发控制

- 写事务在进程内通过单一写锁串行化：同一池（乃至整个系统）的写操作不会交叉，
  可用资金不可能被两个单元重复借出。
- 每个写事务在自己的深拷贝上工作，提交时一次性发布；读路径读取不可变的已提交快照，
  因此并发的其他单元/查询观察不到中间写入。
- 单元内每一步都校验“余额不为负”，池余额与全部已提交借款记录始终自洽。

## 6. 崩溃恢复

- 状态以 JSON 快照（`state.json`）持久化：包含全部池余额、当前配置、下一个借款 ID
  与全部已提交记录。
- 提交采用 **临时文件写入并 `fsync` → 原子 rename → 目录 `fsync`**：要么完整保留
  上一个已提交快照，要么完整出现新快照，不存在半写状态。
- 服务重启（`DurableStore.init`）时从快照恢复：**已提交单元的结果（含手续费）保留，
  未提交单元整体回滚**；失败/崩溃单元不占用借款 ID。

## 7. 审计与确定性重放

- 每条已提交记录包含：`id`、`status`、借出池/币种/本金、`feeRate`/`fee`、
  `configVersion`、逐步 `steps`（含类型、来源、目标、币种、数量，归还步含 `fee`）。
- 失败单元的原因通过同步错误响应的 `failureReason`/`failureMessage` 返回，
  且不产生任何记录或费用。
- **确定性**：相同的请求序列在相同初始状态与配置下产生相同结果——ID 由持久化的
  单调序列分配，运算使用精确十进制，操作按请求顺序执行，不含时间/随机影响
  （`timestamp` 仅出现在 HTTP 错误信封中，不属于业务状态）。

## 8. 运行与测试

```bash
mvn spring-boot:run          # 启动服务，默认端口 8080
mvn test                     # 运行全部自动化测试
```

测试覆盖（见 `src/test/.../tests`）：成功主链路、手续费收取、多池多币种流转、
归还不足 / 中途失败 / 外币残留 / 超额归还回滚、并发竞争与快照隔离、执行超时回滚、
运行时配置更新与生效时点、崩溃恢复（已提交保留 / 未提交回滚）以及 HTTP 接口。
