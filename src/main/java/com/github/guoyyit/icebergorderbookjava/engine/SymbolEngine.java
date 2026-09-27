package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.dto.CancelRequest;
import com.github.guoyyit.icebergorderbookjava.dto.CancelResult;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.PlaceResult;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceRequest;
import com.github.guoyyit.icebergorderbookjava.dto.ReplaceResult;
import com.github.guoyyit.icebergorderbookjava.model.DepthView;
import com.github.guoyyit.icebergorderbookjava.model.ErrorCode;
import com.github.guoyyit.icebergorderbookjava.model.Fill;
import com.github.guoyyit.icebergorderbookjava.model.MatchOutcome;
import com.github.guoyyit.icebergorderbookjava.model.MatchingException;
import com.github.guoyyit.icebergorderbookjava.model.Order;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.PriorityMode;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import com.github.guoyyit.icebergorderbookjava.model.OrderView;
import com.github.guoyyit.icebergorderbookjava.wal.Commit;
import com.github.guoyyit.icebergorderbookjava.wal.CommitReader;
import com.github.guoyyit.icebergorderbookjava.wal.CommitWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one symbol's OrderBook, durable log and a dedicated single
 * thread. All mutations are serialized on that thread, so a slow symbol
 * can never block another symbol and every read observes a
 * self-consistent book.
 *
 * <p>Per request the flow is: validate → compute deterministic outcome
 * against the in-memory book → fsync one commit → apply the same
 * outcome. Crashes before fsync leave no commit (no replay of
 * un-persisted intermediate state); crashes after fsync replay the
 * commit on restart. A requestId is committed inside its commit, so
 * replays after restart are idempotent too.
 */
public class SymbolEngine implements AutoCloseable {

    private final String symbol;
    private final BookConfig config;
    private final String walDir;
    private final OrderBook book;
    private final CommitWriter writer;
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, CachedResult> responses = new HashMap<>();
    private final ExecutorService executor;
    private volatile boolean recovered;

