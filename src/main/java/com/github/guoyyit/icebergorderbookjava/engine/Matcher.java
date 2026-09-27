package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.model.Fill;
import com.github.guoyyit.icebergorderbookjava.model.MakerFill;
import com.github.guoyyit.icebergorderbookjava.model.MatchOutcome;
import com.github.guoyyit.icebergorderbookjava.model.Order;
import com.github.guoyyit.icebergorderbookjava.model.OrderType;
import com.github.guoyyit.icebergorderbookjava.model.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure deterministic matching: given the current book and an aggressing
 * order, produces the exact outcome (fills + mutations + final level
 * ordering) without touching the live book. The same outcome is applied
 * on the live path and again during WAL replay, so replay reproduces
 * quantities, depth and priority deterministically.
 *
 * <p>Iceberg semantics: only the maker's visible peak participates; once
 * a peak is fully consumed, a new slice (replenishment granularity =
 * peak size) is drawn from hidden size and the order re-enters at the
 * tail of its price level, behind orders that did not replenish.
 */
public final class Matcher {

    private Matcher() {
    }

    static Order copyOf(Order o) {
        Order c = new Order();
        c.setOrderId(o.getOrderId());
        c.setSymbol(o.getSymbol());
        c.setOwnerId(o.getOwnerId());
        c.setSide(o.getSide());
        c.setType(o.getType());
        c.setPrice(o.getPrice());
        c.setTotalQty(o.getTotalQty());
        c.setFilledQty(o.getFilledQty());
        c.setPeakSize(o.getPeakSize());
        c.setVisibleQty(o.getVisibleQty());
        c.setRevision(o.getRevision());
        c.setActive(o.isActive());
        return c;
    }

    public static MatchOutcome match(OrderBook book, Order takerIn) {
        Order taker = copyOf(takerIn);
        List<Fill> fills = new ArrayList<>();
        List<MakerFill> makerFills = new ArrayList<>();
        Map<String, Order> touched = new LinkedHashMap<>();
        List<MatchOutcome.FinalLevel> finalLevels = new ArrayList<>();

        if (taker.getSide() == Side.BUY) {
            Iterable<PriceLevel> levels = taker.getType() == OrderType.MARKET
                    ? book.getAsks().values()
                    : book.getAsks().headMap(taker.getPrice(), true).values();
            for (PriceLevel level : levels) {
                if (taker.remainingQty() <= 0) {
                    break;
                }
                crossLevel(book, taker, level, fills, makerFills, touched, finalLevels);
            }
        } else {
            List<PriceLevel> levels = new ArrayList<>();
            if (taker.getType() == OrderType.MARKET) {
                book.getBids().descendingMap().values().forEach(levels::add);
            } else {
                book.getBids().tailMap(taker.getPrice(), true)
                        .descendingMap().values().forEach(levels::add);
            }
            for (PriceLevel level : levels) {
                if (taker.remainingQty() <= 0) {
                    break;
                }
                crossLevel(book, taker, level, fills, makerFills, touched, finalLevels);
            }
        }

        long remaining = taker.remainingQty();
        if (remaining <= 0 || taker.getType() == OrderType.MARKET) {
            // fully filled, or market order whose remainder never rests
            taker.setActive(false);
            taker.setVisibleQty(0);
        } else {
            taker.setActive(true);
            taker.setVisibleQty(Math.min(taker.getPeakSize(), remaining));
        }

        return new MatchOutcome(List.copyOf(fills), List.copyOf(makerFills),
                new ArrayList<>(touched.values()), finalLevels, taker);
    }

    private static void crossLevel(OrderBook book,
                                   Order taker,
                                   PriceLevel level,
                                   List<Fill> fills,
                                   List<MakerFill> makerFills,
                                   Map<String, Order> touched,
                                   List<MatchOutcome.FinalLevel> finalLevels) {
        // working copies created lazily, only for makers actually hit
        Map<String, Order> work = new HashMap<>();
        List<String> cycle = level.getOrders().stream()
                .map(Order::getOrderId).toList();

        while (taker.remainingQty() > 0) {
            List<String> next = new ArrayList<>();
            List<String> replenishedThisCycle = new ArrayList<>();
            long visibleAfter = 0;

            for (String id : cycle) {
                Order live = book.getOrder(id);
                if (live == null || !live.isActive()) {
                    continue;
                }
                Order w = work.get(id);
                if (w == null) {
                    if (taker.remainingQty() <= 0) {
                        // untouched survivor keeps its node
                        next.add(id);
                        continue;
                    }
                    w = copyOf(live);
                    work.put(id, w);
                    touched.put(id, w);
                    if (w.getVisibleQty() <= 0) {
                        continue;
                    }
                }
                if (!w.isActive() || w.getVisibleQty() <= 0) {
                    continue;
                }
                if (taker.remainingQty() <= 0) {
                    next.add(id);
                    visibleAfter += w.getVisibleQty();
                    continue;
                }

                long q = Math.min(taker.remainingQty(), w.getVisibleQty());
                long price = level.getPrice();

                w.setFilledQty(w.getFilledQty() + q);
                w.setVisibleQty(w.getVisibleQty() - q);
                taker.setFilledQty(taker.getFilledQty() + q);

                boolean replenished = false;
                if (w.getVisibleQty() == 0 && w.remainingQty() > 0) {
                    w.setVisibleQty(Math.min(w.getPeakSize(), w.remainingQty()));
                    replenished = true;
                    replenishedThisCycle.add(id);
                } else if (w.remainingQty() == 0) {
                    w.setActive(false);
                }

                makerFills.add(new MakerFill(id, w.getSide(), price, q, replenished));
                fills.add(new Fill(taker.getSymbol(), taker.getOrderId(), id,
                        taker.getSide(), price, q, taker.remainingQty()));

                if (w.isActive()) {
                    next.add(id);
                    visibleAfter += w.getVisibleQty();
                }
            }

            // replenished peaks re-enter at the tail for the next cycle
            next.removeAll(replenishedThisCycle);
            next.addAll(replenishedThisCycle);

            if (taker.remainingQty() <= 0 || visibleAfter == 0) {
                cycle = next;
                break;
            }
            cycle = next;
        }

        // final complete queue at this level (survivors only), used by the
        // applier to rebuild the level deque deterministically
        finalLevels.add(new MatchOutcome.FinalLevel(
                taker.getSide().opposite(), level.getPrice(), List.copyOf(cycle)));
    }
}
