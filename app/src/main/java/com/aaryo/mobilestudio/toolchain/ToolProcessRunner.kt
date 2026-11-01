package com.aaryo.mobilestudio.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ProcessResult(val exitCode: Int, val output: List<String>)

/**
 * Generates a minimal AArch64 shared library (`libtagfix.so`) on-device and injects it via
 * `LD_PRELOAD` when spawning child ELF processes (`java`, etc.).
 *
 * Why this is needed:
 * AndroidManifest attributes (`allowNativeHeapPointerTagging="false"`, `memtagMode="off"`) only
 * apply to the Zygote-forked app process. When `ProcessBuilder` calls `execve()` on a standalone
 * binary like `runtime/bin/java`, Bionic's linker re-enables Tagged Pointers (TBI) in Scudo.
 * Unpatched OpenJDK 17 builds strip the top byte (bits 56..63) of native pointers, causing
 * Bionic `free()`/`realloc()` to abort with:
 *   `Pointer tag for 0x... was truncated` (SIGABRT / exit 134).
 *
 * The preloaded constructor calls `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0)` (`-204, 0`)
 * during `.init_array` before `main()` / `libjvm.so` runs.
 */
object BionicTagFixShim {
    private const val SHIM_FILE_NAME = "libtagfix.so"

    @Volatile
    private var cachedShim: File? = null

