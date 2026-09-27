# Iceberg Order Book（冰山订单簿本地撮合服务）

一个**零外部依赖、可在本地直接复现**的限价/市价 + 冰山（隐藏数量）订单簿撮合服务。
基于 Spring Boot 3 / Java 17，持久化使用本地只追加 WAL 文件，进程崩溃或重启后可完整恢复。

## 运行

```bash
mvn spring-boot:run            # 启动 HTTP 服务，默认端口 8080
mvn test                       # 运行全部自动化测试（28 个）
```

所有数量、价格均为 `long` 整数，避免浮点漂移；现金统一记在 `CASH` 资产账本上。

## 配置（`application.properties` / 系统属性）

| 配置项 | 默认 | 含义 |
| --- | --- | --- |
| `matching.wal-dir` | `data/wal` | 每个品种一个 `<SYMBOL>.wal` 文件的目录 |
| `matching.fsync` | `true` | 每个提交批次是否 `fsync`（测试可关） |
| `matching.default-peak-size` | `100` | 下单未指定 peak 时的默认补充粒度 |
| `matching.min-peak-size` | `1` | 允许的最小显示（peak）数量 |
| `matching.max-peak-size` | `1000000000` | peak 上限（健全性约束） |

## HTTP API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/orders` | 下单（限价/市价） |
| `POST /api/orders/cancel` | 撤单 |
| `POST /api/orders/replace` | 改单（保留 / 放弃时间优先级） |
| `GET  /api/depth/{symbol}` | 可见深度快照 |

### 下单字段

```json
{
  "requestId": "uuid-或客户端幂等键",
  "orderId":   "客户端唯一订单号",
  "symbol":    "BTC",
  "account":   "acc-1",
  "side":      "SELL",
  "type":      "LIMIT",
  "price":     50000,
  "quantity":  1000,
  "peakSize":  100,
  "cashCap":   null
}
```

* `quantity` 是**总量**（含隐藏部分）；`peakSize` 是显示/补充粒度。
  当 `peakSize >= quantity` 时即为普通全可见订单；省略时取默认值。
* 市价单（`type=MARKET`）禁止带 `price`；市价**买入**必须提供 `cashCap`
  （最大可花费现金），现金额度用尽后剩余数量立即作废并释放冻结。
* 撤单 / 改单可带 `expectedVersion`（乐观并发控制）。

## 核心语义

### 1. 可见性：隐藏数量绝不泄露

* 每个订单将 `visibleQty`（当前 peak）与 `hiddenQty`（隐藏储备）分离维护。
* `GET /api/depth` 只聚合各价位的 `visibleQty`，`hiddenQty` 从不离开撮合内核，
  因此对端无法通过任何查询结果推测隐藏规模。
* 当前 peak 仅在被**完全成交**后才从隐藏储备补充一个新 peak；若只成交一半，
  可见量即为剩余的一半，不会提前把可见量补满（否则等于泄露隐藏量）。

### 2. 冰山与市价交叉撮合 / 精确一致

* 市价单与限价冰山订单按价格-时间优先级交叉；买方受 `cashCap` 约束。
* 冰山挂单的 peak 被吃光后，从隐藏部分按 `min(peakSize, hiddenQty)` 补充，
  并回到**该价位队首**（同一 taker 的连续成交可连续吃到新 peak）。
* 成交回报 `TradeFill` 与剩余数量严格一致：
  `剩余 = quantity − Σ成交`，隐藏补充不会产生重复成交或数量漂移。

### 3. 撤单与改单

* **撤单**立即把未成交部分的冻结资产 / 现金原路释放（available 增加、frozen 减少）。
* **改单**支持两种确定模式：
  * `keepPriority=true`：保留时间优先级，**仅允许价格不变**
    （同时改价又要求保留优先级会被拒绝：`KEEP_PRIORITY_PRICE_CHANGE`），
    订单保留其价位内的原始槽位；
  * `keepPriority=false`：放弃优先级，订单获得新的更大序号并排到价位队尾。
