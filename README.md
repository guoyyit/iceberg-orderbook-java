# Iceberg OrderBook (Java / Spring Boot)

本地、单进程、**不依赖任何外部服务**（无数据库、无消息队列）的限价/市价订单簿
撮合服务，支持**冰山订单（iceberg order）**的显示数量与隐藏数量分离、撤单、
改单、多品种隔离、确定性撮合、崩溃恢复以及请求幂等。

- 语言/运行时：Java 17、Spring Boot 3.4（仅用其 Web/生命周期，撮合内核与 Spring 解耦）
- 持久化：本地仅追加预写日志（WAL），每个品种一个 `.log` 文件
- 运行：`mvn spring-boot:run`，默认日志目录 `data/orderbook-wal`（可用
  `ORDERBOOK_WAL_DIR` 覆盖）

---

## 1. 冰山订单：显示数量与隐藏数量

每个订单有三个关键量：

| 字段 | 含义 |
| --- | --- |
| `totalQty` | 订单总量 |
| `filledQty` | 已成交 |
| `remainingQty = totalQty - filledQty` | 剩余总量 |
| `peakSize` | 每一片可见峰值（即“补充粒度”） |
| `visibleQty` | 当前在簿的可见片数量 |
| `hiddenQty = max(0, remainingQty - visibleQty)` | 隐藏数量 |

- 下单时 `peakSize` 缺省等于 `qty`（普通限价单，全可见）。
- 当 `peakSize < qty` 时为冰山单：簿上任何时刻只暴露 `visibleQty ≤ peakSize`，
  隐藏部分由内部状态保存。
- **可见性保证**：公共深度接口只返回每个价位聚合后的 `visibleQty` 与
  `orderCount`，返回结构里**不含**任何隐藏/剩余字段，对端无法据此推测隐藏量。
  成交回报 `Fill` 同样只回报本方（aggressor）信息：成交价、量、本方剩余，
  **不包含**对手方剩余/隐藏量。
- 订单人查询自己的订单（owner view）才能看到 `remainingQty / hiddenQty`。

### 隐藏量补充（replenishment）

当一个冰山单的可见片被**完全吃掉**后：

1. 从隐藏部分按 `peakSize`（补充粒度）切出新的一片；
2. 该订单移动到其所在价位队列的**队尾**（classic iceberg semantics），
   此前未补充的订单获得时间优先；
3. 若只是部分吃掉可见片（未到 0），订单**保留**其原队列位置。

成交回报中各笔成交的累计量、订单剩余量、深度聚合量严格守恒
（`成交 + 剩余 = 总量`；隐藏与可见之和始终等于剩余量）。

## 2. 撮合规则

- 限价单：按价格-时间优先与对手盘交叉；剩余可见片挂簿。
- 市价单：忽略价格、按价格优先吃对手盘；**不挂簿**，流动性不足时未成交部分
  直接丢弃（状态 `MARKET_DONE`，完全成交为 `FILLED`）。
- 撮合是**纯函数**：`Matcher.match(book, taker)` 只读取当前簿并产出确定性结果
  （成交列表 + 受影响订单的最终状态 + 每个受影响价位的**最终完整队列顺序**），
  不就地修改活动簿。实时路径与崩溃回放用**同一份** `CommitApplier` 应用该结果，
  因此回放可逐笔复现数量、深度与优先级。

## 3. 撤单与改单

- **撤单**：立即将订单移出簿并释放其占用的可见（冻结）数量；对已撤/已成的
  订单再次操作返回 `ORDER_NOT_ACTIVE`。
- **改单（replace/amend）**支持两种时间优先级模式：
  - `KEEP`：保留时间优先级。仅允许**同价、同 peakSize、且不增加总量**（向下改），
    队列节点位置不变；任何越界改法被拒绝，不写簿。
  - `LOSE`：放弃时间优先级。可改价/改量/改峰值，旧可见片摘簿后以新属性在
    （可能是新价位的）队尾重新入队。若改后价格会与当前对手盘交叉，则以
    `PRICE_CROSSES_MARKET` 拒绝（避免改单隐式触发不可预期的成交）。
- 每次成功改单 `revision += 1`。撤单/改单可携带可选的乐观 `revision`，
  当与当前版本不一致时返回 `REVISION_CONFLICT`，从而**晚到的旧请求不会覆盖
  新状态**。
- 同一品种内所有操作都在**该品种专属单线程**上串行执行，因此“隐藏补充 +
  并发撤/改”不会产生重复成交或数量漂移。

### 冻结数量（frozen quantity）

冻结量在内部恒等于“当前挂在簿上的可见数量（按买卖方向分别累计）”：

- 挂入可见片 → 冻结增加；成交可见片 → 冻结减少；
- 冰山补充只是“被吃掉的片换成新片”，净额为 0；
- 撤单/改单通过摘簿/伸缩可见片**立即**释放或调整。

深度快照返回 `frozenBuyQty / frozenSellQty`，便于核对守恒。

## 4. 多品种隔离

- 每个配置的品种拥有独立的 `OrderBook`、独立的 WAL 文件、**独立的单线程
  Executor**。品种之间不共享锁或线程，慢品种不会阻塞其他品种；并发压测见
  `ConcurrencyTest`。
- 未在配置中声明的品种返回 `UNKNOWN_SYMBOL`，绝不创建或污染任何簿。
- 任意读取都发生在对应品种的引擎线程上，快照时刻簿始终自洽。

## 5. 持久化与崩溃恢复

