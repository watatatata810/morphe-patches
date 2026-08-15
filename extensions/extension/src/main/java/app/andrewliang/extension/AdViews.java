package app.andrewliang.extension;

import android.view.View;
import android.view.ViewGroup;

/**
 * Helper for the "Hide ad views" LINE patch.
 *
 * <p>The patch used to inject the hiding smali directly — including a branch and an in-block label
 * for the null layout-params case, and a {@code .locals 0} special case that had to borrow a dead
 * parameter register. Moving the work here reduces every injection site to a single branchless
 * {@code invoke-static} on a register the site already holds, which is what makes the setting
 * runtime-toggleable without adding branches to methods whose register layout varies.
 *
 * <p>All calls are no-ops when {@link Settings.Key#HIDE_AD_VIEWS} is off, so the ad view keeps
 * whatever visibility LINE gave it.
 */
public final class AdViews {

    private AdViews() {}

    /**
     * Hides the container an ad controller is about to populate.
     *
     * @param host the {@link View} the controller fills; may be null.
     */
    public static void hideHost(View host) {
        if (host == null || !Settings.isEnabled(Settings.Key.HIDE_AD_VIEWS)) return;
        host.setVisibility(View.GONE);
    }

    /**
     * Collapses an ad view itself: zeroes its height so it leaves no gap, then hides it. These ad
     * classes never call {@code setVisibility} on themselves, so this sticks and no ancestor walk
     * is needed.
     *
     * @param adView the ad {@link View}; may be null.
     */
    public static void hideSelf(View adView) {
        if (adView == null || !Settings.isEnabled(Settings.Key.HIDE_AD_VIEWS)) return;

        ViewGroup.LayoutParams params = adView.getLayoutParams();
        if (params != null) {
            params.height = 0;
            adView.setLayoutParams(params);
        }
        adView.setVisibility(View.GONE);
    }
}
