# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Andrew's Patches** — a **Morphe Patches** bundle (Morphe is a fork of the ReVanced patching ecosystem). It produces an `.mpp` patch bundle that the Morphe CLI / Manager applies to third-party Android APKs to rewrite their bytecode. The current focus is **LINE** (`jp.naver.line.android`). Base package/group is `app.andrewliang` (app-agnostic); per-app patches nest under `app.andrewliang.patches.<app>` (e.g. `app.andrewliang.patches.line`), and target-app compatibility lives in `app.andrewliang.patches.shared.Constants`. The developed-against version is pinned there.

## Commands

```bash
# Build the patch bundle -> patches/build/libs/patches-*.mpp
./gradlew buildAndroid

# Build, then regenerate patches-list.json from the compiled bundle
./gradlew generatePatchesList

# Compile-check without producing a release (what CI runs on non-release commits)
./gradlew :patches:buildAndroid clean --no-daemon
```

There is no test suite. Correctness is validated by applying the built `.mpp` with the Morphe CLI against a real target APK. `generatePatchesList` reflectively loads the built `.mpp` and re-emits `patches-list.json`, so it depends on `build` having run.

`settings.gradle.kts` pulls the `app.morphe.patches` Gradle plugin and patcher libraries from GitHub Packages (`maven.pkg.github.com/MorpheApp/registry`). Building requires `gpr.user`/`gpr.key` Gradle properties **or** `GITHUB_ACTOR`/`GITHUB_TOKEN` env vars with a PAT that can read those packages.

**Local build/verify without a PAT:** if the patcher/plugin artifacts are already in the Gradle cache, build fully offline with *dummy* credential values — `./gradlew :patches:buildAndroid --offline --no-daemon -Pgpr.user=dummy -Pgpr.key=dummy` (the settings plugin only needs the credentials to be non-null when nothing is fetched). Then apply with the bundled Morphe CLI (`java -jar work/morphe-desktop-*.jar patch --exclusive -e "<name>" -o work/out.apk … work/apkm-extract/base.apk`) and inspect the patched dex with **dexlib2** (no `baksmali` CLI ships — STRIP_FAST writes modified classes into a small fresh `classes.dex`). Full recipe + the LINE class/anchor map live in **`docs/line-patch-map.md`**.

## Architecture

Two Gradle modules (`settings.gradle.kts`):

- **`patches/`** — Kotlin. The patches themselves, written against the `app.morphe.patcher` API. This is where nearly all work happens.
- **`extensions/extension/`** — Java, compiled as an Android library to `extensions/extension.mpe`. Holds complex runtime logic that is injected *into* the target app.

### How a patch works

The patching model is: **fingerprint → locate method → inject smali → optionally delegate to extension code**.

1. **Fingerprint** — declaratively describes a method in the *target app* by defining class, name, access flags, return type, parameters, and a list of instruction `filters` (field access, string references, method calls, opcodes, literals). Partial/obfuscation-tolerant matching applies. Prefer anchoring on **string literals** and non-obfuscated class names, since obfuscated names (`Sg1.c`, method `b`, …) change between LINE versions. Declaring fingerprints as named objects/classes means failures name the fingerprint in the stack trace.
2. **Patch** — `bytecodePatch { ... }` with `name`/`description`/`default`. In `execute { }` it resolves the fingerprint's `method` and mutates it via extensions like `addInstructions(index, smali)`. Injected smali calls the extension with `invoke-static {}, Lapp/andrewliang/extension/...;->method()Z`.
3. **`extendWith("extensions/extension.mpe")`** — bundles the compiled extension so injected smali can call it. Simple fixed-value overrides need no extension; use extension Java only for real logic.
4. **`compatibleWith(...)`** / `dependsOn(...)` — declare target-app compatibility (`Constants.COMPATIBILITY_LINE`) and patch dependencies.

**Patch visibility:** a `bytecodePatch` with a `name` is user-facing (shown in Manager/CLI); a nameless one is an internal dependency, hidden from users but pulled in via `dependsOn`.

**Compatibility** (`app/andrewliang/patches/shared/Constants.kt`) — `Compatibility` objects declare target `packageName`, app name, `apkFileType`, icon color, and `AppTarget` version list. `version = null` means "any/latest" (often `isExperimental = true`); always pin at least one confirmed-working version.

### Runtime settings (in-app toggles)

Patches whose behaviour used to be baked in ("always hide") now ask the extension at runtime, so a
user can switch one off without re-patching. Three pieces:

