package app.andrewliang.patches.line.hideevents

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

@Suppress("unused")
val hideEventsButtonPatch = bytecodePatch(
    name = "Hide Events button",
    description = "Removes the \"Events\" row from a chat room's slide-out menu. (Events is a " +
        "separate feature from LINE Calendar — it opens a server-hosted page.) Can be turned " +
        "off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // The Events row is a shared d00.z item built in ChatHistoryMenuFragment, gated by the boolean
    // it loads from Lyz/s4;->l:Z right before the Events label; that value flows straight into the
    // row's isVisible ctor arg. Leave that `iget-boolean` (matched filter [0]) in place and zero
    // its destination register immediately after it while the setting is on — with the setting off
    // the register keeps LINE's own value and the row appears as usual. Only the Events row reads
    // it, so nothing else is affected either way.
    execute {
        val flagMatch = EventsMenuRowFingerprint.instructionMatches.first()
        val flagReg = (flagMatch.instruction as TwoRegisterInstruction).registerA
        EventsMenuRowFingerprint.method.addSettingGate(
            flagMatch.index + 1,
            SettingKeys.HIDE_EVENTS,
            registersToExclude = listOf(flagReg),
        ) { "const/16 v$flagReg, 0x0" }
    }
}
