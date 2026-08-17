package app.andrewliang.patches.line.readreceipts

import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** The read-count `TextView` LINE binds. Present only in its *send* row layouts. */
private const val ID_READ_COUNT = "@id/chat_ui_row_read_count"

/** The anchor in each receive layout: the row's timestamp, styled the same way. */
private const val ID_TIMESTAMP = "@id/chat_ui_row_timestamp"

/** Shared by LINE's own timestamp and read-count views, so the added one matches them exactly. */
private const val STYLE_METADATA = "@style/chat_ui_text_chatroom_message_metadata"

/** Gap LINE uses between two metadata items on a row. */
private const val DIMEN_ITEM_SPACE = "@dimen/chat_ui_row_send_vertical_layout_metadata_item_space"

private const val RES_AUTO_NAMESPACE = "http://schemas.android.com/apk/res-auto"

/**
 * Marks the counters this patch adds, so the extension can tell them from LINE's own on a send row.
 * Kept in step with `ReadReceipts.INCOMING_READ_COUNT_TAG`.
 */
private const val VIEW_TAG = "morphe_incoming_read_count"

/**
 * The two receive-side containers that carry a timestamp. LINE picks between them per row: the
 * `_vertical` one lays the metadata out under the bubble, the horizontal one beside it (through the
 * `_metadata` layout it includes).
 */
private const val RECEIVE_VERTICAL = "res/layout/chat_ui_row_layout_receive_vertical.xml"
private const val RECEIVE_HORIZONTAL_METADATA =
    "res/layout/chat_ui_row_layout_receive_horizontal_metadata.xml"

private fun Node.childElements() =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** Depth-first search for the first element carrying `android:id="[id]"`. */
private fun Node.findById(id: String): Element? {
    childElements().forEach { child ->
        if (child.getAttribute("android:id") == id) return child
        child.findById(id)?.let { return it }
    }
    return null
}

/**
 * The prefix bound to the `res-auto` namespace on this document's root, which is what the
 * `ConstraintLayout` attributes have to be written with. Conventionally `app`, but it is read off
 * the document rather than assumed, since the decoder picks it.
 */
private fun Document.resAutoPrefix(): String {
    val root = documentElement
    val attributes = root.attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (attribute.nodeValue == RES_AUTO_NAMESPACE && attribute.nodeName.startsWith("xmlns:")) {
            return attribute.nodeName.removePrefix("xmlns:")
        }
    }
    root.setAttribute("xmlns:app", RES_AUTO_NAMESPACE)
    return "app"
}

private fun Document.newReadCountView() =
    createElement("TextView").apply {
        setAttribute("android:id", ID_READ_COUNT)
        setAttribute("android:layout_width", "wrap_content")
        setAttribute("android:layout_height", "wrap_content")
        setAttribute("android:tag", VIEW_TAG)
        setAttribute("style", STYLE_METADATA)
    }

/** The layout half: the counter LINE has no place to draw on an incoming row. */
private val incomingReadCountLayoutPatch = resourcePatch {
    execute {
        // Metadata beside the bubble: a plain vertical LinearLayout, so the counter goes on its own
        // line directly above the timestamp — the same order the send side uses.
        document(RECEIVE_HORIZONTAL_METADATA).use { document ->
            val timestamp = document.findById(ID_TIMESTAMP)
                ?: throw PatchException("$RECEIVE_HORIZONTAL_METADATA has no $ID_TIMESTAMP")

            if (document.findById(ID_READ_COUNT) == null) {
                timestamp.parentNode.insertBefore(document.newReadCountView(), timestamp)
            }
        }

        // Metadata under the bubble: a ConstraintLayout, and the timestamp is pinned to the
        // bubble's right edge, so the counter is constrained to sit immediately left of it and
        // share its vertical extent — reading as 「既読 3  12:34」.
        document(RECEIVE_VERTICAL).use { document ->
            val timestamp = document.findById(ID_TIMESTAMP)
                ?: throw PatchException("$RECEIVE_VERTICAL has no $ID_TIMESTAMP")

            if (document.findById(ID_READ_COUNT) != null) return@use

            val app = document.resAutoPrefix()
            val readCount = document.newReadCountView().apply {
                setAttribute("android:layout_marginRight", DIMEN_ITEM_SPACE)
                setAttribute("android:layout_marginEnd", DIMEN_ITEM_SPACE)
                setAttribute("$app:layout_constraintRight_toLeftOf", ID_TIMESTAMP)
                setAttribute("$app:layout_constraintTop_toTopOf", ID_TIMESTAMP)
                setAttribute("$app:layout_constraintBottom_toBottomOf", ID_TIMESTAMP)
            }

            // Order is irrelevant to ConstraintLayout; placing it next to its anchor keeps the
            // decompiled layout readable when this is re-derived on a version bump.
            timestamp.parentNode.insertBefore(readCount, timestamp)
        }
    }
}

