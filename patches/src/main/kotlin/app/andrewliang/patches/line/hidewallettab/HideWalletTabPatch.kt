package app.andrewliang.patches.line.hidewallettab

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingSkip
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideWalletTabPatch = bytecodePatch(
    name = "Hide Wallet tab",
    description = "Removes the Wallet (LINE Pay) tab from the main bottom navigation, " +
        "in both the normal and mini-tab layouts. Can be turned off again from the LINE " +
        "Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // The tab-list builder appends MINI then WALLET, each as `sget-object <const>` followed by
    // `ArrayList.add`. Skip both pairs while the setting is on rather than deleting them, so the
    // tabs come back when it is off. instructionMatches[0] = MINI (earlier), [1] = WALLET (later);
    // gate the higher index first, since injecting shifts every index after it.
    execute {
        val matches = WalletTabListFingerprint.instructionMatches
        val miniIndex = matches[0].index
        val walletIndex = matches[1].index
        WalletTabListFingerprint.method.apply {
            addSettingSkip(walletIndex, 2, SettingKeys.HIDE_WALLET_TAB)
            addSettingSkip(miniIndex, 2, SettingKeys.HIDE_WALLET_TAB)
        }
    }
}
