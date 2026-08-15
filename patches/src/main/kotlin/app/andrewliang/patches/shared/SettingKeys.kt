package app.andrewliang.patches.shared

/**
 * Keys of the runtime toggles the patches read at runtime.
 *
 * Mirrors `Settings.Key` in `extensions/extension` — the injected smali passes these strings to
 * `Settings.isEnabled`, so a key that exists on only one side compiles cleanly and shows up as a
 * switch that does nothing. Keep the two lists in step.
 */
object SettingKeys {
    const val HIDE_AD_VIEWS = "hide_ad_views"
    const val REMOVE_BANNER_ADS = "remove_banner_ads"
    const val HIDE_HOME_MODULES = "hide_home_modules"

    const val HIDE_WALLET_TAB = "hide_wallet_tab"
    const val HIDE_VOOM_TAB = "hide_voom_tab"
    const val HIDE_LINE_TODAY_TAB = "hide_line_today_tab"
    const val HIDE_COMMUNITY_BUTTON = "hide_community_button"

    const val HIDE_GIFT = "hide_gift"
    const val HIDE_TRANSFER = "hide_transfer"
    const val HIDE_ATTACH_MENU_TOOLS = "hide_attach_menu_tools"
    const val HIDE_CALENDAR = "hide_calendar"
    const val HIDE_EVENTS = "hide_events"

    const val DISABLE_PREMIUM = "disable_premium"
    const val HIDE_PREMIUM_UNSEND = "hide_premium_unsend"

    const val DISABLE_VOOM = "disable_voom"
    const val REDIRECT_LINE_PAY = "redirect_line_pay"
    const val EXTERNAL_BROWSER = "external_browser"
    const val KEEP_CHATS_UNREAD = "keep_chats_unread"
    const val KEEP_UNSENT_MESSAGES = "keep_unsent_messages"
}
