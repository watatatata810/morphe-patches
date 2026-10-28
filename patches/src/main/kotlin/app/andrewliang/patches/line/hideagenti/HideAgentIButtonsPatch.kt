package app.andrewliang.patches.line.hideagenti

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/andrewliang/extension/Gates;"
private const val FILTER = "$EXTENSION->agentIEntryShown(Z)Z"

/** Highest register addressable by the 4-bit operands of `invoke-*` (35c). */
private const val MAX_NIBBLE_REGISTER = 15

@Suppress("unused")
val hideAgentIButtonsPatch = bytecodePatch(
    name = "Hide Agent i buttons",
    description = "Removes the Agent i button from the Home header and from the search bar. " +
        "Can be turned off again from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    // Both buttons are filtered through Gates.agentIEntryShown(Z)Z, which hands LINE's own value
    // back while the toggle is off. A one-argument filter writes its result into the register the
    // value came from, so neither site needs a free register or a branch.
    execute {
        // Home header: the boolean the lambda branches on to draw the icon, right after its read.
        val flagRead = HomeHeaderAgentIFingerprint.instructionMatches.first()
        val flagRegister = (flagRead.instruction as OneRegisterInstruction).registerA
        if (flagRegister > MAX_NIBBLE_REGISTER) {
            throw PatchException("agenti: header flag read into v$flagRegister, above v15")
        }
        HomeHeaderAgentIFingerprint.method.addInstructions(
            flagRead.index + 1,
            """
                invoke-static {v$flagRegister}, $FILTER
                move-result v$flagRegister
            """,
        )

        // Search bar: the accessor of its server flag. Filtering the value it returns gives every
        // reader what LINE sees when the server turns the flag off: the bar hides the button and
        // moves its guideline back.
        val accessorCall = (SearchBarAiEntryFlagFingerprint.instructionMatches[1].instruction as ReferenceInstruction)
            .reference as MethodReference
        val accessor = mutableClassDefBy(accessorCall.definingClass).methods.single { method ->
            method.name == accessorCall.name && method.returnType == "Z" && method.parameterTypes.isEmpty()
        }
        val accessorInstructions = accessor.implementation!!.instructions.toList()
        val returnIndices = accessorInstructions.indices.filter {
            accessorInstructions[it].opcode == Opcode.RETURN
        }
        if (returnIndices.isEmpty()) {
            throw PatchException("agenti: no return in ${accessor.definingClass}->${accessor.name}")
        }
        // Later first, so the earlier indices stay valid as injecting shifts them.
        returnIndices.reversed().forEach { returnIndex ->
            val resultRegister = (accessorInstructions[returnIndex] as OneRegisterInstruction).registerA
            if (resultRegister > MAX_NIBBLE_REGISTER) {
                throw PatchException("agenti: search flag returned from v$resultRegister, above v15")
            }
            accessor.addInstructions(
                returnIndex,
                """
                    invoke-static {v$resultRegister}, $FILTER
                    move-result v$resultRegister
                """,
            )
        }
    }
}
