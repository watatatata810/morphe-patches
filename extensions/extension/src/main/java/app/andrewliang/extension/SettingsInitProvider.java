package app.andrewliang.extension;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Gives {@link Settings} a {@link android.content.Context} without hooking any LINE code.
 *
 * <p>Android instantiates every declared {@link ContentProvider} and calls {@link #onCreate()}
 * <i>before</i> {@code Application.onCreate}, which is the same mechanism AndroidX App Startup and
 * the Firebase SDKs use to self-initialize. That matters here: the alternative — fingerprinting
 * LINE's Application subclass and injecting an {@code invoke-static} — would add one more
 * obfuscation-sensitive anchor that breaks on a version bump, while a provider entry in the
 * manifest depends on nothing inside LINE.
 *
 * <p>The provider is declared by the "Morphe settings" patch and is never exported; it exists only
 * for its {@link #onCreate()} side effect and answers no queries.
 */
public final class SettingsInitProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Settings.initialize(getContext());
        // The group read checker needs an app Context to reach LINE's databases from inside the op
        // handler, which has none to pass. Captured here for the same reason Settings is: a provider
        // runs before Application.onCreate with no LINE anchor to break on a version bump.
        ReadReceipts.setContext(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
