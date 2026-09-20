package app.andrewliang.patches.line.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The static initializer of LINE's top-level Settings page definition (`j25.u2` on 26.11.0).
 *
 * `com.linecorp.line.settings.main.LineUserMainSettingsFragment` (not obfuscated) renders a page
 * object whose `<clinit>` builds every row of the main Settings list into one static `List` and
 * whose sole parameterless `()List` accessor returns it. The rows are declarative Kotlin, and the
 * function *references* they hold keep their source names as string literals — LINE's obfuscator
 * renames the methods but not the `kotlin.jvm.internal.o` reflection strings — which is what this
 * anchors on. Both names are unique to this one initializer in the APK.
 *
 * The patch reads the page class off this match, then selects the list accessor by shape (the only
 * non-static `()Ljava/util/List;` method, a bare `sget-object` + `return-object`).
 */
internal object MainSettingsPageFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("shouldShowAccountCenter"),
        string("openChatAndFriendSettings"),
    ),
)

/**
 * The bind method of the view holder that draws LINE's plain string-titled settings row
 * (`ux4.y0.K0(px4.b0)` on 26.11.0, rendering `px4.j0`).
 *
 * That row type is what LINE uses for its "Melody store" entry: its constructor takes the title as
 * a `suspend (Context) -> CharSequence` provider instead of a string resource id, and a country
 * code that selects an icon for `JP` / `TH` / `TW` and hides it otherwise — exactly the row a patch
 * can build without adding anything to LINE's resource table. The binder is the one place the
 * country switch is written out against `Locale.TAIWAN` / `"TH"` / `"JP"` next to an
 * `ImageView.setImageResource`, and it opens with a `check-cast` to the row class, which is how
 * the patch resolves that class without naming it.
 */
internal object StringRowBinderFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.CHECK_CAST),
        fieldAccess(definingClass = "Ljava/util/Locale;", name = "TAIWAN"),
        methodCall(definingClass = "Ljava/util/Locale;", name = "getCountry"),
        string("TH"),
        string("JP"),
        methodCall(definingClass = "Landroid/widget/ImageView;", name = "setImageResource"),
    ),
)
