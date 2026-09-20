package app.andrewliang.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chat, temporary override of the "Keep chats unread" toggle, driven by a chip on the chat
 * screen.
 *
 * <p>The global toggle (see {@link Settings.Key#KEEP_CHATS_UNREAD}) is a blunt instrument: while it
 * is on, no chat ever reads. In a lively conversation the user may want this one chat to read as
 * normal for a while, without leaving the room to flip the global setting and without forgetting to
 * flip it back. So:
 *
 * <ul>
 *   <li>{@link #shouldSkip} is what the injected gate at the head of LINE's open-on-view read
 *       wrapper ({@code q33.e.b(chatId, messageId)} on 26.11.0) asks instead of the global toggle.
 *       A chat with an override uses it; any other chat uses the global value. While a read is
 *       suppressed, the wrapper instance and the message id it was asked to read are remembered, so
 *       the read can be replayed later.</li>
 *   <li>{@link #attach} puts a chip on the chat screen — only while the global toggle is on, which
 *       is the only case in which there is something to temporarily undo. Tapping it flips the
 *       override for the current chat; flipping to "read" immediately replays the last suppressed
 *       read through LINE's own wrapper, which does the local read and sends the receipt exactly as
 *       an unpatched build would. This mirrors what LINE itself does after the lock screen goes
 *       away: it queues the ids it could not read and calls the same wrapper for them.</li>
 *   <li>The override is dropped when the chat is closed ({@link #onChatDestroyed}, injected into
 *       {@code ChatHistoryActivity.onDestroy}) and whenever the whole app goes to the background
 *       (tracked with {@link Application.ActivityLifecycleCallbacks}, so in-app navigation such as
 *       opening a photo does not count). It lives only in memory: a killed process starts over from
 *       the global setting, and nothing can be "forgotten on".</li>
 * </ul>
 */
public final class UnreadOverride {

    private UnreadOverride() {}

    private static final String TAG = "AndrewUnreadOverride";

    /** View tag of the chip, so a resumed activity can find and refresh its own. */
    private static final String CHIP_TAG = "app.andrewliang.extension.unread_chip";

    /** chatId -> whether reads are allowed in that chat right now (overriding the global toggle). */
    private static final Map<String, Boolean> readsAllowed = new ConcurrentHashMap<>();

    /** chatId -> the wrapper instance and message id of the last read this class suppressed. */
    private static final Map<String, Suppressed> suppressed = new ConcurrentHashMap<>();

    private static final class Suppressed {
        final WeakReference<Object> readManager;
        final String messageId;

        Suppressed(Object readManager, String messageId) {
            this.readManager = new WeakReference<>(readManager);
            this.messageId = messageId;
        }
    }

    private static volatile boolean lifecycleTracked;

    /** Number of LINE activities currently started; zero means the app is in the background. */
    private static int startedActivities;

    // ------------------------------------------------------------------ setup

    /** Called by {@link SettingsInitProvider}; registers the app-background tracking. */
    static void setContext(Context context) {
        if (context == null || lifecycleTracked) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            if (!(app instanceof Application)) {
                return;
            }
            ((Application) app).registerActivityLifecycleCallbacks(new LifecycleWatcher());
            lifecycleTracked = true;
        } catch (Throwable t) {
            Log.w(TAG, "Could not track the app lifecycle; overrides reset only on chat close.", t);
        }
    }

    private static final class LifecycleWatcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityStarted(Activity activity) {
            synchronized (UnreadOverride.class) {
                startedActivities++;
            }
        }

        @Override
        public void onActivityResumed(Activity activity) {
            // The override may have been reset while this chat was in the background.
            refreshChip(activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            boolean background;
            synchronized (UnreadOverride.class) {
                startedActivities = Math.max(0, startedActivities - 1);
                background = startedActivities == 0;
            }
            if (background) {
                clearAll();
            }
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

        @Override
        public void onActivityPaused(Activity activity) {}

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

        @Override
        public void onActivityDestroyed(Activity activity) {}
    }

    // ------------------------------------------------------------------ the gate

    /**
     * Entry point for injected smali, at the head of the open-on-view read wrapper.
     *
     * @param readManager the wrapper's {@code this}.
     * @param chatId      the chat about to be marked read.
     * @param messageId   the newest message to mark read up to.
     * @return {@code true} to skip the read (the chat stays unread and no receipt is sent).
     */
    public static boolean shouldSkip(Object readManager, String chatId, String messageId) {
        boolean skip;
        try {
            Boolean allowed = chatId == null ? null : readsAllowed.get(chatId);
            skip = allowed != null ? !allowed : Settings.isEnabled(Settings.Key.KEEP_CHATS_UNREAD.key);
            if (skip && chatId != null && readManager != null) {
                suppressed.put(chatId, new Suppressed(readManager, messageId));
            }
        } catch (Throwable t) {
            // Fail closed: keep the chat unread, matching the global default.
            skip = true;
        }
        return skip;
    }

    /**
     * Replays the last read suppressed for {@code chatId} through LINE's own wrapper.
     *
     * <p>The wrapper is found by shape — the unique {@code (String, String) -> void} method on the
     * remembered instance's class — which is the same rule the patch used to gate it.
     */
    private static void flush(String chatId) {
        Suppressed entry = suppressed.remove(chatId);
        if (entry == null) {
            return;
        }
        Object manager = entry.readManager.get();
        if (manager == null || entry.messageId == null) {
            return;
        }
        try {
            Method wrapper = null;
            for (Method candidate : manager.getClass().getDeclaredMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if (candidate.getReturnType() == void.class && p.length == 2
                        && p[0] == String.class && p[1] == String.class) {
                    if (wrapper != null) {
                        Log.w(TAG, "More than one (String, String) method; not replaying the read.");
                        return;
                    }
                    wrapper = candidate;
                }
            }
            if (wrapper == null) {
                return;
            }
            wrapper.setAccessible(true);
            wrapper.invoke(manager, chatId, entry.messageId);
        } catch (Throwable t) {
            Log.w(TAG, "Could not replay the read for the chat.", t);
        }
    }

    // ------------------------------------------------------------------ reset

    /** Injected at the head of {@code ChatHistoryActivity.onDestroy}. */
    public static void onChatDestroyed(Activity activity) {
        // One chat screen at a time in practice; dropping everything is the safe reading of
        // "back to the settings value once the room is closed".
        clearAll();
    }

    private static void clearAll() {
        readsAllowed.clear();
    }

    // ------------------------------------------------------------------ the chip

    /** Injected at the head of {@code ChatHistoryActivity.onCreate}; adds the chip once laid out. */
    public static void attach(final Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    addChip(activity);
                }
            });
        } catch (Throwable t) {
            // No chip; LINE is otherwise untouched.
        }
    }

    private static void addChip(final Activity activity) {
        try {
            if (!Settings.isEnabled(Settings.Key.KEEP_CHATS_UNREAD.key)) {
                return; // Nothing to temporarily undo.
            }
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) {
                return;
            }
            FrameLayout root = (FrameLayout) content;
            if (root.findViewWithTag(CHIP_TAG) != null) {
                return;
            }

            final TextView chip = new TextView(activity);
            chip.setTag(CHIP_TAG);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
            chip.setGravity(Gravity.CENTER);
            chip.setElevation(dp(activity, 4));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle(activity, chip);
                }
            });

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            params.setMargins(0, chipTop(activity), dp(activity, 12), 0);
            root.addView(chip, params);
            refreshChip(activity);
        } catch (Throwable t) {
            Log.w(TAG, "Could not add the chip.", t);
        }
    }

    /**
     * Top margin that puts the chip just below LINE's header: the message list's top edge, which is
     * laid out by the time this runs, plus a small gap. Falls back to a fixed offset if the list is
     * not there.
     */
    private static int chipTop(Activity activity) {
        try {
            View list = activity.findViewById(ReadReceipts.ID_MESSAGE_LIST);
            View content = activity.findViewById(android.R.id.content);
            if (list != null && content != null && list.getHeight() > 0) {
                int[] listXy = new int[2];
                int[] rootXy = new int[2];
                list.getLocationInWindow(listXy);
                content.getLocationInWindow(rootXy);
                return listXy[1] - rootXy[1] + dp(activity, 8);
            }
        } catch (Throwable ignored) {
            // Fall through to the fixed offset.
        }
        return dp(activity, 96);
    }

    private static void toggle(Activity activity, TextView chip) {
        try {
            String chatId = ReadReceipts.currentChat();
            if (chatId == null) {
                return;
            }
            boolean allowed = !effectiveReadsAllowed(chatId);
            readsAllowed.put(chatId, allowed);
            if (allowed) {
                flush(chatId);
            }
            refreshChip(activity);
        } catch (Throwable t) {
            Log.w(TAG, "Could not toggle the override.", t);
        }
    }

    /** Whether reads currently happen in {@code chatId}, override or global. */
    private static boolean effectiveReadsAllowed(String chatId) {
        Boolean allowed = chatId == null ? null : readsAllowed.get(chatId);
        return allowed != null ? allowed : !Settings.isEnabled(Settings.Key.KEEP_CHATS_UNREAD.key);
    }

    private static void refreshChip(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            View found = content.findViewWithTag(CHIP_TAG);
            if (!(found instanceof TextView)) {
                return;
            }
            TextView chip = (TextView) found;
            boolean reads = effectiveReadsAllowed(ReadReceipts.currentChat());
            boolean night = (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

            chip.setText(reads ? "Read mode" : "Unread mode");
            int background = reads ? 0xFF06C755 : (night ? 0xFF3A3A3A : 0xFF5F6368);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(background);
            shape.setCornerRadius(dp(activity, 14));
            chip.setBackground(shape);
            chip.setTextColor(Color.WHITE);
        } catch (Throwable ignored) {
            // Leave the chip as it was.
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }
}
