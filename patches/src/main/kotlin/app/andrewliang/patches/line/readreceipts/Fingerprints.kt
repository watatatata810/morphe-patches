package app.andrewliang.patches.line.readreceipts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The `NOTIFIED_READ_MESSAGE` operation handler's constructor.
 *
 * LINE dispatches each incoming operation to a per-type handler class extending the base op-handler
 * `d98.a`, whose constructor takes the op type it handles (`d98.a.<init>(Lcb8/ce;)V`, with `cb8.ce`
 * the `OpType` enum). The read handler is the subclass whose constructor passes the read op type:
 *
 * ```
 *     sget-object v0, Lcb8/ce;->NOTIFIED_READ_MESSAGE:Lcb8/ce;
 *     invoke-direct {v1, v0}, Ld98/a;-><init>(Lcb8/ce;)V
 * ```
 *
 * On 26.11.0 that subclass is `e98.e2`, and its override
 * `b(Ld98/q0;, Lcb8/de;, Lkotlin/coroutines/Continuation;)` is the read-op processor whose 2nd
 * parameter is the `Operation` struct (`cb8.de`). The patch reads the handler class off this
 * fingerprint, then injects a capture call at the head of `b`.
 *
 * The stable anchor is the **non-obfuscated** enum-constant name `NOTIFIED_READ_MESSAGE`. The
 * `OpType` enum (`cb8.ce`) and base handler (`d98.a`) descriptors are obfuscated and drift — on a
 * version bump, re-derive them by finding the constructor that reads that constant and chains to the
 * base op-handler's `<init>`. The `methodCall` on `d98.a.<init>` is what distinguishes this
 * constructor from the enum's own `<clinit>`, which also writes `NOTIFIED_READ_MESSAGE` but chains
 * to `cb8.ce.<init>` instead.
 */
internal object ReadOpHandlerFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(definingClass = "Lcb8/ce;", name = "NOTIFIED_READ_MESSAGE"),
        methodCall(definingClass = "Ld98/a;", name = "<init>"),
    ),
)

/**
 * The gate in front of LINE's bulk `getMessageReadRange` sync — `c88.e.a(chatId, syncReason)` on
 * 26.11.0.
 *
 * It reads the chat's `chat_read_count_meta_data.synchronized_time` (through an in-memory
 * `LruCache`) and returns early whenever it is greater than zero:
 *
 * ```
 *     if (SquareChatUtils.b(chatId)) return null;
 *     long syncTime = cache.get(chatId) ?? (SELECT synchronized_time WHERE chat_id=? else 0);
 *     const-wide/16 v11, 0x0          <-- the literal this patch rewrites
 *     if (syncTime > v11) return null;
 *     return b(db, {chatId}, reason);
 * ```
 *
 * So the sync runs **once per chat, ever**, and from then on only the live
 * `NOTIFIED_READ_MESSAGE` operation updates `read_message_range`.
 *
 * Anchors, in program order: the **non-obfuscated** `SquareChatUtils.b` guard, the sole
 * `const-wide/16` in the method (`instructionMatches[1]`, the instruction to rewrite), and the
 * `SQLiteDatabase.query` that reads the stored time. The return type `Lorg/apache/thrift/l;` narrows
 * it further. `c88.e`, `f88.a` and the `LruCache` type are obfuscated and drift; none are named
 * here.
 */
internal object ReadRangeSyncGateFingerprint : Fingerprint(
    returnType = "Lorg/apache/thrift/l;",
    filters = listOf(
        methodCall(definingClass = "Lcom/linecorp/square/chat/SquareChatUtils;", name = "b"),
        opcode(Opcode.CONST_WIDE_16),
        methodCall(definingClass = "Landroid/database/sqlite/SQLiteDatabase;", name = "query"),
    ),
)

/**
 * The chat-screen adapter's `onBindViewHolder` — `ke1.e.onBindViewHolder(RecyclerView$e0, I)` on
 * 26.11.0 — which is where LINE computes each row's 「既読 N」:
 *
 * ```
 *     iget-wide v2, <item>, Ls61/c;->c:J              // the row's message id (server id)
 *     ...
 *     invoke-interface {v6, v2, v3}, Lc88/a;->a(J)I   // read count for that id
 *     ...
 *     invoke-virtual {..}, Landroid/content/res/Resources;->getString(I[Ljava/lang/Object;)
 * ```
 *
 * The `(J)I` interface call is the one place a row's view and its exact message id meet, so the
 * patch hooks it rather than matching the row's text and time back to `chat_history`.
 *
 * Anchors: the non-obfuscated method name `onBindViewHolder`, the `Resources.getString(int,
 * Object...)` that formats 「既読 %s」, and — checked by [isReadCountCall] — an interface call taking a
 * single `long` and returning `int`. The adapter (`ke1.e`), the provider (`c88.a`) and the row model
 * (`s61.c`) are obfuscated and drift; none are named here.
 */
internal object ChatRowBindFingerprint : Fingerprint(
    name = "onBindViewHolder",
    returnType = "V",
    parameters = listOf("L", "I"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/res/Resources;",
            name = "getString",
            parameters = listOf("I", "[Ljava/lang/Object;"),
        ),
    ),
    custom = { method, _ -> method.readCountCallIndex() >= 0 },
)

/** Whether [instruction] is an interface call of shape `(J)I` — the read-count provider's `a(J)I`. */
internal fun isReadCountCall(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_INTERFACE &&
        instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE
    ) {
        return false
    }
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.returnType == "I" &&
        reference.parameterTypes.map { it.toString() } == listOf("J")
}

/** Index of the single `(J)I` interface call in this method, or -1 when there is not exactly one. */
internal fun Method.readCountCallIndex(): Int {
    val instructions = implementation?.instructions?.toList() ?: return -1
    val indices = instructions.indices.filter { isReadCountCall(instructions[it]) }
    return if (indices.size == 1) indices.single() else -1
}
