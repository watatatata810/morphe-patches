package app.andrewliang.patches.line.externalbrowser

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val forceExternalBrowserPatch = bytecodePatch(
    name = "Open links in external browser",
    description = "Opens tapped web links (http/https) in your default browser instead of " +
        "LINE's in-app browser. LIFF mini-apps and LINE deep links are unaffected. " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Overwrite the OpenUriActivity$a mode parameter (p3) with EXTERNAL_WITHOUT_CUSTOMTABS at
    // method entry, so the web-URL branch routes into LINE's own native external-browser path.
    // p3 is only read afterward by a null-check (our constant is non-null) and the mode
    // switch; non-web URLs skip the switch, so they're unaffected. While the setting is off p3
    // keeps the mode the caller passed and links open in the in-app browser as before.
    execute {
        OpenUriIntentBuilderFingerprint.method.addSettingGate(
            0,
            SettingKeys.EXTERNAL_BROWSER,
        ) {
            "sget-object p3, Lcom/linecorp/browser/OpenUriActivity\$a;->" +
                "EXTERNAL_WITHOUT_CUSTOMTABS:Lcom/linecorp/browser/OpenUriActivity\$a;"
        }
    }
}
