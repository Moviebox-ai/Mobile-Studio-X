package com.aaryo.mobilestudio.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ProcessResult(val exitCode: Int, val output: List<String>)

/**
 * Installs `libtagfix.so` (see [TagFixShimElf]) next to the toolchain and injects it through
 * `LD_PRELOAD` when spawning child ELF processes (`java`, `kotlinc`, `d8`, `aapt2`, `apksigner`).
 *
 * Without it, a JDK built for Android aborts with `Pointer tag for 0x... was truncated` as soon as
 * Scudo starts tagging allocations: the manifest flags that disable heap tagging
 * (`allowNativeHeapPointerTagging="false"`, `memtagMode="off"`) only apply to the Zygote-forked
 * app process, not to binaries started with `execve()`.
 *
 * The generated image is validated before it is put on the `LD_PRELOAD` path, because a shim that
 * Bionic refuses to load breaks *every* tool in the pipeline (including `java -version`). If the
 * image cannot be written or fails validation the shim is simply not used.
 */
object BionicTagFixShim {
    private const val SHIM_FILE_NAME = "libtagfix.so"

    /** Set when the on-disk shim could not be (re)generated, so we stop retrying every exec. */
    @Volatile
    private var unavailable = false

    /** Set once the shim has been written and validated, so it is not rewritten for every exec. */
    @Volatile
    private var ready = false

    /**
     * Set when this process' dynamic linker actually rejected the shim. Preloading it again would
     * fail the same way, so every later exec runs without `LD_PRELOAD`.
     */
    @Volatile
    private var rejectedByLoader = false

    /** True when the last [applyToEnvironment] call put the shim on `LD_PRELOAD`. */
    @Volatile
    private var applied = false

    /**
     * Ensures `toolchain/compat/libtagfix.so` contains the current shim and returns it, or `null`
     * when no shim should be preloaded on this device.
     */
    fun ensureShimLibrary(anchorDir: File): File? {
        if (ready) {
            return File(compatDir(anchorDir), SHIM_FILE_NAME).takeIf { it.isFile }
        }
        if (unavailable) return null
        val result = runCatching {
            val compatDir = compatDir(anchorDir)
            val soFile = File(compatDir, SHIM_FILE_NAME)
            // Rewrite whenever the file is missing, stale (older version marker), or fails the
            // structural checks that Bionic applies before it maps the library.
            val existing = runCatching { soFile.readBytes() }.getOrNull()
            if (!TagFixShimElf.isUsableShim(existing)) {
                if (compatDir.isDirectory) {
                    // Drop other leftovers from earlier shim generations; they are never preloaded,
                    // but stale copies make bug reports ambiguous.
                    compatDir.listFiles()
                        ?.filter { it.isFile && it.extension == "so" && it.name != SHIM_FILE_NAME }
                        ?.forEach { runCatching { it.delete() } }
                }
                soFile.writeBytes(TagFixShimElf.buildBytes())
                makeExecutable(soFile)
                val written = soFile.readBytes()
                check(TagFixShimElf.isUsableShim(written)) {
                    "Generated $SHIM_FILE_NAME failed its own ELF validation"
                }
            }
            soFile
        }
        if (result.isFailure) {
            // Do not hammer a broken storage path on every exec; the user can retry explicitly.
            unavailable = true
            return null
        }
        ready = true
        return result.getOrNull()
    }

    /**
     * Adds the shim to `env["LD_PRELOAD"]` when the device runs a 64-bit ARM userspace. Other ABIs
     * keep working without it, they just keep Android's default heap-tagging behaviour.
     */
    fun applyToEnvironment(workingDirectory: File, env: MutableMap<String, String>) {
        applied = false
        if (rejectedByLoader) return
        if (!isArm64Device()) return
        val shim = ensureShimLibrary(workingDirectory) ?: return
        val shimPath = shim.absolutePath
        val existing = env["LD_PRELOAD"]?.trim().orEmpty()
        val entries = existing.split(':', ' ').filter { it.isNotBlank() }
        if (shimPath !in entries) {
            env["LD_PRELOAD"] = if (existing.isEmpty()) shimPath else "$shimPath:$existing"
        }
        // Keep Scudo's own diagnostics quiet in child processes: a "deallocation type mismatch"
        // warning on stderr used to be parsed as a tool error.
        env["MALLOC_CHECK_"] = "0"
        env["SCUDO_OPTIONS"] = "ZeroContents=false:DeallocTypeMismatch=false:DeleteSizeMismatch=false"
        applied = true
    }

