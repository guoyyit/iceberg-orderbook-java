package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.model.Order;
import com.github.guoyyit.icebergorderbookjava.model.Side;
import com.github.guoyyit.icebergorderbookjava.wal.Commit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies one durable commit to an OrderBook. Identical code runs on the
 * live path (after fsync) and again during crash recovery replay, which
 * is what makes recovered quantities/depth/priority identical to the
 * pre-crash state.
 *
 * <p>For a cross, each touched price level is rebuilt from its persisted
 * final order sequence, so iceberg replenishment tail-requeueing is
 * reproduced exactly. Frozen quantity is recomputed from rebuilt visible
 * aggregates, hence it can never drift.
 */
public final class CommitApplier {

    private CommitApplier() {
    }

    public static void apply(OrderBook book, Commit commit) {
        if (commit instanceof Commit.PlaceCommit p) {
            applyPlace(book, p);
        } else if (commit instanceof Commit.CancelCommit c) {
            applyCancel(book, c);
        } else if (commit instanceof Commit.ReplaceCommit r) {
            applyReplace(book, r);
        } else {
            throw new IllegalArgumentException("unknown commit: " + commit);
        }
    }

    private static void copyAttrs(Order target, Order src) {
        target.setOwnerId(src.getOwnerId());
        target.setSide(src.getSide());
        target.setType(src.getType());
        target.setPrice(src.getPrice());
        target.setTotalQty(src.getTotalQty());
        target.setFilledQty(src.getFilledQty());
        target.setPeakSize(src.getPeakSize());
        target.setVisibleQty(src.getVisibleQty());
        target.setRevision(src.getRevision());
        target.setActive(src.isActive());
    }

    private static void applyPlace(OrderBook book, Commit.PlaceCommit p) {
        // 1) update/deleted maker states in the order registry
        for (Commit.MakerState ms : p.makers()) {
            Order live = book.getOrder(ms.order().getOrderId());
            if (live == null) {
                book.getOrders().put(ms.order().getOrderId(), ms.order());
                live = ms.order();
            } else {
                copyAttrs(live, ms.order());
            }
            if (!live.isActive() || live.remainingQty() == 0) {
                book.getOrders().remove(live.getOrderId());
            }
        }

        // 2) rebuild each touched level from its final queue sequence and
        //    recompute visible aggregate and frozen quantity from scratch
        for (Commit.LevelOrderRec lo : p.levelOrders()) {
            Side side = Side.valueOf(lo.side());
            Map<Long, PriceLevel> map =
                    side == Side.BUY ? book.getBids() : book.getAsks();
            PriceLevel level = map.get(lo.price());
            if (level == null) {
                level = new PriceLevel(lo.price());
                map.put(lo.price(), level);
            }
            long oldAgg = level.getVisibleQty();
            // Resolve by the live deque first so untouched orders (not in
            // makers list) keep their exact node; fall back to the registry.
            Map<String, Order> liveById = new HashMap<>();
            for (Order o : level.getOrders()) {
                liveById.put(o.getOrderId(), o);
            }
            Deque<Order> rebuilt = new ArrayDeque<>();
            long agg = 0L;
            for (String id : lo.orderIds()) {
                Order o = liveById.containsKey(id)
                        ? liveById.get(id) : book.getOrder(id);
                if (o != null && o.isActive() && o.getVisibleQty() > 0) {
                    rebuilt.addLast(o);
                    agg += o.getVisibleQty();
                }
            }
            replaceQueue(level, rebuilt, agg);
            book.replaceFrozen(side, lo.price(), oldAgg, agg);
            if (agg == 0) {
                map.remove(lo.price());
            }
        }

        // 3) taker registration / resting of any leftover visible slice
        Order taker = p.taker();
        if (taker == null) {
            return;
        }
        Order existing = book.getOrder(taker.getOrderId());
        if (existing != null) {
            copyAttrs(existing, taker);
            if (existing.isActive() && existing.getVisibleQty() > 0) {
                book.addResting(existing);
            }
        } else {
            book.getOrders().put(taker.getOrderId(), taker);
            if (taker.isActive() && taker.getVisibleQty() > 0) {
                book.addResting(taker);
            }
        }
    }

    private static void replaceQueue(PriceLevel level, Deque<Order> q, long agg) {
        level.getOrders().clear();
        q.forEach(level.getOrders()::addLast);
        level.setAggregate(agg);
    }

    private static void applyCancel(OrderBook book, Commit.CancelCommit c) {
        Order live = book.getOrder(c.orderId());
        if (live == null || !live.isActive()) {
            return;
        }
        if (c.hadVisible()) {
            book.detachResting(live, live.getVisibleQty());
        }
        live.setActive(false);
        live.setVisibleQty(0);
    }

    private static void applyReplace(OrderBook book, Commit.ReplaceCommit r) {
        Order live = book.getOrder(r.orderId());
        if (live == null) {
            return;
        }
        if ("KEEP".equals(r.mode())) {
            long oldVisible = live.getVisibleQty();
            copyAttrs(live, r.after());
            book.adjustRestingVisible(live, live.getVisibleQty() - oldVisible);
        } else {
            if (r.oldHadVisible()) {
                book.detachResting(live, live.getVisibleQty());
            }
            copyAttrs(live, r.after());
            if (live.isActive() && live.getVisibleQty() > 0) {
                book.addResting(live);
            }
        }
    }

    /** Kept to avoid accidental unused-import regressions during edits. */
    @SuppressWarnings("unused")
    private static Map<?, ?> noop() {
        return new HashMap<>();
    }
}
