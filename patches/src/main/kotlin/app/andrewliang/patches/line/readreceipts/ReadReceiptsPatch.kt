package app.andrewliang.patches.line.readreceipts

import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"

/**
 * "Group read checker" — see who is behind the 「既読 N」 count on a message in a group.
 *
 * One injection of its own, a branchless side-effect call the extension gates on the runtime toggle:
 * at the head of the `NOTIFIED_READ_MESSAGE` handler's processor (`ReadOpHandlerFingerprint` -> the
 * handler class -> its `(_, Operation, Continuation)` method), the `Operation` is passed to
 * `ReadReceipts.onReadOp` (`param2` = reader mid, `param3` = message id).
 *
 * That capture is a *supplement*. LINE's own `read_message_range` table is already per-member and is
 * the extension's primary source; the capture covers what that table has not synced. The chat-screen
 * hooks live in [chatHistoryHooksPatch].
 *
 * Off by default (`Settings.Key` defaults it off): the injection ships dormant and records nothing
 * until the toggle is turned on.
 *
 * Stock LINE draws no 「既読 N」 view on incoming rows, so there is nothing to tap on them until
 * [incomingReadCountPatch] adds one.
 */
@Suppress("unused")
val groupReadReceiptsPatch = bytecodePatch(
    name = "Group read checker",
    description = "Makes the 「既読 N」 count in a group chat tappable, showing who is behind it. " +
        "Readers come from LINE's own per-member read table, which its server backfills, plus a " +
        "record this patch keeps on your device from read notifications as they arrive. Off by " +
        "default; turn it on from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    extendWith("extensions/extension.mpe")

    dependsOn(chatHistoryHooksPatch)

    execute {
        // The handler class is the one whose constructor registers the NOTIFIED_READ_MESSAGE op
        // type; its only non-constructor method is the suspend processor b(q0, Operation,
        // Continuation), whose 2nd parameter (this + q0 + op) is the Operation.
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
    }
}
