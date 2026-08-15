package app.andrewliang.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Group read checker" — records who reads each message in a group and shows it by tapping the
 * per-message 「既読 N」 count.
 *
 * <p><b>Why this exists as a live capture rather than a query.</b> LINE hands a group client only an
 * aggregate read <em>count</em>: the bulk API returns per-range counts and the local database stores
 * counts, never a per-member table (see {@code docs/line-patch-map.md}). The single place a reader's
 * identity appears is the live {@code NOTIFIED_READ_MESSAGE} operation, whose {@code param2} is the
 * reader's mid and {@code param3} the message id they have read up to. The patch injects a call to
 * {@link #onReadOp(Object)} at the head of LINE's read-op handler, and this class accumulates those
 * into its own database. So it is inherently <b>forward-only</b>: reads that happened before the
 * patch was installed are nowhere on the device to recover.
 *
 * <p>Everything here is best-effort and wrapped so it can never disrupt LINE's own op processing or
 * UI: a failure leaves LINE behaving exactly as it would unpatched, just without a read record.
 *
 * <p>The reader identity is read from {@code Operation.toString()} rather than by reflecting on
 * obfuscated field names, matching the approach the LIME Xposed module proved on-device — the field
 * names drift between versions but the Thrift {@code toString()} labels ({@code param2:} /
 * {@code param3:}) do not.
 *
 * <p><b>Per-message UI without decoding the obfuscated model.</b> LINE's message model
 * ({@code s61.r}/{@code l11.a}/{@code w81.a}) is a stack of obfuscated polymorphic wrappers with no
 * clean {@code server_id} accessor, so the tapped message is identified from what is on screen: the
 * row's visible text ({@code chat_ui_message_text}) and timestamp ({@code chat_ui_row_timestamp})
 * are matched back to {@code chat_history}. Everything the UI touches is a non-obfuscated resource
 * id. A media/sticker row has no text, so it matches media rows (null {@code content}) at the same
 * minute; when several messages share a minute and content, all are folded together (readers of the
 * earliest), so nothing is missed and nothing is over-counted.
 */
public final class ReadReceipts {

    private ReadReceipts() {}

    private static final String TAG = "AndrewReadReceipts";

    /** Our own accumulation database, created in LINE's private data dir. */
    private static final String OUR_DB = "morphe_read_receipts";
    /** LINE's message database (chat_history, groups). */
    private static final String LINE_MAIN_DB = "naver_line";
    /** LINE's contact database (contacts.mid -> profile_name). */
    private static final String LINE_CONTACT_DB = "contact";

    // Fields of the Operation Thrift struct, read out of its toString() dump:
    //   type:NOTIFIED_READ_MESSAGE ... param1:<chat mid> param2:<reader mid> param3:<message id>
    private static final Pattern PARAM_CHAT = Pattern.compile("param1:([^,\\s)]+)");
    private static final Pattern PARAM_READER = Pattern.compile("param2:([^,\\s)]+)");
    private static final Pattern PARAM_MESSAGE = Pattern.compile("param3:([0-9]+)");

    /**
     * LINE resource ids the UI anchors on. Non-obfuscated and stable across obfuscation; re-derive
     * on a version bump with {@code aapt2 dump resources base.apk | grep <name>} (find the live ids
     * with {@code adb shell uiautomator dump} on an open group chat).
     */
    private static final int ID_MESSAGE_LIST = 0x7f0b096a; // R.id.chathistory_message_list
    private static final int ID_READ_COUNT = 0x7f0b0870;   // R.id.chat_ui_row_read_count
    private static final int ID_MESSAGE_TEXT = 0x7f0b0798;  // R.id.chat_ui_message_text
    private static final int ID_TIMESTAMP = 0x7f0b08a6;     // R.id.chat_ui_row_timestamp

    /** Upper bound on chat_history rows scanned to resolve a tapped message. */
    private static final int MATCH_SCAN_LIMIT = 400;

    /**
     * Where LINE serves profile pictures from. {@code contacts.picture_path} is the object path on
     * this host (see {@link #loadAvatar}); {@code /preview} asks for the thumbnail variant, which is
     * ~10x smaller than the original and still larger than the avatar is drawn.
     */
    private static final String PROFILE_CDN = "https://profile.line-scdn.net";
    private static final String PREVIEW_SUFFIX = "/preview";

    private static volatile ExecutorService avatarExecutor;

    private static volatile Context appContext;

    // Current chat, tracked from ChatHistoryRequest.getChatId (see the patch).
    private static volatile String currentChatId;
    private static volatile boolean currentIsGroup;

    /** mid -> resolved profile name (null cached too, to avoid re-hitting the contact db). */
    private static final Map<String, String> nameCache = new HashMap<>();

    private static volatile SQLiteDatabase ourDb;

    /** Called by {@link SettingsInitProvider} before LINE's Application starts. */
    static void setContext(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    // ------------------------------------------------------------------ current chat tracking

    /**
     * Records the chat the user is currently in, so the tap handler knows which chat to report on
     * and whether to attach at all.
     *
     * @param request LINE's {@code ChatHistoryRequest}; its {@code chatId} and {@code isGroup}
     *                fields are non-obfuscated, so they are read reflectively by name.
     */
    public static void trackChat(Object request) {
        if (request == null) {
            return;
        }
        try {
            Field chatIdField = findField(request.getClass(), "chatId");
            if (chatIdField != null) {
                chatIdField.setAccessible(true);
                Object value = chatIdField.get(request);
                currentChatId = value == null ? null : value.toString();
            }
            Field isGroupField = findField(request.getClass(), "isGroup");
            if (isGroupField != null) {
                isGroupField.setAccessible(true);
                currentIsGroup = isGroupField.getBoolean(request);
            }
        } catch (Throwable t) {
            // Leave the last known values in place.
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Try the superclass.
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ op capture

    /**
     * Entry point injected at the head of LINE's {@code NOTIFIED_READ_MESSAGE} handler.
     *
     * <p>The handler is called once per read op with the {@code Operation} as an argument (and,
     * because it is a suspend function, again with a null argument on coroutine resume — hence the
     * null guard). Only forwards to storage while the toggle is on.
     *
     * @param operation LINE's {@code Operation} struct, or null on a resume.
     */
    public static void onReadOp(Object operation) {
        try {
            if (operation == null || !Settings.isEnabled(Settings.Key.GROUP_READ_CHECKER)) {
                return;
            }
            Context context = appContext;
            if (context == null) {
                return;
            }
            String dump = operation.toString();
            if (dump == null || !dump.contains("NOTIFIED_READ_MESSAGE")) {
                return;
            }
            String chatId = firstGroup(PARAM_CHAT, dump);
            String readerMid = firstGroup(PARAM_READER, dump);
            String messageIdText = firstGroup(PARAM_MESSAGE, dump);
            if (chatId == null || readerMid == null || messageIdText == null) {
                return;
            }
            long messageId;
            try {
                messageId = Long.parseLong(messageIdText);
            } catch (NumberFormatException e) {
                return;
            }
            record(context, chatId, readerMid, messageId);
        } catch (Throwable t) {
            // Never let a read-record failure surface into LINE's op loop.
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Upserts one reader's read watermark for a chat. The watermark only ever moves forward, so a
     * redelivered or out-of-order op cannot walk it back.
     */
    private static void record(Context context, String chatId, String readerMid, long messageId) {
        SQLiteDatabase db = ourDatabase(context);
        if (db == null) {
            return;
        }
        String name = resolveName(context, readerMid);
        try {
            long existing = -1L;
            Cursor cursor = db.rawQuery(
                    "SELECT message_id FROM read_marks WHERE chat_id=? AND reader_mid=?",
                    new String[] {chatId, readerMid});
            try {
                if (cursor.moveToFirst()) {
                    existing = cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }

            if (messageId > existing) {
                db.execSQL(
                        "INSERT OR REPLACE INTO read_marks"
                                + " (chat_id, reader_mid, reader_name, message_id, read_time)"
                                + " VALUES (?, ?, ?, ?, ?)",
                        new Object[] {chatId, readerMid, name, Long.valueOf(messageId),
                                Long.valueOf(System.currentTimeMillis())});
            } else if (name != null) {
                // Watermark unchanged but a name may have resolved since; keep it current.
                db.execSQL("UPDATE read_marks SET reader_name=? WHERE chat_id=? AND reader_mid=?",
                        new Object[] {name, chatId, readerMid});
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not record a read mark.", t);
        }
    }

    /** @return the reader's LINE display name, or null if it can't be resolved. */
    private static String resolveName(Context context, String mid) {
        synchronized (nameCache) {
            if (nameCache.containsKey(mid)) {
                return nameCache.get(mid);
            }
        }
        String name = null;
        SQLiteDatabase db = openLineDatabase(context, LINE_CONTACT_DB);
        if (db != null) {
            try {
                Cursor cursor = db.rawQuery(
                        "SELECT profile_name FROM contacts WHERE mid=?", new String[] {mid});
                try {
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0);
                    }
                } finally {
                    cursor.close();
                }
            } catch (Throwable t) {
                // Fall through to caching null.
            } finally {
                db.close();
            }
        }
        synchronized (nameCache) {
            nameCache.put(mid, name);
        }
        return name;
    }

    // ------------------------------------------------------------------ our storage

    private static SQLiteDatabase ourDatabase(Context context) {
        SQLiteDatabase db = ourDb;
        if (db != null) {
            return db;
        }
        synchronized (ReadReceipts.class) {
            if (ourDb == null) {
                try {
                    SQLiteDatabase created = SQLiteDatabase.openOrCreateDatabase(
                            context.getDatabasePath(OUR_DB), null);
                    created.execSQL(
                            "CREATE TABLE IF NOT EXISTS read_marks ("
                                    + "chat_id TEXT NOT NULL,"
                                    + "reader_mid TEXT NOT NULL,"
                                    + "reader_name TEXT,"
                                    + "message_id INTEGER NOT NULL,"
                                    + "read_time INTEGER NOT NULL,"
                                    + "PRIMARY KEY(chat_id, reader_mid))");
                    ourDb = created;
                } catch (Throwable t) {
                    Log.w(TAG, "Could not open the read-receipts database.", t);
                }
            }
            return ourDb;
        }
    }

    /** Opens one of LINE's own databases read-only; caller closes it. */
    private static SQLiteDatabase openLineDatabase(Context context, String name) {
        try {
            return SQLiteDatabase.openDatabase(
                    context.getDatabasePath(name).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ in-chat UI

    /**
     * Entry point injected at the head of {@code ChatHistoryActivity.onCreate}. Defers the actual
     * work to after the activity has built its content view.
     */
    public static void decorate(final Activity activity) {
        try {
            if (activity == null || !Settings.isEnabled(Settings.Key.GROUP_READ_CHECKER)) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    installReadCountTaps(activity);
                }
            });
        } catch (Throwable t) {
            // No taps installed; LINE is otherwise untouched.
        }
    }

    /**
     * Makes every per-message 「既読 N」 count in a group chat tappable, opening the readers sheet.
     *
     * <p>Anchored on non-obfuscated resource ids rather than on the obfuscated row view controller,
     * so it survives obfuscation drift. Rows scroll in and out of a {@code RecyclerView}, so the
     * listeners are (re)installed on every layout pass; {@link View#hasOnClickListeners()} keeps that
     * idempotent and avoids a resource-id tag key.
     */
    private static void installReadCountTaps(final Activity activity) {
        try {
            if (!currentIsGroup) {
                return;
            }
            View list = activity.findViewById(ID_MESSAGE_LIST);
            final View scanRoot = list != null ? list : activity.findViewById(android.R.id.content);
            if (!(scanRoot instanceof ViewGroup)) {
                return;
            }
            installClicks((ViewGroup) scanRoot, activity);
            scanRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (scanRoot instanceof ViewGroup) {
                                installClicks((ViewGroup) scanRoot, activity);
                            }
                        }
                    });
        } catch (Throwable t) {
            // No taps.
        }
    }

    private static void installClicks(ViewGroup parent, final Activity activity) {
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = parent.getChildAt(i);
            if (child.getId() == ID_READ_COUNT) {
                if (!child.hasOnClickListeners()) {
                    child.setClickable(true);
                    child.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showForRow(activity, v);
                        }
                    });
                }
            } else if (child instanceof ViewGroup) {
                installClicks((ViewGroup) child, activity);
            }
        }
    }

    /** Resolves the tapped read-count view to its message(s) and shows the readers sheet. */
    private static void showForRow(Activity activity, View readCountView) {
        try {
            String chatId = currentChatId;
            if (chatId == null) {
                return;
            }
            View row = rowOf(readCountView);
            String time = row == null ? null : textOf(row, ID_TIMESTAMP);
            String content = row == null ? null : textOf(row, ID_MESSAGE_TEXT);

            List<Message> matches = resolveMessages(activity, chatId, time, content);
            List<Mark> marks = loadMarks(activity, chatId);
            showReadersSheet(activity, matches, marks, content, time, readCountView);
        } catch (Throwable t) {
            Log.w(TAG, "Could not open the readers sheet.", t);
        }
    }

    /** Walks up from a descendant to the RecyclerView's direct child (the message row). */
    private static View rowOf(View view) {
        View current = view;
        while (current.getParent() instanceof View) {
            View parent = (View) current.getParent();
            if (parent.getId() == ID_MESSAGE_LIST) {
                return current;
            }
            current = parent;
        }
        return null;
    }

    private static String textOf(View row, int id) {
        View view = row.findViewById(id);
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text == null ? null : text.toString();
        }
        return null;
    }

    /**
     * Finds the chat_history message(s) that match what the tapped row shows: same visible content
     * (or a media row when the tapped row has no text) at the same minute.
     */
    private static List<Message> resolveMessages(Context context, String chatId, String time,
            String content) {
        List<Message> matches = new ArrayList<>();
        SQLiteDatabase db = openLineDatabase(context, LINE_MAIN_DB);
        if (db == null) {
            return matches;
        }
        try {
            String sql;
            String[] args;
            if (content != null && !content.isEmpty()) {
                sql = "SELECT server_id, content, created_time FROM chat_history"
                        + " WHERE chat_id=? AND server_id IS NOT NULL AND content=?"
                        + " ORDER BY created_time DESC LIMIT ?";
                args = new String[] {chatId, content, String.valueOf(MATCH_SCAN_LIMIT)};
            } else {
                sql = "SELECT server_id, content, created_time FROM chat_history"
                        + " WHERE chat_id=? AND server_id IS NOT NULL"
                        + " AND (content IS NULL OR content='')"
                        + " ORDER BY created_time DESC LIMIT ?";
                args = new String[] {chatId, String.valueOf(MATCH_SCAN_LIMIT)};
            }
            Cursor cursor = db.rawQuery(sql, args);
            try {
                while (cursor.moveToNext()) {
                    long serverId;
                    try {
                        serverId = Long.parseLong(cursor.getString(0));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    long createdTime = 0L;
                    try {
                        createdTime = Long.parseLong(cursor.getString(2));
                    } catch (NumberFormatException ignored) {
                        // Leave at 0; the minute simply won't match a real timestamp.
                    }
                    if (time == null || time.equals(hhmm(createdTime))) {
                        matches.add(new Message(serverId, cursor.getString(1), createdTime));
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not resolve the tapped message.", t);
        } finally {
            db.close();
        }
        return matches;
    }

    private static List<Mark> loadMarks(Context context, String chatId) {
        List<Mark> marks = new ArrayList<>();
        SQLiteDatabase db = ourDatabase(context);
        if (db == null) {
            return marks;
        }
        try {
            Cursor cursor = db.rawQuery(
                    "SELECT reader_mid, reader_name, message_id FROM read_marks WHERE chat_id=?",
                    new String[] {chatId});
            try {
                while (cursor.moveToNext()) {
                    marks.add(new Mark(cursor.getString(0), cursor.getString(1), cursor.getLong(2)));
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read stored marks.", t);
        }
        return marks;
    }

    // ------------------------------------------------------------------ readers bottom sheet

    /**
     * Shows a bottom sheet, sliding up from the bottom (like LINE's reaction viewer), listing who
     * has read the tapped message. When several messages folded together (same minute + content),
     * the readers of the earliest are shown, so no reader is missed.
     */
    private static void showReadersSheet(Activity activity, List<Message> matches, List<Mark> marks,
            String tappedContent, String tappedTime, View tappedView) {
        boolean night = isDark(tappedView, activity);
        int bgColor = night ? 0xFF1F1F1F : 0xFFFFFFFF;
        int titleColor = night ? 0xFFECECEC : 0xFF1A1A1A;
        int subColor = night ? 0xFF9AA0A6 : 0xFF5F6368;
        int nameColor = night ? 0xFFE3E3E3 : 0xFF222222;
        int handleColor = night ? 0xFF444444 : 0xFFDADCE0;
        int dividerColor = night ? 0xFF333333 : 0xFFEEEEEE;

        long earliestServerId = Long.MAX_VALUE;
        for (Message message : matches) {
            earliestServerId = Math.min(earliestServerId, message.serverId);
        }
        List<Mark> readerMarks = new ArrayList<>();
        if (!matches.isEmpty()) {
            for (Mark mark : marks) {
                if (mark.watermark >= earliestServerId) {
                    readerMarks.add(mark);
                }
            }
        }
        Map<String, String> picturePaths = loadPicturePaths(activity, readerMarks);

        final LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        float radius = dp(activity, 18);
        background.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(background);
        panel.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 20));

        View handle = new View(activity);
        GradientDrawable handleBackground = new GradientDrawable();
        handleBackground.setColor(handleColor);
        handleBackground.setCornerRadius(dp(activity, 3));
        handle.setBackground(handleBackground);
        LinearLayout.LayoutParams handleParams =
                new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(activity, 14);
        panel.addView(handle, handleParams);

        TextView title = new TextView(activity);
        title.setTextColor(titleColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);

        TextView subtitle = new TextView(activity);
        subtitle.setTextColor(subColor);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setPadding(0, dp(activity, 3), 0, dp(activity, 12));

        if (matches.isEmpty()) {
            title.setText("既読者を特定できませんでした");
            subtitle.setText("同じ内容・時刻のメッセージが見つかりませんでした。"
                    + "スタンプや一部メッセージは特定できないことがあります。");
            panel.addView(title);
            panel.addView(subtitle);
        } else {
            title.setText("既読 " + readerMarks.size());
            StringBuilder sub = new StringBuilder(preview(tappedContent));
            if (tappedTime != null) {
                sub.append("  ・  ").append(tappedTime);
            }
            if (matches.size() > 1) {
                sub.append("（同時刻・同一内容 ").append(matches.size()).append(" 件をまとめて表示）");
            }
            subtitle.setText(sub.toString());
            panel.addView(title);
            panel.addView(subtitle);

            View divider = new View(activity);
            divider.setBackgroundColor(dividerColor);
            panel.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 1) / 2)));

            LinearLayout listLayout = new LinearLayout(activity);
            listLayout.setOrientation(LinearLayout.VERTICAL);
            if (readerMarks.isEmpty()) {
                TextView none = new TextView(activity);
                none.setText("まだ誰も既読を付けていません（記録開始後の分のみ）。");
                none.setTextColor(subColor);
                none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                none.setPadding(0, dp(activity, 14), 0, 0);
                listLayout.addView(none);
            } else {
                int avatarSize = dp(activity, 34);
                for (Mark mark : readerMarks) {
                    LinearLayout readerRow = new LinearLayout(activity);
                    readerRow.setOrientation(LinearLayout.HORIZONTAL);
                    readerRow.setGravity(Gravity.CENTER_VERTICAL);
                    readerRow.setPadding(0, dp(activity, 8), 0, dp(activity, 8));

                    ImageView avatar = new ImageView(activity);
                    // The initial goes up immediately; the real picture replaces it once fetched.
                    avatar.setImageBitmap(initialAvatar(mark.displayName(), avatarSize, night));
                    loadAvatar(activity, picturePaths.get(mark.mid), avatar, avatarSize);
                    LinearLayout.LayoutParams avatarParams =
                            new LinearLayout.LayoutParams(avatarSize, avatarSize);
                    avatarParams.rightMargin = dp(activity, 12);
                    readerRow.addView(avatar, avatarParams);

                    TextView name = new TextView(activity);
                    name.setText(mark.displayName());
                    name.setTextColor(nameColor);
                    name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                    readerRow.addView(name);

                    listLayout.addView(readerRow);
                }
            }

            // Cap the list at half the screen so a large group still leaves the sheet on-screen.
            ScrollView scroll = new ScrollView(activity) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
                    super.onMeasure(widthSpec,
                            View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
                }
            };
            scroll.addView(listLayout);
            panel.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }

        // Slide up: once measured, drop the panel below the screen and animate it into place.
        panel.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        panel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        panel.setTranslationY(panel.getHeight());
                        panel.animate().translationY(0f).setDuration(200).start();
                    }
                });

        dialog.show();
    }

    /**
     * The tapped 「既読 N」 view's own text colour is the most direct read of LINE's current theme —
     * LINE renders the count in a light colour under its dark theme and a dark colour under its light
     * one, regardless of the system night setting. Falls back to {@link #isDarkTheme} if the view
     * carries no usable colour.
     */
    private static boolean isDark(View tappedView, Activity activity) {
        if (tappedView instanceof TextView) {
            int textColor = ((TextView) tappedView).getCurrentTextColor();
            if (((textColor >> 24) & 0xFF) != 0) {
                return luminance(textColor) > 0.5;
            }
        }
        return isDarkTheme(activity);
    }

    /**
     * Whether to use a dark sheet, matched to LINE's <em>own</em> dark mode rather than the system
     * night setting — LINE ships an in-app dark theme that ignores the system one and paints its
     * chat background with an explicit colour rather than a theme attribute. So the actual painted
     * background of the message list (or an ancestor) is sampled, then the themed background colour,
     * then the system night flag, in that order of reliability.
     */
    private static boolean isDarkTheme(Activity activity) {
        View view = activity.findViewById(ID_MESSAGE_LIST);
        if (view == null) {
            view = activity.findViewById(android.R.id.content);
        }
        while (view != null) {
            Integer color = solidColor(view.getBackground());
            if (color != null && ((color >> 24) & 0xFF) != 0) {
                return luminance(color) < 0.5;
            }
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        try {
            TypedValue value = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)
                    && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return luminance(value.data) < 0.5;
            }
        } catch (Throwable ignored) {
            // Fall through to the system flag.
        }
        return (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Integer solidColor(android.graphics.drawable.Drawable drawable) {
        if (drawable instanceof android.graphics.drawable.ColorDrawable) {
            return ((android.graphics.drawable.ColorDrawable) drawable).getColor();
        }
        return null;
    }

    private static double luminance(int color) {
        return (0.299 * ((color >> 16) & 0xFF)
                + 0.587 * ((color >> 8) & 0xFF)
                + 0.114 * (color & 0xFF)) / 255.0;
    }

    /** mid -> {@code contacts.picture_path} (LINE's local avatar file) for the given readers. */
    private static Map<String, String> loadPicturePaths(Context context, List<Mark> readerMarks) {
        Map<String, String> paths = new HashMap<>();
        if (readerMarks.isEmpty()) {
            return paths;
        }
        SQLiteDatabase db = openLineDatabase(context, LINE_CONTACT_DB);
        if (db == null) {
            return paths;
        }
        try {
            StringBuilder placeholders = new StringBuilder();
            String[] mids = new String[readerMarks.size()];
            for (int i = 0; i < readerMarks.size(); i++) {
                if (i > 0) {
                    placeholders.append(',');
                }
                placeholders.append('?');
                mids[i] = readerMarks.get(i).mid;
            }
            Cursor cursor = db.rawQuery(
                    "SELECT mid, picture_path FROM contacts WHERE mid IN (" + placeholders + ")",
                    mids);
            try {
                while (cursor.moveToNext()) {
                    paths.put(cursor.getString(0), cursor.getString(1));
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read avatar paths.", t);
        } finally {
            db.close();
        }
        return paths;
    }

    /**
     * Replaces an avatar's placeholder with the reader's real profile picture once it is available.
     *
     * <p>{@code contacts.picture_path} is <b>not</b> a filesystem path despite the name — it is the
     * OBS object path of the picture (device-confirmed: the value equals {@code picture_status} with
     * a leading slash, and no such file exists on disk). LINE renders it through its own obfuscated
     * OBS URL builder and image loader; rather than reaching into either, the picture is fetched from
     * the same public profile CDN LINE serves them from and cached in the app's own cache dir, so the
     * network hit happens once per avatar.
     *
     * <p>Runs off the main thread (the sheet is built on it) and only ever <i>upgrades</i> the view:
     * every failure leaves the coloured initial that was set synchronously.
     */
    private static void loadAvatar(Context context, final String obsPath, ImageView view,
            final int size) {
        if (obsPath == null || obsPath.isEmpty()) {
            return;
        }
        final Context app = context.getApplicationContext();
        final java.lang.ref.WeakReference<ImageView> target =
                new java.lang.ref.WeakReference<>(view);
        avatarLoader().execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                try {
                    File cached = avatarCacheFile(app, obsPath);
                    if (!cached.exists() || cached.length() == 0) {
                        download(PROFILE_CDN + obsPath + PREVIEW_SUFFIX, cached);
                    }
                    Bitmap decoded = decodeDownsampled(cached.getAbsolutePath(), size);
                    if (decoded != null) {
                        bitmap = circularBitmap(decoded, size);
                    }
                } catch (Throwable t) {
                    // Keep the initial avatar.
                }
                if (bitmap == null) {
                    return;
                }
                final Bitmap result = bitmap;
                final ImageView view = target.get();
                if (view == null) {
                    return;
                }
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        ImageView current = target.get();
                        if (current != null) {
                            current.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }

    private static ExecutorService avatarLoader() {
        ExecutorService loader = avatarExecutor;
        if (loader != null) {
            return loader;
        }
        synchronized (ReadReceipts.class) {
            if (avatarExecutor == null) {
                avatarExecutor = Executors.newFixedThreadPool(2);
            }
            return avatarExecutor;
        }
    }

    /** Cache file for one OBS path, named by a digest since the path itself is ~180 chars. */
    private static File avatarCacheFile(Context context, String obsPath) throws Exception {
        File directory = new File(context.getCacheDir(), "morphe_avatars");
        directory.mkdirs();
        MessageDigest digest = MessageDigest.getInstance("MD5");
        StringBuilder name = new StringBuilder();
        for (byte b : digest.digest(obsPath.getBytes("UTF-8"))) {
            name.append(String.format("%02x", b));
        }
        return new File(directory, name + ".jpg");
    }

    /** Downloads to a temporary file and renames, so a partial read never becomes a cache entry. */
    private static void download(String url, File destination) throws Exception {
        File temporary = new File(destination.getAbsolutePath() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new java.io.IOException("HTTP " + connection.getResponseCode());
            }
            InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(temporary);
            try {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    output.write(buffer, 0, read);
                }
            } finally {
                output.close();
                input.close();
            }
        } finally {
            connection.disconnect();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
        }
    }

    private static Bitmap decodeDownsampled(String path, int target) {
        if (!new File(path).exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        int larger = Math.max(bounds.outWidth, bounds.outHeight);
        while (target > 0 && larger / sample > target * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    private static Bitmap circularBitmap(Bitmap source, int size) {
        int dimension = Math.min(source.getWidth(), source.getHeight());
        Bitmap square = Bitmap.createBitmap(source,
                (source.getWidth() - dimension) / 2, (source.getHeight() - dimension) / 2,
                dimension, dimension);
        Bitmap scaled = Bitmap.createScaledBitmap(square, size, size, true);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return output;
    }

    private static Bitmap initialAvatar(String name, int size, boolean night) {
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        float radius = size / 2f;

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(avatarColor(name, night));
        canvas.drawCircle(radius, radius, radius, circle);

        String initial = "?";
        if (name != null && !name.isEmpty()) {
            initial = name.substring(0, name.offsetByCodePoints(0, 1))
                    .toUpperCase(Locale.getDefault());
        }
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setTextSize(size * 0.42f);
        canvas.drawText(initial, radius, radius - (text.descent() + text.ascent()) / 2f, text);
        return output;
    }

    /** A stable, well-spread colour per person for the initial avatar. */
    private static int avatarColor(String seed, boolean night) {
        int hash = seed == null ? 0 : seed.hashCode();
        float[] hsv = {Math.abs(hash) % 360, night ? 0.45f : 0.55f, night ? 0.55f : 0.78f};
        return android.graphics.Color.HSVToColor(hsv);
    }

    private static String preview(String content) {
        if (content == null || content.isEmpty()) {
            return "[メディア/その他]";
        }
        String oneLine = content.replace('\n', ' ');
        return oneLine.length() > 40 ? oneLine.substring(0, 40) + "…" : oneLine;
    }

    private static String hhmm(long epochMillis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(epochMillis));
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static final class Mark {
        final String mid;
        final String name;
        final long watermark;

        Mark(String mid, String name, long watermark) {
            this.mid = mid;
            this.name = name;
            this.watermark = watermark;
        }

        String displayName() {
            return name != null && !name.isEmpty() ? name : mid;
        }
    }

    private static final class Message {
        final long serverId;
        final String content;
        final long createdTime;

        Message(long serverId, String content, long createdTime) {
            this.serverId = serverId;
            this.content = content;
            this.createdTime = createdTime;
        }
    }
}