- **`extensions/…/Settings.java`** — a `Key` enum (key + title + summary) over a private
  `SharedPreferences` file. `isEnabled(String)` is the entry point for injected smali. Every default
  is `true`, and an uninitialized store reports the default, so a patch that cannot reach its
  setting behaves like the old unconditional bytecode rather than silently un-patching the app.
- **`SettingsInitProvider`** — a `ContentProvider` declared in LINE's manifest. Android instantiates
  providers *before* `Application.onCreate`, which is how `Settings` gets a `Context` with **no
  bytecode hook and no fingerprint** — deliberately, since LINE's Application class is obfuscated and
  an anchor there would be one more thing to re-derive on a version bump.
- **`SettingsActivity`** — opened from the **long-press shortcut on LINE's own icon**, not from a row
  inside LINE's settings. LINE builds that list from obfuscated declarative Kotlin whose rows are
  region-dependent (`l15.c` / `px4.b0`, see `docs/line-patch-map.md`), so a row injection would break
  per version. The layout is built in code because only the extension's **dex** is merged — it cannot
  reference a resource it declares itself, so framework resources (`@android:style/…`) are the only
  ones the manifest entry may name. Three device-confirmed constraints on it:
  - **Never give it a `MAIN`/`LAUNCHER` filter.** A second launcher activity makes the *package's*
    launch intent ambiguous, and `getLaunchIntentForPackage` — what a home-screen icon resolves
    through — then lands on the system's `ResolverActivity`. Device-confirmed: tapping LINE stopped
    opening LINE, and `cmd package resolve-activity <pkg>` returned
    `android/com.android.internal.app.ResolverActivity` instead of `.activity.SplashActivity`. Note
    `pm disable-user` cannot undo this from adb (shell may not change another app's component
    state), so the only way out is reinstalling a fixed build. The activity stays `exported="true"`
    with **no** filter, which is what lets the shortcut and `adb shell am start` reach it.
  - **Append to the target's existing shortcuts resource; never repoint the meta-data.** Android
    honours exactly one `android.app.shortcuts` resource per activity, and LINE already declares one
    (`res/xml/shortcuts.xml`, holding its `share-target`). Read the reference off the launcher
    activity's meta-data and add a `<shortcut>` to that file. A shortcut's `shortcutShortLabel` must
    be a **resource reference** — a literal string is rejected and the shortcut silently vanishes —
    so the label is added to the target's `res/values/strings.xml`.
  - **`NoActionBar` + own header + own inset handling.** LINE targets a recent SDK, so the window is
    laid out edge to edge: with an action-bar theme the content frame still started at y=0 and the
    bar floated over the first row, while the last row sat under the navigation bar. Owning the
    header removes the negotiation. Apply `systemBars()` insets as padding **and call
    `requestApplyInsets()`** — insets are dispatched at attach, which can precede a listener
    registered in `onCreate`.
  - **Write settings with `commit()`, not `apply()`.** `apply()` writes on a background thread, and
    swiping LINE out of Recents kills the process before it lands — device-confirmed, the toggle
    silently reverted. Settings are written once per tap, so the blocking write costs nothing.

`morpheSettingsPatch` (nameless, `patches/…/line/settings/`) bundles the extension and the manifest
edits; every toggleable patch `dependsOn` it. Keys live twice — `Settings.Key` (Java) and
`SettingKeys` (Kotlin) — and a mismatch compiles fine and shows up as a switch that does nothing.

Three ways to read a setting, in order of preference:

1. **Push the decision into the extension** and inject a branchless `invoke-static`. Best where the
   patch already calls an extension (`HomeModules.shouldHide`), where it only sets a value
   (`AdViews.hideSelf` replaced an in-block label *and* a `.locals 0` register workaround), or where
   a value can be filtered through the extension — `Gates.premiumMarketAvailable(Z)Z` and
   `KeepUnsentMessages.applyUnsend` take what LINE computed and hand it back untouched when the
   toggle is off. **A one-argument filter needs no register at all**: the result goes back into the
   register the value came from. That is the only option at some sites — `e13.a.d()` is
   `.registers 1`, its single register is `this` and it is read before it is written — and the fix
   for any site where every register is live across the injection point (`wi1.j4.<init>`).
2. **`MutableMethod.addSettingGate`** when the patch has to skip code — an early `return`, or
   overwriting a value LINE just computed. It finds a free register, calls `isEnabled`, and binds
   the toggle-off branch to the real instruction at the injection index via `ExternalLabel`.
   Parameter registers are excluded by default so the injected block may refer to `pN`. Uses
   `invoke-static/range` so a method whose free registers are all above v15 still assembles.
   **Prefer injecting *after* the instruction you used to replace**, leaving LINE's own value in
   place and overwriting it only when the toggle is on — that is what makes `hideevents`,
   `hidepremiumunsend` and the calendar patches reversible without touching the original code.
