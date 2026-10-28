package app.andrewliang.extension;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Runtime on/off state for the patches, backed by a private {@link SharedPreferences} file inside
 * LINE's own data directory.
 *
 * <p>Every patch that reads this used to bake its decision into the bytecode ("always hide"); the
 * injected smali now asks {@link #isEnabled(String)} instead, so a setting can be flipped from
 * {@link SettingsActivity} without re-patching the APK.
 *
 * <p><b>Fail-safe:</b> if the preferences were never initialized (see {@link SettingsInitProvider})
 * every key reports its default, and every default is {@code true}. A patch that cannot reach its
 * setting therefore behaves exactly like the old always-on bytecode rather than silently
 * un-patching the app.
 *
 * <p><b>Keys are duplicated on the patch side</b> in
 * {@code app.andrewliang.patches.shared.SettingKeys}. The two lists must stay in sync — a typo
 * shows up as a toggle that does nothing, not as a build failure.
 */
public final class Settings {

    private Settings() {}

    private static final String TAG = "AndrewSettings";

    /** Preferences file created inside LINE's private data dir. */
    static final String PREFERENCES_NAME = "morphe_settings";

    /**
     * Every user-facing toggle, in the order the settings screen lists them.
     *
     * <p>{@link #key} is what the injected smali passes to {@link #isEnabled(String)},
     * {@link #title} matches the patch name shown in Morphe Manager so the two can be mapped onto
     * each other, and {@link #summary} is the patch description condensed to one line.
     */
    public enum Key {
        HIDE_AD_VIEWS(
                "Ads", "hide_ad_views",
                "Hide ad views",
                "LINE Ads SDK containers, the chat-list Smart Channel banner, and AdManager ads."),
        REMOVE_BANNER_ADS(
                "Ads", "remove_banner_ads",
                "Remove banner ads",
                "Stop fetching Smart Channel banner ads from the server."),
        HIDE_HOME_MODULES(
                "Ads", "hide_home_modules",
                "Hide Home modules",
                "Recommended stickers/content, real-time hot topics, and Home feed ads."),

        HIDE_WALLET_TAB(
                "Navigation", "hide_wallet_tab",
                "Hide Wallet tab",
                "Remove the Wallet (LINE Pay) tab, in both the normal and mini-tab layouts."),
        HIDE_VOOM_TAB(
                "Navigation", "hide_voom_tab",
                "Hide VOOM tab",
                "Remove the VOOM (formerly Timeline) tab from the bottom navigation."),
        HIDE_LINE_TODAY_TAB(
                "Navigation", "hide_line_today_tab",
                "Hide LINE TODAY tab",
                "Remove the LINE TODAY (News) tab, in both the news-tab and news-row layouts."),
        HIDE_COMMUNITY_BUTTON(
                "Navigation", "hide_community_button",
                "Hide community button",
                "Remove the community (OpenChat) button from the Chats tab header."),
        HIDE_AGENT_I(
                "Navigation", "hide_agent_i",
                "Hide Agent i buttons",
                "Remove the Agent i button from the Home header and from the search bar."),

        HIDE_GIFT(
                "Chat surfaces", "hide_gift",
                "Hide LINE GIFT button",
                "Remove the LINE GIFT tile from a chat room's + attach menu."),
        HIDE_TRANSFER(
                "Chat surfaces", "hide_transfer",
                "Hide Transfer button",
                "Remove the Transfer (LINE Pay) tile from a chat room's + attach menu."),
        HIDE_ATTACH_MENU_TOOLS(
                "Chat surfaces", "hide_attach_menu_tools",
                "Hide attach menu extra tools",
                "Remove the server-provided extra tools (Poll, Reservation, Schedule, ...)."),
        HIDE_CALENDAR(
                "Chat surfaces", "hide_calendar",
                "Hide calendar buttons",
                "Remove every LINE Calendar button: Chats header, toolbar, + menu, chat menu, "
                        + "and the message long-press menu."),
        HIDE_EVENTS(
                "Chat surfaces", "hide_events",
                "Hide Events button",
                "Remove the Events row from a chat room's slide-out menu."),

        DISABLE_PREMIUM(
                "Premium", "disable_premium",
                "Disable LINE Premium",
                "Hide all LYP Premium surfaces. Doesn't unlock anything (premium is "
                        + "server-enforced)."),
        HIDE_PREMIUM_UNSEND(
                "Premium", "hide_premium_unsend",
                "Hide premium unsend upsells",
                "Remove the discreet-unsend button, the promo link, and the expired-window "
                        + "upsell. Ordinary unsend still works."),

        DISABLE_VOOM(
                "Behaviour", "disable_voom",
                "Disable VOOM",
                "VOOM deep links, shares and notifications do nothing; the feed closes on open."),
        REDIRECT_LINE_PAY(
                "Behaviour", "redirect_line_pay",
                "Redirect LINE Pay",
                "Hand Pay flows to the standalone LINE Pay app instead of running them in-app."),
        EXTERNAL_BROWSER(
                "Behaviour", "external_browser",
                "Open links in external browser",
                "Open tapped http/https links in your default browser, not LINE's in-app browser."),
        KEEP_CHATS_UNREAD(
                "Behaviour", "keep_chats_unread",
                "Keep chats unread",
                "Opening a chat no longer marks it read or sends a read receipt."),
        KEEP_UNSENT_MESSAGES(
                "Behaviour", "keep_unsent_messages",
                "Keep unsent messages",
                "Keep messages that were unsent, with the usual notice below them."),

        GROUP_READ_CHECKER(
                "Read receipts", "group_read_checker",
                "Group read checker",
                "Tap the 「既読 N」 count on a message in a group to see who is behind it. Off "
                        + "by default; read data is stored on your device.",
                false);

        /** Section this setting is listed under. Entries of a category must stay adjacent. */
        public final String category;
        public final String key;
        public final String title;
        public final String summary;

        /**
         * The value {@link #isEnabled(Key)} reports when the store has no entry for this key.
         *
         * <p>Almost every toggle defaults on (see {@link #DEFAULT_VALUE}) so that a patched-in
         * feature keeps behaving like the unconditional bytecode it replaced. A feature that is
         * <em>additive</em> and privacy-sensitive rather than a replacement — the group read
         * checker records who reads your messages — instead defaults off, so installing the bundle
         * never starts collecting anything until the user asks for it.
         */
        public final boolean defaultValue;

        Key(String category, String key, String title, String summary) {
            this(category, key, title, summary, DEFAULT_VALUE);
        }

        Key(String category, String key, String title, String summary, boolean defaultValue) {
            this.category = category;
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.defaultValue = defaultValue;
        }
    }

    /**
     * Default for every key. Patching a feature in and having it start off would surprise anyone
     * upgrading from a build where the behavior was unconditional, so everything starts enabled.
     */
    static final boolean DEFAULT_VALUE = true;

    private static volatile SharedPreferences preferences;

    /** Called by {@link SettingsInitProvider} before LINE's Application starts. */
    static void initialize(Context context) {
        if (context == null) {
            Log.w(TAG, "No context; settings fall back to defaults.");
            return;
        }
        try {
            preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.w(TAG, "Could not open settings; falling back to defaults.", t);
        }
    }

    /**
     * Entry point for injected smali.
     *
     * @param key one of {@link Key#key}.
     * @return whether the patch behavior for {@code key} should be applied right now. Unknown keys
     *         and an uninitialized store both report {@link #DEFAULT_VALUE}.
     */
    public static boolean isEnabled(String key) {
        SharedPreferences prefs = preferences;
        if (prefs == null || key == null) {
            return DEFAULT_VALUE;
        }
        try {
            return prefs.getBoolean(key, DEFAULT_VALUE);
        } catch (Throwable t) {
            // A value of the wrong type would throw; treat it as unset.
            return DEFAULT_VALUE;
        }
    }

    /**
     * @return whether the toggle for {@code key} is on. Unlike {@link #isEnabled(String)} this
     *         honours the key's own {@link Key#defaultValue}, so a key that defaults off reports
     *         off until the user turns it on. This overload is what {@link SettingsActivity} reads
     *         for a switch's initial state and what the extension calls for an off-by-default
     *         feature; injected smali uses the string overload and its global default.
     */
    public static boolean isEnabled(Key key) {
        if (key == null) {
            return DEFAULT_VALUE;
        }
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            return key.defaultValue;
        }
        try {
            return prefs.getBoolean(key.key, key.defaultValue);
        } catch (Throwable t) {
            return key.defaultValue;
        }
    }

    /**
     * Persists {@code value} for {@code key}. No-op if the store never initialized.
     *
     * <p>Committed synchronously rather than applied: {@code apply()} writes on a background
     * thread, and a toggle followed by swiping LINE out of Recents kills the process before that
     * write lands, silently reverting the setting (device-confirmed). Settings are written once per
     * user tap, so the blocking write costs nothing here.
     */
    public static void setEnabled(String key, boolean value) {
        SharedPreferences prefs = preferences;
        if (prefs == null || key == null) {
            Log.w(TAG, "Cannot save " + key + "; settings were never initialized.");
            return;
        }
        prefs.edit().putBoolean(key, value).commit();
    }
}