    public SymbolEngine(String symbol, BookConfig config, String walDir) {
        this.symbol = symbol;
        this.config = config;
        this.walDir = walDir;
        this.book = new OrderBook(symbol, config);
        this.writer = new CommitWriter(walDir, symbol);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "engine-" + symbol);
            t.setDaemon(true);
            return t;
        });
    }

    public String getSymbol() {
        return symbol;
    }

    /** Package-internal accessor used by tests for owner-side assertions. */
    OrderBook bookView() {
        return book;
    }

    /** Idempotency marker; views are always rebuilt live from the book. */
    private record CachedResult(Kind kind,
                                String orderId,
                                String status,
                                List<Fill> fills) {
        enum Kind { PLACE, CANCEL, REPLACE }
    }

    // -------------------------------------------------------------
    // recovery
    // -------------------------------------------------------------

    public void recover() {
        run(() -> {
            List<Commit> commits = CommitReader.readAll(walDir, symbol);
            writer.open();
            for (Commit c : commits) {
                CommitApplier.apply(book, c);
                responses.put(c.requestId(), cachedFor(c));
                seq.set(Math.max(seq.get(), c.seq()));
            }
            recovered = true;
            return null;
        });
    }

    // -------------------------------------------------------------
    // public API (all serialized on the symbol thread)
    // -------------------------------------------------------------

    public PlaceResult place(PlaceRequest req) {
        return run(() -> doPlace(req));
    }

    public CancelResult cancel(CancelRequest req) {
        return run(() -> doCancel(req));
    }

    public ReplaceResult replace(ReplaceRequest req) {
        return run(() -> doReplace(req));
    }

    public DepthView depth(int maxLevels) {
        return run(() -> book.toDepthView(maxLevels <= 0 ? 20 : maxLevels));
    }

    @Override
    public void close() {
        run(() -> {
            try {
                writer.close();
            } catch (IOException e) {
                throw new MatchingException(ErrorCode.INTERNAL_ERROR, e.getMessage());
            }
            return null;
        });
        executor.shutdown();
    }

    private <T> T run(Callable<T> task) {
        try {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MatchingException(ErrorCode.INTERNAL_ERROR, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof MatchingException me) {
                throw me;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new MatchingException(ErrorCode.INTERNAL_ERROR,
                    cause == null ? "engine failure" : cause.getMessage());
        }
    }

    // -------------------------------------------------------------
    // place
    // -------------------------------------------------------------

    private PlaceResult doPlace(PlaceRequest req) {
        requireRequestId(req.requestId());
        CachedResult cached = responses.get(req.requestId());
        if (cached != null) {
            return new PlaceResult(cached.orderId(), cached.status(),
                    toView(book.getOrder(cached.orderId())), cached.fills(),
                    visibleOnBook(cached.orderId()));
        }
        if (req.orderId() == null || req.orderId().isBlank()) {
            throw reject(ErrorCode.INVALID_REQUEST, "orderId required");
        }
        if (book.getOrder(req.orderId()) != null) {
            throw reject(ErrorCode.ORDER_ALREADY_EXISTS, req.orderId());
        }
        Side side = req.side();
        if (side == null) {
            throw reject(ErrorCode.INVALID_SIDE, "side required");
        }
        OrderType type = req.type();
        if (type == null) {
            throw reject(ErrorCode.INVALID_ORDER_TYPE, "type required");
        }
        long qty = requirePositive(req.qty(), "qty");
        if (qty > config.maxQty()) {
            throw reject(ErrorCode.QTY_EXCEEDS_MAX, String.valueOf(qty));
        }
        Long price = req.price();
        if (type == OrderType.LIMIT) {
            if (price == null || price <= 0) {
                throw reject(ErrorCode.INVALID_PRICE, "limit price required");
            }
        } else if (price != null) {
            throw reject(ErrorCode.INVALID_PRICE, "market order must not carry price");
        }
        long peakSize;
        if (type == OrderType.MARKET) {
            if (req.peakSize() != null) {
                throw reject(ErrorCode.INVALID_PEAK_SIZE,
                        "market order cannot be iceberg");
            }
            peakSize = qty;
        } else {
            peakSize = req.peakSize() == null ? qty : req.peakSize();
            if (peakSize <= 0) {
                throw reject(ErrorCode.INVALID_PEAK_SIZE, "peakSize > 0");
            }
            if (peakSize > qty) {
                throw reject(ErrorCode.INVALID_PEAK_SIZE, "peakSize > total qty");
            }
            if (peakSize < config.minVisibleQty()) {
                throw reject(ErrorCode.PEAK_BELOW_MIN_VISIBLE,
                        "peakSize " + peakSize + " < minVisibleQty "
                                + config.minVisibleQty());
            }
            if (req.peakSize() != null
                    && peakSize % config.replenishGranularity() != 0) {
                throw reject(ErrorCode.PEAK_GRANULARITY,
                        "peakSize must be multiple of "
                                + config.replenishGranularity());
            }
        }

        Order taker = new Order();
        taker.setOrderId(req.orderId());
        taker.setSymbol(symbol);
        taker.setOwnerId(req.ownerId());
        taker.setSide(side);
        taker.setType(type);
        taker.setPrice(price);
        taker.setTotalQty(qty);
        taker.setFilledQty(0);
        taker.setPeakSize(peakSize);
        taker.setVisibleQty(0);
        taker.setRevision(1);
        taker.setActive(true);

        MatchOutcome outcome = Matcher.match(book, taker);

        List<Commit.FillRec> fillRecs = new ArrayList<>();
        for (Fill f : outcome.fills()) {
            fillRecs.add(new Commit.FillRec(f.takerOrderId(), f.makerOrderId(),
                    f.takerSide().name(), f.price(), f.qty(),
                    f.takerRemainingAfter()));
        }
        List<Commit.MakerState> makers = new ArrayList<>();
        for (Order m : outcome.makerAfter()) {
            makers.add(new Commit.MakerState(Matcher.copyOf(m)));
        }
        List<Commit.LevelOrderRec> levelRecs = new ArrayList<>();
        for (MatchOutcome.FinalLevel fl : outcome.finalLevels()) {
            levelRecs.add(new Commit.LevelOrderRec(
                    fl.side().name(), fl.price(), fl.orderIds()));
        }
        Order takerSnapshot = Matcher.copyOf(outcome.takerAfter());

        Commit.PlaceCommit commit = new Commit.PlaceCommit(symbol,
                req.requestId(), seq.incrementAndGet(),
                List.copyOf(fillRecs), List.copyOf(makers),
                List.copyOf(levelRecs), takerSnapshot, type.name());
        persist(commit);
        CommitApplier.apply(book, commit);

        List<Fill> publicFills = List.copyOf(outcome.fills());
        responses.put(req.requestId(), new CachedResult(
                CachedResult.Kind.PLACE, taker.getOrderId(),
                statusOf(outcome.takerAfter(), publicFills.isEmpty()),
                publicFills));
        return new PlaceResult(taker.getOrderId(),
                statusOf(outcome.takerAfter(), publicFills.isEmpty()),
                toView(book.getOrder(taker.getOrderId())), publicFills,
                visibleOnBook(taker.getOrderId()));
    }

    // -------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------

    private CancelResult doCancel(CancelRequest req) {
        requireRequestId(req.requestId());
        CachedResult cached = responses.get(req.requestId());
        if (cached != null) {
            Order o = book.getOrder(cached.orderId());
            return new CancelResult(cached.orderId(), cached.status(),
                    cached.status().equals("CANCELLED")
                            ? 0L : o == null ? 0L : o.getVisibleQty(),
                    toView(o));
        }
        Order live = requireActiveOrder(req.orderId());
        if (req.revision() != null && req.revision() != live.getRevision()) {
            throw reject(ErrorCode.REVISION_CONFLICT,
                    "revision " + req.revision() + " != current "
                            + live.getRevision());
        }
        long released = live.getVisibleQty();
        Commit.CancelCommit commit = new Commit.CancelCommit(symbol,
                req.requestId(), seq.incrementAndGet(), live.getOrderId(),
                released, live.getPrice() == null ? 0L : live.getPrice(),
                released > 0);
        persist(commit);
        CommitApplier.apply(book, commit);

        responses.put(req.requestId(), new CachedResult(
                CachedResult.Kind.CANCEL, live.getOrderId(),
                "CANCELLED", List.of()));
        return new CancelResult(live.getOrderId(), "CANCELLED", released,
                toView(book.getOrder(live.getOrderId())));
    }

    // -------------------------------------------------------------
    // replace / amend
    // -------------------------------------------------------------

    private ReplaceResult doReplace(ReplaceRequest req) {
        requireRequestId(req.requestId());
        CachedResult cached = responses.get(req.requestId());
        if (cached != null) {
            return new ReplaceResult(cached.orderId(), cached.status(),
                    toView(book.getOrder(cached.orderId())), List.of());
        }
        Order live = requireActiveOrder(req.orderId());
        if (live.getType() != OrderType.LIMIT) {
            throw reject(ErrorCode.INVALID_REPLACE, "only limit orders amendable");
        }
        if (req.revision() != null && req.revision() != live.getRevision()) {
            throw reject(ErrorCode.REVISION_CONFLICT,
                    "revision " + req.revision() + " != current "
                            + live.getRevision());
        }
        PriorityMode mode;
        if (req.priorityMode() == null) {
            mode = PriorityMode.LOSE;
        } else {
            try {
                mode = PriorityMode.valueOf(req.priorityMode());
            } catch (IllegalArgumentException e) {
                throw reject(ErrorCode.INVALID_PRIORITY_MODE, req.priorityMode());
            }
        }

        long newTotal = req.newTotalQty() == null
                ? live.getTotalQty() : req.newTotalQty();
        if (newTotal <= live.getFilledQty()) {
            throw reject(ErrorCode.INVALID_REPLACE,
                    "new total must exceed filled qty");
        }
        if (newTotal > config.maxQty()) {
            throw reject(ErrorCode.QTY_EXCEEDS_MAX, String.valueOf(newTotal));
        }

        Order after = Matcher.copyOf(live);
        after.setTotalQty(newTotal);

        if (mode == PriorityMode.KEEP) {
            if (req.newPrice() != null && !req.newPrice().equals(live.getPrice())) {
                throw reject(ErrorCode.INVALID_REPLACE,
                        "KEEP mode cannot change price");
            }
            if (req.newPeakSize() != null
                    && req.newPeakSize() != live.getPeakSize()) {
                throw reject(ErrorCode.INVALID_REPLACE,
                        "KEEP mode cannot change peakSize");
            }
            if (newTotal > live.getTotalQty()) {
                throw reject(ErrorCode.INVALID_REPLACE,
                        "KEEP mode cannot increase total qty");
            }
            after.setVisibleQty(Math.min(live.getVisibleQty(), after.remainingQty()));
        } else {
            long newPrice = req.newPrice() == null ? live.getPrice() : req.newPrice();
            if (newPrice <= 0) {
                throw reject(ErrorCode.INVALID_PRICE, String.valueOf(newPrice));
            }
            long newPeak = req.newPeakSize() == null
                    ? live.getPeakSize() : req.newPeakSize();
            if (newPeak <= 0) {
                throw reject(ErrorCode.INVALID_PEAK_SIZE, "peakSize > 0");
            }
            if (newPeak > newTotal - live.getFilledQty()) {
                throw reject(ErrorCode.INVALID_PEAK_SIZE, "peakSize > remaining");
            }
            if (newPeak < config.minVisibleQty()) {
                throw reject(ErrorCode.PEAK_BELOW_MIN_VISIBLE, String.valueOf(newPeak));
            }
            if (req.newPeakSize() != null
                    && newPeak % config.replenishGranularity() != 0) {
                throw reject(ErrorCode.PEAK_GRANULARITY,
                        "peakSize must be multiple of "
                                + config.replenishGranularity());
            }
            if (crossesBook(after.getSide(), newPrice)) {
                throw reject(ErrorCode.PRICE_CROSSES_MARKET,
                        "amended price would cross; cancel/replace instead");
            }
            after.setPrice(newPrice);
            after.setPeakSize(newPeak);
            after.setVisibleQty(Math.min(newPeak, after.remainingQty()));
        }
        after.setRevision(live.getRevision() + 1);
        after.setActive(true);

        long oldVisible = live.getVisibleQty();
        Commit.ReplaceCommit commit = new Commit.ReplaceCommit(symbol,
                req.requestId(), seq.incrementAndGet(), live.getOrderId(),
                mode.name(), Matcher.copyOf(after),
                live.getPrice(), oldVisible > 0, oldVisible,
                after.getVisibleQty());
        persist(commit);
        CommitApplier.apply(book, commit);

        String rstatus = mode == PriorityMode.KEEP
                ? "AMENDED_KEEP_PRIORITY" : "AMENDED_LOST_PRIORITY";
        responses.put(req.requestId(), new CachedResult(
                CachedResult.Kind.REPLACE, live.getOrderId(), rstatus, List.of()));
        return new ReplaceResult(live.getOrderId(), rstatus,
                toView(book.getOrder(live.getOrderId())), List.of());
    }

    private boolean crossesBook(Side side, long price) {
        if (side == Side.BUY) {
            var ask = book.getAsks().firstEntry();
            return ask != null && price >= ask.getKey();
        } else {
            var bid = book.getBids().lastEntry();
            return bid != null && price <= bid.getKey();
        }
    }

    // -------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------

    private void persist(Commit commit) {
        try {
            writer.append(commit);
        } catch (IOException e) {
            throw new MatchingException(ErrorCode.INTERNAL_ERROR,
                    "persist failed: " + e.getMessage());
        }
    }

    private Order requireActiveOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw reject(ErrorCode.INVALID_REQUEST, "orderId required");
        }
        Order o = book.getOrder(orderId);
        if (o == null) {
            throw reject(ErrorCode.ORDER_NOT_FOUND, orderId);
        }
        if (!o.isActive()) {
            throw reject(ErrorCode.ORDER_NOT_ACTIVE, orderId);
        }
        return o;
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw reject(ErrorCode.MISSING_REQUEST_ID, "requestId required");
        }
    }

    private static long requirePositive(Long v, String name) {
        if (v == null || v <= 0) {
            throw new MatchingException(ErrorCode.INVALID_QTY,
                    name + " must be a positive integer");
        }
        return v;
    }

    private static MatchingException reject(ErrorCode code, String msg) {
        return new MatchingException(code, msg);
    }

    private CachedResult cachedFor(Commit c) {
        if (c instanceof Commit.PlaceCommit p) {
            List<Fill> fills = new ArrayList<>();
            for (Commit.FillRec f : p.fills()) {
                fills.add(new Fill(symbol, f.takerOrderId(), f.makerOrderId(),
                        Side.valueOf(f.takerSide()), f.price(), f.qty(),
                        f.takerRemainingAfter()));
            }
            OrderType tt = OrderType.valueOf(p.takerType());
            boolean full = p.taker() != null && p.taker().remainingQty() == 0;
            return new CachedResult(CachedResult.Kind.PLACE,
                    p.taker().getOrderId(),
                    statusOf(tt, p.taker().isActive(), full, fills.isEmpty()),
                    List.copyOf(fills));
        }
        if (c instanceof Commit.CancelCommit cc) {
            return new CachedResult(CachedResult.Kind.CANCEL,
                    cc.orderId(), "CANCELLED", List.of());
        }
        if (c instanceof Commit.ReplaceCommit rc) {
            return new CachedResult(CachedResult.Kind.REPLACE, rc.orderId(),
                    "KEEP".equals(rc.mode())
                            ? "AMENDED_KEEP_PRIORITY" : "AMENDED_LOST_PRIORITY",
                    List.of());
        }
        throw new MatchingException(ErrorCode.INTERNAL_ERROR,
                "unknown commit during recovery: " + c);
    }

    private static String statusOf(Order t, boolean noFills) {
        return statusOf(t.getType(), t.isActive(), t.remainingQty() == 0, noFills);
    }

    private static String statusOf(OrderType type, boolean active,
                                   boolean fullyFilled, boolean noFills) {
        if (type == OrderType.MARKET) {
            // market orders never rest: fully consumed vs liquidity-exhausted
            return fullyFilled ? "FILLED" : "MARKET_DONE";
        }
        if (active) {
            return noFills ? "RESTING" : "PARTIAL_RESTING";
        }
        return "FILLED";
    }

    private long visibleOnBook(String orderId) {
        Order o = book.getOrder(orderId);
        return o != null && o.isActive() ? o.getVisibleQty() : 0L;
    }

    static OrderView toView(Order o) {
        if (o == null) {
            return null;
        }
        return new OrderView(o.getOrderId(), o.getSymbol(), o.getOwnerId(),
                o.getSide(), o.getType(), o.getPrice(), o.getTotalQty(),
                o.getFilledQty(), o.remainingQty(), o.getPeakSize(),
                o.getVisibleQty(), o.hiddenQty(), o.getRevision(), o.isActive());
    }
}
