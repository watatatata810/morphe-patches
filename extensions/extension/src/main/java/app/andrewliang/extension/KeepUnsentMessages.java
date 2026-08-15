package app.andrewliang.extension;

import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Helper for the "Keep unsent messages" patch.
 *
 * <p>The patch makes LINE skip the database work it normally does when a message is unsent in a
 * 1:1 or group chat, so the row survives untouched — text, media, reactions and its search-index
 * entry all intact. Nothing then marks the message as recalled, so we add that marker ourselves:
 * a second, synthetic {@code chat_history} row of type {@code i38.c.UNSENT}.
 *
 * <p>That type is all LINE's renderer needs. It maps the row to the {@code i38.g.s.h0} content
 * model, which resolves to {@code chathistory_message_format_unsent_receiver}
 * ("%1$s unsent a message.") — or the "You unsent a message." variant when {@code from_mid} is
 * your own account. Copying {@code from_mid} from the original row therefore gives correct sender
 * naming and free localisation, with no injected strings.
 *
 * <p>Best-effort: any failure is swallowed, leaving the kept message in place without a notice.
 */
public final class KeepUnsentMessages {

    private KeepUnsentMessages() {}

    private static final String TAG = "AndrewKeepUnsent";

    /** {@code i38.c.UNSENT} db value — the "this message was unsent" chat_history.type. */
    private static final int TYPE_UNSENT = 27;

    /**
     * The tombstone types, i.e. exactly what {@code i38.c.h()} — the guard the patch skips —
     * returns true for: {@code UNSENT}(27), {@code UNSENT_NO_MARK}(28), {@code UNSENT_SILENT}(38).
     * A row of one of these types already <em>is</em> an "unsent a message" notice.
     */
    private static final String TYPES_ALREADY_UNSENT = "27, 28, 38";

    /** {@code cb8.q7.NONE} db value, matching what LINE's own unsend writes. */
    private static final int ATTACHMENT_NONE = 0;

    /**
     * Suffix appended to the original {@code server_id} to form the placeholder's own id. Keeps
     * the synthetic row distinguishable from real server rows and makes the insert idempotent.
     */
    private static final String MARK = "_unsent";

    /**
     * Copies the identifying columns of the unsent message into a new tombstone row.
     *
     * <p>{@code created_time} is a TEXT column holding epoch millis, so the +1 that orders the
     * placeholder directly below its message needs a cast round-trip. Ordering follows
     * {@code created_time} (the {@code IDX_CHAT_ID_ID_CREATED_TIME} sort columns), so the notice
     * lands under the message rather than at the end of the conversation; a one millisecond
     * offset is invisible at the minute precision the UI displays.
     *
     * <p>{@code status}, {@code read_count} and {@code sent_count} are inherited so the notice
     * carries the same read receipt as the message it annotates. These are per-message columns and
     * are <em>not</em> what the chat list's unread badge is made of: that is
     * {@code chat.message_count - chat.read_message_count}, two stored counters on the {@code chat}
     * row (see {@code z13.o}), which only LINE's own insert path maintains and this patch never
     * writes. So the badge is safe regardless of what is copied here — but it also means
     * {@code message_count} now sits one behind the real row count per kept message. Harmless
     * while nothing recomputes it from {@code chat_history}; {@code g38.g2.j(chatId)} is a
     * per-chat {@code count(*)} of that shape, with no caller found that writes it back.
     * Worth re-checking on a version bump.
     *
     * <p>{@code content} is left NULL deliberately — for this type the renderer ignores it and
     * builds the text from {@code from_mid}.
     *
     * <p>Rows that are already tombstones are skipped. The patch injects ahead of the branch it
     * flips, so this runs even when LINE's own guard would have exited early — a redelivered
     * unsend for a message tombstoned before the patch was installed, or one stripped by the
     * offline {@code KEY_UNSENT_MESSAGE} sync path, which never reaches that guard at all.
     * Annotating either would draw the notice twice. Checking the row's type here is the same
     * question the guard asks, answered from the database rather than from a register the
     * injection has already overwritten.
     */
    private static final String INSERT_PLACEHOLDER =
            "INSERT INTO chat_history"
                    + " (server_id, type, chat_id, from_mid, created_time, delivered_time,"
                    + " status, read_count, sent_count, attachement_type)"
                    + " SELECT o.server_id || ?, ?, o.chat_id, o.from_mid,"
                    + " CAST(CAST(o.created_time AS INTEGER) + 1 AS TEXT), o.delivered_time,"
                    + " o.status, o.read_count, o.sent_count, ?"
                    + " FROM chat_history o"
                    + " WHERE (o.server_id = ? OR o.id = ?)"
                    + " AND o.server_id IS NOT NULL"
                    + " AND o.created_time IS NOT NULL"
                    + " AND o.type NOT IN (" + TYPES_ALREADY_UNSENT + ")"
                    + " AND NOT EXISTS ("
                    + "  SELECT 1 FROM chat_history x WHERE x.server_id = o.server_id || ?)"
                    + " LIMIT 1";

