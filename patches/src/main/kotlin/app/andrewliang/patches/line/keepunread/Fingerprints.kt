package app.andrewliang.patches.line.keepunread

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * `toString()` of the Thrift struct `ReactRequest` (`cb8.ff` on 26.11.0). Only used for its
 * defining class: the request type is what tells the "send a reaction" call apart from
 * "cancel a reaction" in the same method (see `keepChatsUnreadPatch`).
 */
internal object ReactRequestToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(string("ReactRequest(reqSeq:")),
)

/**
 * The 1:1/group read worker `q33.e.d(J, String, Z)` — it performs both the local read-state
 * update and the server read receipt (`sendChatChecked`). It is the ONLY method in the APK
 * that calls the non-obfuscated `TalkServiceClient.j1(...)`, which makes it a stable anchor to
 * locate the read-manager class `q33.e` (all of q33.e's names are obfuscated and have no
 * string literals).
 *
 * We don't patch this worker (it's shared by manual mark-as-read); we only use it to find the
 * class, then no-op the sibling open-on-view wrapper `b(String, String)` in the same class.
 */
internal object ReadWorkerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("J", "Ljava/lang/String;", "Z"),
    filters = listOf(
        methodCall(
            definingClass = "Ljp/naver/line/android/thrift/client/TalkServiceClient;",
            name = "j1",
        ),
    ),
)