/**
 * "Show read count on received messages" — put LINE's own 「既読 N」 counter on incoming rows too.
 *
 * LINE already computes the count for **every** row: `ke1.e.onBindViewHolder` calls the read-count
 * provider with the row's message id and shows the result whenever it is greater than zero, with no
 * check for who sent the message. The count is per-member and chat-wide — LINE's `read_message_range`
 * table (in its `read_notification` database) stores one read range per *member*, and nothing in that
 * data model is scoped to a sender.
 *
 * The only reason incoming rows show nothing is that the row controller binds the counter through
 * `if (findViewById(R.id.chat_ui_row_read_count) != null)`, and that id exists solely in LINE's
 * *send* layouts. So this is a pure resource patch: add a `TextView` carrying that id to the two
 * receive containers and LINE fills it in itself. No fingerprint, no injected code.
 *
 * The counter is part of the layout, so unlike the runtime-toggleable patches it cannot be switched
 * off from the settings screen — deselect the patch to remove it. That is also why it is the one
 * patch that is **not** selected by default: it changes what LINE displays about other people.
 *
 * **Only groups get a counter.** LINE counts every member whose read range covers the message and a
 * sender's own range always does, so on an incoming row the count includes the sender. In a group
 * that still carries information; in a one-to-one chat it does not — LINE renders the count there as
 * a bare 「既読」 (`@string/chathistory_message_single_read`) that would appear on *every* incoming
 * message, saying only that the person who wrote it has read it. Device-confirmed. The counters
 * added here therefore carry [VIEW_TAG], and `ReadReceipts.decorate` hides the tagged ones outside
 * group chats — a runtime decision, because both chat kinds share these layouts.
 *
 * Tapping a count to see *who* read is a separate feature, provided by [groupReadReceiptsPatch];
 * with that patch's toggle off, the counts here are still shown but inert.
 *
 * **Keeping the counts current.** Stock LINE fetches per-member read ranges from the server exactly
 * **once per chat, ever** — `ReadRangeSyncGateFingerprint` documents the gate — because after that
 * it only needs the live read operations, which arrive for messages *you* sent. On someone else's
 * messages that leaves the count frozen at whatever the one-time sync captured: device-confirmed,
 * a group showed 既読 on yesterday's incoming messages and nothing on today's, even where the
 * message had a reaction from another member. Reacting tells your client nothing about the
 * reactor's read position; only an operation or a sync does.
 *
 * So the gate's literal `0` is replaced with a cutoff the extension supplies, turning "sync once"
 * into "sync when the stored time is older than the cutoff". One instruction, no extra registers,
 * control flow untouched. LINE itself writes both the database row and its in-memory cache after a
 * successful sync, so the interval rate-limits with no bookkeeping of ours.
 *
 * This is the one change in this bundle that makes LINE issue a **network request it otherwise
 * would not**. The call is LINE's own `getMessageReadRange`, made the way LINE makes it, but the
 * interval is deliberately not zero: the gate sits on `onChatMessageListChanged`, which fires on
 * every new message and every pagination step, not merely when a chat is opened.
 */
@Suppress("unused")
val incomingReadCountPatch = bytecodePatch(
    name = "Show read count on received messages",
    description = "Show LINE's read count on messages other people sent in a group, not only on " +
        "your own. LINE already knows the count for every message and simply has no place to draw " +
        "it on an incoming row; this adds one, and keeps it current by letting LINE re-fetch read " +
        "state for a chat it would otherwise fetch only once. Note that LINE's count includes the " +
        "message's sender. Not selected by default. It is partly a layout change, so it cannot be " +
        "turned off from the settings screen — deselect this patch to remove it.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_LINE)

    extendWith("extensions/extension.mpe")

    dependsOn(chatHistoryHooksPatch, incomingReadCountLayoutPatch)

    execute {
        // instructionMatches[1] is the sole const-wide/16 in the gate: the 0 the stored sync time
        // is compared against. Its destination register is read off the instruction rather than
        // hardcoded, and reused, so nothing else in the method has to move.
        val match = ReadRangeSyncGateFingerprint.instructionMatches[1]
        val register = (match.instruction as OneRegisterInstruction).registerA

        ReadRangeSyncGateFingerprint.method.apply {
            replaceInstruction(
                match.index,
                "invoke-static { }, $EXTENSION->readRangeSyncCutoff()J",
            )
            addInstructions(match.index + 1, "move-result-wide v$register")
        }
    }
}
