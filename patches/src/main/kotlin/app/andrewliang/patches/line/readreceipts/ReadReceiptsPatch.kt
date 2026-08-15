package app.andrewliang.patches.line.readreceipts

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

private const val EXTENSION = "Lapp/andrewliang/extension/ReadReceipts;"

private const val CHAT_HISTORY_ACTIVITY =
    "Ljp/naver/line/android/activity/chathistory/ChatHistoryActivity;"
private const val CHAT_HISTORY_REQUEST =
    "Lcom/linecorp/line/chat/request/ChatHistoryRequest;"
private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"

/**
 * Register holding `this` — the first parameter — of a non-static method. Parameters occupy the tail
 * of the register file, so this is `registerCount` minus the number of parameter registers.
 */
private fun MutableMethod.firstParameterRegister(): Int {
    val registerCount = implementation?.registerCount
        ?: throw PatchException("read checker: $definingClass->$name has no body")
    val parameterRegisters = 1 + parameterTypes.sumOf { type ->
        // Longs and doubles occupy a register pair; none of the sites here use them.
        if (type.toString() == "J" || type.toString() == "D") 2 else 1
    }
    return registerCount - parameterRegisters
}

/**
 * "Group read checker" — record who reads each message in a group and view it from a button inside
 * the chat.
 *
 * Three injections, all branchless side-effect calls the extension gates on the runtime toggle:
 *
 *  1. **Capture.** At the head of the `NOTIFIED_READ_MESSAGE` handler's processor
 *     (`ReadOpHandlerFingerprint` -> the handler class -> its `(_, Operation, Continuation)` method),
 *     pass the `Operation` to `ReadReceipts.onReadOp`. That is the only place a reader's identity
 *     reaches the client (`param2` = reader mid, `param3` = message id); LINE keeps only a count.
 *  2. **Track the chat.** At the head of `ChatHistoryRequest.getChatId`, hand the request to
 *     `ReadReceipts.trackChat`, which reads its non-obfuscated `chatId` / `isGroup` fields so the
 *     button knows which chat to report on and whether to appear.
 *  3. **Show the button.** At the head of `ChatHistoryActivity.onCreate`, hand the activity to
 *     `ReadReceipts.decorate`, which posts a button onto the chat screen once it is laid out.
 *
 * The two activity/request classes are non-obfuscated, so they are resolved by name rather than
 * fingerprinted; only the obfuscated op handler needs one. Off by default (`Settings.Key` defaults
 * it off): the injections ship dormant and record nothing until the toggle is turned on.
 */
@Suppress("unused")
val groupReadReceiptsPatch = bytecodePatch(
    name = "Group read checker",
    description = "Records who reads each message in a group chat and lets you view it from a " +
        "button inside the chat. LINE only keeps a read count, so this works going forward only " +
        "— it cannot show reads from before the patch was installed — and it stores the read data " +
        "on your device. Off by default; turn it on from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    extendWith("extensions/extension.mpe")

    dependsOn(morpheSettingsPatch)

    execute {
        // 1. Capture each read op. The handler class is the one whose constructor registers the
        // NOTIFIED_READ_MESSAGE op type; its only non-constructor method is the suspend processor
        // b(q0, Operation, Continuation), whose 2nd parameter (this + q0 + op) is the Operation.
        val handlerClass = mutableClassDefBy(ReadOpHandlerFingerprint.method.definingClass)
        val processMethod = handlerClass.methods.single { method ->
            method.name != "<init>" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes.last().toString() == CONTINUATION
        }
        // this(+0), q0(+1), operation(+2). invoke-static/range keeps this valid above v15.
        val operationRegister = processMethod.firstParameterRegister() + 2
        processMethod.addInstructions(
            0,
            "invoke-static/range { v$operationRegister .. v$operationRegister }, " +
                "$EXTENSION->onReadOp(Ljava/lang/Object;)V",
        )

        // 2. Track the current chat off ChatHistoryRequest.getChatId (this is the request itself).
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

        // 3. Add the in-chat button from ChatHistoryActivity.onCreate (this is the activity).
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
