package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.model.Order;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * One price-time priority queue. An iceberg order whose visible peak is
 * exhausted is moved to the tail ({@link #requeueToTail(Order)}),
 * preserving classic iceberg queueing semantics. Only visible quantities
 * live in this queue.
 *
 * <p>Not thread-safe; confined to its symbol's single engine thread.
 */
public class PriceLevel {

    private final long price;
    private final Deque<Order> orders = new ArrayDeque<>();
    private long visibleQty;

    public PriceLevel(long price) {
        this.price = price;
    }

    public long getPrice() {
        return price;
    }

    public Deque<Order> getOrders() {
        return orders;
    }

    public long getVisibleQty() {
        return visibleQty;
    }

    public int orderCount() {
        return orders.size();
    }

    /** Append a resting order's visible slice at the time-priority tail. */
    public void addTail(Order o) {
        orders.addLast(o);
        visibleQty += o.getVisibleQty();
    }

    /** Permanently remove an order and its visible slice. */
    public void remove(Order o) {
        removeWithQty(o, o.getVisibleQty());
    }

    /**
     * Remove the node while subtracting an explicit amount from the
     * aggregated visible quantity. Needed when an order's mutable
     * visible field has already been advanced (e.g. post-fill removal).
     */
    public void removeWithQty(Order o, long nodeVisibleQty) {
        for (Iterator<Order> it = orders.iterator(); it.hasNext(); ) {
            if (it.next() == o) {
                it.remove();
                visibleQty -= nodeVisibleQty;
                return;
            }
        }
    }

    /**
     * Iceberg replenishment: detach the exhausted peak and re-insert a
     * fresh slice at the tail, so other orders at this price gain time
     * priority over the replenished slice.
     */
    public void requeueToTail(Order o) {
        orders.remove(o);
        orders.addLast(o);
    }

    /** Adjust aggregated visible quantity while the node stays in place. */
    void adjustVisible(long delta) {
        this.visibleQty += delta;
    }

    /** Replace the aggregated visible quantity after a queue rebuild. */
    void setAggregate(long visibleQty) {
        this.visibleQty = visibleQty;
    }
}
