package app.andrewliang.patches.line.hideattachmenutools

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideAttachMenuExtraToolsPatch = bytecodePatch(
    name = "Hide attach menu extra tools",
    description = "Removes all the server-provided extra tools from a chat room's + attach menu " +
        "(Poll, Reservation, Schedule, Ladder shuffle, and any others). The built-in tiles " +
        "(camera, gallery, files, contact, etc.) are unaffected. Can be turned off again from " +
        "the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Every server-driven attach service is rendered by the shared class hg1.d, shown only when its
    // per-item gate f(...) returns true. hg1.d is the sole renderer of these services, so forcing
    // its f() to return false drops the entire set — with no dependency on any server channel id
    // (the reason a single-service patch can't be stable). With the setting off the gate falls
    // through and LINE decides as usual.
    execute {
        // `f` is `.locals 0` (three parameters plus `this`); the gate takes the parameter LINE
        // overwrites with a `const/4` before reading it. The injected block names no parameter.
        AttachMenuServiceGateFingerprint.method.addSettingGate(
            0,
            SettingKeys.HIDE_ATTACH_MENU_TOOLS,
            excludeParameterRegisters = false,
        ) { gateRegister ->
            """
                const/4 v$gateRegister, 0x0
                return v$gateRegister
            """
        }
    }
}
