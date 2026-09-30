# Flash Loan Service (Java / Spring Boot)

本地闪电贷服务。借款方无需抵押，在单个原子执行单元内借出资金、执行一组资金
操作并归还本金与手续费；归还不足、任一操作失败或执行超时，整个单元的全部
状态变更都会回滚。服务以嵌入式文件 H2 为持久化存储，通过 HTTP 接口提供资金池
管理、借款执行、审计查询和配置管理。

## 运行

```bash
mvn spring-boot:run
# 或
mvn -DskipTests package
java -jar target/flashloan-java-1.0-SNAPSHOT.jar
```

默认端口 `8080`，文件数据库位于 `./data/flashloan.mv.db`。

## 原子性语义

- 一次借款请求（一个 `requestId`）在**单个数据库事务**内执行：放款、全部资金
  操作、池余额更新、借款记录与资金流水要么全部提交，要么全部回滚。
- 放款后，资金进入该单元的虚拟借款账户 `loan:<币种>`。操作可以把资金在虚拟
  账户与资金池之间转移或兑换。单元结束时必须同时满足：
  - 借款池最终余额 ≥ 期初余额 + 手续费（本金全额归还，手续费留存在池内）；
  - 每个币种的虚拟借款账户余额为 0（资金不能滞留或丢失）。
- 任一操作非法、余额不足、归还不足、超过步数上限或执行超时，事务立即回滚，
  池余额和流水不留下任何部分写入；失败单元**不收取任何手续费**，但会留下一条
  `FAILED` 借款记录用于审计。
- 多币种兑换不做全局守恒校验（不同币种是不同的账）；同一币种在一次单元内
  通过“池余额 + 虚拟账户余额为 0”的约束保证不凭空增减。

### 多池与并发

- 单元涉及的全部资金池在执行前按池 ID 升序一次性 `SELECT ... FOR UPDATE`
  加行锁，从加锁后的当前行值读取余额。因此并发单元严格串行化，既不会死锁，
  也不会基于过期余额重复借出同一笔可用资金。
- 一个单元对多个池的改动随同一事务提交，彼此之间看不到中间状态。

### 费率、限额与运行中更新

- 配置只有一行（版本号 `version` 单调递增），每次更新是一个独立的原子事务，
  对未提供的字段做局部更新。
- **生效时点**：借款单元在自己的事务开始时读取当时已提交的配置快照，整笔
  借款（限额、手续费、步数、超时）都绑定该快照及其 `configVersion`。因此更新
  配置不会干扰已经在执行中的单元；只对更新提交之后新开始的单元生效。

### 崩溃恢复

- H2 以 `WRITE_DELAY=0` 运行，事务提交在返回前同步写入预写日志，已提交单元
  不会因进程被杀而丢失。
- 执行中途（事务尚未提交）崩溃时，数据库在重启打开时自动回滚该事务，池余额、
  流水与借款记录恢复到单元开始前；该 `requestId` 之后可以重新执行。
- 应用启动时执行对账：池表余额必须等于该池账本账户的末条余额；不得存在
  `RUNNING` 等非终态借款；流水只能来自成功提交的单元。结果可通过
  `GET /api/config/recovery` 查询。

### 确定性与审计

- 每次成功借款的完整资金路径（放款、每步转移/兑换、余额）写入 `ledger`，
  可通过 `GET /api/loans/{requestId}/ledger` 查询。
- `requestId` 幂等：相同 `requestId` 重放返回已存储的同一结果（成功返回 200
  与原金额/手续费；失败返回 409 与原失败码和原因），不会重复执行或重复收费。
  相同请求序列重放产生相同结果；手续费按 `HALF_UP` 取整、兑换按 `DOWN` 取整，
  汇率由请求显式给出，不依赖任何外部随机源。

## 资金操作

| 类型 | 说明 |
| --- | --- |
| `TRANSFER` | 同币种转移，一侧为虚拟借款账户（`fromPoolId` 或 `toPoolId` 置空），另一侧为池。 |
| `EXCHANGE` | 跨币种兑换，源金额 `amount`、目标入账 `floor(amount * rate)`；任一侧可为虚拟账户，允许池到池（用于兑换后直接归还）。 |

