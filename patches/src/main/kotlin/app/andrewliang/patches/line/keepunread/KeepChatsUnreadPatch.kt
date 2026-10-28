package app.andrewliang.patches.line.keepunread

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.util.findFreeRegister
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/andrewliang/extension/UnreadOverride;"
private const val CHAT_HISTORY_ACTIVITY =
    "Ljp/naver/line/android/activity/chathistory/ChatHistoryActivity;"

private const val TALK_SERVICE_CLIENT = "Ljp/naver/line/android/thrift/client/TalkServiceClient;"

/** Whether this instruction writes [register] (including as the high half of a wide pair). */
private fun Instruction.writes(register: Int): Boolean {
    if (this !is OneRegisterInstruction || !opcode.setsRegister()) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

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
        "marks the chat read and sends the receipt as normal, and so does replying: sending a " +
        "message or a reaction in the open chat reads it. A chip on the chat screen lets " +
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

        // Sending a reaction reads the chat, the way sending a message does. LINE's send task reads
        // up to the newest message when the chat is on screen (e98.u4.f -> q33.e.e -> a + d), a
        // path that never meets the gate above; reacting has no such step. The extension repeats
        // that read after the react call, through the read manager it is handed from the
        // constructor, choosing a(String)J and d(J, String, Z)V by shape — checked unique here.
        for ((returnType, parameters) in listOf(
            "J" to listOf("Ljava/lang/String;"),
            "V" to listOf("J", "Ljava/lang/String;", "Z"),
        )) {
            val count = readManager.methods.count { method ->
                method.returnType == returnType &&
                    method.parameterTypes.map { it.toString() } == parameters
            }
            if (count != 1) {
                throw PatchException(
                    "keepunread: ${readManager.type} has $count ($parameters)$returnType methods, expected 1",
                )
            }
        }

        val readManagerCtor = readManager.methods.singleOrNull { it.name == "<init>" }
            ?: throw PatchException("keepunread: ${readManager.type} has no unique constructor")
        val ctorThis = readManagerCtor.thisRegister()
        val ctorInstructions = readManagerCtor.implementation!!.instructions.toList()
        if (ctorInstructions.any { it.writes(ctorThis) }) {
            throw PatchException("keepunread: ${readManager.type}.<init> reuses the `this` register")
        }
        val ctorReturn = ctorInstructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (ctorReturn < 0 || ctorInstructions.count { it.opcode == Opcode.RETURN_VOID } != 1) {
            throw PatchException("keepunread: ${readManager.type}.<init> has no single return-void")
        }
        readManagerCtor.addInstructions(
            ctorReturn,
            "invoke-static/range { v$ctorThis .. v$ctorThis }, $EXTENSION->setReadManager(Ljava/lang/Object;)V",
        )

        // The reaction coroutine (g38.b2.invokeSuspend, "MessageDataManager.updateMessageReaction")
        // calls TalkServiceClient twice: with a ReactRequest to react, with a CancelReactionRequest
        // to take one back. Only the first should read, so the call is keyed on the request type.
        val reactRequest = ReactRequestToStringFingerprint.method.definingClass
        val reactionMatches = Fingerprint(
            name = "invokeSuspend",
            filters = listOf(
                methodCall(definingClass = TALK_SERVICE_CLIENT, parameters = listOf(reactRequest)),
            ),
        ).matchAll()
        val reactionMatch = reactionMatches.singleOrNull() ?: throw PatchException(
            "keepunread: ${reactionMatches.size} methods send a $reactRequest, expected 1",
        )
        val reactionMethod = reactionMatch.method
        val reactionInstructions = reactionMethod.implementation!!.instructions.toList()
        val reactCall = reactionMatch.instructionMatches[0].index
        if (reactionInstructions.getOrNull(reactCall + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("keepunread: the react call is no longer followed by move-result-object")
        }
        val reactInjectIndex = reactCall + 2

        // The chat id comes from the message the coroutine loaded before reacting (i38.b, read
        // later in the method as `message.c`). The message class is the one with the sentinel
        // `sget-object i38.b.A:Li38/b;` at the top; its chat id field is confirmed against the
        // class's own toString, which prints it after ", chatId=".
        val messageClass = reactionInstructions.firstNotNullOfOrNull { instruction ->
            if (instruction.opcode != Opcode.SGET_OBJECT) return@firstNotNullOfOrNull null
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            field.type.takeIf { it == field.definingClass }
        } ?: throw PatchException("keepunread: no message sentinel in the reaction coroutine")

        val chatIdReads = reactionInstructions.filter { instruction ->
            if (instruction.opcode != Opcode.IGET_OBJECT) return@filter false
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            field.definingClass == messageClass && field.type == "Ljava/lang/String;"
        }
        val chatIdRead = chatIdReads.singleOrNull() ?: throw PatchException(
            "keepunread: ${chatIdReads.size} String reads of $messageClass in the reaction coroutine, expected 1",
        )
        val chatIdField = (chatIdRead as ReferenceInstruction).reference as FieldReference
        val messageToString = classDefBy(messageClass).methods.singleOrNull { it.name == "toString" }
            ?.implementation?.instructions?.toList()
            ?: throw PatchException("keepunread: $messageClass has no toString")
        val chatIdLabel = messageToString.indexOfFirst { instruction ->
            instruction.opcode == Opcode.CONST_STRING &&
                ((instruction as ReferenceInstruction).reference as StringReference).string == ", chatId="
        }
        val printedField = messageToString.drop(chatIdLabel + 1).firstOrNull { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).type == "Ljava/lang/String;"
        }?.let { (it as ReferenceInstruction).reference as FieldReference }
        if (chatIdLabel < 0 || printedField?.name != chatIdField.name) {
            throw PatchException("keepunread: $messageClass.${chatIdField.name} is not the printed chatId")
        }

        // The message must still be in its register at the react call: the last write to it before
        // the call has to be the move-result of the lookup that returned the message.
        val messageRegister = (chatIdRead as TwoRegisterInstruction).registerB
        val lastWrite = (reactCall - 1 downTo 1).firstOrNull { reactionInstructions[it].writes(messageRegister) }
        val loadedBy = lastWrite?.let {
            (reactionInstructions[it - 1] as? ReferenceInstruction)?.reference as? MethodReference
        }
        if (lastWrite == null || reactionInstructions[lastWrite].opcode != Opcode.MOVE_RESULT_OBJECT ||
            loadedBy?.returnType != messageClass
        ) {
            throw PatchException("keepunread: v$messageRegister does not hold the message at the react call")
        }

        val chatIdRegister = try {
            reactionMethod.findFreeRegister(reactInjectIndex, messageRegister)
        } catch (exception: IllegalArgumentException) {
            throw PatchException("keepunread: no free register after the react call", exception)
        }
        if (chatIdRegister > 15 || messageRegister > 15) {
            throw PatchException("keepunread: iget-object needs v0-v15 (v$chatIdRegister, v$messageRegister)")
        }
        reactionMethod.addInstructions(
            reactInjectIndex,
            """
                iget-object v$chatIdRegister, v$messageRegister, $chatIdField
                invoke-static/range { v$chatIdRegister .. v$chatIdRegister }, $EXTENSION->onReactionSent(Ljava/lang/String;)V
            """,
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
