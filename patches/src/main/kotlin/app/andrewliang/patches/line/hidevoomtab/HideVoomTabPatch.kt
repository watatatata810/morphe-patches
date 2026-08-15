package app.andrewliang.patches.line.hidevoomtab

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingSkip
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideVoomTabPatch = bytecodePatch(
    name = "Hide VOOM tab",
    description = "Removes the VOOM (formerly Timeline) tab from the main bottom navigation. " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Skip the TIMELINE `sget-object` + following `ArrayList.add` pair in the tab-list builder
    // while the setting is on. TIMELINE is added exactly once (behind a feature-flag guard, which
    // harmlessly remains). instructionMatches[0] = the TIMELINE sget-object.
    execute {
        val timelineIndex = VoomTabListFingerprint.instructionMatches.first().index
        VoomTabListFingerprint.method.addSettingSkip(timelineIndex, 2, SettingKeys.HIDE_VOOM_TAB)
    }
}