    fun ensureShimLibrary(anchorDir: File): File? = runCatching {
        cachedShim?.takeIf { it.isFile && it.length() == 4096L }?.let { return it }

        val baseDir = anchorDir.canonicalFile.let { dir ->
            generateSequence(dir) { it.parentFile }
                .firstOrNull { it.name == "files" && it.parentFile?.name?.contains(".") == true }
                ?: dir
        }
        val compatDir = File(baseDir, "toolchain/compat").apply { mkdirs() }
        val soFile = File(compatDir, SHIM_FILE_NAME)

        val existingBytes = runCatching { soFile.readBytes() }.getOrNull()
        // If file is missing or old invalid ELF (where e_shstrndx at offset 62 is 0), regenerate
        val needsRewrite = existingBytes == null || existingBytes.size != 4096 ||
            ByteBuffer.wrap(existingBytes).order(ByteOrder.LITTLE_ENDIAN).getShort(62).toInt() == 0

        if (needsRewrite) {
            soFile.writeBytes(buildArm64TagFixElf())
            soFile.setReadable(true, false)
            soFile.setExecutable(true, false)
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", soFile.absolutePath)).waitFor()
            }
        }
        cachedShim = soFile
        soFile
    }.getOrNull()

    fun applyToEnvironment(workingDirectory: File, env: MutableMap<String, String>) {
        val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull().orEmpty()
        if (!abi.contains("arm64")) return

        val shim = ensureShimLibrary(workingDirectory) ?: return
        val shimPath = shim.absolutePath
        val existing = env["LD_PRELOAD"]?.trim().orEmpty()
        val entries = existing.split(':', ' ').filter { it.isNotBlank() }
        if (shimPath !in entries) {
            env["LD_PRELOAD"] = if (existing.isEmpty()) shimPath else "$shimPath:$existing"
        }
        env["MALLOC_CHECK_"] = "0"
        env["SCUDO_OPTIONS"] = "ZeroContents=false:DeallocTypeMismatch=false:DeleteSizeMismatch=false"
    }

    /**
     * Constructs a valid 4096-byte 64-bit ARM (`EM_AARCH64`) ELF `ET_DYN` shared object that:
     * 1. Declares `DT_NEEDED` = `libc.so`
     * 2. Imports `mallopt` via `.rela.plt` / `.got.plt`
     * 3. Registers `init_tagfix` in `DT_INIT_ARRAY` (`.init_array`)
     * 4. Executes `mallopt(-204, 0)` (`M_BIONIC_SET_HEAP_TAGGING_LEVEL`, `M_HEAP_TAGGING_LEVEL_NONE`)
     * 5. Includes a strictly valid Section Header Table (`e_shoff`, `e_shnum`, `e_shentsize`, `e_shstrndx = 1`)
     *    with matching `SHT_DYNAMIC` and `SHT_STRTAB` entries, fully conforming to Android Bionic's
     *    dynamic linker verification (`linker_phdr.cpp`) to prevent `invalid e_shstrndx` fatal aborts.
     */
    fun buildArm64TagFixElf(): ByteArray {
        val buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

        // String table (.dynstr) at 0x200
        val dynstrOffset = 0x200
        val dynstrBytes = "\u0000libc.so\u0000mallopt\u0000libtagfix.so\u0000".toByteArray(Charsets.US_ASCII)
        buf.position(dynstrOffset)
        buf.put(dynstrBytes)

        // Symbol table (.dynsym) at 0x240 (2 entries * 24 bytes = 48 bytes)
        val dynsymOffset = 0x240
        // Entry 0: STN_UNDEF (24 zero bytes)
        // Entry 1: mallopt (GLOBAL FUNC UND)
        buf.position(dynsymOffset + 24)
        buf.putInt(0x09) // st_name = "mallopt"
        buf.put(0x12.toByte()) // st_info = STB_GLOBAL (1) << 4 | STT_FUNC (2)
        buf.put(0.toByte()) // st_other
        buf.putShort(0.toShort()) // st_shndx = SHN_UNDEF
        buf.putLong(0L) // st_value
        buf.putLong(0L) // st_size

        // SysV Hash table (.hash) at 0x280 (nbucket=1, nchain=2)
        val hashOffset = 0x280
        buf.position(hashOffset)
        buf.putInt(1) // nbucket
        buf.putInt(2) // nchain
        buf.putInt(1) // bucket[0] -> sym 1
        buf.putInt(0) // chain[0]
        buf.putInt(0) // chain[1]

        // Relocation table (.rela.plt) at 0x2A0 (1 entry * 24 bytes)
        // Targets GOT slot at vaddr 0x518 with R_AARCH64_JUMP_SLOT (1026) for sym 1 (mallopt)
        val relaPltOffset = 0x2A0
        buf.position(relaPltOffset)
        buf.putLong(0x518L) // r_offset = GOT[3] vaddr
        buf.putLong((1L shl 32) or 1026L) // r_info = sym 1, R_AARCH64_JUMP_SLOT (0x402)
        buf.putLong(0L) // r_addend

        // Code (.plt + .text) at 0x300
        // PLT stub at 0x300 loads GOT slot at 0x518 and branches to mallopt:
        //   0x300: adrp x16, 0x0
        //   0x304: ldr  x17, [x16, #0x518]
        //   0x308: br   x17
        //   0x30c: nop
        val pltOffset = 0x300
        buf.position(pltOffset)
        buf.putInt(0x90000010.toInt()) // adrp x16, #0
        buf.putInt(0xF9428E11.toInt()) // ldr x17, [x16, #1304] (0x518)
        buf.putInt(0xD61F0220.toInt()) // br x17
        buf.putInt(0xD503201F.toInt()) // nop

        // Constructor function `init_tagfix` at 0x310:
        //   stp x29, x30, [sp, #-16]!   -> 0xA9BF7BFD
        //   mov x29, sp                 -> 0x910003FD
        //   mov w0, #-204               -> 0x12801960 (M_BIONIC_SET_HEAP_TAGGING_LEVEL = -204)
        //   mov w1, #0                  -> 0x52800001 (M_HEAP_TAGGING_LEVEL_NONE = 0)
        //   bl  0x300                   -> 0x97FFFFFA (calls PLT stub at 0x300 from 0x320)
        //   ldp x29, x30, [sp], #16     -> 0xA8C17BFD
        //   ret                         -> 0xD65F03C0
        val initFuncOffset = 0x310
        buf.position(initFuncOffset)
        buf.putInt(0xA9BF7BFD.toInt()) // stp x29, x30, [sp, #-16]!
        buf.putInt(0x910003FD.toInt()) // mov x29, sp
        buf.putInt(0x12801960)         // mov w0, #-204
        buf.putInt(0x52800001)         // mov w1, #0
        buf.putInt(0x97FFFFFA.toInt()) // bl 0x300
        buf.putInt(0xA8C17BFD.toInt()) // ldp x29, x30, [sp], #16
        buf.putInt(0xD65F03C0.toInt()) // ret

        // Section header string table (.shstrtab) at 0x400
        //   0: ""
        //   1: ".shstrtab"
        //  11: ".dynstr"
        //  19: ".dynamic"
        //  28: ".text"
        //  34: ".init_array"
        val shstrOffset = 0x400
        val shstrBytes = "\u0000.shstrtab\u0000.dynstr\u0000.dynamic\u0000.text\u0000.init_array\u0000".toByteArray(Charsets.US_ASCII)
        buf.position(shstrOffset)
        buf.put(shstrBytes)

        // .init_array at 0x500 (8 bytes): pointer to init_tagfix (0x310)
        val initArrayOffset = 0x500
        buf.position(initArrayOffset)
        buf.putLong(initFuncOffset.toLong())

        // .got.plt at 0x508 (3 reserved entries + 1 mallopt slot at 0x518)
        val gotPltOffset = 0x508
        buf.position(gotPltOffset)
        buf.putLong(0L)
        buf.putLong(0L)
        buf.putLong(0x300L) // initial slot at 0x518

        // .dynamic section at 0x520 (14 entries * 16 bytes = 224 bytes)
        val dynamicOffset = 0x520
        buf.position(dynamicOffset)
        fun putDyn(tag: Long, value: Long) {
            buf.putLong(tag)
            buf.putLong(value)
        }
        putDyn(1L, 0x01L)                      // DT_NEEDED = "libc.so"
        putDyn(14L, 0x11L)                     // DT_SONAME = "libtagfix.so"
        putDyn(25L, initArrayOffset.toLong())  // DT_INIT_ARRAY = 0x500
        putDyn(27L, 8L)                        // DT_INIT_ARRAYSZ = 8
        putDyn(4L, hashOffset.toLong())        // DT_HASH = 0x280
        putDyn(5L, dynstrOffset.toLong())      // DT_STRTAB = 0x200
        putDyn(6L, dynsymOffset.toLong())      // DT_SYMTAB = 0x240
        putDyn(10L, dynstrBytes.size.toLong()) // DT_STRSZ
        putDyn(11L, 24L)                       // DT_SYMENT = 24
        putDyn(3L, gotPltOffset.toLong())      // DT_PLTGOT = 0x508
        putDyn(2L, 24L)                        // DT_PLTRELSZ = 24
        putDyn(20L, 7L)                        // DT_PLTREL = DT_RELA (7)
        putDyn(23L, relaPltOffset.toLong())    // DT_JMPREL = 0x2A0
        putDyn(0L, 0L)                         // DT_NULL
        val dynamicSize = 14 * 16

        // Section Headers at 0x700 (6 headers * 64 bytes = 384 bytes, 8-byte aligned)
        val shdrOffset = 0x700
        val shdrCount = 6
        fun putShdr(
            name: Int, type: Int, flags: Long, addr: Long, offset: Long,
            size: Long, link: Int, info: Int, align: Long, entSize: Long
        ) {
            buf.putInt(name)
            buf.putInt(type)
            buf.putLong(flags)
            buf.putLong(addr)
            buf.putLong(offset)
            buf.putLong(size)
            buf.putInt(link)
            buf.putInt(info)
            buf.putLong(align)
            buf.putLong(entSize)
        }

        buf.position(shdrOffset)
        // 0: SHT_NULL
        putShdr(0, 0, 0L, 0L, 0L, 0L, 0, 0, 0L, 0L)
        // 1: .shstrtab (type = SHT_STRTAB = 3) -> referenced by e_shstrndx = 1
        putShdr(1, 3, 0L, 0L, shstrOffset.toLong(), shstrBytes.size.toLong(), 0, 0, 1L, 0L)
        // 2: .dynstr (type = SHT_STRTAB = 3, SHF_ALLOC = 2) -> linked by .dynamic sh_link = 2
        putShdr(11, 3, 2L, dynstrOffset.toLong(), dynstrOffset.toLong(), dynstrBytes.size.toLong(), 0, 0, 1L, 0L)
        // 3: .dynamic (type = SHT_DYNAMIC = 6, SHF_WRITE | SHF_ALLOC = 3) -> matches PT_DYNAMIC phdr
        putShdr(19, 6, 3L, dynamicOffset.toLong(), dynamicOffset.toLong(), dynamicSize.toLong(), 2, 0, 8L, 16L)
        // 4: .text (type = SHT_PROGBITS = 1, SHF_ALLOC | SHF_EXECINSTR = 6)
        putShdr(28, 1, 6L, 0x300L, 0x300L, 0x30L, 0, 0, 4L, 0L)
        // 5: .init_array (type = SHT_INIT_ARRAY = 14, SHF_WRITE | SHF_ALLOC = 3)
        putShdr(34, 14, 3L, initArrayOffset.toLong(), initArrayOffset.toLong(), 8L, 0, 0, 8L, 8L)

        // Program Headers at 0x40 (3 * 56 bytes)
        val phdrOffset = 0x40
        val phdrCount = 3
        buf.position(phdrOffset)
        fun putPhdr(type: Int, flags: Int, offset: Long, vaddr: Long, filesz: Long, memsz: Long, align: Long) {
            buf.putInt(type)
            buf.putInt(flags)
            buf.putLong(offset)
            buf.putLong(vaddr)
            buf.putLong(vaddr) // p_paddr
            buf.putLong(filesz)
            buf.putLong(memsz)
            buf.putLong(align)
        }
        // 1. PT_LOAD (RWX single page 0x0..0x1000 so code & GOT share page 0)
        putPhdr(
            type = 1, // PT_LOAD
            flags = 7, // PF_R | PF_W | PF_X
            offset = 0L,
            vaddr = 0L,
            filesz = 0x1000L,
            memsz = 0x1000L,
            align = 0x1000L
        )
        // 2. PT_DYNAMIC (matches .dynamic section at 0x520)
        putPhdr(
            type = 2, // PT_DYNAMIC
            flags = 6, // PF_R | PF_W
            offset = dynamicOffset.toLong(),
            vaddr = dynamicOffset.toLong(),
            filesz = dynamicSize.toLong(),
            memsz = dynamicSize.toLong(),
            align = 8L
        )
        // 3. PT_GNU_STACK (RW, non-executable stack)
        putPhdr(
            type = 0x6474e551, // PT_GNU_STACK
            flags = 6, // PF_R | PF_W
            offset = 0L,
            vaddr = 0L,
            filesz = 0L,
            memsz = 0L,
            align = 16L
        )

        // ELF64 Header at 0x00 (64 bytes)
        buf.position(0)
        buf.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buf.put(2.toByte()) // EI_CLASS = ELFCLASS64
        buf.put(1.toByte()) // EI_DATA = ELFDATA2LSB
        buf.put(1.toByte()) // EI_VERSION = EV_CURRENT
        buf.put(0.toByte()) // EI_OSABI = ELFOSABI_SYSV
        buf.putLong(0L)     // padding
        buf.putShort(3.toShort())   // e_type = ET_DYN
        buf.putShort(183.toShort()) // e_machine = EM_AARCH64
        buf.putInt(1)               // e_version = EV_CURRENT
        buf.putLong(0L)             // e_entry
        buf.putLong(phdrOffset.toLong()) // e_phoff = 64 (0x40)
        buf.putLong(shdrOffset.toLong()) // e_shoff = 1792 (0x700)
        buf.putInt(0)               // e_flags
        buf.putShort(64.toShort())  // e_ehsize = 64
        buf.putShort(56.toShort())  // e_phentsize = 56
        buf.putShort(phdrCount.toShort()) // e_phnum = 3
        buf.putShort(64.toShort())  // e_shentsize = 64
        buf.putShort(shdrCount.toShort()) // e_shnum = 6
        buf.putShort(1.toShort())   // e_shstrndx = 1 (.shstrtab index; non-zero!)

        return buf.array()
    }
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
        val process = try {
            ProcessBuilder(command).directory(workingDirectory)
                .redirectErrorStream(true).apply {
                    val env = environment()
                    env.putAll(envOverrides)
                    // Inject Bionic tagged-pointer disable shim for Java / child ELF binaries
                    // unless the caller explicitly opted out with MO_SKIP_TAGFIX=1.
                    if (env["MO_SKIP_TAGFIX"] != "1") {
                        BionicTagFixShim.applyToEnvironment(workingDirectory, env)
                    } else {
                        env.remove("MO_SKIP_TAGFIX")
                    }
                }.start()
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
        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
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