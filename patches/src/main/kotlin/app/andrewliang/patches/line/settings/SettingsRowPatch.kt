package app.andrewliang.patches.line.settings

import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION = "Lapp/andrewliang/extension/SettingsRow;"
private const val EXTENSION_APPEND =
    "$EXTENSION->append(Ljava/util/List;Ljava/lang/Class;Ljava/lang/Class;)Ljava/util/List;"

/** Name of the helper added to the page class; anything LINE's obfuscator would never emit. */
private const val ROWS_HELPER = "morpheSettingsRows"

/**
 * Appends a "Patch settings" row to the end of LINE's own top-level Settings screen, opening the
 * same screen the icon long-press shortcut does.
 *
 * LINE's main Settings list is one static `List` of row objects (see [MainSettingsPageFingerprint]),
 * returned by a bare accessor. The patch replaces that accessor's body with a call to a new static
 * helper on the same class which hands the list, the row class and the navigation-target class to
 * the extension; the extension builds one more row (cached) and returns a copy of the list with it
 * appended. Nothing in the injected bytecode names an obfuscated LINE class: the row class comes
 * from [StringRowBinderFingerprint], and the target class from the row's own construction site.
 *
 * Why not build the row in smali: the row constructor takes Kotlin function objects (title
 * provider, tap handler, enabled predicate). Implementing those interfaces needs classes; a
 * `java.lang.reflect.Proxy` over the interface types the constructor declares does the same job
 * with nothing hardcoded, and the extension is where that logic belongs.
 */
@Suppress("unused")
val settingsRowPatch = bytecodePatch(
    name = "Settings row in LINE settings",
    description = "Adds a \"Patch settings\" row at the end of LINE's own Settings screen, " +
        "opening the LINE Patches settings. The icon long-press shortcut keeps working too.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    extendWith("extensions/extension.mpe")

    dependsOn(morpheSettingsPatch)

    execute {
        // region the page class and its list accessor

        val pageClass = mutableClassDefBy(MainSettingsPageFingerprint.method.definingClass)

        val listAccessor = pageClass.methods.singleOrNull { method ->
            method.parameterTypes.isEmpty() &&
                method.returnType == "Ljava/util/List;" &&
                !AccessFlags.STATIC.isSet(method.accessFlags)
        } ?: throw PatchException("settingsrow: no unique ()List accessor on ${pageClass.type}")

        val accessorInstructions = listAccessor.implementation!!.instructions.toList()
        if (accessorInstructions.size != 2 ||
            accessorInstructions[0].opcode != Opcode.SGET_OBJECT ||
            accessorInstructions[1].opcode != Opcode.RETURN_OBJECT
        ) {
            throw PatchException(
                "settingsrow: ${pageClass.type} list accessor is no longer `sget-object; return`",
            )
        }
        val listField = (accessorInstructions[0] as ReferenceInstruction).reference as FieldReference
        val listRegister = (accessorInstructions[0] as OneRegisterInstruction).registerA

        // endregion

        // region the row class and the navigation-target class LINE pairs with it

        val rowClass = (
            (StringRowBinderFingerprint.instructionMatches[0].instruction as ReferenceInstruction)
                .reference as TypeReference
            ).type

        val rowCtor = classDefBy(rowClass).methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.size == 6
        } ?: throw PatchException("settingsrow: $rowClass has no unique 6-argument constructor")
        val targetBase = rowCtor.parameterTypes[4].toString()

        // LINE constructs this row type in two places on 26.11.0: the ringtone settings page, which
        // `new-instance`s the navigation target right there, and the VoIP settings builder, which
        // receives one built elsewhere. The subclass to use is the one a site constructs itself:
        // read the `new-instance` that fills the constructor's 5th argument register. Keying on
        // the register rather than proximity keeps it honest if a version interleaves the
        // argument setup differently.
        var targetClass: String? = null
        classDefForEach { classDef ->
            if (targetClass != null) return@classDefForEach
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                for ((index, instruction) in instructions.withIndex()) {
                    if (instruction !is RegisterRangeInstruction) continue
                    val reference = (instruction as ReferenceInstruction).reference as? MethodReference
                        ?: continue
                    if (reference.definingClass != rowClass || reference.name != "<init>" ||
                        reference.parameterTypes.size != 6
                    ) {
                        continue
                    }
                    // `invoke-direct/range {vN .. vN+6}`: vN is `this`, the 5th argument is vN+5.
                    val targetRegister = instruction.startRegister + 5
                    val constructed = (index - 1 downTo 0).asSequence()
                        .map { instructions[it] }
                        .filter { it.opcode == Opcode.NEW_INSTANCE }
                        .filter { (it as OneRegisterInstruction).registerA == targetRegister }
                        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                        .firstOrNull { classDefByOrNull(it)?.superclass == targetBase }
                    if (constructed != null) {
                        targetClass = constructed
                        return@classDefForEach
                    }
                }
            }
        }
        targetClass ?: throw PatchException(
            "settingsrow: no call site of $rowClass.<init> constructs a $targetBase subclass",
        )

        // endregion

        // region inject

        // A new static helper rather than growing the accessor: the accessor has one register and
        // the call needs three. Same pattern as hidehomemodules / gmscoreauth.
        val helper = MutableMethod(
            ImmutableMethod(
                pageClass.type,
                ROWS_HELPER,
                emptyList(),
                "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ),
        )
        pageClass.methods.add(helper)
        helper.addInstructions(
            0,
            """
                sget-object v0, ${listField.definingClass}->${listField.name}:${listField.type}
                const-class v1, $rowClass
                const-class v2, $targetClass
                invoke-static {v0, v1, v2}, $EXTENSION_APPEND
                move-result-object v0
                return-object v0
            """,
        )

        // `sget-object vN, e` -> `invoke-static {}, helper; move-result-object vN`; the
        // `return-object vN` that follows is untouched.
        listAccessor.removeInstruction(0)
        listAccessor.addInstructions(
            0,
            """
                invoke-static {}, ${pageClass.type}->$ROWS_HELPER()Ljava/util/List;
                move-result-object v$listRegister
            """,
        )

        // endregion
    }
}
