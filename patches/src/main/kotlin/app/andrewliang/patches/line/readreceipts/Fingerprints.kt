package app.andrewliang.patches.line.readreceipts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

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
