package app.andrewliang.patches.line.removeads

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val removeBannerAdsPatch = bytecodePatch(
    name = "Remove banner ads",
    description = "Stops LINE from loading Smart Channel banner ads " +
        "(neutralizes the getBanners and getPrefetchableBanners responses). " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true, // applied by default in Morphe Manager; users can deselect it.
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Returning null is safe: the caller (repository fi5.a.a) wraps the whole path in a catch-all
    // and degrades to "no banner". While the setting is off the gate falls through and the
    // response is delivered untouched.
    execute {
        val returnNull = { gateRegister: Int ->
            """
                const/4 v$gateRegister, 0x0
                return-object v$gateRegister
            """
        }

        // getPrefetchableBanners: dedicated recv lambda -> return null immediately.
        GetPrefetchableBannersRecvFingerprint.method.addSettingGate(
            0,
            SettingKeys.REMOVE_BANNER_ADS,
            excludeParameterRegisters = false,
            whenEnabled = returnNull,
        )

        // getBanners: SHARED packed-switch (default branch = a live wallet op). Gate the
        // null-return inside the getBanners branch only, so the wallet default branch (which
        // returns earlier) is untouched. instructionMatches[0] is the string("getBanners")
        // filter match.
        //
        // Inject *at* that const-string rather than after it. Both positions sit inside the
        // getBanners branch and ahead of the paired o.a recv call, but only this one has a free
        // register: the lambda is `.locals 1` and from the instruction after the const-string
        // onwards every register is live through the recv call and the result handling.
        // The const-string's own destination is written before it is read, so the gate can use it.
        val bannerStringIndex = GetBannersRecvFingerprint.instructionMatches.first().index
        GetBannersRecvFingerprint.method.addSettingGate(
            bannerStringIndex,
            SettingKeys.REMOVE_BANNER_ADS,
            excludeParameterRegisters = false,
            whenEnabled = returnNull,
        )
    }
}
