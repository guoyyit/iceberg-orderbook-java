package com.github.guoyyit.icebergorderbookjava.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory frozen-balance ledger shared across all symbols.
 *
 * <p>Each (account, asset) row holds {@code [available, frozen]} quantities:
 * <ul>
 *   <li>order placement moves amount from available to frozen;</li>
 *   <li>cancellation / unspent market cash moves it back;</li>
 *   <li>a trade removes the consumed amount from frozen.</li>
 * </ul>
 * Methods are synchronized because different symbol workers mutate the same
 * account concurrently; within a symbol all mutations stay serialized.
 */
public class BalanceLedger {

    public record Balance(String account, String asset,
                          long available, long frozen) {
    }

    private record Key(String account, String asset) {
    }

    public static final String CASH = "CASH";

    private final Map<Key, long[]> balances = new LinkedHashMap<>();

    private synchronized long[] row(String account, String asset) {
        return balances.computeIfAbsent(new Key(account, asset), k -> new long[2]);
    }

    /** Credit available funds (test / seeding helper). */
    public synchronized void credit(String account, String asset, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative credit");
        }
        row(account, asset)[0] += amount;
    }

    /** Move {@code amount} from available to frozen. */
    public synchronized boolean tryFreeze(String account, String asset, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative freeze");
        }
        long[] r = row(account, asset);
        if (r[0] < amount) {
            return false;
        }
        r[0] -= amount;
        r[1] += amount;
        return true;
    }

    /**
     * Recovery only: restore a frozen amount directly without an available
     * balance check. The in-memory ledger is fully derived from the WAL during
     * crash recovery, so frozen rows are reconstructed as absolute state.
     */
    public synchronized void restoreFreeze(String account, String asset, long amount) {
        if (amount <= 0) {
            return;
        }
        row(account, asset)[1] += amount;
    }

    /** Recovery only: restore an available balance (seeding after restart). */
    public synchronized void restoreAvailable(String account, String asset, long amount) {
        if (amount > 0) {
            row(account, asset)[0] += amount;
        }
    }

    /** Release frozen funds back to available. */
    public synchronized void release(String account, String asset, long amount) {
        if (amount <= 0) {
            return;
        }
        long[] r = row(account, asset);
        r[1] -= amount;
        r[0] += amount;
    }

    /** Consume {@code amount} of frozen funds (a trade happened). */
    public synchronized void trade(String account, String asset, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative trade");
        }
        long[] r = row(account, asset);
        r[1] -= amount;
    }

    public synchronized long available(String account, String asset) {
        return row(account, asset)[0];
    }

    public synchronized long frozen(String account, String asset) {
        return row(account, asset)[1];
    }

    public synchronized Map<String, Balance> snapshot() {
        Map<String, Balance> out = new LinkedHashMap<>();
        balances.forEach((k, v) -> out.put(k.account() + ":" + k.asset(),
                new Balance(k.account(), k.asset(), v[0], v[1])));
        return out;
    }

    public synchronized void reset() {
        balances.clear();
    }
}