3. **`MutableMethod.addSettingSkip`** for patches that used to `removeInstructions`. LINE appends
   navigation tabs and header buttons as a `sget-object` of the entry's enum plus an `add(...)`
   call; skipping that pair is the reversible form of deleting it. Its register rules differ from
   the gate's on purpose, and both differences are load-bearing:
   - **Search from the *resume* index, not the injection index.** The gate's write has to survive
     the jump, and what the skipped instructions do to a register says nothing about the path that
     skips them.
   - **Exclude only the block's live-in registers** (used before the block writes them), and **do
     not exclude parameter registers** — the injected block is empty, so nothing names `pN`.
     Excluding either more broadly makes the search fail on real LINE methods: `wy7.b.a` reuses the
     `this` register as the per-entry scratch in its NEWS branches, and it is the only candidate
     there.

The register search is `patches/…/util/FreeRegisterProvider.kt`, a **vendored copy** from
`morphe-patches-library` (this bundle only depends on `morphe-patcher` and `smali`). Its GPLv3
Section 7 notice block must be preserved. Two things about it, both device-relevant:

- **It carries an upstream off-by-one, fixed in our copy.** `getBranchTargetInstructionIndex`
  asserted `index > 0`, so a method whose *first* instruction is a branch died with
  "Invalid index: 0" when searching from index 0. `offsetArray[0]` is a valid address; the bound is
  `>= 0`. Re-check this when re-syncing from upstream.
- **`addSettingGate(excludeParameterRegisters = false)` is not an optimisation, it is the only
  option on `.locals 0` methods.** LINE's attach-menu availability predicates (`hg1.h.j`,
  `hg1.k.j`, `hg1.d.f`) have *no* locals — every register is a parameter — which is exactly why the
  old unconditional patches could write `const/4 p0, 0x0; return p0`. The gate still works there
  because LINE overwrites one of those parameters (`iget`/`const/4`) before ever reading it, so the
  search finds it free. Only pass `false` when the injected block names no `pN` itself.

**A free register is about the injection point, not the method's size.** `g38.b0.invoke` has
`.registers 31` and still has none at the "keep unsent" site, because that site is immediately
before a conditional branch and nothing is written-before-read on *both* paths. When that happens,
do not hunt for a scratch register — **pass the registers the site already holds to one extension
method and let it do the work** (`KeepUnsentMessages.applyUnsend(Object, Z, J)Z` takes LINE's
transaction object, the guard, and the message id, and returns the new guard). Reflecting on the
field's *type* inside the extension is obfuscation-proof where a hardcoded `iget` is not; keep the
patch-time field lookup anyway, as the proof that the register holds what you think it does.

**Verify without a device before verifying with one.** `work/DexVerify.java` (compile against the
`morphe-desktop` jar, which bundles dexlib2) has two modes: `sweep <apk|dex>` decodes every method
and checks that each branch target, try range and handler address lands on an instruction boundary —
the whole patched APK, 637k methods, in ~2 seconds — and `dump <apk|dex> <class> [method]` prints
instructions with their registers, which is how an injection site's free registers are established
in the first place. Apply with `--continue-on-error` to collect every patch failure in one run
instead of one per round.

### Patcher API notes (hard-won)

