# On-device APK compiler — how it works and why builds failed

## TL;DR (Hinglish)

Pehle "Build APK" button ek nakli APK banata tha: manifest aur resources bina compile kiye,
`resources.arsc` haath se likha hua, `classes.dex` me user ka code hi nahi tha, aur V1 signature
ke digest galat the. Isliye build "success" dikhta tha par APK install hi nahi hota tha
(`INSTALL_PARSE_FAILED_*`). Ab compiler asli Android build tools chalata hai — AAPT2, kotlinc/javac,
D8, zipalign aur apksigner — aur agar koi tool missing ho to build saaf-saaf error deta hai
(jaise "AAPT2 missing") uske bajaye ki toota hua APK de de.

## Pipeline

`DirectCompilerEngine` (`app/src/main/java/com/aaryo/mobilestudio/toolchain/DirectCompilerEngine.kt`)
runs seven stages, all on the device:

| # | Stage | Real tool | Output |
|---|-------|-----------|--------|
| 1 | Config | manifest + `app/build.gradle.kts` parsing | applicationId, minSdk, targetSdk, versionCode/Name |
| 2 | Resources | `aapt2 compile --dir res` + `aapt2 link` | binary `AndroidManifest.xml`, `resources.arsc`, compiled `res/`, generated `R.java` |
| 3 | Sources | `javac` (JDK 17) and `kotlinc` (K2 JVM) | `.class` files |
| 4 | DEX | `d8.jar` (`com.android.tools.r8.D8`) | `classes.dex` (+ `classes2.dex` when needed) |
| 5 | Package | `ZipOutputStream` | APK = linked resources + dex + `assets/` + `lib/<abi>/` |
| 6 | Align | `zipalign -p 4` | `resources.arsc` stored and 4-byte aligned |
| 7 | Sign | `apksigner sign` | v1 + v2 + v3 signatures, verified with `apksigner verify` |

Additional correctness fixes:

* `JarV1Signer` / `JarV1Verifier` (new) implement the JAR signature scheme to spec:
  per-entry `SHA-256-Digest` in `MANIFEST.MF`, `SHA-256-Digest-Manifest` plus per-section digests in
  `CERT.SF` (digested over the exact manifest section bytes), 72-byte line folding, and PKCS#7
  `SignedData` over `CERT.SF`. Verified locally with `keytool -printcert -jarfile`.
* `ApkSigningEngine.verifyApkSignature` no longer reports "signed" for any file when `apksigner` is
  absent; it falls back to a real V1 verification.
* `StandaloneAndroidTools.linkResources` no longer hard-codes `--min-sdk-version 29`; the project's
  `minSdk` / `targetSdk` / `versionCode` / `versionName` are propagated, and `--java <dir>` can
  generate `R.java`.
* The Kotlin standard library is fed to D8 as program input, so Kotlin APKs do not crash with
  `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`.

## Requirements (Toolchain screen → “Download & install”)

| Component | Why |
|-----------|-----|
| OpenJDK 17 (ARM64) | `java` (runs kotlinc/D8/apksigner) and `javac` |
| Android SDK Build Tools 34.0.4 | `aapt2`, `lib/d8.jar`, `apksigner`, `zipalign` |
| Android SDK Platform 36 | `android.jar` compile + D8 library |
| Kotlin Compiler 2.2.10 (new component) | `toolchain/kotlin/lib/kotlin-compiler.jar` + `kotlin-stdlib.jar` |

Missing components are listed one by one in the build log, together with the exact paths.

## Gradle engine

The Gradle path had its own blocker: generated projects requested **AGP 8.2.2 + Kotlin 1.9.22**,
which cannot run on the bundled **Gradle 9.3.1**. Templates now generate:

```kotlin
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
```

with `compileSdk = 36`, `targetSdk = 36`, `buildToolsVersion = "34.0.4"` (the revision the toolchain
ships), no `org.jetbrains.kotlin.android` plugin (AGP 9 has built-in Kotlin), no `kotlinOptions` and
no `composeOptions`. Release builds are signed with the debug key so `assembleRelease` also produces
an installable APK.

`GradleBuildEngine` now accepts any Gradle **≥ 9.3.1** (previously it hard-failed unless the version
string was exactly `9.3.1`), fails early with a clear message when AAPT2 is missing (AGP would
otherwise try to run the x86_64 Linux AAPT2 and crash), and does not assume `projectDir.parentFile`
is non-null.

## Which engine is used?

* **Direct** — no Maven dependency resolution. Compiles against `android.jar`, the Kotlin standard
  library and any jars in `app/libs`. Ideal for small apps and for devices where Gradle cannot run.
* **Gradle** — required as soon as the module declares library dependencies (Compose, AndroidX,
  Material…). `IdeViewModel` detects that and automatically routes the build to Gradle when the
  Gradle toolchain is installed, telling the user why.

## Diagnostics

`ErrorDiagnosticsEngine` understands the failure modes that used to be opaque:

* AGP/Gradle/Kotlin-plugin version mismatch (`GRADLE_COMPATIBILITY`).
* “Failed to find Build Tools revision X” — tells you to install it or pin `buildToolsVersion`.
* Incomplete on-device toolchain — lists the missing tools and points at the Toolchain screen.

## The `LD_PRELOAD` shim (`libtagfix.so`) and the W^X rejection

Every child process the app starts (`java`, `javac`, `kotlinc`, `d8`, `aapt2`, `apksigner`) is
launched with `LD_PRELOAD=toolchain/compat/libtagfix.so`. That tiny shared object calls
`mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)` from its constructor, before
`main()` / `libjvm.so`, because the manifest attributes that disable heap tagging only apply to the
Zygote-forked app process, not to binaries started with `execve()`. Without it the JDK aborts with
`Pointer tag for 0x... was truncated` (exit 134).

