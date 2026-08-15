package app.andrewliang.patches.line.hidelinetodaytab

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingSkip
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideLineTodayTabPatch = bytecodePatch(
    name = "Hide LINE TODAY tab",
    description = "Removes the LINE TODAY (News) tab from the main bottom navigation, " +
        "in both the news-tab and news-row layouts. Can be turned off again from the LINE " +
        "Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Skip the NEWS and NEWS_ROW `sget-object` + following `ArrayList.add` pairs in the tab-list
    // builder while the setting is on. instructionMatches[0] = NEWS (earlier), [1] = NEWS_ROW
    // (later); gate the higher index first, since injecting shifts every index after it.
    execute {
        val matches = LineTodayTabListFingerprint.instructionMatches
        val newsIndex = matches[0].index
        val newsRowIndex = matches[1].index
        LineTodayTabListFingerprint.method.apply {
            addSettingSkip(newsRowIndex, 2, SettingKeys.HIDE_LINE_TODAY_TAB)
            addSettingSkip(newsIndex, 2, SettingKeys.HIDE_LINE_TODAY_TAB)
        }
    }
}
