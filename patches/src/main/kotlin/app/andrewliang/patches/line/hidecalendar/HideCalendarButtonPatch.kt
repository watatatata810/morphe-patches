package app.andrewliang.patches.line.hidecalendar

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.andrewliang.patches.shared.addSettingSkip
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val hideCalendarButtonPatch = bytecodePatch(
    name = "Hide calendar buttons",
    description = "Removes every LINE Calendar button inside the messenger: the one in the " +
        "Chats-tab header, and the four inside a chat room — the top toolbar, the + attach menu, " +
        "the slide-out chat menu, and the message long-press menu. Can be turned off again from " +
        "the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    execute {
        // 1. Chats-tab header: skip the `sget-object CALENDAR` + following list `add(...)` pair
        //    so the header button is not added. instructionMatches[0] = the CALENDAR sget.
        val calendarSgetIndex = CalendarButtonFingerprint.instructionMatches.first().index
        CalendarButtonFingerprint.method.addSettingSkip(
            calendarSgetIndex,
            2,
            SettingKeys.HIDE_CALENDAR,
        )

        // 2. Chat-room "+" attach menu: the CalendarButtonType (hg1.b) is shown only if its
        //    availability predicate `j(gi1.b)Z` returns true (the attach-menu filter hg1.r.f
        //    gates on it). Neuter that predicate to false; the item is then dropped by the
        //    existing filter in gg1.e. Anchor via the constructor (the sole reader of the
        //    fg1.a$b.CALENDAR enum constant), then select `j` by its unique descriptor.
        val attachMenuCalendarClass =
            mutableClassDefBy(AttachMenuCalendarButtonFingerprint.method.definingClass)
        val availabilityMethod = attachMenuCalendarClass.methods.first { method ->
            method.returnType == "Z" &&
                method.parameterTypes.map { it.toString() } == listOf("Lgi1/b;")
        }
        //    `j` is `.locals 0`, so the gate has to take the parameter LINE overwrites before
        //    reading it; the injected block names no parameter, so that is safe here.
        availabilityMethod.addSettingGate(
            0,
            SettingKeys.HIDE_CALENDAR,
            excludeParameterRegisters = false,
        ) { gateRegister ->
            """
                const/4 v$gateRegister, 0x0
                return v$gateRegister
            """
        }

        // 3. Chat-room top toolbar: ed1.d0.a adds the button at two chat-type branches, each a
        //    `sget-object CALENDAR_BUTTON` + following `ed1.s1.g(...)` add call. Skip both
        //    pairs. instructionMatches[0] = earlier site, [1] = later; gate the higher index
        //    first, since injecting shifts every index after it.
        val toolbarMatches = ChatRoomToolbarCalendarButtonFingerprint.instructionMatches
        ChatRoomToolbarCalendarButtonFingerprint.method.apply {
            addSettingSkip(toolbarMatches[1].index, 2, SettingKeys.HIDE_CALENDAR)
            addSettingSkip(toolbarMatches[0].index, 2, SettingKeys.HIDE_CALENDAR)
        }

        // 4. Slide-out chat menu: the calendar row (d00.o) forwards its first ctor bool as the
        //    row's isVisible field (d00.a.e); the menu builder only renders rows whose e is true.
        //    Zero that bool at method entry (p1 is the first param) so the row is filtered out.
        //    The block names p1, so the gate must not take a parameter register.
        ChatMenuCalendarRowFingerprint.method.addSettingGate(
            0,
            SettingKeys.HIDE_CALENDAR,
        ) { "const/4 p1, 0x0" }

        // 5. Message long-press menu: the calendar provider ne1.y0$c.a(...) returns a j51.c action
        //    or null (null = hide). Return null while the setting is on. .locals 3 -> a local is
        //    free without touching the parameters.
        ContextMenuCalendarProviderFingerprint.method.addSettingGate(
            0,
            SettingKeys.HIDE_CALENDAR,
        ) { gateRegister ->
            """
                const/4 v$gateRegister, 0x0
                return-object v$gateRegister
            """
        }
    }
}