* 改单时可见 peak / 隐藏量基于「新总量 − 已成交量」重算；
  增加数量只会**追加冻结**，不会重复冻结已有部分。
* `expectedVersion` 不匹配返回 `VERSION_CONFLICT`，晚到的旧请求不会覆盖新状态。

### 4. 品种隔离与并发

* 每个品种拥有**独立的 `SymbolEngine`、独立订单簿、独立 WAL 文件、独立单线程 worker**。
  一个品种的磁盘停顿或长队列不会阻塞其他品种。
* 同一品种的所有下单/撤改/深度读取都在其单线程上**全序串行**，
  因此任意读取时刻订单簿都是自洽的，并发撤改不会产生重复成交。
* 共享的 `BalanceLedger` 以细粒度同步方法支持跨品种并发记账。

### 5. 持久化与崩溃恢复

* 每个请求产生**一个原子事件批次**（`ORDER_ADDED / TRADE / ENTER_BOOK /
  ORDER_CANCELLED / ORDER_REPLACED / REQUEST_REJECTED`），
  先 `flush`（可选 `fsync`）成功，才向调用方报告提交。
* WAL 帧格式：`[4字节长度][4字节 CRC32][UTF-8 JSON]`。
  崩溃时的尾部残缺帧 / CRC 不符帧会被干净地截断忽略——
  **已提交成交绝不丢失，未持久化的中间状态绝不重放**。
* 恢复时把事件流 fold 回内存，重建订单簿、深度、价格-时间优先级、
  成交序号与冻结账本（恢复路径直接还原冻结量，不做可用余额校验），
  恢复后的数量、深度、优先级与崩溃前完全一致。
* 若运行期 WAL 追加失败，该品种引擎进入 failed 状态拒绝后续请求，
  必须重启恢复，避免对外提供未落盘的状态。

### 6. 幂等与乱序

* `requestId` 是客户端幂等键。同一请求重放（含崩溃重启后）返回**首次执行时
  存储的原始结果**（相同的成交明细），不会重复成交、重复冻结。
  被拒绝的请求同样持久化，重放返回同一拒绝结果。
* 撤单 / 改单的 `expectedVersion` 提供乐观并发控制，晚到的旧版本请求被拒绝，
  不会覆盖更新后的状态。

### 7. 非法请求拒绝（可区分错误码，且绝不写入订单簿）

`INVALID_SYMBOL / INVALID_ACCOUNT / INVALID_QTY / INVALID_PRICE /
INVALID_PEAK_SIZE / PEAK_BELOW_MIN / PEAK_ABOVE_MAX /
MARKET_PRICE_NOT_ALLOWED / MARKET_CASH_REQUIRED / INVALID_SIDE / INVALID_TYPE /
INVALID_REQUEST_ID / UNKNOWN_ORDER / ORDER_DEAD / VERSION_CONFLICT /
INSUFFICIENT_BALANCE / BAD_REPLACE / KEEP_PRIORITY_PRICE_CHANGE`

拒绝的请求会记录 `REQUEST_REJECTED` 事件（用于幂等重放），但订单簿与冻结余额
不会被改动；HTTP 层对首次拒绝返回 `400` 与上述 `rejectCode`。

## 测试覆盖

`src/test/java/.../tests`：

* `IcebergVisibilityTest` —— 可见深度只暴露 peak、隐藏补充、同一 aggressor 连续成交。
* `CancelReplaceTest` —— 撤单释放冻结、保留/放弃优先级、版本冲突、幂等撤单。
* `ValidationTest` —— 各类非法请求拒绝码、拒绝不进簿、拒绝幂等、余额不足。
* `RecoveryTest` —— 重启后数量/深度/优先级/冻结一致、跨重启幂等、残缺尾帧处理。
* `DeterministicReplayTest` —— 同一 WAL 多次重建状态与成交序列完全一致。
* `ConcurrencyIsolationTest` —— 并发撤改数量守恒、重复请求只成交/冻结一次、品种隔离。
* `TradingControllerTest` —— HTTP 端到端：下单、深度、成交、撤单、可区分拒绝码。