    /** True when the current process still has the shim enabled. */
    fun shimApplied(): Boolean = applied && !rejectedByLoader

    /**
     * Called when the linker rejected the shim. The generated file is kept, but this process stops
     * preloading it; [resetCache] re-enables it after the file has been regenerated.
     */
    fun disableForProcess() {
        rejectedByLoader = true
        applied = false
        ready = false
    }

    /**
     * Forgets any cached state. The next [applyToEnvironment] / [ensureShimLibrary] call rewrites
     * and re-validates the file, which gives the user a clean retry after fixing storage problems.
     */
    fun resetCache() {
        ready = false
        unavailable = false
        rejectedByLoader = false
        applied = false
    }

    /** True when the shim file exists on disk and validates for the current version. */
    fun isShimReady(anchorDir: File): Boolean =
        File(compatDir(anchorDir), SHIM_FILE_NAME).let { file ->
            file.isFile && TagFixShimElf.isUsableShim(runCatching { file.readBytes() }.getOrNull())
        }

    private fun compatDir(anchorDir: File): File {
        // Keep the shim outside any project directory: `toolchain/compat` under the app's files
        // dir, which is derived from whichever directory the caller happens to hold.
        val baseDir = anchorDir.canonicalFile.let { dir ->
            generateSequence(dir) { it.parentFile }
                .firstOrNull { it.name == "files" && it.parentFile?.name?.contains(".") == true }
                ?: dir
        }
        return File(baseDir, "toolchain/compat").apply { mkdirs() }
    }

    private fun makeExecutable(file: File) {
        file.setReadable(true, false)
        file.setExecutable(true, false)
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath)).waitFor()
        }
    }

    private fun isArm64Device(): Boolean = runCatching {
        android.os.Build.SUPPORTED_64_BIT_ABIS?.any { it.contains("arm64", ignoreCase = true) } == true
    }.getOrDefault(false)
}

