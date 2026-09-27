package com.github.guoyyit.icebergorderbookjava.engine;

import com.github.guoyyit.icebergorderbookjava.api.CancelOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.DepthSnapshot;
import com.github.guoyyit.icebergorderbookjava.api.NewOrderRequest;
import com.github.guoyyit.icebergorderbookjava.api.OrderResponse;
import com.github.guoyyit.icebergorderbookjava.api.ReplaceOrderRequest;
import com.github.guoyyit.icebergorderbookjava.config.MatchingProperties;
import com.github.guoyyit.icebergorderbookjava.ledger.BalanceLedger;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point of the matching core. Each symbol has its own
 * {@link SymbolEngine} and a dedicated single-thread worker, so:
 * <ul>
 *   <li>different instruments are fully isolated (own book, own WAL file);</li>
 *   <li>a slow symbol (disk stall, long queue) never blocks other symbols;</li>
 *   <li>all requests and depth reads of one symbol are totally ordered and
 *       every read observes a self-consistent book.</li>
 * </ul>
 */
@Component
public class MatchingEngine {

    private static final class SymbolNode {
        final SymbolEngine engine;
        final ExecutorService worker;

        SymbolNode(SymbolEngine engine, ExecutorService worker) {
            this.engine = engine;
            this.worker = worker;
        }
    }

    private final MatchingProperties properties;
    private final BalanceLedger ledger;
    private final Map<String, SymbolNode> nodes = new ConcurrentHashMap<>();
    private final AtomicInteger workerCounter = new AtomicInteger();

    public MatchingEngine(MatchingProperties properties, BalanceLedger ledger) {
        this.properties = properties;
        this.ledger = ledger;
    }

    private SymbolNode node(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }
        SymbolNode existing = nodes.get(symbol);
        if (existing != null) {
            return existing;
        }
        return nodes.computeIfAbsent(symbol, s -> {
            SymbolEngine engine = new SymbolEngine(s, properties, ledger);
            try {
                engine.open();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open engine for " + s, e);
            }
            int idx = workerCounter.incrementAndGet();
            ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "matching-" + s + "-" + idx);
                t.setDaemon(true);
                return t;
            });
            return new SymbolNode(engine, worker);
        });
    }

    private static <T> T runOn(SymbolNode node, java.util.function.Supplier<T> task) {
        Future<T> f = node.worker.submit(task::get);
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for matching worker", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        }
    }

    public OrderResponse submit(NewOrderRequest req) {
        SymbolNode n = node(req.symbol());
        return runOn(n, () -> n.engine.newOrder(req));
    }

    public OrderResponse cancel(CancelOrderRequest req) {
        SymbolNode n = node(req.symbol());
        return runOn(n, () -> n.engine.cancel(req));
    }

    public OrderResponse replace(ReplaceOrderRequest req) {
        SymbolNode n = node(req.symbol());
        return runOn(n, () -> n.engine.replace(req));
    }

    public DepthSnapshot depth(String symbol) {
        SymbolNode n = node(symbol);
        return runOn(n, n.engine::snapshot);
    }

    /** Test/admin helper: synchronously close a symbol engine and its worker. */
    public void shutdownSymbol(String symbol) {
        SymbolNode n = nodes.remove(symbol);
        if (n != null) {
            n.worker.shutdown();
            try {
                n.engine.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        nodes.forEach((s, n) -> {
            n.worker.shutdown();
            try {
                n.engine.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        nodes.clear();
    }

    /** CompletableFuture variant useful for non-blocking tests. */
    CompletableFuture<OrderResponse> submitAsync(NewOrderRequest req) {
        SymbolNode n = node(req.symbol());
        return CompletableFuture.supplyAsync(() -> n.engine.newOrder(req), n.worker);
    }
}
