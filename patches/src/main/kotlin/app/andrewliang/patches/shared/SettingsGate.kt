package app.andrewliang.patches.shared

import app.andrewliang.util.findFreeRegister
import app.andrewliang.util.registersUsed
import app.andrewliang.util.writeRegister
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

/** The extension class the injected gate calls. */
const val EXTENSION_SETTINGS = "Lapp/andrewliang/extension/Settings;"

private const val IS_ENABLED = "isEnabled(Ljava/lang/String;)Z"

/** Reads the toggle into [gateRegister]. Shared by the gate and the skip. */
private fun readSettingInto(gateRegister: Int, key: String) =
    """
        const-string v$gateRegister, "$key"
        invoke-static/range { v$gateRegister .. v$gateRegister }, $EXTENSION_SETTINGS->$IS_ENABLED
        move-result v$gateRegister
    """

/**
 * The registers holding this method's parameters, `this` included.
 *
 * Parameters occupy the highest registers, so this is the tail of the register file. A gate never
 * takes one of these: the register search only proves a register is not *read* after the injection
 * point, which is not the same as it being safe to clobber when the injected block itself refers to
 * a parameter by its `pN` name.
 */
private val MutableMethod.parameterRegisters: List<Int>
    get() {
        val registerCount = implementation?.registerCount ?: return emptyList()
        val parameterRegisterCount =
            (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
                parameterTypes.sumOf { type ->
                    // Longs and doubles occupy a register pair.
                    if (type.toString() == "J" || type.toString() == "D") 2 else 1
                }
        return (registerCount - parameterRegisterCount until registerCount).toList()
    }

/**
 * Wraps [whenEnabled] in a runtime check of the [key] toggle, injected at [index].
 *
 * This is what turns a patch that used to hard-code its behaviour into one the user can switch off
 * from the settings screen: when the toggle is on the injected block runs exactly as before, and
 * when it is off control falls through to whatever instruction was at [index], so the method
 * behaves like an unpatched build.
 *
 * [whenEnabled] receives the register the gate used. That register is dead once the branch is
 * taken, so the block is free to reuse it — which is usually enough to avoid needing a second one.
 *
 * The branch target is bound to the real instruction at [index] via [ExternalLabel]. A label
 * declared inside the injected block resolves against the block's own addresses and is not rebased
 * to where the block lands, so at any non-zero [index] it points into the middle of an earlier
 * instruction and ART rejects the whole class with a `VerifyError`.
 *
 * @param index Instruction index to inject at.
 * @param key One of [SettingKeys].
 * @param excludeParameterRegisters Whether the gate must not take a parameter register. Required
 *                                  when [whenEnabled] names a parameter (`pN`), since the register
 *                                  search only proves a register is not *read* after [index] —
 *                                  which is not the same as it being safe to clobber. Pass `false`
 *                                  when the block names no parameter: several LINE predicates are
 *                                  `.locals 0`, where a parameter overwritten before its first read
 *                                  is the only register available at all.
 * @param registersToExclude Further registers [whenEnabled] needs to survive.
 * @param whenEnabled Smali to run while the toggle is on. It must not fall out of the block — it
 *                    either returns, or continues into the original code deliberately.
 * @throws PatchException If no register is free at [index].
 */
fun MutableMethod.addSettingGate(
    index: Int,
    key: String,
    excludeParameterRegisters: Boolean = true,
    registersToExclude: List<Int> = emptyList(),
    whenEnabled: (gateRegister: Int) -> String,
) {
    val excluded = registersToExclude +
        if (excludeParameterRegisters) parameterRegisters else emptyList()

    val gateRegister = try {
        findFreeRegister(index, excluded)
    } catch (exception: IllegalArgumentException) {
        throw PatchException(
            "No free register for the \"$key\" gate at index $index of $definingClass->$name",
            exception,
        )
    }

    // The instruction currently at `index` — where control resumes when the toggle is off.
    val resumeAt = getInstruction(index)
    val offLabel = "morphe_setting_off"

    // invoke-static/range rather than the 35c form: its register operand is 16-bit, so the gate
    // works just as well in a method whose free registers are all above v15.
    addInstructionsWithLabels(
        index,
        """
            ${readSettingInto(gateRegister, key)}
            if-eqz v$gateRegister, :$offLabel
            ${whenEnabled(gateRegister)}
        """,
        ExternalLabel(offLabel, resumeAt),
    )
}

/**
 * Jumps over [instructionCount] instructions starting at [index] while the [key] toggle is on.
 *
 * This is the runtime-toggleable form of deleting those instructions. LINE builds its navigation
 * tabs and header buttons by appending each entry to a list as a `sget-object` of the entry's enum
 * constant followed by an `add(...)` call, and the patches used to remove that pair outright — which
 * cannot be undone at runtime. Skipping it instead leaves the entry out with the toggle on, and
 * executes it untouched with the toggle off.
 *
 * The register is searched for from the **resume** index, not from [index]: the gate's write has to
 * survive the jump, and what the skipped instructions do to a register says nothing about the path
 * that skips them. Registers the skipped block reads *without writing them first* are excluded on
 * top of that, since those are the ones it needs intact when the toggle is off. A register the block
 * writes before reading — LINE's per-entry scratch register, in the tab-list pattern — is fair game,
 * and is usually the only candidate available.
 *
 * Parameter registers are *not* excluded, unlike [addSettingGate]. That exclusion exists because an
 * injected block may name a parameter as `pN`, and this one injects no block at all — so a parameter
 * register the liveness search proves dead is as usable as any local. It regularly is the only
 * candidate: LINE reuses the `this` register as the per-entry scratch in some tab-list branches.
 *
 * @param index First instruction to skip.
 * @param instructionCount How many instructions to skip.
 * @param key One of [SettingKeys].
 * @throws PatchException If no register is free, or the skip runs off the method.
 */
fun MutableMethod.addSettingSkip(
    index: Int,
    instructionCount: Int,
    key: String,
) {
    require(instructionCount > 0) { "instructionCount must be positive: $instructionCount" }

    val resumeIndex = index + instructionCount
    if (resumeIndex >= instructions.count()) {
        throw PatchException(
            "Skipping $instructionCount from $index runs off the end of $definingClass->$name",
        )
    }

    // Registers the skipped block needs on entry: used before the block writes them.
    val written = mutableSetOf<Int>()
    val liveIn = mutableSetOf<Int>()
    for (i in index until resumeIndex) {
        val instruction = getInstruction(i)
        val destination = instruction.writeRegister
        instruction.registersUsed
            .filter { it != destination && it !in written }
            .forEach(liveIn::add)
        destination?.let(written::add)
    }

    val gateRegister = try {
        findFreeRegister(resumeIndex, liveIn.toList())
    } catch (exception: IllegalArgumentException) {
        throw PatchException(
            "No free register for the \"$key\" skip at index $index of $definingClass->$name",
            exception,
        )
    }

    val resumeAt = getInstruction(resumeIndex)
    val skipLabel = "morphe_setting_skip"

    addInstructionsWithLabels(
        index,
        """
            ${readSettingInto(gateRegister, key)}
            if-nez v$gateRegister, :$skipLabel
        """,
        ExternalLabel(skipLabel, resumeAt),
    )
}
