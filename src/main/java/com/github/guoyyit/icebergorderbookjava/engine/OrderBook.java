package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.domain.Order;
import com.github.guoyyit.icebergorderbookjava.domain.Side;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Price-time priority book for one symbol. Each side is a price map whose
 * value preserves insertion order (FIFO). Iceberg replenishment re-enters at
 * the <em>front</em> of its price level, giving deterministic
 * within-level priority.
 *
 * <p>Only {@code visibleQty} participates in matching and depth; hidden
 * reserves are never observable through this class.
 *
 * <p>This class is NOT thread-safe: all access is serialized by
 * {@link SymbolEngine}'s single worker thread.
 */
final class OrderBook {

    static final int MAX_DEPTH_LEVELS = 50;

    // bids: descending; asks: ascending
    private final TreeMap<Long, LinkedHashMap<String, Order>> bids =
            new TreeMap<>((a, b) -> Long.compare(b, a));
    private final TreeMap<Long, LinkedHashMap<String, Order>> asks = new TreeMap<>();

    void addToBack(Order o) {
        map(o.getSide()).computeIfAbsent(o.getPrice(), p -> new LinkedHashMap<>())
                .put(o.getOrderId(), o);
    }

    /** Insert at the head of the price level (iceberg peak replenishment). */
    void addToFront(Order o) {
        TreeMap<Long, LinkedHashMap<String, Order>> m = map(o.getSide());
        LinkedHashMap<String, Order> old = m.get(o.getPrice());
        LinkedHashMap<String, Order> rebuilt = new LinkedHashMap<>();
        rebuilt.put(o.getOrderId(), o);
        if (old != null) {
            old.forEach((id, ord) -> {
                if (!id.equals(o.getOrderId())) {
                    rebuilt.put(id, ord);
                }
            });
        }
        m.put(o.getPrice(), rebuilt);
    }

    /**
     * Re-insert an order keeping its previous within-level slot: if the level
     * contains a successor the order keeps its relative position ahead of it,
     * otherwise it goes to the back. Used by keep-priority replaces at an
     * unchanged price.
     */
    void addInPlace(Order o, long successorHint) {
        TreeMap<Long, LinkedHashMap<String, Order>> m = map(o.getSide());
        LinkedHashMap<String, Order> old = m.remove(o.getPrice());
        LinkedHashMap<String, Order> rebuilt = new LinkedHashMap<>();
        boolean inserted = false;
        if (old != null) {
            for (var en : old.entrySet()) {
                if (!inserted && en.getValue().getSeq() > successorHint) {
                    rebuilt.put(o.getOrderId(), o);
                    inserted = true;
                }
                if (!en.getKey().equals(o.getOrderId())) {
                    rebuilt.put(en.getKey(), en.getValue());
                }
            }
        }
        if (!inserted) {
            rebuilt.put(o.getOrderId(), o);
        }
        m.put(o.getPrice(), rebuilt);
    }

    void remove(Order o) {
        TreeMap<Long, LinkedHashMap<String, Order>> m = map(o.getSide());
        LinkedHashMap<String, Order> level = m.get(o.getPrice());
        if (level != null) {
            level.remove(o.getOrderId());
            if (level.isEmpty()) {
                m.remove(o.getPrice());
            }
        }
    }

    private TreeMap<Long, LinkedHashMap<String, Order>> map(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private TreeMap<Long, LinkedHashMap<String, Order>> opposite(Side takerSide) {
        return takerSide == Side.BUY ? asks : bids;
    }

    /**
     * First live order at the most aggressive price of the opposite side,
     * lazily pruning tombstoned orders. Returns null if the book is empty.
     */
    Order best(Side takerSide) {
        TreeMap<Long, LinkedHashMap<String, Order>> m = opposite(takerSide);
        while (!m.isEmpty()) {
            var first = m.firstEntry();
            LinkedHashMap<String, Order> level = first.getValue();
            Iterator<Order> it = level.values().iterator();
            while (it.hasNext()) {
                Order o = it.next();
                if (o.isDead() || o.getVisibleQty() <= 0) {
                    it.remove();
                } else {
                    if (level.isEmpty()) {
                        m.pollFirstEntry();
                    }
                    return o;
                }
            }
            m.pollFirstEntry(); // whole level was dead
        }
        return null;
    }

    /** Best opposite price, or MAX_VALUE (no asks) / 0 (no bids). */
    long bestOppositePrice(Side takerSide) {
        Order o = best(takerSide);
        return o == null ? (takerSide == Side.BUY ? Long.MAX_VALUE : 0L) : o.getPrice();
    }

    DepthSnapshot snapshot(String symbol, long lastTradeSeq) {
        List<DepthSnapshot.Level> bidLevels = levels(bids);
        List<DepthSnapshot.Level> askLevels = levels(asks);
        return new DepthSnapshot(symbol, lastTradeSeq, bidLevels, askLevels);
    }

    private List<DepthSnapshot.Level> levels(TreeMap<Long, LinkedHashMap<String, Order>> m) {
        List<DepthSnapshot.Level> out = new ArrayList<>();
        for (var e : m.entrySet()) {
            long qty = 0L;
            int count = 0;
            for (Order o : e.getValue().values()) {
                if (o.isDead() || o.getVisibleQty() <= 0) {
                    continue;
                }
                qty += o.getVisibleQty();
                count++;
            }
            if (count > 0) {
                out.add(new DepthSnapshot.Level(e.getKey(), qty, count));
                if (out.size() >= MAX_DEPTH_LEVELS) {
                    break;
                }
            }
        }
        return out;
    }
}
