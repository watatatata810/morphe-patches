package app.andrewliang.patches.line.disablevoom

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val disableVoomPatch = bytecodePatch(
    name = "Disable VOOM",
    description = "Neutralizes VOOM entry points — deep links, shares, and notifications do " +
        "nothing and the standalone VOOM feed closes on open. Messaging and other tabs are " +
        "unaffected. Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    execute {
        // 1. Scheme handler for line://home/* : return the existing no-op "not handled"
        //    singleton at entry, so every VOOM deep link / share / notification does nothing.
        VoomSchemeHandlerFingerprint.method.addSettingGate(
            0,
            SettingKeys.DISABLE_VOOM,
        ) { gateRegister ->
            """
                sget-object v$gateRegister, Lv98/i;->b:Lv98/i${'$'}a;
                return-object v$gateRegister
            """
        }

        // 2. Standalone VOOM feed (notification center bypasses the router): finish after
        //    super.onCreate so it never renders. Finishing before super would throw
        //    SuperNotCalledException, which is why the gate sits after the super call rather
        //    than at method entry.
        val afterSuperIndex = LineVoomActivityOnCreateFingerprint.instructionMatches.first().index + 1
        LineVoomActivityOnCreateFingerprint.method.addSettingGate(
            afterSuperIndex,
            SettingKeys.DISABLE_VOOM,
        ) {
            """
                invoke-virtual/range {p0 .. p0}, Landroid/app/Activity;->finish()V
                return-void
            """
        }
    }
}