class ToolProcessRunner {
    suspend fun run(
        command: List<String>, workingDirectory: File,
        envOverrides: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {}, timeoutMinutes: Long = 30,
        maxCapturedLines: Int = 5_000
    ): ProcessResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "Command cannot be empty." }
        require(command.none { it.indexOf('\u0000') >= 0 }) { "Command contains a NUL character." }
        require(workingDirectory.isDirectory) { "Working directory does not exist: $workingDirectory" }
        require(timeoutMinutes > 0 && maxCapturedLines > 0)
        val exe = File(command.first())
        if (exe.isFile) {
            if (!exe.canExecute()) exe.setExecutable(true, false)
            runCatching { Runtime.getRuntime().exec(arrayOf("chmod", "755", exe.absolutePath)).waitFor() }
        }

        fun startProcess(useShim: Boolean): Process {
            val builder = ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(true)
            val env = builder.environment()
            env.putAll(envOverrides)
            // Inject the Bionic heap-tagging shim for Java / child ELF binaries unless the caller
            // explicitly opted out with MO_SKIP_TAGFIX=1.
            if (env["MO_SKIP_TAGFIX"] != "1" && useShim) {
                BionicTagFixShim.applyToEnvironment(workingDirectory, env)
            } else {
                env.remove("MO_SKIP_TAGFIX")
            }
            return try {
                builder.start()
            } catch (e: java.io.IOException) {
                val msg = e.message.orEmpty()
                if (msg.contains("error=13", ignoreCase = true) || msg.contains("Permission denied", ignoreCase = true)) {
                    throw java.io.IOException(
                        "Execution permission denied on ${command.first()} (error=13). Check Android executable-file policy, app storage location, ELF architecture and file permissions. This app must keep targetSdk <= 28 (Android 10+ W^X policy blocks executing files from app storage for higher targetSdk).",
                        e
                    )
                }
                throw e
            }
        }

        // A rejected LD_PRELOAD library kills the child before main() runs (Bionic prints
        // "CANNOT LINK EXECUTABLE ..." and exits 1). That is a limitation of our shim, not of the
        // tool, so retry the command once without it instead of failing the whole build.
        var process = startProcess(useShim = true)
        val preRunLines = mutableListOf<String>()
        if (BionicTagFixShim.shimApplied()) {
            // A rejected LD_PRELOAD library makes the loader print "CANNOT LINK EXECUTABLE ..." and
            // exit before main(). That is a limitation of our shim, not of the tool, so retry the
            // command once without it. Only a linker complaint about the shim triggers the retry;
            // any other early exit is reported as-is, so no command ever runs twice.
            val early = process.waitAndDrainIfExited(timeoutMillis = 10_000)
            if (early != null) {
                val text = early.joinToString("\n")
                if (ErrorDiagnosticsEngine.isLinkerRejection(text) && text.contains("libtagfix.so", true)) {
                    val warning = "BUILD: [WARN] Android's linker rejected the heap-tagging shim; retrying without it."
                    runCatching { onLine(warning) }
                    early.forEach { runCatching { onLine(it) } }
                    preRunLines += warning
                    preRunLines += early
                    BionicTagFixShim.disableForProcess()
                    process = startProcess(useShim = false)
                } else {
                    early.forEach { runCatching { onLine(it) } }
                    return@withContext ProcessResult(process.exitValue(), capLines(early, maxCapturedLines))
                }
            }
        }
        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
        output.addAll(preRunLines)
        val readerError = AtomicReference<Throwable?>(null)
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    synchronized(output) {
                        output.add(line)
                        if (output.size > maxCapturedLines) output.removeAt(0)
                    }
                    runCatching { onLine(line) }
                } }
            } catch (t: Throwable) { readerError.set(t) }
        }, "mobilestudio-tool-output").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
        try {
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    error("Tool timed out after $timeoutMinutes minutes.")
                }
                process.waitFor(250, TimeUnit.MILLISECONDS)
            }
            reader.join(5_000)
            if (reader.isAlive) { process.inputStream.close(); reader.join(1_000) }
            readerError.get()?.let { throw IllegalStateException("Could not read tool output.", it) }
            ProcessResult(process.exitValue(), synchronized(output) { output.toList() })
        } catch (t: Throwable) {
            process.destroy()
            if (process.isAlive && !process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            reader.interrupt()
            throw t
        }
    }

    suspend fun requireSuccess(
        command: List<String>, workingDirectory: File,
        envOverrides: Map<String, String> = emptyMap(), onLine: (String) -> Unit = {},
        timeoutMinutes: Long = 30
    ): ProcessResult {
        val result = run(command, workingDirectory, envOverrides, onLine, timeoutMinutes)
        check(result.exitCode == 0) {
            val signalHint = when (result.exitCode) {
                134 -> "Process aborted (SIGABRT). Inspect JVM fatal-error logs and native library compatibility."
                137 -> "Process was killed (SIGKILL), often due to memory pressure or OS process limits."
                139 -> "Process crashed with SIGSEGV. Check native libraries and ABI compatibility."
                else -> ""
            }
            "Command failed (${result.exitCode}). $signalHint\n" +
                "Command: ${command.joinToString(" ")}\n" +
                result.output.takeLast(60).joinToString("\n")
        }
        return result
    }
}

/**
 * Waits at most [timeoutMillis] for a freshly started child process to exit and, if it did, returns
 * everything it printed. Returns `null` while the process is still running, in which case its
 * stream is left untouched for the regular output reader.
 *
 * Reading after `exit` is safe: the pipe reaches EOF, so this cannot block for longer than it takes
 * the kernel to hand over the buffered bytes.
 */
internal fun Process.waitAndDrainIfExited(timeoutMillis: Long): List<String>? {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (isAlive) {
        if (System.nanoTime() >= deadline) return null
        try {
            waitFor(100, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
    }
    return runCatching {
        inputStream.bufferedReader().useLines { lines -> lines.toList() }
    }.getOrDefault(emptyList())
}

/** Keeps the last [maxLines] entries, mirroring what the streaming reader does. */
private fun capLines(lines: List<String>, maxLines: Int): List<String> =
    if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