- **Finding instruction indices:** there is no `indexOfFirstInstructionOrThrow` (that's ReVanced). Use `fingerprint.instructionMatches[i].index` — one match per instruction `filter`, in program order — or `instructionMatchesOrNull` for best-effort. `.method` and `.instructionMatches` are context-receiver accessors usable inside `execute { }`.
- **Instruction filter builders** (imported from `app.morphe.patcher`): `fieldAccess`, `methodCall`, `string`, `literal`, `opcode`, `checkCast`, `instanceOf`, `newInstance`. Two *identical* filters match the first two occurrences in program order — use this to grab both sites of a repeated `sget`+`add` pair in one method (see `hidewallettab`). `fieldAccess` matches both reads and writes, so pin the ctor (parameters) when a field is also `sput` in an enum `<clinit>`.
- **Reading a matched instruction's operands:** `fingerprint.instructionMatches[i].instruction` returns the dexlib2 `Instruction`; cast it (e.g. `as TwoRegisterInstruction`) to read `.registerA`. Lets you "replace this `iget` with a `const 0` into its *own* destination register" without hardcoding the register (see `hideevents`: `removeInstruction(idx)` + `addInstructions(idx, "const/16 v$reg, 0x0")`).
- **Hiding a UI list item:** LINE lists (attach-menu tiles, chat-menu rows, context-menu actions) render each entry through a per-item availability predicate; force *that* predicate false rather than editing the (often shared or looping) list builder. To hide a **whole server-driven category**, neuter the shared renderer's gate — stable, no ids (see `hideattachmenutools` → `hg1.d.f()`); to hide **one** server item you must match an id/type that can drift server-side — fragile, avoid. Details/anchors in `docs/line-patch-map.md`.
- **Register operand limits:** `invoke-*` (format 35c) and `iget/iput` (22c) take **4-bit register operands — only v0–v15**. Referencing v16+ there is silently dropped/mis-assembled (the filter/injection appears to apply but does nothing). Use a low free register, or the `/range` instruction variants.
- **Don't inject a backward-branching loop into an existing method** — it can corrupt that method's branch layout and throw a runtime `VerifyError` ("target dex pc … not at instruction start"). Instead extract the loop into a **new** method (`mutableClassDefBy(desc).methods.add(MutableMethod(ImmutableMethod(...)))`, then `addInstructions`) and inject only a branchless `invoke-static` + `move-result` at the call site (see `hidehomemodules`).
- **Targeting a method in an obfuscated class:** fingerprint a *sibling* on a stable anchor (a non-obfuscated framework/API call or string literal), then `mutableClassDefBy(fp.method.definingClass)` and select the target method by descriptor (`returnType` + `parameterTypes`) — see `keepunread` anchoring on `TalkServiceClient.j1`.
- **Neutralising a numeric gate:** when a check is `const-wide/32 vN, <limit>` + `cmp-long` + `if-*`, rewrite the **compared literal** (`replaceInstruction(idx, "const-wide/32 v$reg, 0x7fffffff")`) rather than the branch. Control flow stays byte-identical, it's one instruction per gate, and register allocation is untouched — far safer than deleting a branch and leaving unreachable code. Read the destination register off the matched instruction (`as OneRegisterInstruction`) instead of hardcoding it. Two gates in one method = two identical `literal(...)` filters (worked example in `docs/line-patch-map.md`, outbound photo pipeline).
- **Resolving an obfuscated class from a framework-typed field read:** inside a matched method, the only `iget-object` whose `FieldReference.type` is a **framework** type (e.g. `Landroid/content/Context;`) hands you both the obfuscated owner's descriptor *and* the field name for free. Feed that descriptor back in to find the enclosing lambda's synthetic captures (`definingClass == method.definingClass`). Lets an injection reference obfuscated fields with nothing hardcoded but framework types — worked example in `docs/line-patch-map.md` (outbound photo pipeline), resolving `u13.c1`, its `Context`, and `u13.y0`'s `Uri` and `Integer`-rotation captures. **Pass the captures the stock code reads, don't re-derive their values** — that extension took `y0`'s rotation `Integer` rather than reading EXIF itself, because the sibling encoder writing the standard variant uses that same value and the two outputs have to agree.
- **Never write a label inside injected smali — use `addInstructionsWithLabels` + `ExternalLabel`.** A label declared in the block is resolved against the *block's own* addresses and is **not** rebased to where the block lands, so at any non-zero injection index the branch points into the middle of an earlier instruction and ART rejects the entire class at first use: `java.lang.VerifyError: … target dex pc 0xN is not at instruction start`. Device-confirmed: an `if-eqz` at 0x11 targeted 0xf — the block-relative address of its own trailing label. Instead bind the target to a real instruction: `val target = method.getInstruction(idx)` … `method.addInstructionsWithLabels(idx, smali, ExternalLabel("name", target))`. (`hideadviews` gets away with an in-block label *only* because it injects at index 0, where block-relative and method addresses coincide; `hidehomemodules` because it assembles a whole new method body. Neither is a pattern to copy.)
- **Verified bytecode does NOT mean the code runs — instrument the path before adding more sites.** One investigation burned four device rounds on edits that disassembled perfectly and never executed: LINE duplicates the same `>= 20 MB / >= 100 MP` decision across *five* places on two independent send paths (chatroom `th1.*` vs the media picker `t73.k0`/`m63.n0`), and the flow under test only touched one of them. When a patch applies cleanly and changes nothing on device, do **not** hunt for the next gate in the decompile — inject `Log` calls at the decision points (LINE's own methods, not just your extension) and let one run say which code executes. A probe that logs *nothing* is as informative as one that logs a value. Also log unconditionally on entry: a hook whose every branch falls through is indistinguishable from a hook that never ran.
- **A clean `buildAndroid` + a correct-looking instruction dump does NOT prove a branch is valid.** Instruction order, register operands and try-block alignment can all be right while the branch *offset* is wrong. Decode it: walk the method summing `getCodeUnits()`, then check `offset + OffsetInstruction.getCodeOffset()` is a key in the offset→index map — for **every** `OffsetInstruction`, plus each try range start/end and handler address. A whole-dex sweep of the rewritten `classes.dex` costs seconds and covers every patch at once.
- **Redirecting a bundled SDK by rewriting strings takes several passes, and each site hides the next.** Actions, target packages, account types and **explicit `ComponentName` binds** are independent mechanisms: an explicit component bind ignores every action redirect, so a flow can keep reaching the original service with all its actions rewritten. Budget one device round per site — the runtime error only names the *next* unfixed site once the previous one stops failing (worked example: `gmscoreauth` needed five string sites plus one injection, found over five rounds). Also watch namespace vs applicationId: a library can ship `applicationId app.revanced.android.gms` while keeping `namespace com.google.android.gms`, so `<service android:name=".auth.GetToken">` is the class `com.google.android.gms.auth.GetToken` *inside* the app.revanced package — rewrite only the package half of such a ComponentName, or the bind silently targets a class that does not exist (generic app-level error, nothing in logcat, because no service starts).
- **When you replace a method's body, enumerate every input the original tolerated.** The original's leniency is often implicit rather than a guard: `aq.a.b(String)` accepted `""` only because its `getAccountsByType` scan matched nothing, so a replacement that constructs `new Account(name, type)` crashes on the empty string LINE passes when a screen opens with nothing selected. Guard null *and* empty, and branch into the original body (via `ExternalLabel`) rather than reimplementing the fallback.
- **Manifest/resource edits:** `resourcePatch { … execute { document("AndroidManifest.xml").use { doc -> … } } }` — the `Document` is a standard W3C DOM.
- **Kotlin block comments NEST:** a `/*` inside a `/** … */` KDoc (e.g. writing a `line://home/*` scheme) opens a nested comment and eats the file — use `//` or reword.
- **Always verify by APPLYING**, not just building: fingerprints resolve at *apply* time (against the target APK), not build time, so a clean `buildAndroid` does **not** prove a fingerprint matches. Apply the `.mpp` with the Morphe CLI (`patch --exclusive -e "<name>"`) and disassemble the output to confirm the injected bytecode. Note the built `.mpp` filename carries the semantic-release version (e.g. `patches-1.0.0-dev.9.mpp`); wipe `patches/build/libs` before a verify run so a stale artifact isn't applied by mistake.

### Metadata generation

`util/PatchListGenerator.kt` (`main()`, run by the `generatePatchesList` task) loads the built `.mpp` via `loadPatchesFromJar`, reads the bundle version from the JAR manifest, and serializes every patch's metadata (name, description, deps, compatibility, options) to `patches-list.json`. Third-party tools consume this file — do not hand-edit it.

## Target app integrity (LINE) — what patching must respect

From decompiling LINE 26.11.0 (full detail in `work/decompiled-line-<ver>/NOTES-integrity-checks.md`, gitignored):

- The **core messenger has no enforcing** signature/integrity check — a re-signed patched APK runs fine for login/chat/general features.
- Every root/debugger/emulator/signature check in the general app is **telemetry only** (Firebase Crashlytics, obfuscated `es` package; Sentry `io.sentry.android.core`) — no `exit`/`finish`/`throw`.
- **No Play Integrity / SafetyNet** is bundled. The `attest` code is LINE's own *server-side* WebAuthn/FIDO2 and a fire-and-forget "DeviceAttestation" WorkManager job (always returns success).
- **Enforcement is confined to LINE Pay**, via the bundled native **VKey V-OS / V-Guard** engine (`libvosWrapperEx.so`; `VosWrapperBase.getAppSignerHash()` → native SHA-256 signer compare). It initializes only when entering Pay flows. The Block/Warn/Bypass decision per threat is **server-driven** (`TamperSettingsGetResDto`); on block, `VGuardDetectionActivity` ends the Pay flow — it does not kill the whole app.

**Implication for patches:** messaging patches are safe on a re-signed build. Defeating LINE Pay's protection would require neutralizing the VKey native library (out of scope). Prefer fingerprints anchored on **string literals / non-obfuscated class names** — LINE obfuscates class and method names (even `org.apache.thrift`'s), and they drift between versions.

### Re-signed builds & closed-app push notifications (FIXED in-APK by the "Fix push notifications" patch)

Re-signing (Standard install) breaks LINE's push while the app is *fully closed*, but — unlike the Google sign-in limitation below — this **is** fixable from the patch side. Patch: `patches/line/fixpushnotifications/`. Device-confirmed on a re-signed build (LINE 26.11.0).

- **Symptom (unpatched re-sign):** notifies while open/backgrounded (LINE's persistent **LEGY** socket) but not while fully closed (swiped from Recents); reopening flushes the queue. Closed, LINE's only wakeup path is **FCM**.
- **Root cause:** LINE's bundled Firebase Installations (FIS) SDK self-reports the app's signing-cert SHA-1 in the `X-Android-Cert` HTTP header (`ct.c.c(URL,String)` → `rl.a.a` = `getPackageInfo(pkg, GET_SIGNATURES)` → SHA-1 of `signatures[0]` → uppercase hex in `rl.h.b`). LINE's embedded Firebase API key is **restricted to LINE's official cert SHA-1**, so a re-signed APK's SHA-1 is rejected. Evidence: `/data/data/jp.naver.line.android/no_backup/PersistedInstallation.*.json` = `"Status":4` (REGISTER_ERROR) + `"FisError":"BAD CONFIG"` + `"TokenCreationEpochInSecs":0` → no FCM token minted, so LINE's servers have nothing to push to. Battery/App-Standby ruled out (bucket `5` EXEMPTED + deviceidle-whitelisted, still fails).
- **Why it's patchable (contrast with Google sign-in):** the cert is a **self-reported HTTP header built inside LINE's own process**, so a patch can overwrite it. (Google sign-in's cert check happens in GmsCore reading the real signature — not patchable; see below.) The patch anchors the FIS request builder on the `X-Android-Cert` string + its `addRequestProperty` call and rewrites the value register with LINE's **original** signing-cert SHA-1. Only the FIS call site is touched; the shared `rl.a`/`rl.h` helpers (also used by Remote Config) are left intact.
- **Which cert:** `base.apk` uses APK Signature Scheme **v3.1 key rotation** (two certs). `GET_SIGNATURES` returns the lineage-**root/original** cert, so the patch injects the SDK 24–32 signer `89396DC419292473972813922867E6973D6F5C50`. Fallback if `BAD CONFIG` persists on-device: the rotated SDK 33+ signer `6A2927D945AEA6571E1DA5566802F25045D367BD`. Re-derive both from the pinned APK with `apksigner verify --print-certs base.apk` when bumping the LINE version.
- **Verify:** apply the `.mpp` and disassemble `ct/c` — `const-string v$reg, "<sha1>"` must land in the `addRequestProperty` value register (`registerE`) immediately before the `X-Android-Cert` send. On-device, `PersistedInstallation*.json` `Status` should flip `4 → 3` (REGISTERED). **The on-device flip is the real proof** — a clean build/disassembly only proves the header is rewritten, not that GmsCore's own registration (which reads the real signature) also accepts it.

### Known limitation: Google account sign-in on re-signed builds (login unfixable; backup patched)

Documented as a "Known limitation" in `README.md`. Same *class* of root cause as the FCM signature break (Google-side config pinned to LINE's official cert SHA-1), but a distinct mechanism — and, **unlike FCM, not fixable in-APK**. **Device-confirmed** (LINE 26.11.0, Android 16); NOT a patch bug — no patch touches the backup/sign-in code. Full investigation, including the GmsCore route that was built and tested, in **`docs/line-patch-map.md`**:

- **Two independent paths — establish which one a symptom belongs to before touching anything.** (1) **Log in with / link a Google account** (Settings ▸ Account, registration, account restore) goes through `jl0.t` → `kl0.i` → **`androidx.credentials.CredentialManager`**. (2) **Drive chat-history backup** goes through `ix4.a0` → `AccountPicker.newChooseAccountIntent` → `GoogleAuthUtil`. Path 1 is **not patchable**; path 2 **is**, and the `gmscoreauth` patch does it. Conflating them cost a full round of wrong conclusions.
- **Symptom:** on a re-signed build, picking a Google account fails at the account-selection step on both paths, so Google login/linking and Drive-based chat history backup/restore are both unavailable.
- **Cause:** LINE stores chat history in Google **Drive** and needs an OAuth token for the `https://www.googleapis.com/auth/drive.appdata` scope. Android OAuth clients are keyed by **package name + signing-cert SHA-1**; re-signing changes the SHA-1, so no client matches and Google refuses. Device-observed error: `UNREGISTERED_ON_API_CONSOLE` → `[16] Account reauth failed` under the `Auth.Api.Credentials` tag.
- **Why path 1 can't be patched (device-confirmed):** LINE asks the platform for a credential and the *framework* service in `system_server` picks Google's provider `…credman.service.GoogleIdService`. LINE never names Play Services, so there is no package, action or string in the APK to rewrite, and MicroG-RE implements no `android.service.credentials.CredentialProviderService` so it can't be offered as a provider either. The bundled classic Google Sign-In classes (`al.g`, `SignInHubActivity`, `gm.n`) are referenced only from inside the GMS library — no LINE code uses them, so there is no legacy route to force.
- **Distinct from FCM:** FCM fails on a Firebase **API key** restriction → `REGISTER_ERROR`/"BAD CONFIG"; this fails on an **OAuth client** match → `UNREGISTERED_ON_API_CONSOLE`. Both are Google Cloud config keyed to LINE's official cert.
- **Why it can't be patched like FCM:** the FCM fix works because LINE's own bundled SDK self-reports the cert SHA-1 in an HTTP header (`ct.c.c`'s `X-Android-Cert`), which a patch can overwrite. Here LINE never names Play Services at all — it asks the platform for a credential and the platform picks the provider. There is no package, action, or cert string in LINE's APK to rewrite.
- **Path 2 is fixed by the `gmscoreauth` patch ("Fix chat backup sign-in via GmsCore", opt-in) — device-confirmed working (2026-08-12: picker, token grant, completed restore).** The backup flow needs only an account *name* (`ix4.a0`'s CHOOSE_ACCOUNT activity intent, hardcoded `setPackage` → redirectable) and a token for it (`aq.a.b`/`aq.a.a` → `GoogleAuthUtil` via `internal.auth.d` → redirectable). Authorization is device-proven: with LINE's original cert reported via the generic `app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE` meta-data (**no GmsCore fork needed**), Google returns a consent flow for `drive.appdata` instead of `UNREGISTERED_ON_API_CONSOLE`. Reaching GmsCore took **five** string sites (picker action/package/type, `aq.a` type, `vk.b.i` type, `vk.g` allow-list, and `vk.g`'s explicit bind **ComponentName**) plus one code injection (`aq.a.b` must construct the `Account` directly — `getAccountsByType` can never see a GmsCore account, since visibility is granted only after a token). The patch **must not rename the package**, and must move only the *package* half of the ComponentName (GmsCore keeps namespace `com.google.android.gms`). Requires MicroG-RE. **Do not re-derive any of this**; read the patch map first.
- **No patch-side fix for path 1:** can't register the re-sign's SHA-1 in LINE's Google Cloud project, and a substitute OAuth client wouldn't reach LINE's account anyway. Workaround is install-side: **Root Mount** install (keeps original signature → both paths work) instead of **Standard** (re-signs). Re-check on a version bump by watching `logcat | grep -iE "START u0|CredentialManager|Auth\.Api|SpoofUtils|UNREGISTERED_ON_API_CONSOLE"` at the account-selection tap; obfuscated names (`ix4.a0`, `aq.a`, `kl0.i`, `u7.*`, `l18/d`, `hx4/i`) drift.

## Feature limits — client-side vs server-enforced (LINE)

Before patching any LINE "limit", establish **who decides**. The same test that separates the FCM
fix from the Google sign-in limitation applies to ordinary features: a constraint LINE computes or
self-reports **inside its own process** is patchable; one decided by a remote server, or by another
process, is not — and removing the client-side check just moves the failure later and makes it
worse. Findings so far (full anchors in `docs/line-patch-map.md`):

| Limit | Who decides | Verdict |
|---|---|---|
| Outbound photo compression | **client** — `u13.c1` resamples + JPEG-encodes locally before upload; the server never sees the original | patchable, but **dropped**: one gate per entry point, and only the chatroom flow was coverable (see `docs/line-patch-map.md`) |
| Unsend time window | **server** — `unsendMessage` carries only `(seq, messageId)`; `MESSAGE_NOT_DESTRUCTIBLE(71)` comes back | not patchable |
| Video length / file size | **server** — client checks in `c81.b.c()` mirror an OBS ceiling (`EXCEED_FILE_MAX_SIZE`) | not usefully patchable |
| LYP premium entitlements | **server** — account state; `hidepremium` only hides the upsells | not patchable |
| Call ringtone selection | **client** — `be7.c.a` can return an arbitrary `Uri`, played by LINE's own `MediaPlayer` in-process (`xx.c`) | **patchable** — investigated, deliberately not shipped |
| Ringback tone friends hear | **server** — the callee's tone is delivered to the *caller's* client in their connect info | not patchable |
| Group read receipts (who read) | **at rest: aggregate; live: per-reader** — bulk `getMessageReadRange` + local DB (`chat_read_count_meta_data`, `ReadCountManager`) are count-only, but each live `NOTIFIED_READ_MESSAGE` op carries `param2`=reader mid, `param3`=message server_id | **implemented, device-verified, forward-only** — the `Group read checker` patch (`readreceipts/`) captures live ops, self-accumulates, and makes the 「既読 N」 count tappable to show readers; opt-in (off by default). See `docs/line-patch-map.md` |

- **Client-side config is not the same as server enforcement.** The unsend windows and photo tiers
  are *both* pushed from server settings (`function.chatroom.message.unsend.timelimit`,
  `function.media.image_high`). But the photo tier is only ever consumed locally to decide how hard
  to compress, whereas the unsend window is a UI pre-filter for a decision the server re-makes. A
  server-supplied *value* is patchable; a server-made *decision* is not.
- **Look for the error code.** A dedicated Thrift/HTTP error for the exact condition
  (`MESSAGE_NOT_DESTRUCTIBLE`, `EXCEED_FILE_MAX_SIZE`, `EXCEED_DAILY_QUOTA`) is proof the server
  enforces it independently — the client check is only there to fail fast.
- **A client check with no server counterpart is the opportunity.** Nothing validates the *encoded*
  output of LINE's image pipeline: `c1.o()` is a single-shot `Bitmap.compress` with no size check,
  and the picker's gates test the **source** file, never the result.
- **Ask which process opens the resource, not just which process decides.** The call ringtone is
  patchable because LINE's own `MediaPlayer` opens the URI inside LINE's process; the Google sign-in
  break is not, because GmsCore reads the real signature in a *different* process. Same question,
  opposite answers.
- **"Patchable" is not "worth patching."** The ringtone came out patchable and still shipped nothing:
  following the device ringtone is a feature nobody asked for, and an in-app picker needs a new
  Activity plus a settings row injected into obfuscated declarative Kotlin. Record the finding in
  `docs/line-patch-map.md` and stop there.

## Release pipeline — do not fight it

Releases are fully automated by **semantic-release** (`.releaserc`, `.github/workflows/release.yml`). This drives several rules:

- **All development happens on `dev`.** `dev` produces pre-releases; merging `dev → main` (plain merge, **not squash**) produces a stable release. A push to `dev` auto-opens the `dev → main` PR (`open_pull_request.yml`).
- **Use conventional commits.** `fix:` → patch bump, `feat:` → minor bump (both create releases and appear in the changelog); `bump:`/`perf:` also release; `chore:`/`build:` do **not** create a release. The commit type determines the version and the user-facing changelog section.
- **Never hand-edit generated files:** `patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`, `gradle.properties` (`version`), and the `<!-- PATCHES_START -->`…`<!-- PATCHES_END -->` block in `README.md` are all rewritten during release. The README patches section is generated by `.github/scripts/generate_patches_readme.py`.
- **Never manually create/upload GitHub releases**, and never force-push a semantic-release commit — either breaks the release state.
- **`release.yml` uses a draft→upload→publish flow** (semantic-release creates the GitHub release as a *draft* with no assets, a workflow step uploads the `patches-*.mpp` to it, then publishes) plus a build-provenance **attestation**. This is deliberate — it's compatible with GitHub immutable releases, which reject asset uploads to an already-published release. Don't collapse it back to a direct publish. (`open_pull_request.yml` also tolerates a compare 404 so it never hard-fails.)

## Decompiled reference & prior art

Fingerprint authoring relies on inspecting LINE's bytecode.

- **`docs/line-patch-map.md`** (tracked) — the LINE `+` attach menu architecture (static `hg1.r` tiles vs server-driven `hg1.d` services), the Calendar vs Events vs Message-scheduler feature map, the outbound photo pipeline (the `IMAGE_STANDARD` vs `IMAGE_ORIGINAL` split, the Normal/High tier map, and why the pixel budget is a *total* not a per-side cap), per-surface class/anchor references for the shipped patches, the dead ends investigated so far, and the offline build + dexlib2 disassembly recipe. Update it when bumping the pinned LINE version (the obfuscated descriptors drift).

These live outside the repo / are gitignored:

- **`work/decompiled-line-<version>/`** (gitignored) — decompiled LINE. `apktool/` has smali (what fingerprints match against) + resources; `jadx/` has readable Java for understanding logic. Regenerate with `apktool d` / `jadx` from `work/apkm-extract/base.apk`. Anchor grep across `apktool/smali*` for strings/classes.
