package app.andrewliang.patches.line.hidepremiumunsend

import app.andrewliang.patches.line.settings.morpheSettingsPatch
import app.andrewliang.patches.shared.Constants.COMPATIBILITY_LINE
import app.andrewliang.patches.shared.SettingKeys
import app.andrewliang.patches.shared.addSettingGate
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val GATES = "Lapp/andrewliang/extension/Gates;"

/** Highest register addressable by the 4-bit operands of `invoke-*` (35c). */
private const val MAX_NIBBLE_REGISTER = 15

@Suppress("unused")
val hidePremiumUnsendPatch = bytecodePatch(
    name = "Hide premium unsend upsells",
    description = "Removes the LYP premium-unsend upsells that survive \"Disable LINE " +
        "Premium\": the \"Unsend discreetly\" button, the post-unsend promo link, and the " +
        "expired-window unsend upsell. Ordinary unsend still works. Can be turned off again " +
        "from the LINE Patches settings screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LINE)

    dependsOn(morpheSettingsPatch)

    execute {
        // --- 1) The premium "Unsend discreetly" affordance in UnsendMessageLdsDialog.onViewCreated.
        // The affordance is three views shown only for the UnsendSilently variant: the green button
        // r3(), plus the icon n3() and label o3() inside/beside it. We (a) force the guarding
        // `instance-of …$a$c` false so n3()/o3() take the hide branch, and (b) hide the green button
        // r3() itself. r3() exists ONLY in the silent dialog — NormalUnsendMessageLdsDialog.r3()
        // returns null and onViewCreated wires r3() under an `if (r3() != null)` guard — so hiding it
        // can't affect the ordinary dialog, which keeps its Unsend/Close buttons (p3()/m3()).
        val dialogMethod = UnsendDiscreetlyButtonFingerprint.method
        val instructions = dialogMethod.implementation!!.instructions.toList()

        // (b) first (higher index than the instance-of, so it doesn't shift that match's index):
        // r3() is the Button-returning no-arg call whose result is null-checked (invoke ->
        // move-result-object -> if-eqz). m3()/p3() are used without a null check. Hide it inside the
        // non-null branch. v0 is a safe scratch: the next original instruction overwrites it.
        val r3CallIndex = instructions.indices.firstOrNull { index ->
            val insn = instructions[index]
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ref?.returnType == "Landroid/widget/Button;" &&
                ref.parameterTypes.isEmpty() &&
                instructions.getOrNull(index + 2)?.opcode == Opcode.IF_EQZ
        } ?: throw PatchException("UnsendMessageLdsDialog.onViewCreated: r3() null-check not found")
        val buttonReg = (instructions[r3CallIndex + 1] as OneRegisterInstruction).registerA
        // invoke-virtual (35c) takes 4-bit register operands; v16+ there is silently mis-assembled,
        // so the hide would appear to apply and do nothing. Fail loudly instead.
        if (buttonReg > MAX_NIBBLE_REGISTER) {
            throw PatchException(
                "UnsendMessageLdsDialog.onViewCreated: r3() result spilled to v$buttonReg",
            )
        }
        dialogMethod.addSettingGate(
            r3CallIndex + 3,
            SettingKeys.HIDE_PREMIUM_UNSEND,
            registersToExclude = listOf(buttonReg),
        ) { gateRegister ->
            """
                const/16 v$gateRegister, 0x8
                invoke-virtual {v$buttonReg, v$gateRegister}, Landroid/view/View;->setVisibility(I)V
            """
        }

        // (a) force the discreet-variant `instance-of` false -> hides icon n3() + label o3().
        // Injected after the instance-of rather than replacing it, so the register keeps LINE's
        // own answer while the setting is off.
        UnsendDiscreetlyButtonFingerprint.instructionMatches.first().let { instanceOfMatch ->
            val reg = (instanceOfMatch.instruction as TwoRegisterInstruction).registerA
            dialogMethod.addSettingGate(
                instanceOfMatch.index + 1,
                SettingKeys.HIDE_PREMIUM_UNSEND,
                registersToExclude = listOf(reg),
            ) { "const/16 v$reg, 0x0" }
        }

        // --- 2) "How to unsend discreetly" promo link (wi1.j4 constructor) ---
        // The link handler is created only when `k2.a(i1.W() && i1.X(), …) == SUPPORTED_CHAT`.
        // Force k2.a's first argument (the W()&&X() result) to 0 so it returns non-SUPPORTED and
        // the link stays null. k2.a is called exactly once in this class; obfuscated `Lne1/k2;`
        // drifts between versions (re-verify on version bump).
        val promoClass = mutableClassDefBy(UnsendPromoLinkFingerprint.method.definingClass)
        var promoPatched = false
        promoClass.methods.forEach forEachMethod@{ method ->
            val methodInstructions = method.implementation?.instructions?.toList() ?: return@forEachMethod
            val callIndex = methodInstructions.indexOfFirst { instruction ->
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "Lne1/k2;" && ref.name == "a"
            }
            if (callIndex < 0) return@forEachMethod
            val firstArgReg = (methodInstructions[callIndex] as FiveRegisterInstruction).registerC
            if (firstArgReg > MAX_NIBBLE_REGISTER) {
                throw PatchException("unsend promo-link first argument spilled to v$firstArgReg")
            }
            // Every register is live across this call site, so there is none for a branch. Filter
            // the eligibility flag through the extension instead — the result goes back into the
            // argument register, needing no register of its own.
            method.addInstructions(
                callIndex,
                """
                    invoke-static {v$firstArgReg}, $GATES->premiumUnsendPromoEligible(Z)Z
                    move-result v$firstArgReg
                """,
            )
            promoPatched = true
        }
        if (!promoPatched) throw PatchException("unsend promo-link k2.a call not found in ${promoClass.type}")

        // --- 3) Remove the "Unsend" menu item for messages past the FREE unsend window ---
        // ne1.y0$y.a adds the long-press "Unsend" item only if `sentTime + window >= now`. For
        // premium-eligible chats `window` is the premium window (~7d, Lj51/a;->p), so the item
        // survives for old messages and tapping it triggers the "Give yourself more time" upsell in
        // oe1.c0.a. Rewrite that read to the FREE window (Lj51/a;->o, ~1h) so the gate uses the free
        // window for all chats: the item then disappears past ~1h exactly like it already does past
        // 7d — no item, no upsell. Within ~1h, unsend still works (the item is still added and its
        // tap path is unchanged).
        // Injected after the premium-window read rather than replacing it, so the gate can hand the
        // premium window back untouched while the setting is off.
        UnsendMenuAgeGateFingerprint.instructionMatches.first().let { premiumWindowRead ->
            val read = premiumWindowRead.instruction as TwoRegisterInstruction
            UnsendMenuAgeGateFingerprint.method.addSettingGate(
                premiumWindowRead.index + 1,
                SettingKeys.HIDE_PREMIUM_UNSEND,
                registersToExclude = listOf(read.registerA, read.registerB),
            ) { "iget v${read.registerA}, v${read.registerB}, Lj51/a;->o:I" }
        }
    }
}
