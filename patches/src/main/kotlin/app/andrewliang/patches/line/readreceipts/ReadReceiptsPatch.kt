package app.andrewliang.patches.line.readreceipts

import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"

/**
 * "Group read checker" — see who is behind the 「既読 N」 count on a message in a group.
 *
 * Branchless side-effect calls the extension gates on the runtime toggle:
 *
 *  1. At the head of the `NOTIFIED_READ_MESSAGE` handler's processor (`ReadOpHandlerFingerprint` ->
 *     the handler class -> its `(_, Operation, Continuation)` method), the `Operation` is passed to
 *     `ReadReceipts.onReadOp` (`param2` = reader mid, `param3` = message id). That capture is a
 *     *supplement*: LINE's own `read_message_range` table is already per-member and is the
 *     extension's primary source; the capture covers what that table has not synced.
 *  2. In the chat adapter's `onBindViewHolder` ([ChatRowBindFingerprint]), the ViewHolder at the
 *     head (`onRowBind`) and, just before LINE's per-row read-count call, that call's message id
 *     (`onRowMessage`). Together they map each row's view to the exact message it shows, so a tap
 *     does not have to match the row's text and time back to `chat_history` — which cannot tell
 *     apart the same short text sent at the same minute on different days or by different members.
 *
 * The chat-screen hooks live in [chatHistoryHooksPatch].
 *
 * Off by default (`Settings.Key` defaults it off): the injections ship dormant and record nothing
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

        // Tie each chat row's view to the exact message it shows, so a tap needs no guessing.
        // Two branchless calls in the adapter's onBindViewHolder: at its head, the ViewHolder being
        // bound (parameter 1, still intact at index 0); immediately before LINE's own read-count
        // call, the message id that call is given — its long operand pair, read off the
        // instruction, so no register is chosen by us. Binding runs on the main thread, one row at
        // a time, which is what lets the extension pair the two calls.
        ChatRowBindFingerprint.method.apply {
            val readCountIndex = readCountCallIndex()
            if (readCountIndex < 0) {
                throw PatchException("read checker: no single (J)I read-count call in $definingClass")
            }
            val call = getInstruction(readCountIndex) as? FiveRegisterInstruction
                ?: throw PatchException("read checker: read-count call in $definingClass is a range call")
            val (low, high) = call.registerD to call.registerE
            if (call.registerCount != 3 || high != low + 1) {
                throw PatchException("read checker: unexpected read-count call operands in $definingClass")
            }
            addInstructions(
                readCountIndex,
                "invoke-static/range { v$low .. v$high }, $EXTENSION->onRowMessage(J)V",
            )

            val holderRegister = firstParameterRegister() + 1
            addInstructions(
                0,
                "invoke-static/range { v$holderRegister .. v$holderRegister }, " +
                    "$EXTENSION->onRowBind(Ljava/lang/Object;)V",
            )
        }
    }
}
