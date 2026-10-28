package app.andrewliang.patches.line.readreceipts

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

internal const val EXTENSION = "Lapp/andrewliang/extension/ReadReceipts;"

private const val CHAT_HISTORY_ACTIVITY =
    "Ljp/naver/line/android/activity/chathistory/ChatHistoryActivity;"
private const val CHAT_HISTORY_REQUEST =
    "Lcom/linecorp/line/chat/request/ChatHistoryRequest;"

/**
 * Register holding `this` — the first parameter — of a non-static method. Parameters occupy the tail
 * of the register file, so this is `registerCount` minus the number of parameter registers.
 */
internal fun MutableMethod.firstParameterRegister(): Int {
    val registerCount = implementation?.registerCount
        ?: throw PatchException("read checker: $definingClass->$name has no body")
    val parameterRegisters = 1 + parameterTypes.sumOf { type ->
        // Longs and doubles occupy a register pair; none of the sites here use them.
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    return registerCount - parameterRegisters
}

/**
 * The two hooks any read-receipt feature needs on the chat screen, shared so that neither
 * [groupReadReceiptsPatch] nor [incomingReadCountPatch] has to inject them itself — applying both
 * would otherwise call the same extension entry points twice per chat.
 *
 *  1. **Track the chat.** At the head of `ChatHistoryRequest.getChatId`, hand the request to
 *     `ReadReceipts.trackChat`, which reads its non-obfuscated `chatId` / `isGroup` fields. Only a
 *     fallback: `getChatId` also runs from `writeToParcel`, and LINE parcels a request for every
 *     message notification it posts, so this follows whichever chat last notified. The extension
 *     reads the chat a screen shows from that screen's own intent (`chatHistoryRequest`) first.
 *  2. **Walk the message list.** At the head of `ChatHistoryActivity.onCreate`, hand the activity to
 *     `ReadReceipts.decorate`, which re-runs on every layout pass — attaching the reader-sheet taps
 *     when that toggle is on, and hiding the added incoming counters where they are meaningless.
 *
 * Both classes are non-obfuscated, so they are resolved by name rather than fingerprinted. Nameless,
 * so it stays an internal dependency rather than a separate entry in the Manager list.
 */
val chatHistoryHooksPatch = bytecodePatch(
    description = "Chat-screen hooks shared by the read-receipt patches.",
) {
    extendWith("extensions/extension.mpe")

    dependsOn(morpheSettingsPatch)

    execute {
        val requestClass = mutableClassDefBy(CHAT_HISTORY_REQUEST)
        val getChatId = requestClass.methods.single { method ->
            method.name == "getChatId" && method.parameterTypes.isEmpty()
        }
        val requestRegister = getChatId.firstParameterRegister()
        getChatId.addInstructions(
            0,
            "invoke-static/range { v$requestRegister .. v$requestRegister }, " +
                "$EXTENSION->trackChat(Ljava/lang/Object;)V",
        )

        val activityClass = mutableClassDefBy(CHAT_HISTORY_ACTIVITY)
        val onCreate = activityClass.methods.single { method ->
            method.name == "onCreate" &&
                method.parameterTypes.map { it.toString() } == listOf("Landroid/os/Bundle;")
        }
        val activityRegister = onCreate.firstParameterRegister()
        onCreate.addInstructions(
            0,
            "invoke-static/range { v$activityRegister .. v$activityRegister }, " +
                "$EXTENSION->decorate(Landroid/app/Activity;)V",
        )
    }
}
