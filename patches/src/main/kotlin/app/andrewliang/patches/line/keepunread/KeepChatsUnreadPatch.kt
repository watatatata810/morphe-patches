package app.andrewliang.patches.line.keepunread

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.util.findFreeRegister
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION = "Lapp/andrewliang/extension/UnreadOverride;"
private const val CHAT_HISTORY_ACTIVITY =
    "Ljp/naver/line/android/activity/chathistory/ChatHistoryActivity;"

/** Register holding `this` of a non-static method: parameters occupy the tail of the register file. */
private fun MutableMethod.thisRegister(): Int {
    val registerCount = implementation?.registerCount
        ?: throw PatchException("keepunread: $definingClass->$name has no body")
    val parameterRegisters = (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
        parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2L else 1L }.toInt()
    return registerCount - parameterRegisters
}

@Suppress("unused")
val keepChatsUnreadPatch = bytecodePatch(
    name = "Keep chats unread",
    description = "Opening a 1:1 or group chat no longer marks it read — it stays unread and " +
        "no read receipt is sent. Manually using \"Mark as read\" / \"Mark all as read\" still " +
        "marks the chat read and sends the receipt as normal. A chip on the chat screen lets " +
        "one chat read as normal for a while; it reverts when the chat is closed or the app " +
        "goes to the background. Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    extendWith("extensions/extension.mpe")

    dependsOn(morpheSettingsPatch)

    // ReadWorkerFingerprint resolves q33.e.d — the only caller of TalkServiceClient.j1 — which
    // identifies the read-manager class. Both open-on-view and manual mark-as-read funnel
    // through that worker (local read + server receipt), but only the OPEN path reaches it via
    // the sibling wrapper b(String chatId, String messageId) (its sole caller is the
    // auto-read-on-view subscriber). Ask the extension whether to skip that wrapper: it answers
    // from the global toggle unless the chat has a temporary override from the chip, and while
    // skipping it remembers the wrapper instance + message id so that flipping the chip can
    // replay the read through LINE's own code. With everything off the wrapper runs in full and
    // chats read as they always did. b is the unique (String,String) -> void method in the class.
    execute {
        val readManager = mutableClassDefBy(ReadWorkerFingerprint.method.definingClass)
        val openReadWrapper = readManager.methods.single { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } ==
                listOf("Ljava/lang/String;", "Ljava/lang/String;")
        }

        // The injected block names p0..p2, so the result register must not be one of them.
        val thisRegister = openReadWrapper.thisRegister()
        val resultRegister = try {
            openReadWrapper.findFreeRegister(0, listOf(thisRegister, thisRegister + 1, thisRegister + 2))
        } catch (exception: IllegalArgumentException) {
            throw PatchException("keepunread: no free register at the head of the read wrapper", exception)
        }
        val readLabel = "morphe_read"
        openReadWrapper.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p2 }, $EXTENSION->shouldSkip(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z
                move-result v$resultRegister
                if-eqz v$resultRegister, :$readLabel
                return-void
            """,
            ExternalLabel(readLabel, openReadWrapper.getInstruction(0)),
        )

        // The chip on the chat screen, and the reset when the chat is closed. Both methods are on
        // the non-obfuscated ChatHistoryActivity, so they are resolved by name.
        val activityClass = mutableClassDefBy(CHAT_HISTORY_ACTIVITY)

        val onCreate = activityClass.methods.single { method ->
            method.name == "onCreate" &&
                method.parameterTypes.map { it.toString() } == listOf("Landroid/os/Bundle;")
        }
        val createThis = onCreate.thisRegister()
        onCreate.addInstructions(
            0,
            "invoke-static/range { v$createThis .. v$createThis }, $EXTENSION->attach(Landroid/app/Activity;)V",
        )

        val onDestroy = activityClass.methods.single { method ->
            method.name == "onDestroy" && method.parameterTypes.isEmpty()
        }
        val destroyThis = onDestroy.thisRegister()
        onDestroy.addInstructions(
            0,
            "invoke-static/range { v$destroyThis .. v$destroyThis }, $EXTENSION->onChatDestroyed(Landroid/app/Activity;)V",
        )
    }
}
