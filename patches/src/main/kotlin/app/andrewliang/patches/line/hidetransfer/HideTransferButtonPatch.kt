package app.andrewliang.patches.line.hidetransfer

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideTransferButtonPatch = bytecodePatch(
    name = "Hide Transfer button",
    description = "Removes the Transfer (LINE Pay) tile from a chat room's + attach menu. " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // The Transfer tile (hg1.k) is shown only if its availability predicate `j(gi1.b)Z` returns
    // true (the attach-menu filter hg1.r.f gates on it). Force that predicate false while the
    // setting is on; the tile is then dropped by the existing filter in gg1.e. With the setting
    // off the gate falls through to LINE's own answer. Anchor via the constructor (the sole reader
    // of the fg1.a$b.PAY enum constant), then select `j` by its unique descriptor.
    execute {
        val transferClass =
            mutableClassDefBy(TransferAttachButtonFingerprint.method.definingClass)
        val availabilityMethod = transferClass.methods.first { method ->
            method.returnType == "Z" &&
                method.parameterTypes.map { it.toString() } == listOf("Lgi1/b;")
        }
        // `j` is `.locals 0`; see the note in the LINE GIFT patch for why the gate may take a
        // parameter register here.
        availabilityMethod.addSettingGate(
            0,
            SettingKeys.HIDE_TRANSFER,
            excludeParameterRegisters = false,
        ) { gateRegister ->
            """
                const/4 v$gateRegister, 0x0
                return v$gateRegister
            """
        }
    }
}
