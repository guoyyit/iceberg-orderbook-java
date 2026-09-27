package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.api.CancelOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.api.RejectedRequestException;
import com.github.guoyyit.icebergorderbookjava.api.ReplaceOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.TradeFill;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.domain.Order;
import com.github.guoyyit.icebergorderbookjava.domain.OrderType;
import com.github.guoyyit.icebergorderbookjava.domain.RejectCode;
import com.github.guoyyit.icebergorderbookjava.domain.Side;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import com.github.guoyyit.icebergorderbookjava.wal.WalEvent;
import com.github.guoyyit.icebergorderbookjava.wal.WalReader;
import com.github.guoyyit.icebergorderbookjava.wal.WalWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful, single-threaded matching engine for one trading symbol. All
 * requests and snapshots for the symbol are serialized on its own worker, so
 * slow symbols never block other symbols and every read sees a self
 * consistent book.
 *
 * <h2>Durability</h2>
 * Every request produces an atomic batch of {@link WalEvent}s that is
 * flushed (and fsynced) <em>before</em> the response is reported as
 * committed. The live path mutates state while building the batch; recovery
 * folds the identical events back, reaching the same quantities, depth and
 * priority. If a WAL append fails the engine is marked failed and refuses
 * further requests until restarted (so unpersisted state can never serve
 * traffic).
 *
 * <h2>Idempotency / ordering</h2>
 * {@code requestId} is the client idempotency key: a replayed request
 * returns the exact stored outcome (same fills) and never trades or freezes
 * twice. {@code expectedVersion} on cancel/replace rejects stale requests
 * with {@link RejectCode#VERSION_CONFLICT}.
 */
public final class SymbolEngine {

    private final String symbol;
    private final MatchingProperties props;
    private final BalanceLedger ledger;
    private final OrderBook book = new OrderBook();
    private final Map<String, Order> orders = new HashMap<>();
    private final Map<String, OrderResponse> outcomes = new HashMap<>();

    private WalWriter writer;
    private long eventSeq;     // monotonic per-symbol WAL sequence
    private long prioritySeq;  // monotonic price-time priority sequence
    private long tradeSeq;     // monotonic trade sequence
    private boolean failed;

    public SymbolEngine(String symbol, MatchingProperties props, BalanceLedger ledger) {
        this.symbol = symbol;
        this.props = props;
        this.ledger = ledger;
    }

    public String getSymbol() {
        return symbol;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public void open() throws IOException {
        Path dir = Path.of(props.getWalDir());
        Files.createDirectories(dir);
        Path file = dir.resolve(safeName(symbol) + ".wal");
        recover(file);
        this.writer = new WalWriter(file.toString(), props.isFsync());
    }

    private static String safeName(String s) {
        if (s == null || !s.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("invalid symbol: " + s);
        }
        return s;
    }

    private void recover(Path file) throws IOException {
        List<WalEvent> all = WalReader.readAll(file.toString());
        int i = 0;
        while (i < all.size()) {
            int j = i + 1;
            // A committed request is one atomic batch; every event in it
            // carries the originating requestId (including the TRADE /
            // ENTER_BOOK events that touch resting makers). A requestId change
            // therefore marks the exact batch boundary. (seq continuity alone
            // is insufficient because distinct requests are also contiguous.)
            String rid = all.get(i).requestId;
            while (j < all.size() && java.util.Objects.equals(all.get(j).requestId, rid)) {
                j++;
            }
            List<WalEvent> group = all.subList(i, j);
            for (WalEvent e : group) {
                fold(e);
            }
            rememberOutcome(group);
            i = j;
        }
    }

    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    // ------------------------------------------------------------------
    // public operations (all run on the symbol's single worker thread)
    // ------------------------------------------------------------------

    public OrderResponse newOrder(NewOrderRequest r) {
        checkAlive();
        if (duplicate(r.requestId())) {
            return outcomes.get(r.requestId());
        }
        try {
            validateNew(r);
        } catch (RejectedRequestException ex) {
            return reject(ex);
        }

        long peak = resolvePeak(r.peakSize());
        Order o = new Order();
        o.setOrderId(r.orderId());
        o.setSymbol(symbol);
        o.setAccount(r.account());
        o.setSide(r.side());
        o.setType(r.type());
        o.setPrice(r.type() == OrderType.LIMIT ? r.price() : Long.MAX_VALUE);
        o.setTotalQty(r.quantity());
        o.setFilledQty(0L);
        o.setPeakSize(peak);
        o.setVisibleQty(Math.min(peak, r.quantity()));
        o.setHiddenQty(Math.max(0, r.quantity() - o.getVisibleQty()));
        o.setVersion(1L);
        o.setSeq(nextPriority());

        long freezeQty = r.side() == Side.SELL ? r.quantity() : 0L;
        long freezeCash = r.side() == Side.BUY
                ? (r.type() == OrderType.LIMIT ? r.price() * r.quantity() : r.cashCap())
                : 0L;
        if (!ledger.tryFreeze(r.account(), symbol, freezeQty)
                || !ledger.tryFreeze(r.account(), BalanceLedger.CASH, freezeCash)) {
            ledger.release(r.account(), symbol, freezeQty);
            ledger.release(r.account(), BalanceLedger.CASH, freezeCash);
            return reject(new RejectedRequestException(
                    RejectCode.INSUFFICIENT_BALANCE, r.requestId(), r.orderId()));
        }
        o.setFrozenQty(freezeQty);
        o.setFrozenCash(freezeCash);

        List<WalEvent> batch = new ArrayList<>();
        WalEvent added = baseEvent(WalEvent.Kind.ORDER_ADDED, r.requestId());
        added.withOrder(o.getOrderId(), o.getAccount(), o.getSide(), o.getType(),
                o.getType() == OrderType.LIMIT ? o.getPrice() : null,
                o.getTotalQty(), o.getPeakSize(),
                o.getType() == OrderType.MARKET ? r.cashCap() : null);
        added.version = o.getVersion();
        added.prioritySeq = o.getSeq();
        added.freezeAssetQty = freezeQty;
        added.freezeCash = freezeCash;
        added.visibleQty = o.getVisibleQty();
        added.hiddenQty = o.getHiddenQty();
        batch.add(added);
        orders.put(o.getOrderId(), o);

        List<TradeFill> fills = new ArrayList<>();
        matchIncoming(o, r.requestId(), batch, fills);

        append(batch);
        OrderResponse resp = new OrderResponse(r.requestId(), o.getOrderId(), true, false,
                null, o.getVersion(), o.isDead() ? 0L : o.getVisibleQty(),
                o.getFilledQty(), false, List.copyOf(fills));
        remember(r.requestId(), resp);
        return resp;
    }

    public OrderResponse cancel(CancelOrderRequest r) {
        checkAlive();
        if (duplicate(r.requestId())) {
            return outcomes.get(r.requestId());
        }
        Order o = orders.get(r.orderId());
        RejectCode code = validateCancelReplace(r.symbol(), r.orderId(), r.expectedVersion(), o);
        if (code != null) {
            return reject(new RejectedRequestException(code, r.requestId(), r.orderId()));
        }
        long releaseQty = o.getFrozenQty();
        long releaseCash = o.getFrozenCash();

        WalEvent e = baseEvent(WalEvent.Kind.ORDER_CANCELLED, r.requestId());
        e.orderId = o.getOrderId();
        e.version = o.getVersion();
        e.releaseAssetQty = releaseQty;
        e.releaseCash = releaseCash;
        append(List.of(e));

        book.remove(o);
        o.setFrozenQty(0L);
        o.setFrozenCash(0L);
        o.setDead(true);
        orders.remove(o.getOrderId());
        ledger.release(o.getAccount(), symbol, releaseQty);
        ledger.release(o.getAccount(), BalanceLedger.CASH, releaseCash);

        OrderResponse resp = new OrderResponse(r.requestId(), o.getOrderId(), true, false,
                null, e.version, 0L, o.getFilledQty(), true, List.of());
        remember(r.requestId(), resp);
        return resp;
    }

    public OrderResponse replace(ReplaceOrderRequest r) {
        checkAlive();
        if (duplicate(r.requestId())) {
            return outcomes.get(r.requestId());
        }
        Order o = orders.get(r.orderId());
        RejectCode code = validateCancelReplace(r.symbol(), r.orderId(), r.expectedVersion(), o);
        if (code != null) {
            return reject(new RejectedRequestException(code, r.requestId(), r.orderId()));
        }
        if (o.getType() == OrderType.MARKET) {
            return reject(new RejectedRequestException(
                    RejectCode.BAD_REPLACE, r.requestId(), r.orderId()));
        }
        long newPrice = r.newPrice() != null ? r.newPrice() : o.getPrice();
        long newTotal = r.newTotalQty();
        long newPeak = r.newPeakSize() != null ? r.newPeakSize() : o.getPeakSize();

        code = validateReplaceShape(newPrice, newTotal, newPeak, o.getFilledQty());
        if (code != null) {
            return reject(new RejectedRequestException(code, r.requestId(), r.orderId()));
        }
        if (r.keepPriority() && r.newPrice() != null && r.newPrice() != o.getPrice()) {
            return reject(new RejectedRequestException(
                    RejectCode.KEEP_PRIORITY_PRICE_CHANGE, r.requestId(), r.orderId()));
        }

        long remaining = newTotal - o.getFilledQty();
        long newVisible = Math.min(newPeak, remaining);
        long newHidden = Math.max(0, remaining - newVisible);
        long oldRemain = o.getTotalQty() - o.getFilledQty();

        long extraFreezeAsset = 0L;
        long extraFreezeCash = 0L;
        if (o.getSide() == Side.SELL && remaining > oldRemain) {
            extraFreezeAsset = remaining - oldRemain;
        }
        if (o.getSide() == Side.BUY) {
            long newNotional = newPrice * remaining;
            long oldNotional = o.getPrice() * oldRemain;
            if (newNotional > oldNotional) {
                extraFreezeCash = newNotional - oldNotional;
            }
        }
        if (!ledger.tryFreeze(o.getAccount(), symbol, extraFreezeAsset)
                || !ledger.tryFreeze(o.getAccount(), BalanceLedger.CASH, extraFreezeCash)) {
            ledger.release(o.getAccount(), symbol, extraFreezeAsset);
            ledger.release(o.getAccount(), BalanceLedger.CASH, extraFreezeCash);
            return reject(new RejectedRequestException(
                    RejectCode.INSUFFICIENT_BALANCE, r.requestId(), r.orderId()));
        }

        long newVersion = o.getVersion() + 1;
        long newSeq = r.keepPriority() ? o.getSeq() : nextPriority();

        WalEvent e = baseEvent(WalEvent.Kind.ORDER_REPLACED, r.requestId());
        e.orderId = o.getOrderId();
        e.version = newVersion;
        e.price = newPrice;
        e.totalQty = newTotal;
        e.peakSize = newPeak;
        e.visibleQty = newVisible;
        e.hiddenQty = newHidden;
        e.bookSeq = newSeq;
        e.prioritySeq = newSeq;
        e.extraFreezeAsset = extraFreezeAsset;
        e.extraFreezeCash = extraFreezeCash;
        WalEvent re = baseEvent(WalEvent.Kind.ENTER_BOOK, r.requestId());
        re.orderId = o.getOrderId();
        re.visibleQty = newVisible;
        re.hiddenQty = newHidden;
        re.atFront = r.keepPriority() ? null : false;
        re.atSameSlot = false;
        re.bookSeq = newSeq;
        append(List.of(e, re));

        long oldSeq = o.getSeq();
        book.remove(o);
        o.setPrice(newPrice);
        o.setTotalQty(newTotal);
        o.setPeakSize(newPeak);
        o.setVisibleQty(newVisible);
        o.setHiddenQty(newHidden);
        o.setVersion(newVersion);
        o.setSeq(newSeq);
        o.setFrozenQty(o.getFrozenQty() + extraFreezeAsset);
        o.setFrozenCash(o.getFrozenCash() + extraFreezeCash);
        if (remaining > 0) {
            if (r.keepPriority()) {
                book.addInPlace(o, oldSeq);
            } else {
                book.addToBack(o);
            }
        } else {
            o.setDead(true);
            orders.remove(o.getOrderId());
        }

        OrderResponse resp = new OrderResponse(r.requestId(), o.getOrderId(), true, false,
                null, newVersion, o.isDead() ? 0L : o.getVisibleQty(),
                o.getFilledQty(), false, List.of());
        remember(r.requestId(), resp);
        return resp;
    }

    public DepthSnapshot snapshot() {
        checkAlive();
        return book.snapshot(symbol, tradeSeq);
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    private void validateNew(NewOrderRequest r) {
        if (isBlank(r.requestId())) {
            throw new RejectedRequestException(RejectCode.INVALID_REQUEST_ID, null, r.orderId());
        }
        if (isBlank(r.symbol()) || !r.symbol().equals(symbol)) {
            throw new RejectedRequestException(RejectCode.INVALID_SYMBOL, r.requestId(), r.orderId());
        }
        if (isBlank(r.account())) {
            throw new RejectedRequestException(RejectCode.INVALID_ACCOUNT, r.requestId(), r.orderId());
        }
        if (isBlank(r.orderId())) {
            throw new RejectedRequestException(RejectCode.INVALID_ORDER_ID, r.requestId(), r.orderId());
        }
        if (r.side() == null) {
            throw new RejectedRequestException(RejectCode.INVALID_SIDE, r.requestId(), r.orderId());
        }
        if (r.type() == null) {
            throw new RejectedRequestException(RejectCode.INVALID_TYPE, r.requestId(), r.orderId());
        }
        if (r.quantity() <= 0) {
            throw new RejectedRequestException(RejectCode.INVALID_QTY, r.requestId(), r.orderId());
        }
        if (r.type() == OrderType.LIMIT) {
            if (r.price() == null || r.price() <= 0) {
                throw new RejectedRequestException(RejectCode.INVALID_PRICE, r.requestId(), r.orderId());
            }
            if (r.cashCap() != null) {
                throw new RejectedRequestException(RejectCode.MARKET_CASH_REQUIRED,
                        r.requestId(), r.orderId());
            }
        } else {
            if (r.price() != null) {
                throw new RejectedRequestException(RejectCode.MARKET_PRICE_NOT_ALLOWED,
                        r.requestId(), r.orderId());
            }
            if (r.side() == Side.BUY && (r.cashCap() == null || r.cashCap() <= 0)) {
                throw new RejectedRequestException(RejectCode.MARKET_CASH_REQUIRED,
                        r.requestId(), r.orderId());
            }
        }
        if (r.peakSize() != null) {
            long p = r.peakSize();
            if (p <= 0) {
                throw new RejectedRequestException(RejectCode.INVALID_PEAK_SIZE, r.requestId(), r.orderId());
            }
            if (p < props.getMinPeakSize()) {
                throw new RejectedRequestException(RejectCode.PEAK_BELOW_MIN, r.requestId(), r.orderId());
            }
            if (p > props.getMaxPeakSize()) {
                throw new RejectedRequestException(RejectCode.PEAK_ABOVE_MAX, r.requestId(), r.orderId());
            }
        }
    }

    private RejectCode validateCancelReplace(String reqSymbol, String orderId,
                                             Long expectedVersion, Order o) {
        if (isBlank(reqSymbol) || !reqSymbol.equals(symbol)) {
            return RejectCode.INVALID_SYMBOL;
        }
        if (isBlank(orderId) || o == null) {
            return RejectCode.UNKNOWN_ORDER;
        }
        if (o.isDead()) {
            return RejectCode.ORDER_DEAD;
        }
        if (expectedVersion != null && expectedVersion != o.getVersion()) {
            return RejectCode.VERSION_CONFLICT;
        }
        return null;
    }

    private RejectCode validateReplaceShape(long newPrice, long newTotal,
                                            long newPeak, long filled) {
        if (newPrice <= 0) {
            return RejectCode.INVALID_PRICE;
        }
        if (newTotal <= 0 || newTotal < filled) {
            return RejectCode.INVALID_QTY;
        }
        if (newPeak < props.getMinPeakSize()) {
            return RejectCode.PEAK_BELOW_MIN;
        }
        if (newPeak > props.getMaxPeakSize()) {
            return RejectCode.PEAK_ABOVE_MAX;
        }
        return null;
    }

    private long resolvePeak(Long requested) {
        long p = requested != null ? requested : props.getDefaultPeakSize();
        if (p < props.getMinPeakSize()) {
            p = props.getMinPeakSize();
        }
        return p;
    }

    // ------------------------------------------------------------------
    // matching (mutates state + ledger while emitting the event batch)
    // ------------------------------------------------------------------

    private void matchIncoming(Order taker, String reqId,
                               List<WalEvent> batch, List<TradeFill> fills) {
        boolean exhausted = false; // no more matchable liquidity / limit out of range / cash cap hit
        while (!exhausted && takerRemaining(taker) > 0) {
            // refill the taker's visible peak from its own hidden reserve
            if (taker.getVisibleQty() == 0 && taker.getHiddenQty() > 0) {
                long add = Math.min(taker.getPeakSize(), taker.getHiddenQty());
                taker.setHiddenQty(taker.getHiddenQty() - add);
                taker.setVisibleQty(add);
            }
            if (taker.getVisibleQty() == 0) {
                break;
            }
            Order maker = book.best(taker.getSide());
            if (maker == null || !crosses(taker, maker.getPrice())) {
                exhausted = true;
                break;
            }
            long qty = Math.min(taker.getVisibleQty(), maker.getVisibleQty());
            if (taker.getSide() == Side.BUY) {
                long affordable = taker.getFrozenCash() / maker.getPrice();
                qty = Math.min(qty, affordable);
                if (qty <= 0) {
                    exhausted = true;
                    break;
                }
            }
            long tradePrice = maker.getPrice();

            taker.setFilledQty(taker.getFilledQty() + qty);
            taker.setVisibleQty(taker.getVisibleQty() - qty);
            maker.setFilledQty(maker.getFilledQty() + qty);
            maker.setVisibleQty(maker.getVisibleQty() - qty);

            if (taker.getSide() == Side.BUY) {
                // buyer consumes frozen cash; seller (maker) consumes frozen asset
                taker.setFrozenCash(taker.getFrozenCash() - qty * tradePrice);
                maker.setFrozenQty(maker.getFrozenQty() - qty);
            } else {
                taker.setFrozenQty(taker.getFrozenQty() - qty);
                maker.setFrozenCash(maker.getFrozenCash() - qty * tradePrice);
            }

            long makerNewVisible = maker.getVisibleQty();
            long makerNewHidden = maker.getHiddenQty();
            boolean makerDead = maker.getFilledQty() >= maker.getTotalQty();
            boolean replenish = false;
            if (!makerDead && maker.getVisibleQty() == 0 && maker.getHiddenQty() > 0) {
                long add = Math.min(maker.getPeakSize(), maker.getHiddenQty());
                maker.setHiddenQty(maker.getHiddenQty() - add);
                maker.setVisibleQty(add);
                replenish = true;
                makerNewVisible = add;
                makerNewHidden = maker.getHiddenQty();
            }
            if (makerDead) {
                makerNewVisible = 0L;
            }

            long seq = ++tradeSeq;
            WalEvent te = baseEvent(WalEvent.Kind.TRADE, reqId);
            te.tradeSeq = seq;
            te.tradePrice = tradePrice;
            te.tradeQty = qty;
            te.makerOrderId = maker.getOrderId();
            te.takerOrderId = taker.getOrderId();
            te.makerAccount = maker.getAccount();
            te.takerAccount = taker.getAccount();
            te.makerFilledQty = maker.getFilledQty();
            te.takerFilledQty = taker.getFilledQty();
            te.makerNewVisible = makerNewVisible;
            te.makerNewHidden = makerNewHidden;
            te.takerNewVisible = taker.getVisibleQty();
            te.takerNewHidden = taker.getHiddenQty();
            te.makerFrozenQty = maker.getSide() == Side.SELL ? maker.getFrozenQty() : null;
            te.takerFrozenCash = taker.getSide() == Side.BUY ? taker.getFrozenCash() : null;
            te.takerFrozenQty = taker.getSide() == Side.SELL ? taker.getFrozenQty() : null;
            te.makerFrozenCash = maker.getSide() == Side.BUY ? maker.getFrozenCash() : null;
            batch.add(te);

            if (taker.getSide() == Side.BUY) {
                ledger.trade(taker.getAccount(), BalanceLedger.CASH, qty * tradePrice);
                ledger.trade(maker.getAccount(), symbol, qty);
            } else {
                ledger.trade(maker.getAccount(), BalanceLedger.CASH, qty * tradePrice);
                ledger.trade(taker.getAccount(), symbol, qty);
            }

            fills.add(new TradeFill(seq, maker.getOrderId(), taker.getOrderId(), symbol,
                    tradePrice, qty, taker.getTotalQty() - taker.getFilledQty(),
                    taker.getSide() == Side.BUY ? taker.getAccount() : maker.getAccount(),
                    taker.getSide() == Side.SELL ? taker.getAccount() : maker.getAccount()));

            if (makerDead) {
                maker.setDead(true);
                book.remove(maker);
                orders.remove(maker.getOrderId());
            } else if (replenish) {
                book.remove(maker);
                book.addToFront(maker);
                WalEvent re = baseEvent(WalEvent.Kind.ENTER_BOOK, reqId);
                re.orderId = maker.getOrderId();
                re.visibleQty = maker.getVisibleQty();
                re.hiddenQty = maker.getHiddenQty();
                re.atFront = true;
                re.atSameSlot = false;
                re.bookSeq = maker.getSeq();
                batch.add(re);
            } else {
                // peak partially consumed, no replenishment: the maker keeps
                // its exact within-level slot; only its visible qty changed.
                WalEvent re = baseEvent(WalEvent.Kind.ENTER_BOOK, reqId);
                re.orderId = maker.getOrderId();
                re.visibleQty = maker.getVisibleQty();
                re.hiddenQty = maker.getHiddenQty();
                re.atFront = false;
                re.atSameSlot = true;
                re.bookSeq = maker.getSeq();
                batch.add(re);
            }
        }

        boolean takerDead = taker.getFilledQty() >= taker.getTotalQty();
        if (takerDead) {
            taker.setDead(true);
            orders.remove(taker.getOrderId());
        } else if (taker.getType() == OrderType.LIMIT) {
            // unfilled limit quantity rests on the book
            book.addToBack(taker);
            WalEvent re = baseEvent(WalEvent.Kind.ENTER_BOOK, reqId);
            re.orderId = taker.getOrderId();
            re.visibleQty = taker.getVisibleQty();
            re.hiddenQty = taker.getHiddenQty();
            re.atFront = false;
            re.atSameSlot = false;
            re.bookSeq = taker.getSeq();
            batch.add(re);
        } else {
            // unfilled market quantity expires immediately; freezes released
            long releaseQty = taker.getFrozenQty();
            long releaseCash = taker.getFrozenCash();
            taker.setFrozenQty(0L);
            taker.setFrozenCash(0L);
            taker.setDead(true);
            orders.remove(taker.getOrderId());
            ledger.release(taker.getAccount(), symbol, releaseQty);
            ledger.release(taker.getAccount(), BalanceLedger.CASH, releaseCash);
            WalEvent ce = baseEvent(WalEvent.Kind.ORDER_CANCELLED, reqId);
            ce.orderId = taker.getOrderId();
            ce.releaseAssetQty = releaseQty;
            ce.releaseCash = releaseCash;
            ce.version = taker.getVersion();
            batch.add(ce);
        }
    }

    private long takerRemaining(Order taker) {
        return taker.getTotalQty() - taker.getFilledQty();
    }

    private boolean crosses(Order taker, long makerPrice) {
        if (taker.getType() == OrderType.MARKET) {
            return true;
        }
        return taker.getSide() == Side.BUY
                ? taker.getPrice() >= makerPrice
                : taker.getPrice() <= makerPrice;
    }

    // ------------------------------------------------------------------
    // commit helpers
    // ------------------------------------------------------------------

    private void append(List<WalEvent> batch) {
        try {
            writer.appendBatch(batch);
        } catch (IOException ex) {
            failed = true;
            throw new IllegalStateException("WAL append failed for " + symbol, ex);
        }
    }

    private OrderResponse reject(RejectedRequestException ex) {
        String requestId = ex.getRequestId();
        if (requestId != null && !duplicate(requestId)) {
            WalEvent e = baseEvent(WalEvent.Kind.REQUEST_REJECTED, requestId);
            e.orderId = ex.getOrderId();
            e.rejectCode = ex.getCode().name();
            append(List.of(e));
            OrderResponse resp = OrderResponse.rejected(requestId, ex.getOrderId(),
                    ex.getCode().name());
            remember(requestId, resp);
        }
        throw ex;
    }

    private void remember(String requestId, OrderResponse resp) {
        if (requestId != null) {
            outcomes.put(requestId, resp);
        }
    }

    private boolean duplicate(String requestId) {
        return requestId != null && outcomes.containsKey(requestId);
    }

    private void checkAlive() {
        if (failed) {
            throw new IllegalStateException("engine for " + symbol + " is failed; restart required");
        }
    }

    private WalEvent baseEvent(WalEvent.Kind kind, String requestId) {
        WalEvent e = new WalEvent();
        e.kind = kind;
        e.symbol = symbol;
        e.requestId = requestId;
        e.seq = ++eventSeq;
        return e;
    }

    private long nextPriority() {
        return ++prioritySeq;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------
    // event fold (recovery only; live path mutates state directly)
    // ------------------------------------------------------------------

    private void fold(WalEvent e) {
        switch (e.kind) {
            case ORDER_ADDED -> foldOrderAdded(e);
            case TRADE -> foldTrade(e);
            case ENTER_BOOK -> foldEnterBook(e);
            case ORDER_CANCELLED -> foldCancelled(e);
            case ORDER_REPLACED -> foldReplaced(e);
            case REQUEST_REJECTED -> { /* outcome handled in rememberOutcome */ }
        }
        if (e.seq > eventSeq) {
            eventSeq = e.seq;
        }
        if (e.prioritySeq != null && e.prioritySeq > prioritySeq) {
            prioritySeq = e.prioritySeq;
        }
        if (e.bookSeq != null && e.bookSeq > prioritySeq) {
            prioritySeq = e.bookSeq;
        }
        if (e.tradeSeq != null && e.tradeSeq > tradeSeq) {
            tradeSeq = e.tradeSeq;
        }
    }

    private void foldOrderAdded(WalEvent e) {
        if (orders.containsKey(e.orderId)) {
            return;
        }
        Order o = new Order();
        o.setOrderId(e.orderId);
        o.setSymbol(symbol);
        o.setAccount(e.account);
        o.setSide(e.side);
        o.setType(e.type);
        o.setPrice(e.type == OrderType.LIMIT ? e.price : Long.MAX_VALUE);
        o.setTotalQty(e.totalQty);
        o.setPeakSize(e.peakSize);
        o.setVisibleQty(e.visibleQty != null ? e.visibleQty : 0L);
        o.setHiddenQty(e.hiddenQty != null ? e.hiddenQty : 0L);
        o.setVersion(e.version != null ? e.version : 1L);
        o.setSeq(e.prioritySeq != null ? e.prioritySeq : 0L);
        o.setFrozenQty(e.freezeAssetQty != null ? e.freezeAssetQty : 0L);
        o.setFrozenCash(e.freezeCash != null ? e.freezeCash : 0L);
        orders.put(e.orderId, o);
        ledger.restoreFreeze(e.account, symbol, o.getFrozenQty());
        ledger.restoreFreeze(e.account, BalanceLedger.CASH, o.getFrozenCash());
    }

    private void foldTrade(WalEvent e) {
        Order maker = orders.get(e.makerOrderId);
        Order taker = orders.get(e.takerOrderId);
        long qty = e.tradeQty;
        long price = e.tradePrice;
        if (taker != null) {
            taker.setFilledQty(e.takerFilledQty);
            taker.setVisibleQty(e.takerNewVisible != null ? e.takerNewVisible : 0L);
            taker.setHiddenQty(e.takerNewHidden != null ? e.takerNewHidden : 0L);
            // frozen amounts are authoritative absolute values in the event
            if (e.takerFrozenCash != null) {
                taker.setFrozenCash(e.takerFrozenCash);
            }
            if (e.takerFrozenQty != null) {
                taker.setFrozenQty(e.takerFrozenQty);
            }
            if (taker.getFilledQty() >= taker.getTotalQty()) {
                taker.setDead(true);
            }
        }
        if (maker != null) {
            maker.setFilledQty(e.makerFilledQty);
            maker.setVisibleQty(e.makerNewVisible != null ? e.makerNewVisible : 0L);
            maker.setHiddenQty(e.makerNewHidden != null ? e.makerNewHidden : 0L);
            if (e.makerFrozenQty != null) {
                maker.setFrozenQty(e.makerFrozenQty);
            }
            if (e.makerFrozenCash != null) {
                maker.setFrozenCash(e.makerFrozenCash);
            }
            if (maker.getFilledQty() >= maker.getTotalQty()) {
                maker.setDead(true);
            }
        }
        String buyAccount = taker != null && taker.getSide() == Side.BUY ? taker.getAccount()
                : (maker != null && maker.getSide() == Side.BUY ? maker.getAccount() : null);
        String sellAccount = taker != null && taker.getSide() == Side.SELL ? taker.getAccount()
                : (maker != null && maker.getSide() == Side.SELL ? maker.getAccount() : null);
        if (buyAccount != null) {
            ledger.trade(buyAccount, BalanceLedger.CASH, qty * price);
        }
        if (sellAccount != null) {
            ledger.trade(sellAccount, symbol, qty);
        }
    }

    private void foldEnterBook(WalEvent e) {
        Order o = orders.get(e.orderId);
        if (o == null || o.isDead()) {
            return;
        }
        o.setVisibleQty(e.visibleQty);
        o.setHiddenQty(e.hiddenQty != null ? e.hiddenQty : o.getHiddenQty());
        if (e.bookSeq != null) {
            o.setSeq(e.bookSeq);
        }
        if (Boolean.TRUE.equals(e.atSameSlot)) {
            // book structure (price level + within-level order) is unchanged;
            // the mutated Order object is the same instance already held by
            // the level, so nothing to re-link.
            return;
        }
        book.remove(o);
        if (Boolean.TRUE.equals(e.atFront)) {
            book.addToFront(o);
        } else if (Boolean.FALSE.equals(e.atFront)) {
            book.addToBack(o);
        } else {
            // keep-priority replace: seq is the original one, reinsert in slot
            book.addInPlace(o, o.getSeq());
        }
    }

    private void foldCancelled(WalEvent e) {
        Order o = orders.get(e.orderId);
        if (o != null) {
            book.remove(o);
            o.setDead(true);
            long releaseQty = e.releaseAssetQty != null ? e.releaseAssetQty : o.getFrozenQty();
            long releaseCash = e.releaseCash != null ? e.releaseCash : o.getFrozenCash();
            o.setFrozenQty(0L);
            o.setFrozenCash(0L);
            ledger.release(o.getAccount(), symbol, releaseQty);
            ledger.release(o.getAccount(), BalanceLedger.CASH, releaseCash);
            orders.remove(o.getOrderId());
        }
    }

    private void foldReplaced(WalEvent e) {
        Order o = orders.get(e.orderId);
        if (o == null) {
            return;
        }
        long remaining = e.totalQty - o.getFilledQty();
        // Detach from the book; the following ENTER_BOOK event in the same
        // atomic batch re-links the order at the correct position (back for
        // lose-priority, original slot for keep-priority).
        book.remove(o);
        o.setPrice(e.price);
        o.setTotalQty(e.totalQty);
        o.setPeakSize(e.peakSize);
        o.setVisibleQty(e.visibleQty != null ? e.visibleQty : 0L);
        o.setHiddenQty(e.hiddenQty != null ? e.hiddenQty : 0L);
        o.setVersion(e.version);
        o.setSeq(e.bookSeq != null ? e.bookSeq : o.getSeq());
        if (e.extraFreezeAsset != null) {
            o.setFrozenQty(o.getFrozenQty() + e.extraFreezeAsset);
        }
        if (e.extraFreezeCash != null) {
            o.setFrozenCash(o.getFrozenCash() + e.extraFreezeCash);
        }
        if (remaining <= 0) {
            o.setDead(true);
            orders.remove(o.getOrderId());
        }
    }

    /**
     * Rebuild the idempotent response for a recovered request group so that a
     * replayed request after restart returns the same outcome without
     * re-executing anything.
     */
    private void rememberOutcome(List<WalEvent> group) {
        WalEvent first = group.get(0);
        String rid = first.requestId;
        if (rid == null || outcomes.containsKey(rid)) {
            return;
        }
        if (first.kind == WalEvent.Kind.REQUEST_REJECTED) {
            outcomes.put(rid, OrderResponse.rejected(rid, first.orderId, first.rejectCode));
            return;
        }
        if (first.kind == WalEvent.Kind.ORDER_CANCELLED) {
            outcomes.put(rid, new OrderResponse(rid, first.orderId, true, false, null,
                    first.version, 0L, 0L, true, List.of()));
            return;
        }
        if (first.kind == WalEvent.Kind.ORDER_REPLACED) {
            Order o = orders.get(first.orderId);
            outcomes.put(rid, new OrderResponse(rid, first.orderId, true, false, null,
                    first.version, o == null || o.isDead() ? 0L : o.getVisibleQty(),
                    o == null ? 0L : o.getFilledQty(), false, List.of()));
            return;
        }
        // ORDER_ADDED group: reconstruct fills from TRADE events. The
        // recovered taker (the submitted order) may already be gone, so derive
        // sides from the ORDER_ADDED event.
        Side takerSide = first.side;
        List<TradeFill> fills = new ArrayList<>();
        long filled = 0L;
        long total = first.totalQty != null ? first.totalQty : 0L;
        for (WalEvent e : group) {
            if (e.kind == WalEvent.Kind.TRADE) {
                filled = e.takerFilledQty;
                String buy = takerSide == Side.BUY ? e.takerAccount : e.makerAccount;
                String sell = takerSide == Side.SELL ? e.takerAccount : e.makerAccount;
                fills.add(new TradeFill(e.tradeSeq, e.makerOrderId, e.takerOrderId,
                        symbol, e.tradePrice, e.tradeQty, total - e.takerFilledQty,
                        buy, sell));
            }
        }
        Order o = orders.get(first.orderId);
        long remainingVisible = o == null || o.isDead() ? 0L : o.getVisibleQty();
        outcomes.put(rid, new OrderResponse(rid, first.orderId, true, false, null,
                first.version, remainingVisible, filled, false, List.copyOf(fills)));
    }
}
