package app.andrewliang.patches.line.settings

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val EXTENSION_PACKAGE = "app.andrewliang.extension"
private const val SETTINGS_ACTIVITY = "$EXTENSION_PACKAGE.SettingsActivity"
private const val INIT_PROVIDER = "$EXTENSION_PACKAGE.SettingsInitProvider"

private const val SHORTCUTS_META_DATA = "android.app.shortcuts"

/** Shown on the long-press shortcut of LINE's own home-screen icon. */
private const val SHORTCUT_LABEL = "Patch settings"

private const val SHORTCUT_ID = "morphe_settings"
private const val SHORTCUT_STRING = "morphe_settings_shortcut_label"

/** Resource reference used only when the target has no shortcuts resource of its own. */
private const val OWN_SHORTCUTS_REFERENCE = "@xml/morphe_shortcuts"

/** The element children of this node. */
private fun Node.childElements() =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

private fun Node.hasChild(tag: String, name: String) =
    childElements().any { it.tagName == tag && it.getAttribute("android:name") == name }

/** Whether this activity is the one the launcher shows an icon for. */
private fun Element.declaresLauncherEntry() =
    childElements()
        .filter { it.tagName == "intent-filter" }
        .any { filter ->
            filter.hasChild("action", "android.intent.action.MAIN") &&
                filter.hasChild("category", "android.intent.category.LAUNCHER")
        }

/** `@xml/shortcuts` -> `res/xml/shortcuts.xml`. */
private fun resourcePathOf(reference: String) = "res/" + reference.removePrefix("@") + ".xml"

/**
 * Manifest and resource half: the settings screen, the provider that initializes it, and the
 * long-press shortcut that opens it.
 *
 * Only the extension's *dex* is merged into the target APK — its own manifest is discarded — so
 * every component has to be declared against LINE's manifest here.
 */
