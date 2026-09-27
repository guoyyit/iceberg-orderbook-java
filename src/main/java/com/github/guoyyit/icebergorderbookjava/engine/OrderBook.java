package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.config.BookConfig;
import com.github.guoyyit.icebergorderbookjava.model.DepthView;
import com.github.guoyyit.icebergorderbookjava.model.LevelView;
import com.github.guoyyit.icebergorderbookjava.model.Order;
import com.github.guoyyit.icebergorderbookjava.model.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * In-memory book for one symbol. Touched only by the owning symbol's
 * single-threaded engine, hence internally unsynchronized yet always
 * self-consistent for any read performed through that engine.
 *
 * <p>Frozen quantity is exactly the visible quantity currently resting
 * on the book per side: placing a visible slice freezes it, matching
 * consumes it, replenishment swaps consumed visible for a fresh slice
 * (net zero), and cancel/amend release the difference immediately.
 */
public class OrderBook {

    private final String symbol;
    private final BookConfig config;
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>();
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<String, Order> orders = new java.util.HashMap<>();
    private long frozenBuyQty;
    private long frozenSellQty;

    public OrderBook(String symbol, BookConfig config) {
        this.symbol = symbol;
        this.config = config;
    }

    public String getSymbol() {
        return symbol;
    }

    public BookConfig getConfig() {
        return config;
    }

    public Map<String, Order> getOrders() {
        return orders;
    }

    public Order getOrder(String orderId) {
        return orders.get(orderId);
    }

    public NavigableMap<Long, PriceLevel> getBids() {
        return bids;
    }

    public NavigableMap<Long, PriceLevel> getAsks() {
        return asks;
    }

    public long getFrozenBuyQty() {
        return frozenBuyQty;
    }

    public long getFrozenSellQty() {
        return frozenSellQty;
    }

    private NavigableMap<Long, PriceLevel> levels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    public void addFrozen(Order o, long qty) {
        if (o.getSide() == Side.BUY) {
            frozenBuyQty += qty;
        } else {
            frozenSellQty += qty;
        }
    }

    public void releaseFrozen(Order o, long qty) {
        if (o.getSide() == Side.BUY) {
            frozenBuyQty -= qty;
        } else {
            frozenSellQty -= qty;
        }
    }

    /**
     * Recompute one side's frozen quantity for a rebuilt price level:
     * remove the level's old visible contribution and add the new one.
     */
    public void replaceFrozen(Side side, long price, long oldAgg, long newAgg) {
        long delta = newAgg - oldAgg;
        if (delta == 0) {
            return;
        }
        if (side == Side.BUY) {
            frozenBuyQty += delta;
        } else {
            frozenSellQty += delta;
        }
    }

    /** Insert an order's visible slice at the tail of its price level. */
    public void addResting(Order o) {
        PriceLevel level = levels(o.getSide())
                .computeIfAbsent(o.getPrice(), PriceLevel::new);
        level.addTail(o);
        addFrozen(o, o.getVisibleQty());
    }

    /**
     * Shrink/expand the visible aggregate of an order that keeps its
     * queue node (partial fill without replenishment, or KEEP amend).
     * Frozen quantity moves by {@code -delta} (a negative delta frees
     * consumed visible quantity).
     */
    public void adjustRestingVisible(Order o, long delta) {
        if (delta == 0) {
            return;
        }
        PriceLevel level = levels(o.getSide()).get(o.getPrice());
        if (level != null) {
            level.adjustVisible(delta);
        }
        if (delta < 0) {
            releaseFrozen(o, -delta);
        } else {
            addFrozen(o, delta);
        }
    }

    /** Remove an order's visible slice; prune emptied price levels. */
    public void removeResting(Order o) {
        detachResting(o, o.getVisibleQty());
    }

    /**
     * Detach a queue node, subtracting an explicit amount from both the
     * level aggregate and the side's frozen quantity. The amount may
     * differ from the order's current mutable visible field (post-fill
     * removal/replenishment).
     */
    public void detachResting(Order o, long nodeVisibleQty) {
        NavigableMap<Long, PriceLevel> map = levels(o.getSide());
        PriceLevel level = map.get(o.getPrice());
        if (level != null) {
            level.removeWithQty(o, nodeVisibleQty);
            if (level.orderCount() == 0) {
                map.remove(o.getPrice());
            }
        }
        releaseFrozen(o, nodeVisibleQty);
    }

    /**
     * Visible depth only. Aggregated visible quantity per price; the
     * hidden remainder of iceberg orders is absent and cannot be
     * inferred from this view.
     */
    public DepthView toDepthView(int maxLevels) {
        List<LevelView> bidViews = new ArrayList<>();
        for (PriceLevel level : bids.descendingMap().values()) {
            if (bidViews.size() >= maxLevels) {
                break;
            }
            if (level.getVisibleQty() > 0) {
                bidViews.add(new LevelView(level.getPrice(),
                        level.getVisibleQty(), level.orderCount()));
            }
        }
        List<LevelView> askViews = new ArrayList<>();
        for (PriceLevel level : asks.navigableKeySet().stream()
                .sorted().map(asks::get).toList()) {
            if (askViews.size() >= maxLevels) {
                break;
            }
            if (level.getVisibleQty() > 0) {
                askViews.add(new LevelView(level.getPrice(),
                        level.getVisibleQty(), level.orderCount()));
            }
        }
        return new DepthView(symbol, bidViews, askViews,
                frozenBuyQty, frozenSellQty);
    }
}
