package app.andrewliang.extension;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Patch settings" row appended to the end of LINE's own top-level Settings screen.
 *
 * <p>LINE builds that screen from a static {@code List} of row objects, each an instance of one of
 * its (obfuscated) row classes rendered by a shared adapter. This class builds one more of them —
 * a plain text row of the same class LINE uses for its "Melody store" entry, whose constructor takes
 * the title as a <em>string provider</em> rather than a resource id, so nothing has to be added to
 * LINE's resource table — and hands it back with the original list.
 *
 * <p>Everything obfuscated is resolved at runtime from the two {@link Class} objects the patch
 * passes in (it resolves them at apply time by fingerprint, so nothing here names a LINE class):
 *
 * <ul>
 *   <li>the row class: its single six-argument constructor is
 *       {@code (String id, Function2 title, String iconCountry, Function1 onClick, Target target,
 *       Function2 enabled)}, where the {@code FunctionN} types are Kotlin's function interfaces
 *       (renamed by LINE's obfuscator, but still interfaces, so {@link Proxy} implements them);</li>
 *   <li>the navigation-target class: the {@code (String)} constructor LINE itself uses for this
 *       row type, found by the patch at the row's own construction site.</li>
 * </ul>
 *
 * <p>The row is built once and cached: LINE re-reads the list on every refresh and keys its diffing
 * on object identity, so returning a fresh row each time would make the list churn.
 */
public final class SettingsRow {

    private SettingsRow() {}

    private static final String TAG = "AndrewSettingsRow";

    /** Row id; also the navigation-target key. Must not collide with a LINE row id. */
    static final String ROW_ID = "morphe_settings";

    /** Title drawn in the row. */
    static final String TITLE = "Patch settings";

    private static volatile Context appContext;

    private static volatile Object row;

    /** Set once a build attempt failed, so a broken build does not retry on every refresh. */
    private static volatile boolean failed;

    /** Called by {@link SettingsInitProvider}; the fallback when the tapped row has no Fragment. */
    static void setContext(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /**
     * Entry point for injected smali.
     *
     * @param rows        LINE's own row list (never modified — it is a fixed-size list).
     * @param rowClass    the row class to instantiate.
     * @param targetClass the navigation-target class with a {@code (String)} constructor.
     * @return {@code rows} plus the settings row, or {@code rows} itself if the row cannot be built.
     */
    public static List<Object> append(List<Object> rows, Class<?> rowClass, Class<?> targetClass) {
        if (rows == null) {
            return null;
        }
        Object entry = rowOrNull(rowClass, targetClass);
        if (entry == null) {
            return rows;
        }
        ArrayList<Object> out = new ArrayList<>(rows.size() + 1);
        out.addAll(rows);
        out.add(entry);
        return out;
    }

    private static Object rowOrNull(Class<?> rowClass, Class<?> targetClass) {
        Object cached = row;
        if (cached != null || failed) {
            return cached;
        }
        synchronized (SettingsRow.class) {
            if (row == null && !failed) {
                try {
                    row = build(rowClass, targetClass);
                } catch (Throwable t) {
                    failed = true;
                    Log.w(TAG, "Could not build the settings row; LINE's list is left as is.", t);
                }
            }
            return row;
        }
    }

    private static Object build(Class<?> rowClass, Class<?> targetClass) throws Exception {
        Constructor<?> rowCtor = null;
        for (Constructor<?> candidate : rowClass.getDeclaredConstructors()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 6
                    && p[0] == String.class && p[1].isInterface()
                    && p[2] == String.class && p[3].isInterface()
                    && p[4].isAssignableFrom(targetClass) && p[5].isInterface()) {
                rowCtor = candidate;
                break;
            }
        }
        if (rowCtor == null) {
            throw new NoSuchMethodException(rowClass.getName() + " has no (String, Function2, "
                    + "String, Function1, Target, Function2) constructor");
        }
        Class<?>[] p = rowCtor.getParameterTypes();
        rowCtor.setAccessible(true);

        Constructor<?> targetCtor = targetClass.getDeclaredConstructor(String.class);
        targetCtor.setAccessible(true);
        Object target = targetCtor.newInstance(ROW_ID);

        // `suspend (Context) -> CharSequence`: the title. Returning the value directly (instead of
        // the coroutine's SUSPENDED marker) is how a suspend function completes synchronously.
        Object title = function(p[1], TITLE);
        // `(Fragment) -> Unit`: the tap. The argument is the hosting settings Fragment.
        Object onClick = function(p[3], null);
        // `suspend (Context) -> Boolean`: whether the row is shown/enabled. LINE's default is TRUE.
        Object enabled = function(p[5], Boolean.TRUE);

        // The third argument selects a country-specific icon ("JP" / "TH" / "TW") and hides the
        // icon for anything else; an empty string is the cleanest "no icon".
        return rowCtor.newInstance(ROW_ID, title, "", onClick, target, enabled);
    }

    /**
     * A Kotlin function-interface implementation whose {@code invoke} returns {@code result}, or,
     * when {@code result} is null, opens the settings screen and returns null (Unit is not needed
     * by the caller, which discards the value).
     */
    private static Object function(Class<?> functionInterface, final Object result) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("invoke".equals(name)) {
                    if (result != null) {
                        return result;
                    }
                    open(args != null && args.length > 0 ? args[0] : null);
                    return null;
                }
                if ("equals".equals(name)) {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name)) {
                    return "SettingsRow." + functionInterface.getSimpleName();
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(
                functionInterface.getClassLoader(), new Class<?>[] {functionInterface}, handler);
    }

    /** Opens {@link SettingsActivity} from the tapped row's Fragment, or from the app if absent. */
    static void open(Object fragment) {
        Context context = contextOf(fragment);
        if (context == null) {
            Log.w(TAG, "No context to open the settings screen from.");
            return;
        }
        try {
            Intent intent = new Intent(context, SettingsActivity.class);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Could not open the settings screen.", t);
        }
    }

    /**
     * The Fragment's Context, by reflection: the AndroidX {@code Fragment} class is renamed in LINE
     * but its public accessors keep their names.
     */
    private static Context contextOf(Object fragment) {
        if (fragment != null) {
            for (String accessor : new String[] {"getActivity", "requireContext", "getContext"}) {
                try {
                    Object value = fragment.getClass().getMethod(accessor).invoke(fragment);
                    if (value instanceof Context) {
                        return (Context) value;
                    }
                } catch (Throwable ignored) {
                    // Try the next accessor.
                }
            }
        }
        return appContext;
    }
}