private val morpheSettingsManifestPatch = resourcePatch {
    execute {
        // Read off the manifest rather than hardcoded, so the provider authority and the shortcut
        // intent stay correct if this is ever pointed at another target.
        lateinit var packageName: String
        lateinit var shortcutsPath: String

        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            val application = document.getElementsByTagName("application").item(0)
            packageName = manifest.getAttribute("package")

            // The settings screen. Reached from the long-press shortcut on LINE's own icon, which
            // needs no LINE anchor at all, and — through the separate, named `Settings row in LINE
            // settings` patch — from a row appended to LINE's own Settings list. That row does
            // depend on LINE anchors (see docs/line-patch-map.md), which is why it is not part of
            // this mandatory dependency: if it breaks on a version bump, only that patch fails.
            //
            // It must NOT carry a MAIN/LAUNCHER filter. A second launcher activity makes the
            // package's launch intent ambiguous, and `getLaunchIntentForPackage` — which is what a
            // home-screen icon resolves through — then lands on the system's ResolverActivity
            // instead of LINE. Device-confirmed: tapping LINE stopped opening LINE.
            //
            // `exported` stays true with no filter: it is what lets the launcher shortcut and
            // `adb shell am start` reach the screen, while nothing advertises it as an entry point.
            if (!application.hasChild("activity", SETTINGS_ACTIVITY)) {
                application.appendChild(
                    document.createElement("activity").apply {
                        setAttribute("android:name", SETTINGS_ACTIVITY)
                        setAttribute("android:label", SHORTCUT_LABEL)
                        setAttribute("android:icon", "@android:drawable/ic_menu_preferences")
                        // NoActionBar: the window is edge to edge on recent SDKs, where an action
                        // bar floats over content that starts at y=0. The activity draws its own
                        // header instead and applies the system bar insets itself.
                        setAttribute(
                            "android:theme",
                            "@android:style/Theme.DeviceDefault.Light.NoActionBar",
                        )
                        setAttribute("android:exported", "true")
                    },
                )
            }

            // Static shortcuts hang off the activity the launcher already shows, and Android honours
            // exactly ONE shortcuts resource per activity. LINE already declares one, so the
            // shortcut is appended to that file rather than a new one being pointed at — pointing
            // the meta-data elsewhere would drop LINE's own `share-target` with it.
            val launcherActivity = application.childElements()
                .firstOrNull { it.tagName == "activity" && it.declaresLauncherEntry() }
                ?: throw PatchException("No MAIN/LAUNCHER activity found in the manifest")

            val declaredShortcuts = launcherActivity.childElements().firstOrNull {
                it.tagName == "meta-data" && it.getAttribute("android:name") == SHORTCUTS_META_DATA
            }

            shortcutsPath = if (declaredShortcuts != null) {
                resourcePathOf(declaredShortcuts.getAttribute("android:resource"))
            } else {
                launcherActivity.appendChild(
                    document.createElement("meta-data").apply {
                        setAttribute("android:name", SHORTCUTS_META_DATA)
                        setAttribute("android:resource", OWN_SHORTCUTS_REFERENCE)
                    },
                )
                resourcePathOf(OWN_SHORTCUTS_REFERENCE)
            }

            // Android instantiates providers before Application.onCreate, which is how the settings
            // get a Context without fingerprinting LINE's Application subclass. The authority is
            // derived from the target package so it stays unique on the device.
            if (!application.hasChild("provider", INIT_PROVIDER)) {
                application.appendChild(
                    document.createElement("provider").apply {
                        setAttribute("android:name", INIT_PROVIDER)
                        setAttribute("android:authorities", "$packageName.morphe.settings")
                        setAttribute("android:exported", "false")
                        setAttribute("android:initOrder", "100")
                    },
                )
            }
        }

        // A shortcut label must be a resource reference — a literal string is rejected and the
        // shortcut silently disappears — so the label goes into the target's string table.
        document("res/values/strings.xml").use { document ->
            val resources = document.getElementsByTagName("resources").item(0)

            val alreadyAdded = resources.childElements()
                .any { it.tagName == "string" && it.getAttribute("name") == SHORTCUT_STRING }

            if (!alreadyAdded) {
                resources.appendChild(
                    document.createElement("string").apply {
                        setAttribute("name", SHORTCUT_STRING)
                        textContent = SHORTCUT_LABEL
                    },
                )
            }
        }

        // Create the shortcuts resource if the target had none, then append ours to it.
        get(shortcutsPath).apply {
            if (!exists()) {
                parentFile?.mkdirs()
                writeText(
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <shortcuts xmlns:android="http://schemas.android.com/apk/res/android" />
                    """.trimIndent(),
                )
            }
        }

        document(shortcutsPath).use { document ->
            val shortcuts = document.getElementsByTagName("shortcuts").item(0)
                ?: throw PatchException("$shortcutsPath has no <shortcuts> root")

            val alreadyAdded = shortcuts.childElements().any {
                it.tagName == "shortcut" && it.getAttribute("android:shortcutId") == SHORTCUT_ID
            }

            if (!alreadyAdded) {
                shortcuts.appendChild(
                    document.createElement("shortcut").apply {
                        setAttribute("android:shortcutId", SHORTCUT_ID)
                        setAttribute("android:enabled", "true")
                        setAttribute("android:icon", "@android:drawable/ic_menu_preferences")
                        setAttribute("android:shortcutShortLabel", "@string/$SHORTCUT_STRING")

                        appendChild(
                            document.createElement("intent").apply {
                                setAttribute("android:action", "android.intent.action.VIEW")
                                setAttribute("android:targetPackage", packageName)
                                setAttribute("android:targetClass", SETTINGS_ACTIVITY)
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * Mandatory dependency of every patch whose behaviour can be toggled at runtime: it bundles the
 * extension the injected gates call into, and adds the settings screen that writes what they read.
 *
 * Nameless, so it stays an internal dependency instead of appearing as a separate entry in the
 * Manager list — selecting any toggleable patch pulls it in.
 */
val morpheSettingsPatch = bytecodePatch(
    description = "Adds the settings screen the runtime-toggleable patches read their state from.",
) {
    extendWith("extensions/extension.mpe")

    dependsOn(morpheSettingsManifestPatch)
}