金额均为最小货币单位的非负整数；账户任何时刻余额不得为负。

操作可选字段 `delayMs`（正数）在该步执行前等待，仅用于确定性地测试执行超时；
普通业务请求无需传递。

## HTTP 接口

### 资金池
- `POST /api/pools` — 建池 `{currency, initialDeposit}`
- `GET /api/pools` / `GET /api/pools/{id}`
- `POST /api/pools/{id}/deposit` — 充值 `{amount}`（同步写账本）

### 借款
- `POST /api/loans` — 执行借款单元，见下方请求示例
- `GET /api/loans` / `GET /api/loans/{requestId}` — 借款记录（含失败码与原因）
- `GET /api/loans/{requestId}/ledger` — 资金流转明细

### 配置与恢复
- `GET /api/config` — 当前配置与版本
- `POST /api/config` — 局部更新配置（原子、版本 +1）
- `GET /api/config/recovery` — 实时对账报告

借款请求示例：

```json
{
  "requestId": "loan-1",
  "poolId": 1,
  "amount": 10000,
  "timeoutMs": 5000,
  "operations": [
    {"type": "EXCHANGE", "toPoolId": 2, "amount": 10000,
     "currency": "USDC", "toCurrency": "DAI", "rate": 1.01},
    {"type": "EXCHANGE", "fromPoolId": 2, "toPoolId": 1, "amount": 10100,
     "currency": "DAI", "toCurrency": "USDC", "rate": 1.0}
  ]
}
```

## 配置项

| 配置 | 默认值 | 约束 | 说明 |
| --- | --- | --- | --- |
| `feeRate` | `0.001` | `[0, 1]` | 手续费率，手续费 `round-half-up(amount * feeRate)`。 |
| `maxLoanAmount` | `1000000000` | `> 0` | 单笔借款上限（最小单位）。 |
| `maxSteps` | `32` | `1..10000` | 单个单元的操作步数上限。 |
| `timeoutMs` | `5000` | `1..3600000` | 单元执行超时（毫秒），逐步检查；请求可传更小值，不能超过配置值。 |

系统属性（非业务配置）：
- `flashloan.port`（默认 `8080`）HTTP 端口。
- `flashloan.data-dir`（默认 `./data`）H2 数据目录。
- `flashloan.crash-hook-enabled`：仅测试用，开启后请求中的 `crashAfterCommit`
  /`crashBeforeCommit` 可在提交后/提交前硬终止 JVM，用于崩溃恢复测试；生产
  环境不要开启。

## 错误码

失败响应为 `422`（请求体问题为 `400`，未知资源 `404`，已失败请求重放为
`409`）。借款记录中的 `failureCode` 包括：`INVALID_REQUEST`、
`LOAN_AMOUNT_EXCEEDED`、`TOO_MANY_STEPS`、`EXECUTION_TIMEOUT`、`POOL_NOT_FOUND`、
`INSUFFICIENT_LIQUIDITY`、`INVALID_OPERATION`、`CURRENCY_MISMATCH`、
`INSUFFICIENT_FUNDS`、`REPAYMENT_SHORTFALL`、`FUNDS_NOT_RETURNED` 等。

## 测试

```bash
mvn test               # 除崩溃恢复外的全部单元/集成测试（内存 H2）
mvn verify             # 额外运行 CrashRecoveryTests（fork 真实子进程 + 文件 H2）
```

- `MultiPoolFlowTests`：多池转移/兑换、手续费、账本与重放。
- `RollbackTests`：归还不足、后步操作失败、流动性不足的整体回滚与零手续费。
- `ConcurrencyTests`：同池并发借款不重复借出、无丢失更新。
- `ConfigTests`：费率/限额/步数/超时的运行中更新与原子生效。
- `CrashRecoveryTests`：fork JVM 硬崩溃，验证已提交保留、未提交回滚、重启对账一致。
