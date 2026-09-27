package com.github.guoyyit.icebergorderbookjava.engine;

/** Test-only accessor for owner-side order state (including hidden qty). */
public final class Views {

    private Views() {
    }

    public static long remaining(SymbolEngine e, String orderId) {
        return SymbolEngine.toView(get(e, orderId)).remainingQty();
    }

    public static long total(SymbolEngine e, String orderId) {
        return SymbolEngine.toView(get(e, orderId)).totalQty();
    }

    public static long visible(SymbolEngine e, String orderId) {
        return SymbolEngine.toView(get(e, orderId)).visibleQty();
    }

    public static long hidden(SymbolEngine e, String orderId) {
        return SymbolEngine.toView(get(e, orderId)).hiddenQty();
    }

    public static long filled(SymbolEngine e, String orderId) {
        return SymbolEngine.toView(get(e, orderId)).filledQty();
    }

    public static long revision(SymbolEngine e, String orderId) {
        return SymbolEngine.toView(get(e, orderId)).revision();
    }

    private static com.github.guoyyit.icebergorderbookjava.model.Order get(
            SymbolEngine e, String orderId) {
        return e.bookView().getOrder(orderId);
    }
}
