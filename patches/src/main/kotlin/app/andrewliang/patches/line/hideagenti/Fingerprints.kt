package app.andrewliang.patches.line.hideagenti

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The Compose lambda of the Home tab header (`gg2.k.i` on 26.11.0), which draws the header icons.
 * It draws the Agent i icon only if its first captured boolean (`gg2.k.a`) is true: the first
 * `iget-boolean` of the method, followed by the icon's label `access_agenti` (0x7f15006b). Nothing
 * else in the APK uses that label.
 *
 * Ported from andrewliang25/morphe-patches (`hideagenti`, written against 26.14.0), where the same
 * shape holds in `wm2.j`.
 */
internal object HomeHeaderAgentIFingerprint : Fingerprint(
    filters = listOf(
        opcode(Opcode.IGET_BOOLEAN),
        literal(0x7f15006b),
    ),
)

/**
 * The parse of the server flag `function.search.line_ai_entry.enabled` (`jw4.i2.a` on 26.11.0).
 * The key string is immediately followed by a call to the flag's accessor on the config object
 * (`jw4.h2.b()Z`), which supplies the default value. R8 renames the accessor but keeps the key, so
 * the key finds the accessor. The search bar shows its Agent i entry
 * (`main_tab_ai_entry_icon_container`) only while that accessor returns true.
 */
internal object SearchBarAiEntryFlagFingerprint : Fingerprint(
    filters = listOf(
        string("function.search.line_ai_entry.enabled"),
        methodCall(returnType = "Z", parameters = listOf(), location = MatchAfterImmediately()),
    ),
)
