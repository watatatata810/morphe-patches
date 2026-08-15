package app.andrewliang.patches.line.chatheaderbuttons

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingSkip
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideCommunityButtonPatch = bytecodePatch(
    name = "Hide community button",
    description = "Removes the community (OpenChat) button from the top of the Chats tab " +
        "header. Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Skip the `sget-object OPEN_CHAT` + following `add(...)` pair while the setting is on, so the
    // button is not added to the header list. instructionMatches[0] = the OPEN_CHAT sget-object;
    // the add is the immediately-following instruction.
    execute {
        val openChatSgetIndex = CommunityButtonFingerprint.instructionMatches.first().index
        CommunityButtonFingerprint.method.addSettingSkip(
            openChatSgetIndex,
            2,
            SettingKeys.HIDE_COMMUNITY_BUTTON,
        )
    }
}
