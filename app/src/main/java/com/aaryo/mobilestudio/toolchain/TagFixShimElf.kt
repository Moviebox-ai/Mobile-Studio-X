package com.aaryo.mobilestudio.toolchain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds (and validates) `libtagfix.so`, the tiny AArch64 shared object that Mobile Studio X
 * preloads via `LD_PRELOAD` for every child process it spawns (`java`, `kotlinc`, `d8`, `aapt2`,
 * `apksigner`, ...).
 *
 * Why it exists: OpenJDK builds for Android assume that heap pointers from Scudo carry the "top
 * byte ignore" (TBI) tag. When the JVM is spawned through `execve()` (instead of being forked from
 * Zygote) the `allowNativeHeapPointerTagging=false` manifest flag no longer applies, so Scudo
 * starts tagging allocations and the unpatched JVM aborts with
 * `Pointer tag for 0x... was truncated` (SIGABRT / exit 134). The shim's constructor runs before
 * `main()` / `libjvm.so` and calls `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL,
 * M_HEAP_TAGGING_LEVEL_NONE)` to switch tagging off for that process.
 *
 * The file is hand-assembled because the app has no NDK/clang at runtime; everything is written
 * byte by byte with [ByteBuffer]. Bionic's linker (`linker_phdr.cpp` in AOSP) is strict, and two
 * rules matter:
 *
 *  1. **W^X** - since API 26 a shared object with a `PT_LOAD` segment that is both writable and
 *     executable is rejected with
 *     `"libtagfix.so" has load segments that are both writable and executable`.
 *     Version 1 of this shim used a single RWX segment and could never be loaded on a modern
 *     device, so the whole toolchain (JDK included) failed to start. Version 2 splits the image
 *     into a read-only load segment (headers, `.text`, `.dynstr`, `.dynamic`) and a read-write,
 *     non-executable load segment (`.init_array`, `.got`). The linker flips the first one to
 *     RW while it applies relocations and then back to R+X.
 *
 *  2. **Program headers must be inside the first load segment** - `CheckPhdr()` requires the phdr
 *     table to live in a `PT_LOAD` range that is mapped from the file.
 *
 * Layout (every `PT_LOAD` keeps `p_vaddr == p_offset`, so "offset" and "address" are
 * interchangeable below and in the code):
 *
 * ```
 * 0x000  ELF header, program headers (4 x 56 bytes)      } PT_LOAD #1  R   0x0000..0x0900
 * 0x180  .dynstr   "libc.so", "mallopt", "libtagfix.so"  }
 * 0x1a0  .dynsym   [0] undefined, [1] mallopt (weak)     }
 * 0x1d0  .hash     SysV hash table                       }
 * 0x200  .rela.dyn R_AARCH64_GLOB_DAT -> &mallopt        }
 * 0x240  .text     init_tagfix (12 AArch64 instructions) }
 * 0x280  .comment  "mobilestudio:tagfix:2"               }
 * 0x2c0  .shstrtab section names                         }
 * 0x400  .dynamic  + section headers (0x500..0x700)      }
 * 0x1000 .init_array (pointer to init_tagfix), .got      } PT_LOAD #2  RW  0x1000..0x1020
 * ```
 *
 * `p_align` is 16 KiB: files built for 4 KiB pages keep working on 16 KiB-page kernels, and files
 * with a smaller alignment are rejected outright once 16 KiB app-compat is off.
 *
 * The `mallopt` import is declared **weak**: while resolving the relocations Bionic looks it up in
 * `libc.so` (exported since API 26) and, if a device ever stopped exporting it, the slot simply
 * stays `NULL`, the constructor returns immediately and the child process keeps its default
 * allocator behaviour instead of failing to link.
 */
object TagFixShimElf {

    /** Bumped whenever the generated image changes, so stale files on device are rebuilt. */
    const val VERSION: Int = 2

    /** Total size of the generated image. */
    const val FILE_SIZE: Int = 0x1020

    private const val MARKER_PREFIX = "mobilestudio:tagfix:"

