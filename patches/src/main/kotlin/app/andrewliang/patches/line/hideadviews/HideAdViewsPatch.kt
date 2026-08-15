package app.andrewliang.patches.line.hideadviews

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION = "Lapp/andrewliang/extension/AdViews;"

// Hide the Smart Channel banner host (p1 = the FrameLayout the controller populates).
private const val HIDE_HOST = """
    invoke-static/range {p1 .. p1}, $EXTENSION->hideHost(Landroid/view/View;)V
"""

// Collapse the receiver ad view itself. The extension zeroes the height and hides the view, and
// tolerates the null layout params an ad view still has while it is being constructed.
private const val HIDE_SELF = """
    invoke-static/range {p0 .. p0}, $EXTENSION->hideSelf(Landroid/view/View;)V
"""

@Suppress("unused")
val hideAdViewsPatch = bytecodePatch(
    name = "Hide ad views",
    description = "Hides LINE display ad views — the LINE Ads SDK containers across the app, " +
        "the chat-list Smart Channel banner, and Google AdManager ads. " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Each site is one branchless invoke-static on a register it already holds, with the setting
    // check and the hiding itself living in the extension. That is what lets the same injection
    // serve both states, and it also removes the two register hazards the inline version carried:
    // an in-block label for the null-layout-params case, and a `.locals 0` wrapper that had to
    // borrow a dead parameter register because it had no free one. The /range form keeps the
    // register operand 16-bit, so a method whose p0 sits above v15 assembles correctly too.
    execute {
        // Chat-list Smart Channel banner: hide its host container at controller construction.
        SmartChannelControllerFingerprint.method.addInstructions(0, HIDE_HOST)

        // LINE Ads SDK display ads (Home/Wallet/etc.): self-collapse at onAttachedToWindow.
        LadAdViewFingerprint.method.addInstructions(0, HIDE_SELF)
        LyadAdViewFingerprint.method.addInstructions(0, HIDE_SELF)

        // Google AdManager wrappers — BEST-EFFORT. Obfuscated names drift across versions,
        // so skip silently if not found; can never break the robust hiding above. Inject
        // on self right AFTER the super <init> call.
        val adManagerWrappers = listOf(
            AdManagerBannerChatroomFingerprint,
            AdManagerNativeChatroomFingerprint,
            AdManagerBannerGeneralFingerprint,
            AdManagerNativeGeneralFingerprint,
            AdManagerBannerMinorRegionFingerprint,
            AdManagerNativeMinorRegionFingerprint,
        )
        adManagerWrappers.forEach { fingerprint ->
            val method = fingerprint.methodOrNull ?: return@forEach
            val afterSuperIndex = (
                fingerprint.instructionMatchesOrNull?.firstOrNull()?.index
                    ?: return@forEach
                ) + 1
            method.addInstructions(afterSuperIndex, HIDE_SELF)
        }
    }
}