    /**
     * Applies the patch's whole decision for one incoming unsend, and reports what LINE's
     * "is this row already an unsend tombstone?" guard should say.
     *
     * <p>This is a single call taking registers the patch site already holds, because the
     * injection point — between the guard's {@code move-result} and the {@code if-eqz} that reads
     * it — has <b>no free register</b>, even though the method has 31 of them: it sits immediately
     * before a branch, and no register is written-before-read on both paths. So the database
     * cannot be loaded into a scratch register with an {@code iget-object} the way the rest of the
     * patch's anchors are read; the transaction object is passed instead and its database field
     * found here.
     *
     * <p>Reflecting on the field <b>type</b> rather than its name is what keeps that safe across
     * versions — {@code g38.f3} and its field names are obfuscated and drift, while
     * {@code SQLiteDatabase} does not. The patch still proves at apply time that the class has such
     * a field, so a version where this stops holding fails loudly there rather than silently here.
     *
     * @param transaction   LINE's transaction object, holding the open chat database.
     * @param originalGuard what {@code i38.c.h()} returned for this row.
     * @param messageId     the unsent message's id. The caller reads whichever of the row's two
     *                      long ids is already loaded at the patch site, so this may be either the
     *                      local {@code chat_history.id} or the {@code server_id}; the statement
     *                      matches on either. Local ids are small and server ids are large, so the
     *                      two id spaces do not realistically overlap, and {@code LIMIT 1} bounds
     *                      it regardless.
     * @return whether LINE should skip the destructive unsend block. Always true while the setting
     *         is on; otherwise the guard's own answer, so the unsend applies as on a stock build.
     */
    public static boolean applyUnsend(Object transaction, boolean originalGuard, long messageId) {
        if (!Settings.isEnabled(Settings.Key.KEEP_UNSENT_MESSAGES)) {
            return originalGuard;
        }

        insertPlaceholder(databaseOf(transaction), messageId);
        return true;
    }

    /** @return the first {@link SQLiteDatabase} held by {@code transaction}, or null. */
    private static SQLiteDatabase databaseOf(Object transaction) {
        if (transaction == null) return null;

        for (Class<?> type = transaction.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != SQLiteDatabase.class) continue;
                try {
                    field.setAccessible(true);
                    SQLiteDatabase database = (SQLiteDatabase) field.get(transaction);
                    if (database != null) return database;
                } catch (Throwable t) {
                    // Try the next candidate rather than giving up on the notice entirely.
                }
            }
        }

        Log.w(TAG, "No database on " + transaction.getClass().getName()
                + "; keeping the message without a notice.");
        return null;
    }

    /** Best-effort: add the "unsent a message" notice under the message we just kept. */
    private static void insertPlaceholder(SQLiteDatabase db, long messageId) {
        if (db == null) return;
        try {
            db.execSQL(INSERT_PLACEHOLDER, new Object[] {
                    MARK,
                    Integer.valueOf(TYPE_UNSENT),
                    Integer.valueOf(ATTACHMENT_NONE),
                    String.valueOf(messageId),
                    Long.valueOf(messageId),
                    MARK,
            });
        } catch (Throwable t) {
            Log.w(TAG, "Could not add the unsent notice; keeping the message without one.", t);
        }
    }
}
