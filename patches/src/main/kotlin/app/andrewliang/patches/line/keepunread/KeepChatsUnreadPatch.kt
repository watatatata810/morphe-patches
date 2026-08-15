package app.andrewliang.patches.line.keepunread

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val keepChatsUnreadPatch = bytecodePatch(
    name = "Keep chats unread",
    description = "Opening a 1:1 or group chat no longer marks it read — it stays unread and " +
        "no read receipt is sent. Manually using \"Mark as read\" / \"Mark all as read\" still " +
        "marks the chat read and sends the receipt as normal. Can be turned off again from the " +
        "LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // ReadWorkerFingerprint resolves q33.e.d — the only caller of TalkServiceClient.j1 — which
    // identifies the read-manager class. Both open-on-view and manual mark-as-read funnel
    // through that worker (local read + server receipt), but only the OPEN path reaches it via
    // the sibling wrapper b(String, String) (its sole caller is the auto-read-on-view
    // subscriber). Skip that wrapper while the setting is on, so opening a chat marks nothing
    // read — locally or on the server — while the manual wrappers (e/c) are left intact. With
    // the setting off the wrapper runs in full and chats read as they always did. b is the
    // unique (String,String) -> void method in the class.
    execute {
        val readManager = mutableClassDefBy(ReadWorkerFingerprint.method.definingClass)
        val openReadWrapper = readManager.methods.first { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } ==
                listOf("Ljava/lang/String;", "Ljava/lang/String;")
        }
        openReadWrapper.addSettingGate(
            0,
            SettingKeys.KEEP_CHATS_UNREAD,
            excludeParameterRegisters = false,
        ) { "return-void" }
    }
}