The library is generated on device by `TagFixShimElf` (`toolchain/TagFixShimElf.kt`), because the
app cannot ship or run clang. Bionic's linker is strict, and the previous 4 KB image got these things wrong, each of which
broke *every* tool:

| Problem | Symptom | Fix |
|---------|---------|-----|
| one `PT_LOAD` with `PF_R\|PF_W\|PF_X` | `"libtagfix.so" has load segments that are both writable and executable` | split into `R+X` (code, phdrs, `.dynamic`) and `RW` (`.init_array`, `.got`) |
| `p_align = 0x1000` | rejected on 16 KiB-page kernels once app-compat is off | `p_align = 0x4000` |
| v1 built with `R_AARCH64_COPY` (1024) and no `DT_INIT_ARRAY` | GOT never filled, constructor never called | `R_AARCH64_GLOB_DAT` (1025) + `DT_INIT_ARRAY`/`DT_INIT_ARRAYSZ` |
| `mallopt` imported as a strong symbol | hard link failure if a device lacks the export | `STB_WEAK` + `cbz` guard, so the constructor no-ops instead |

`TagFixShimElf.isUsableShim()` re-checks a cached file on disk (size, ELF header, section header
table, `e_shstrndx`, no W+X segment, `.comment` version marker). Anything older or malformed is
rewritten before it can reach `LD_PRELOAD`, which is what repairs devices that already stored the
broken v1 file.

If Bionic still rejects the shim, `ToolProcessRunner` notices the loader's
`CANNOT LINK EXECUTABLE` message, stops preloading it for the rest of the process and retries the
command once *without* `LD_PRELOAD` — so the build continues (just without the heap-tagging fix)
instead of failing with "the JDK cannot run". "Full toolchain verification" regenerates the file and
re-runs `java -version`, and `ErrorDiagnosticsEngine` reports this case as a shim/loader problem
instead of suggesting a JDK reinstall.

## Why a JDK install can still fail verification (exit 139 / 134 / 137)

`Tools ▸ Toolchain ▸ Download & install` probes the extracted runtime with
`java -XX:ErrorFile=<cache>/jdk-probe/hs_err_pid%p.log -version` **twice**: once with the heap-tagging
shim on `LD_PRELOAD` and, if that fails, once without it. The second run exists because the shim is
only useful for this device's JVM; if the loader rejects it, blaming the download was wrong.

Only when both runs fail is the archive itself declared unusable, and the message now says what
actually happened instead of always suggesting a different download:

| Exit | Meaning | Message / action |
|------|---------|------------------|
| 139 | `SIGSEGV` inside the JVM or a `.so` | crash log (`hs_err_pid*.log`) is read and attached; try a different JDK build |
| 134 | `SIGABRT` (allocator, missing symbol) | same, with the fatal log |
| 137 | `SIGKILL`, usually out of memory | "free RAM and retry", **not** a download problem |
| linker rejection | `CANNOT LINK EXECUTABLE ...` naming the shim | points at Full toolchain verification, not at the archive |

If the crash log shows `Problematic frame: C [libjvm.so+...]`, the archive's native libraries do not
match this device (ABI, page size, or a missing dependency) — no amount of re-downloading the same
URL fixes that; a different Android ARM64 JDK 17 build is required.

## Troubleshooting: "Mobile Studio X isn't responding" / stuck on "Gradle bootstrap"

Those two symptoms came from the app itself, and both are fixed:

* **Frozen UI.** Build orchestration, the log pane and the startup toolchain inventory ran on the
  main thread, and every single line of Gradle output published a new StateFlow value (one
  recomposition per line). Builds now run on `Dispatchers.IO`, startup inventory is off the main
  thread, and log lines are queued and flushed in batches (~every 120 ms, last 500 lines).
* **Endless JVM retries.** The Gradle engine used to try five JVM flag sets, two minutes each,
  ending in interpreter-only (`-Xint`) mode — up to ten minutes of doing nothing. Now it runs at
  most three sets (default → tagging-safe → conservative 512 MB/no-tiered-JIT), 60 s each with a
  180 s budget, skips straight to the working set on later builds (cached in
  `toolchain/.gradle-jvm-flags`) and stops immediately when the JVM itself aborts.

If the log shows `Pointer tag for 0x... was truncated`, the device's Java runtime cannot run the
JVM: that is a heap-tagging incompatibility, not a project error. What to do:

1. Tools ▸ Toolchain ▸ **Download & install** to reinstall the bundled ARM64 JDK 17.
2. If the device offers *Developer options ▸ Memory Tagging Extension / heap tagging*, disable it
   and reinstall the app.
3. Build the same project on a PC with Android Studio — the generated Gradle files are standard.

The Toolchain card also tells a **broken** JDK apart from a **missing** one: `Installed, but the
startup test failed` plus the raw reason, instead of the old, misleading "Missing".

## Tests

`app/src/test/java/com/aaryo/mobilestudio/MobileStudioUnitTest.kt` covers:

* the toolchain catalog (8 components incl. the Kotlin compiler, all with SHA-256);
* the generated `libtagfix.so`: valid ELF64 header, no writable+executable `PT_LOAD`, 16 KiB
  `p_align`, `.dynamic` matching `PT_DYNAMIC`, the twelve `init_tagfix` instructions, `GLOB_DAT`
  relocation, stale/corrupt images being rejected, and the loader rejection being diagnosed as a
  shim bug rather than a JDK problem;
* generated projects using AGP 9.1.1 / built-in Kotlin / build-tools 34.0.4;
* the direct compiler failing with an actionable message (and producing **no** APK) when tools are
  missing;
* `JarV1Signer` producing a signature that verifies, and a tampered entry being detected.