每个**已接受请求**生成一个原子提交 `Commit`（`PLACE / CANCEL / REPLACE`），
处理顺序严格为：

```
校验 → 在内存簿上做纯撮合得到结果 → 把结果作为一帧追加到 WAL 并 fsync
     → 用 CommitApplier 应用到内存簿 → 返回
```

- 日志帧格式：`int32 payloadLen | int32 CRC32(payload) | payload(JSON)`。
- `append` 在返回前对文件 `force(true)`。**fsync 是唯一的持久化点**：
  - fsync 之前崩溃：没有完整帧，重启后不会重放任何中间状态；
  - fsync 之后崩溃：该提交在重启时被回放，已提交成交不丢失。
- 单帧是最小原子单元，读取时校验长度与 CRC；遇到半截/损坏的**尾部帧**直接
  截断到上一完整帧，未持久化的脏尾巴不会被重放（见
  `RecoveryTest.tornTailFrameIsTruncatedNotReplayed`）。
- 恢复即“按 seq 顺序重放所有提交”，由同一 `CommitApplier` 应用，恢复后的
  数量、可见深度、价位内时间优先级、冻结量、`revision` 与崩溃前完全一致。

## 6. 幂等与乱序

- 所有写请求必须带 `requestId`。`requestId` 与其提交一起落盘。
- 同一 `requestId` 重放（含进程重启后）返回首次的结果（成交/状态标记一致），
  **不会重复成交、不会重复冻结**。重复响应中的订单视图始终由当前簿实时重建，
  因此不会返回陈旧数量。
- 不同 `requestId` 但相同 `orderId` 被拒绝（`ORDER_ALREADY_EXISTS`），
  防止“换个请求号重提同一订单”绕过幂等。
- 基于 `revision` 的乐观校验保证乱序到达的旧撤/改不会覆盖更新后的状态。

## 7. 配置与校验

`application.properties`：

```properties
orderbook.wal-dir=data/orderbook-wal
orderbook.depth-levels=20
orderbook.symbols.BTC-USD.min-visible-qty=1
orderbook.symbols.BTC-USD.replenish-granularity=1
orderbook.symbols.BTC-USD.max-qty=1000000000
```

按品种配置：最小显示数量 `minVisibleQty`、补充粒度
`replenishGranularity`（每个 peakSize 必须是其正整数倍）、最大数量 `maxQty`。

非法订单一律拒绝、**不写簿**，并返回可区分错误码，例如：

`INVALID_QTY`、`INVALID_PRICE`、`INVALID_SIDE`、`INVALID_ORDER_TYPE`、
`INVALID_PEAK_SIZE`、`PEAK_BELOW_MIN_VISIBLE`、`PEAK_GRANULARITY`、
`QTY_EXCEEDS_MAX`、`ORDER_ALREADY_EXISTS`、`ORDER_NOT_FOUND`、
`ORDER_NOT_ACTIVE`、`REVISION_CONFLICT`、`INVALID_REPLACE`、
`PRICE_CROSSES_MARKET`、`INVALID_PRIORITY_MODE`、`UNKNOWN_SYMBOL`、
`MISSING_REQUEST_ID`。

## 8. HTTP API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/orderbook/place` | 下限价/市价（含冰山）单 |
| POST | `/api/orderbook/cancel` | 撤单 |
| POST | `/api/orderbook/replace` | 改单（KEEP / LOSE） |
| GET  | `/api/orderbook/depth/{symbol}?levels=N` | **仅可见**深度快照 |

统一响应包络：`{ ok, data, errorCode, message, requestId }`；
业务拒绝返回 HTTP 400 且带具体 `errorCode`。

### 示例

```bash
# 冰山卖单：总量 100，每片只显示 10
curl -s localhost:8080/api/orderbook/place -H 'Content-Type: application/json' -d '{
  "requestId":"r1","orderId":"ICE1","symbol":"BTC-USD","ownerId":"trader-a",
  "side":"SELL","type":"LIMIT","price":100,"qty":100,"peakSize":10}'

# 深度里只能看到 10，而不是 100
curl -s localhost:8080/api/orderbook/depth/BTC-USD

# 撤单 / 改单（保留时间优先级）
curl -s localhost:8080/api/orderbook/cancel -H 'Content-Type: application/json' -d '{
  "requestId":"r2","orderId":"ICE1","symbol":"BTC-USD"}'
curl -s localhost:8080/api/orderbook/replace -H 'Content-Type: application/json' -d '{
  "requestId":"r3","orderId":"ICE1","symbol":"BTC-USD",
  "newTotalQty":80,"priorityMode":"KEEP","revision":1}'
```

## 9. 测试

```bash
mvn test
```

覆盖场景（`src/test/.../engine`）：

- `IcebergReplenishTest`：隐藏补充、补充后到队尾、部分成交保位、深度只暴露
  可见片、市价吃冰山。
- `CancelReplaceTest`：撤单立即释放冻结、KEEP/LOSE 两种改单优先级、改价拒单、
  陈旧 revision。
- `IdempotencyTest`：重复下单/撤单/改单不重复成交/冻结/升版本、旧请求不覆盖新状态。
- `RecoveryTest`：重启恢复数量/深度/优先级/冻结/版本、已提交成交不丢、撤单不复活、
  损坏尾帧截断、确定性回放。
- `ConcurrencyTest`：并发撤改与吃单下的守恒（无重复成交/无漂移）、多品种隔离、
  慢品种不阻塞快品种。
- `ValidationTest` / `OrderControllerTest`：非法订单可区分拒绝、HTTP 错误码、
  可见性。