    // ---------------------------------------------------------------- file layout
    private const val PHDR_OFFSET = 0x040
    private const val PHDR_SIZE = 56
    private const val PHDR_COUNT = 4
    private const val DYNSTR_OFFSET = 0x180
    private const val DYNSYM_OFFSET = 0x1A0
    private const val HASH_OFFSET = 0x1D0
    private const val RELA_OFFSET = 0x200
    private const val TEXT_OFFSET = 0x240
    private const val TEXT_SIZE = 0x30
    private const val COMMENT_OFFSET = 0x280
    private const val SHSTRTAB_OFFSET = 0x2C0
    private const val DYNAMIC_OFFSET = 0x400
    private const val SHSTRTAB_LIMIT = DYNAMIC_OFFSET
    private const val DYNAMIC_ENTRY_SIZE = 16
    private const val SHDR_OFFSET = 0x500
    private const val SHDR_SIZE = 64
    private const val SHDR_COUNT = 8
    private const val DATA_OFFSET = 0x1000
    private const val INIT_ARRAY_OFFSET = DATA_OFFSET
    private const val GOT_OFFSET = DATA_OFFSET + 8
    private const val LOAD_PAGE_ALIGN = 0x4000

    /** End of the first, file-backed read-only load segment. */
    private const val RO_SEGMENT_SIZE = 0x900

    /** Size of the read-write data segment (`.init_array` + `.got`). */
    private const val RW_SEGMENT_SIZE = 0x20

    // ---------------------------------------------------------------- section indices
    private const val SH_NULL = 0
    private const val SH_TEXT = 1
    private const val SH_DYNSTR = 2
    private const val SH_DYNAMIC = 3
    private const val SH_INIT_ARRAY = 4
    private const val SH_GOT = 5
    private const val SH_COMMENT = 6
    private const val SH_SHSTRTAB = 7

    private val SECTION_NAMES = arrayOf(
        "",
        ".text",
        ".dynstr",
        ".dynamic",
        ".init_array",
        ".got",
        ".comment",
        ".shstrtab"
    )

    private val SYMBOL_NAMES = arrayOf("libc.so", "mallopt", "libtagfix.so")

    // ELF constants
    private const val ET_DYN = 3
    private const val EM_AARCH64 = 183
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val PT_GNU_STACK = 0x6474e551
    private const val PF_X = 1
    private const val PF_W = 2
    private const val PF_R = 4
    private const val SHT_PROGBITS = 1
    private const val SHT_STRTAB = 3
    private const val SHT_DYNAMIC = 6
    private const val SHT_INIT_ARRAY = 14
    private const val SHF_WRITE = 0x1L
    private const val SHF_ALLOC = 0x2L
    private const val SHF_EXECINSTR = 0x4L

    // Dynamic tags
    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_HASH = 4L
    private const val DT_STRTAB = 5L
    private const val DT_SYMTAB = 6L
    private const val DT_RELA = 7L
    private const val DT_RELASZ = 8L
    private const val DT_STRSZ = 10L
    private const val DT_SYMENT = 11L
    private const val DT_INIT_ARRAY = 25L
    private const val DT_INIT_ARRAYSZ = 27L
    private const val DT_SONAME = 14L

    // R_AARCH64_GLOB_DAT (elf.h): write the symbol address into the GOT slot.

    private const val R_AARCH64_GLOB_DAT = 1025L

    // st_info for a weak function symbol: STB_WEAK (2) << 4 | STT_FUNC (2). Weak so that a device
    // without the export only leaves the GOT slot NULL instead of failing the whole link.
    private const val STB_WEAK_FUNC: Int = (2 shl 4) or 2

