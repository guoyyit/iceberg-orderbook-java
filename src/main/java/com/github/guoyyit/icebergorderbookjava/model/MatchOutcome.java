package com.github.guoyyit.icebergorderbookjava.model;

import java.util.List;

/**
 * Deterministic result of an aggressing request: public fills plus the
 * exact book mutations that must be committed/applied. {@code takerAfter}
 * is the aggressor state after crossing; {@code makerAfter} holds the
 * post-state of touched resting orders; orders replaced at level tail are
 * present in {@code requeuedOrderIds}.
 */
public record MatchOutcome(List<Fill> fills,
                           List<MakerFill> makerFills,
                           List<Order> makerAfter,
                           List<FinalLevel> finalLevels,
                           Order takerAfter) {

    /** Final full queue order of one touched price level. */
    public record FinalLevel(Side side, long price, List<String> orderIds) {
    }
}
