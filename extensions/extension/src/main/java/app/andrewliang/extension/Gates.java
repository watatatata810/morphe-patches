package app.andrewliang.extension;

/**
 * Value filters for patch sites that have no register to spare.
 *
 * <p>The usual runtime gate needs one free register to hold the setting and branch on it. A few of
 * LINE's methods have none — {@code e13.a.d()} is {@code .registers 1}, where the only register is
 * {@code this} and it is read before it is written — and at other sites every register is live
 * across the injection point. Passing the value LINE already computed through a one-argument static
 * call sidesteps that entirely: the result goes back into the register the value came from, so the
 * injection needs no register of its own and adds no branch.
 *
 * <p>Each method takes what the stock code produced and returns what it should be now, so a
 * disabled setting hands the original value straight back and the patched app behaves like a stock
 * one.
 */
public final class Gates {

    private Gates() {}

    /**
     * Filters LINE's market-availability flag for LYP Premium ({@code e13.a.d()}), the single gate
     * every premium surface cascades from.
     *
     * @param original what LINE's own config said.
     * @return false while the toggle is on, reproducing the app's shipped "LYP not available here"
     *         state; otherwise the original answer.
     */
    public static boolean premiumMarketAvailable(boolean original) {
        return !Settings.isEnabled(Settings.Key.DISABLE_PREMIUM) && original;
    }

    /**
     * Filters the premium-unsend eligibility flag passed to {@code ne1.k2.a}, which decides whether
     * the "How to unsend discreetly" promo link is created.
     *
     * @param original the eligibility LINE computed.
     * @return false while the toggle is on, so the link stays null; otherwise the original answer.
     */
    public static boolean premiumUnsendPromoEligible(boolean original) {
        return !Settings.isEnabled(Settings.Key.HIDE_PREMIUM_UNSEND) && original;
    }
}