    /**
     * `init_tagfix`, 12 AArch64 instructions (hand-assembled and verified with
     * `llvm-mc`/Keystone + Capstone). It reads `&mallopt` from the relocated `.got` slot, returns
     * if it is `NULL` (weak symbol that could not be resolved) and otherwise calls
     * `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL = -204, M_HEAP_TAGGING_LEVEL_NONE = 0)`.
     *
     * ```
     * 0x240  sub   sp, sp, #32
     * 0x244  stp   x29, x30, [sp, #16]
     * 0x248  mov   x29, sp
     * 0x24c  mov   w0, #-204                 M_BIONIC_SET_HEAP_TAGGING_LEVEL
     * 0x250  mov   w1, #0                    M_HEAP_TAGGING_LEVEL_NONE
     * 0x254  adrp  x16, 0x1000               page of .got (vaddr 0x1008)
     * 0x258  ldr   x17, [x16, #8]            x17 = GOT entry for mallopt
     * 0x25c  cbz   x17, 0x2e8                nothing to call -> unwind
     * 0x260  blr   x17
     * 0x264  ldp   x29, x30, [sp, #16]
     * 0x268  add   sp, sp, #32
     * 0x26c  ret
     * ```
     */
    private val TEXT_WORDS: IntArray = intArrayOf(
        0xD10083FF.toInt(), // sub sp, sp, #32
        0xA9017BFD.toInt(), // stp x29, x30, [sp, #16]
        0x910003FD.toInt(), // mov x29, sp
        0x12801960,         // mov w0, #-204
        0x52800001,         // mov w1, #0
        0xB0000010.toInt(), // adrp x16, #0x1000
        0xF9400611.toInt(), // ldr x17, [x16, #8]
        0xB4000071.toInt(), // cbz x17, #0x2e8
        0xD63F0220.toInt(), // blr x17
        0xA9417BFD.toInt(), // ldp x29, x30, [sp, #16]
        0x910083FF.toInt(), // add sp, sp, #32
        0xD65F03C0.toInt()  // ret
    )

    // ---------------------------------------------------------------- derived tables

    /** NUL-separated string table for `.dynstr`, plus the offset of each name. */
    private class StringTable(names: Array<String>) {
        val bytes: ByteArray
        private val offsets: Map<String, Int>

        init {
            val out = ByteArrayOutputStream()
            val map = LinkedHashMap<String, Int>()
            out.write(0) // index 0 must be the empty string
            for (name in names) {
                map[name] = out.size()
                out.write(name.toByteArray(Charsets.US_ASCII))
                out.write(0)
            }
            bytes = out.toByteArray()
            offsets = map
        }

        operator fun get(name: String): Int =
            offsets[name] ?: error("string \"$name\" is not part of the shim string table")
    }

    private val DYNSTR = StringTable(SYMBOL_NAMES)
    private val SHSTRTAB = StringTable(SECTION_NAMES.drop(1).toTypedArray())

    /** Version marker stored in `.comment`, used to detect stale shims on device. */
    val MARKER: String = "$MARKER_PREFIX$VERSION"

    private val COMMENT: ByteArray = "$MARKER\u0000".toByteArray(Charsets.US_ASCII)

    private const val DYNAMIC_ENTRY_COUNT = 12
    private const val DYNAMIC_SIZE = DYNAMIC_ENTRY_COUNT * DYNAMIC_ENTRY_SIZE

    init {
        require(TEXT_WORDS.size * 4 == TEXT_SIZE) { "TEXT_SIZE does not match TEXT_WORDS" }
        require(DYNSTR.bytes.size < DYNSYM_OFFSET - DYNSTR_OFFSET) { ".dynstr overlaps .dynsym" }
        require(SHSTRTAB.bytes.size < SHSTRTAB_LIMIT - SHSTRTAB_OFFSET) { ".shstrtab overflows" }
        require(COMMENT.size < SHSTRTAB_OFFSET - COMMENT_OFFSET) { ".comment overlaps .shstrtab" }
        require(SHDR_OFFSET + SHDR_COUNT * SHDR_SIZE <= RO_SEGMENT_SIZE) { "shdr outside RO load segment" }
        require(DATA_OFFSET + RW_SEGMENT_SIZE == FILE_SIZE) { "RW load segment must end at FILE_SIZE" }
        require(LOAD_PAGE_ALIGN >= 0x4000) { "p_align must stay 16 KiB friendly" }
    }

    // ---------------------------------------------------------------- building

