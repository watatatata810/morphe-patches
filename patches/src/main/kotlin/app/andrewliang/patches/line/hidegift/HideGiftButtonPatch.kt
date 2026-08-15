package app.andrewliang.patches.line.hidegift

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideGiftButtonPatch = bytecodePatch(
    name = "Hide LINE GIFT button",
    description = "Removes the LINE GIFT tile from a chat room's + attach menu. " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // The LINE GIFT tile (hg1.h) is shown only if its availability predicate `j(gi1.b)Z` returns
    // true (the attach-menu filter hg1.r.f gates on it). Force that predicate false while the
    // setting is on; the tile is then dropped by the existing filter in gg1.e. With the setting
    // off the gate falls through to LINE's own answer. Anchor via the constructor (the sole reader
    // of the fg1.a$b.GIFT enum constant), then select `j` by its unique descriptor.
    execute {
        val giftClass = mutableClassDefBy(GiftAttachButtonFingerprint.method.definingClass)
        val availabilityMethod = giftClass.methods.first { method ->
            method.returnType == "Z" &&
                method.parameterTypes.map { it.toString() } == listOf("Lgi1/b;")
        }
        // `j` is `.locals 0` — every register is a parameter, and the only one the gate can take is
        // the `this` register LINE overwrites with an iget before ever reading it. The injected
        // block names no parameter, so letting the search consider them is safe here.
        availabilityMethod.addSettingGate(
            0,
            SettingKeys.HIDE_GIFT,
            excludeParameterRegisters = false,
        ) { gateRegister ->
            """
                const/4 v$gateRegister, 0x0
                return v$gateRegister
            """
        }
    }
}
