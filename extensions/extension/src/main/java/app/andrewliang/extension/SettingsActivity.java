package app.andrewliang.extension;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The on/off screen for every runtime-toggleable patch.
 *
 * <p>Declared as a second launcher entry by the "Morphe settings" patch, so it is reachable from
 * the home screen without touching LINE's own settings UI. LINE builds its settings list from
 * obfuscated declarative Kotlin (see {@code docs/line-patch-map.md}) whose rows differ by region,
 * so injecting a row there would add a per-version breakage point; a launcher entry depends on
 * nothing inside LINE.
 *
 * <p>The layout is built in code on purpose: only the extension's <b>dex</b> is merged into the
 * target APK, so this class cannot reference any resource it declares itself.
 */
public final class SettingsActivity extends Activity {

    private static final int COLOR_BACKGROUND = Color.parseColor("#FFFFFF");
    private static final int COLOR_TITLE = Color.parseColor("#1A1A1A");
    private static final int COLOR_SUMMARY = Color.parseColor("#5F6368");
    private static final int COLOR_DIVIDER = Color.parseColor("#E0E0E0");

    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        setTitle("Andrew's Patches");

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(COLOR_BACKGROUND);

        content.addView(buildHeader());

        String category = null;
        for (Settings.Key key : Settings.Key.values()) {
            if (!key.category.equals(category)) {
                category = key.category;
                content.addView(buildDivider());
                content.addView(buildCategory(category));
            }
            content.addView(buildDivider());
            content.addView(buildRow(key));
        }

        content.addView(buildDivider());
        content.addView(buildFooter());

        // Without this the first Switch takes initial focus and the ScrollView jumps to it,
        // hiding the header and the first row on open.
        content.setFocusableInTouchMode(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        applySystemBarInsets(scroll);

        setContentView(scroll);
    }

    /**
     * Keeps the content clear of the status and navigation bars.
     *
     * <p>The window is laid out edge to edge, so without this the first row sits behind the status
     * bar and the last behind the navigation bar — both device-confirmed, the view tree reporting
     * bounds from y=0 to the full display height.
     *
     * <p>{@code requestApplyInsets} matters: insets are dispatched once when the view is attached,
     * which can happen before a listener registered in {@code onCreate} would ever be called.
     */
    private void applySystemBarInsets(final ViewGroup view) {
        view.setClipToPadding(false);
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View target, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }

                target.setPadding(left, top, right, bottom);
                return insets;
            }
        });
        view.requestApplyInsets();
    }

    /**
     * Title and explanation, drawn as content rather than in an action bar.
     *
     * <p>The activity runs with a NoActionBar theme on purpose: LINE targets a recent SDK, so the
     * window is laid out edge to edge, and an action bar then floats over content that starts at
     * y=0 — device-confirmed, with the first row hidden behind it. Owning the header means one
     * view tree and one place to apply the system bar insets.
     */
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("Andrew's Patches");
        title.setTextColor(COLOR_TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Turn individual patches on or off. Everything is on by default.");
        subtitle.setTextColor(COLOR_SUMMARY);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle);

        return header;
    }

    private View buildFooter() {
        TextView footer = new TextView(this);
        footer.setText("Some changes only take effect after LINE is fully closed and reopened, "
                + "because the screens they affect are built once at startup.");
        footer.setTextColor(COLOR_SUMMARY);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setPadding(dp(20), dp(16), dp(20), dp(28));
        return footer;
    }

    /** Section heading. The list is long enough that a flat run of rows is hard to scan. */
    private View buildCategory(String title) {
        TextView category = new TextView(this);
        category.setText(title);
        category.setTextColor(COLOR_SUMMARY);
        category.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        category.setAllCaps(true);
        category.setLetterSpacing(0.08f);
        category.setTypeface(category.getTypeface(), android.graphics.Typeface.BOLD);
        category.setPadding(dp(20), dp(18), dp(20), dp(6));
        return category;
    }

    private View buildDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_DIVIDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return divider;
    }

    private View buildRow(final Settings.Key setting) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(16), dp(14));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(setting.title);
        title.setTextColor(COLOR_TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        labels.addView(title);

        TextView summary = new TextView(this);
        summary.setText(setting.summary);
        summary.setTextColor(COLOR_SUMMARY);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        summary.setPadding(0, dp(2), 0, 0);
        labels.addView(summary);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(labels, labelParams);

        final Switch toggle = new Switch(this);
        toggle.setChecked(Settings.isEnabled(setting));
        // Set the listener after the initial state so restoring it does not count as a change.
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean isChecked) {
                Settings.setEnabled(setting.key, isChecked);
            }
        });
        row.addView(toggle);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle.toggle();
            }
        });

        return row;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }
}