    /** Returns the complete `libtagfix.so` image. */
    fun buildBytes(): ByteArray {
        val buf = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // .dynstr
        buf.putBytes(DYNSTR_OFFSET, DYNSTR.bytes)

        // .dynsym: entry 0 is the mandatory undefined symbol, entry 1 is `mallopt`,
        // STB_WEAK (2) << 4 | STT_FUNC (2) so a missing export only leaves the GOT slot NULL.
        buf.putUInt(DYNSYM_OFFSET + 24, DYNSTR["mallopt"])   // st_name
        buf.put(DYNSYM_OFFSET + 28, STB_WEAK_FUNC.toByte())  // st_info
        buf.put(DYNSYM_OFFSET + 30, 0.toByte())              // st_shndx = SHN_UNDEF
        // st_value (0x10) and st_size (0x18) stay zero.

        // .hash: nbucket = 1, nchain = 2, bucket[0] = 1, chain = {0, 0}
        buf.putUInt(HASH_OFFSET, 1)
        buf.putUInt(HASH_OFFSET + 4, 2)
        buf.putUInt(HASH_OFFSET + 8, 1)
        buf.putUInt(HASH_OFFSET + 12, 0)
        buf.putUInt(HASH_OFFSET + 16, 0)

        // .rela.dyn: R_AARCH64_GLOB_DAT writes &mallopt into the .got slot.
        buf.putLong(RELA_OFFSET, GOT_OFFSET.toLong())                    // r_offset
        buf.putLong(RELA_OFFSET + 8, (1L shl 32) or R_AARCH64_GLOB_DAT)  // r_info = sym 1
        buf.putLong(RELA_OFFSET + 16, 0L)                                // r_addend

        // .text
        for ((index, word) in TEXT_WORDS.withIndex()) {
            buf.putInt(TEXT_OFFSET + index * 4, word)
        }

        // .comment (version marker)
        buf.putBytes(COMMENT_OFFSET, COMMENT)

        // .shstrtab
        buf.putBytes(SHSTRTAB_OFFSET, SHSTRTAB.bytes)

        // .dynamic -- PT_DYNAMIC must describe exactly this section.
        var dyn = DYNAMIC_OFFSET
        fun putDyn(tag: Long, value: Long) {
            buf.putLong(dyn, tag)
            buf.putLong(dyn + 8, value)
            dyn += DYNAMIC_ENTRY_SIZE
        }
        putDyn(DT_NEEDED, DYNSTR["libc.so"].toLong())
        putDyn(DT_SONAME, DYNSTR["libtagfix.so"].toLong())
        putDyn(DT_STRTAB, DYNSTR_OFFSET.toLong())
        putDyn(DT_STRSZ, DYNSTR.bytes.size.toLong())
        putDyn(DT_SYMTAB, DYNSYM_OFFSET.toLong())
        putDyn(DT_SYMENT, 24L)
        putDyn(DT_HASH, HASH_OFFSET.toLong())
        putDyn(DT_RELA, RELA_OFFSET.toLong())
        putDyn(DT_RELASZ, 24L)
        // Without these two the library would link fine but its constructor would never run.
        putDyn(DT_INIT_ARRAY, INIT_ARRAY_OFFSET.toLong())
        putDyn(DT_INIT_ARRAYSZ, 8L)
        putDyn(DT_NULL, 0L)
        require(dyn == DYNAMIC_OFFSET + DYNAMIC_SIZE) { ".dynamic size mismatch" }

        // Section headers
        var shdr = SHDR_OFFSET
        fun putShdr(
            nameIndex: Int,
            type: Int,
            flags: Long,
            address: Long,
            offset: Long,
            size: Long,
            link: Int = 0,
            info: Int = 0,
            align: Long = 1,
            entrySize: Long = 0
        ) {
            buf.putInt(shdr, nameIndex)
            buf.putInt(shdr + 4, type)
            buf.putLong(shdr + 8, flags)
            buf.putLong(shdr + 16, address)
            buf.putLong(shdr + 24, offset)
            buf.putLong(shdr + 32, size)
            buf.putInt(shdr + 40, link)
            buf.putInt(shdr + 44, info)
            buf.putLong(shdr + 48, align)
            buf.putLong(shdr + 56, entrySize)
            shdr += SHDR_SIZE
        }

        val allocExec = SHF_ALLOC or SHF_EXECINSTR
        val allocWrite = SHF_ALLOC or SHF_WRITE
        val allocOnly = SHF_ALLOC
        putShdr(SH_NULL, 0, 0, 0, 0, 0)
        putShdr(SHSTRTAB[".text"], SHT_PROGBITS, allocExec, TEXT_OFFSET.toLong(), TEXT_OFFSET.toLong(), TEXT_SIZE.toLong(), align = 8)
        putShdr(SHSTRTAB[".dynstr"], SHT_STRTAB, allocOnly, DYNSTR_OFFSET.toLong(), DYNSTR_OFFSET.toLong(), DYNSTR.bytes.size.toLong())
        // .dynamic links to the string table it uses; Bionic rejects a link to a non-STRTAB.
        putShdr(
            SHSTRTAB[".dynamic"], SHT_DYNAMIC, allocWrite, DYNAMIC_OFFSET.toLong(), DYNAMIC_OFFSET.toLong(),
            DYNAMIC_SIZE.toLong(), link = SH_DYNSTR, align = 8, entrySize = DYNAMIC_ENTRY_SIZE.toLong()
        )
        putShdr(SHSTRTAB[".init_array"], SHT_INIT_ARRAY, allocWrite, INIT_ARRAY_OFFSET.toLong(), INIT_ARRAY_OFFSET.toLong(), 8, align = 8, entrySize = 8)
        putShdr(SHSTRTAB[".got"], SHT_PROGBITS, allocWrite, GOT_OFFSET.toLong(), GOT_OFFSET.toLong(), 8, align = 8)
        putShdr(SHSTRTAB[".comment"], SHT_PROGBITS, 0, 0, COMMENT_OFFSET.toLong(), COMMENT.size.toLong())
        putShdr(SHSTRTAB[".shstrtab"], SHT_STRTAB, 0, 0, SHSTRTAB_OFFSET.toLong(), SHSTRTAB.bytes.size.toLong())
        require(shdr == SHDR_OFFSET + SHDR_COUNT * SHDR_SIZE) { "section header count mismatch" }

        // Data segment content: .init_array points at init_tagfix, .got starts empty and is
        // filled in by the linker through .rela.dyn.
        buf.putLong(INIT_ARRAY_OFFSET, TEXT_OFFSET.toLong())
        buf.putLong(GOT_OFFSET, 0L)

        // Program headers
        var phdr = PHDR_OFFSET
        fun putPhdr(type: Int, flags: Int, offset: Long, address: Long, fileSize: Long, memSize: Long, align: Long) {
            buf.putInt(phdr, type)
            buf.putInt(phdr + 4, flags)
            buf.putLong(phdr + 8, offset)
            buf.putLong(phdr + 16, address)
            buf.putLong(phdr + 24, address) // p_paddr
            buf.putLong(phdr + 32, fileSize)
            buf.putLong(phdr + 40, memSize)
            buf.putLong(phdr + 48, align)
            phdr += PHDR_SIZE
        }
        // #1 read + execute: ELF header, phdrs, .text, .dynstr, .dynamic, shdrs.
        // Never W+X - that is exactly what Bionic refuses to map.
        putPhdr(PT_LOAD, PF_R or PF_X, 0, 0, RO_SEGMENT_SIZE.toLong(), RO_SEGMENT_SIZE.toLong(), LOAD_PAGE_ALIGN.toLong())
        // #2 read-write, non-executable: .init_array + .got (the linker writes the GOT).
        putPhdr(PT_LOAD, PF_R or PF_W, DATA_OFFSET.toLong(), DATA_OFFSET.toLong(), RW_SEGMENT_SIZE.toLong(), RW_SEGMENT_SIZE.toLong(), LOAD_PAGE_ALIGN.toLong())
        // #3 .dynamic, must match the section header exactly.
        putPhdr(PT_DYNAMIC, PF_R or PF_W, DYNAMIC_OFFSET.toLong(), DYNAMIC_OFFSET.toLong(), DYNAMIC_SIZE.toLong(), DYNAMIC_SIZE.toLong(), 8)
        // #4 non-executable stack.
        putPhdr(PT_GNU_STACK, PF_R or PF_W, 0, 0, 0, 0, 16)
        require(phdr == PHDR_OFFSET + PHDR_COUNT * PHDR_SIZE) { "program header count mismatch" }

        // ELF header (written last so every offset above is already known)
        buf.putBytes(0, byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buf.put(4, 2.toByte())  // ELFCLASS64
        buf.put(5, 1.toByte())  // ELFDATA2LSB
        buf.put(6, 1.toByte())  // EV_CURRENT
        buf.put(7, 0.toByte())  // ELFOSABI_SYSV
        buf.putShort(16, ET_DYN.toShort())
        buf.putShort(18, EM_AARCH64.toShort())
        buf.putInt(20, 1)                           // e_version
        buf.putLong(24, 0L)                         // e_entry (shared object)
        buf.putLong(32, PHDR_OFFSET.toLong())       // e_phoff
        buf.putLong(40, SHDR_OFFSET.toLong())       // e_shoff
        buf.putInt(48, 0)                           // e_flags
        buf.putShort(52, 64)                        // e_ehsize
        buf.putShort(54, PHDR_SIZE.toShort())       // e_phentsize
        buf.putShort(56, PHDR_COUNT.toShort())      // e_phnum
        buf.putShort(58, SHDR_SIZE.toShort())       // e_shentsize
        buf.putShort(60, SHDR_COUNT.toShort())      // e_shnum
        buf.putShort(62, SH_SHSTRTAB.toShort())     // e_shstrndx (must not be 0)

        return buf.array()
    }

    // ---------------------------------------------------------------- validation

    /**
     * True when [bytes] is a shim this app should keep using: correct size, valid ELF64 header,
     * a section header table whose `e_shstrndx` resolves, no writable+executable load segment and
     * the [VERSION] marker. Anything else (including the legacy 4096-byte v1 image) is rebuilt.
     */
    fun isUsableShim(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size != FILE_SIZE) return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.get(0) != 0x7F.toByte() || buf.get(1) != 'E'.code.toByte() ||
            buf.get(2) != 'L'.code.toByte() || buf.get(3) != 'F'.code.toByte()
        ) return false
        if (buf.get(4) != 2.toByte()) return false                       // ELFCLASS64
        if (buf.get(5) != 1.toByte()) return false                       // little endian
        if (buf.getShort(16) != ET_DYN.toShort()) return false
        if (buf.getShort(18) != EM_AARCH64.toShort()) return false
        if (buf.getShort(52) != 64.toShort()) return false
        if (buf.getShort(54) != PHDR_SIZE.toShort()) return false
        if (buf.getShort(58) != SHDR_SIZE.toShort()) return false

