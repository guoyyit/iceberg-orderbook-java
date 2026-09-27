package com.github.guoyyit.icebergorderbookjava.wal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.guoyyit.icebergorderbookjava.model.Order;

import java.util.List;

/**
 * One atomic, durable commit for exactly one accepted request. A commit
 * contains the deterministic outcome already computed against the book;
 * recovery simply replays commits and applies them. A commit is either
 * fully persisted (fsynced) or absent, so torn/partial state is never
 * replayed.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Commit.PlaceCommit.class, name = "PLACE"),
        @JsonSubTypes.Type(value = Commit.CancelCommit.class, name = "CANCEL"),
        @JsonSubTypes.Type(value = Commit.ReplaceCommit.class, name = "REPLACE")
})
public sealed interface Commit permits Commit.PlaceCommit, Commit.CancelCommit, Commit.ReplaceCommit {

    String symbol();

    String requestId();

    long seq();

    /**
     * Accepted new-order outcome. {@code fills} are the aggressor's
     * public trade reports; {@code taker} is null when fully filled;
     * makers carry post-state and requeue markers.
     */
    record PlaceCommit(String symbol,
                       String requestId,
                       long seq,
                       List<FillRec> fills,
                       List<MakerState> makers,
                       List<LevelOrderRec> levelOrders,
                       Order taker,
                       String takerType) implements Commit {
    }

    /** Final complete order-id sequence of one touched price level. */
    record LevelOrderRec(String side, long price, List<String> orderIds) {
    }

    /** Accepted cancel outcome: order deactivated and frozen qty freed. */
    record CancelCommit(String symbol,
                        String requestId,
                        long seq,
                        String orderId,
                        long releasedQty,
                        long oldPrice,
                        boolean hadVisible) implements Commit {
    }

    /**
     * Accepted replace outcome. KEEP: in-place attribute update (same
     * queue node). LOSE: old visible node removed and {@code after}
     * inserted at the tail of its new price level.
     */
    record ReplaceCommit(String symbol,
                         String requestId,
                         long seq,
                         String orderId,
                         String mode,
                         Order after,
                         long oldPrice,
                         boolean oldHadVisible,
                         long releasedQty,
                         long reservedQty) implements Commit {
    }

    /** Serializable shape of a public fill. */
    record FillRec(String takerOrderId,
                   String makerOrderId,
                   String takerSide,
                   long price,
                   long qty,
                   long takerRemainingAfter) {
    }

    /** Post-state of a resting maker order touched by a match. */
    record MakerState(Order order) {
    }
}