        val phnum = buf.getShort(56).toInt()
        val shnum = buf.getShort(60).toInt()
        val shstrndx = buf.getShort(62).toInt()
        if (phnum <= 0 || shnum <= 0) return false
        if (shstrndx <= 0 || shstrndx >= shnum) return false
        val phoff = buf.getLong(32)
        val shoff = buf.getLong(40)
        if (phoff < 0 || phoff + phnum.toLong() * PHDR_SIZE > bytes.size) return false
        if (shoff < 0 || shoff + shnum.toLong() * SHDR_SIZE > bytes.size) return false
        if (hasWritableExecutableSegment(bytes)) return false
        if (versionOf(bytes) != VERSION) return false

        // The .dynamic section header must exist and agree with PT_DYNAMIC.
        val dynamic = sectionOffset(buf, SHT_DYNAMIC) ?: return false
        val ptDynamic = programHeaderRange(buf, PT_DYNAMIC) ?: return false
        if (dynamic.first != ptDynamic.first || dynamic.second != ptDynamic.second) return false
        return true
    }

    /** Version encoded in the `.comment` section, or `null` when the file is not a shim. */
    fun versionOf(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.size < SHDR_OFFSET + SHDR_COUNT * SHDR_SIZE) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.get(0) != 0x7F.toByte() || buf.get(1) != 'E'.code.toByte() ||
            buf.get(2) != 'L'.code.toByte() || buf.get(3) != 'F'.code.toByte()
        ) return null
        val shnum = buf.getShort(60).toInt()
        val shoff = buf.getLong(40)
        if (shnum <= 0 || shoff <= 0 || shoff + shnum.toLong() * SHDR_SIZE > bytes.size) return null
        val shstrndx = buf.getShort(62).toInt()
        if (shstrndx <= 0 || shstrndx >= shnum) return null
        val shstr = buf.getLong(shoff.toInt() + shstrndx * SHDR_SIZE + 24).toInt()
        val shstrSize = buf.getLong(shoff.toInt() + shstrndx * SHDR_SIZE + 32).toInt()
        if (shstr < 0 || shstrSize <= 0 || shstr + shstrSize > bytes.size) return null
        val names = String(bytes, shstr, shstrSize, Charsets.US_ASCII)
        for (i in 0 until shnum) {
            val base = shoff.toInt() + i * SHDR_SIZE
            val nameOffset = buf.getInt(base)
            val name = cString(names, nameOffset) ?: continue
            if (name != ".comment") continue
            val offset = buf.getLong(base + 24).toInt()
            val size = buf.getLong(base + 32).toInt()
            if (offset < 0 || size <= 0 || offset + size > bytes.size) return null
            val comment = String(bytes, offset, size, Charsets.US_ASCII).trimEnd('\u0000')
            if (!comment.startsWith(MARKER_PREFIX)) return null
            return comment.removePrefix(MARKER_PREFIX).toIntOrNull()
        }
        return null
    }

    /** True when any `PT_LOAD` segment is both writable and executable (rejected by Bionic). */
    fun hasWritableExecutableSegment(bytes: ByteArray): Boolean {
        if (bytes.size < PHDR_OFFSET + PHDR_SIZE) return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val phnum = buf.getShort(56).toInt()
        val phoff = buf.getLong(32)
        if (phnum <= 0 || phoff < 0 || phoff + phnum.toLong() * PHDR_SIZE > bytes.size) return false
        for (i in 0 until phnum) {
            val base = phoff.toInt() + i * PHDR_SIZE
            if (buf.getInt(base) != PT_LOAD) continue
            val flags = buf.getInt(base + 4)
            if ((flags and PF_W) != 0 && (flags and PF_X) != 0) return true
        }
        return false
    }

    private fun sectionOffset(buf: ByteBuffer, type: Int): Pair<Long, Long>? {
        val shnum = buf.getShort(60).toInt()
        val shoff = buf.getLong(40).toInt()
        for (i in 0 until shnum) {
            val base = shoff + i * SHDR_SIZE
            if (buf.getInt(base + 4) != type) continue
            return buf.getLong(base + 24) to buf.getLong(base + 32)
        }
        return null
    }

    private fun programHeaderRange(buf: ByteBuffer, type: Int): Pair<Long, Long>? {
        val phnum = buf.getShort(56).toInt()
        val phoff = buf.getLong(32).toInt()
        for (i in 0 until phnum) {
            val base = phoff + i * PHDR_SIZE
            if (buf.getInt(base) != type) continue
            return buf.getLong(base + 8) to buf.getLong(base + 32)
        }
        return null
    }

    private fun cString(table: String, offset: Int): String? {
        if (offset < 0 || offset >= table.length) return null
        val end = table.indexOf('\u0000', offset)
        return if (end < 0) table.substring(offset) else table.substring(offset, end)
    }
}


// Offset-addressed helpers on top of ByteBuffer. ByteBuffer already has absolute get/put for
// primitives and byte arrays; only the "absolute put without touching position()" variants are
// missing, so each one saves and restores the position.

private fun ByteBuffer.putBytes(offset: Int, data: ByteArray) {
    val previous = position()
    position(offset)
    put(data)
    position(previous)
}

private fun ByteBuffer.putUInt(offset: Int, value: Int) {
    val previous = position()
    position(offset)
    putInt(value)
    position(previous)
}

private fun ByteBuffer.putInt(offset: Int, value: Int) {
    val previous = position()
    position(offset)
    putInt(value)
    position(previous)
}

private fun ByteBuffer.putLong(offset: Int, value: Long) {
    val previous = position()
    position(offset)
    putLong(value)
    position(previous)
}

private fun ByteBuffer.putShort(offset: Int, value: Short) {
    val previous = position()
    position(offset)
    putShort(value)
    position(previous)
}
