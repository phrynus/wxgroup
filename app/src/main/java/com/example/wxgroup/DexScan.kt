package com.example.wxgroup

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 迷你 dex 扫描器 —— 不依赖 DexKit，只做一件小事：
 *
 *   **在微信的所有 dex 里，找出「哪个方法引用了某个字符串常量」。**
 *
 * 为什么需要它：微信自己的类名 / 方法名全被 R8 混淆了
 * （实测 8.0.79 上已经找不到 `com.tencent.mm.sdk.platformtools.XmlParser`），
 * 但**字符串常量不会被混淆**。所以拿日志 tag（`MicroMsg.SDK.XmlParser`）
 * 这类字面量反查类，是版本无关的做法 —— 和 WAuxv / WeKit 走的是同一条路。
 *
 * 只解析用得上的那点 dex 结构：字符串池、type / proto / method 表、
 * class_def、class_data_item、code_item（只认 const-string / const-string/jumbo）。
 *
 * 性能：先对整份 dex 做一次廉价的字节预筛，没命中目标串就整份跳过 ——
 * 微信 18 个 dex 里通常只有两三个真正需要解析。
 */
internal object DexScan {

    private val DEX_NAME = Regex("classes\\d*\\.dex")

    /** 一条命中：某个类的某个方法里出现了目标字符串 */
    internal class Hit(
        val descriptor: String,
        val className: String,
        val method: String,
        val paramCount: Int,
        val params: List<String>,
        val ret: String,
        val needles: Set<String>,
    )

    /**
     * 每条指令占几个 16 位码元（dalvik 指令长度表）。
     * 只有按表走才能保证不会把操作数当成下一条指令的 opcode ——
     * 否则会把随机的索引当成 const-string，查出一堆假类。
     */
    private val INSNS_LEN = IntArray(256) { 1 }.also { t ->
        intArrayOf(
            0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1c, 0x1f, 0x20, 0x22, 0x23,
            0x29, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x3a, 0x3b, 0x3c, 0x3d,
        ).forEach { t[it] = 2 }
        for (op in 0x44..0x6d) t[op] = 2
        for (op in 0x90..0xaf) t[op] = 2
        for (op in 0xd0..0xe2) t[op] = 2
        t[0xfe] = 2
        t[0xff] = 2

        intArrayOf(0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c)
            .forEach { t[it] = 3 }
        for (op in 0x6e..0x72) t[op] = 3
        for (op in 0x74..0x78) t[op] = 3
        t[0xfc] = 3
        t[0xfd] = 3

        t[0xfa] = 4   // invoke-polymorphic
        t[0xfb] = 4   // invoke-polymorphic/range
        t[0x18] = 5   // const-wide (51l)
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 扫描微信的所有 dex，返回引用了 [needles] 中任意一个字符串的 (类, 方法)。
     * 只读 IO + 纯计算，可以随便丢到后台线程。
     */
    fun scan(context: Context, needles: Collection<String>): List<Hit> {
        val ai = context.applicationInfo
        val paths = LinkedHashSet<String>()
        ai.sourceDir?.let { paths.add(it) }
        ai.publicSourceDir?.let { paths.add(it) }
        ai.splitSourceDirs?.forEach { it?.let(paths::add) }
        ai.splitPublicSourceDirs?.forEach { it?.let(paths::add) }

        val needleList = needles.toList()
        val needleBytes = needleList.map { it.toByteArray(Charsets.UTF_8) }
        val out = ArrayList<Hit>()

        for (p in paths) {
            val f = File(p)
            if (!f.isFile) continue
            try {
                ZipFile(f).use { zip ->
                    for (e in zip.entries().toList()) {
                        if (!DEX_NAME.matches(e.name)) continue
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        try {
                            scanDex(bytes, needleList, needleBytes, out)
                        } catch (t: Throwable) {
                            // 静默：解析不了就当这份 dex 里没有目标串
                        }
                    }
                }
            } catch (t: Throwable) {
                // 静默：某个 apk 读不了就跳过
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 单份 dex

    private fun scanDex(
        b: ByteArray,
        needles: List<String>,
        needleBytes: List<ByteArray>,
        out: MutableList<Hit>,
    ) {
        if (b.size < 112) return
        if (u32(b, 0) != 0x0A786564) return              // magic "dex\n"
        // 廉价预筛：整份 dex 里都没有目标串，说明这里的类不可能引用它
        if (!containsAny(b, needleBytes)) return

        val stringIdsSize = u32(b, 56)
        val stringIdsOff = u32(b, 60)
        val typeIdsOff = u32(b, 68)
        val protoIdsOff = u32(b, 76)
        val methodIdsOff = u32(b, 92)
        val classDefsSize = u32(b, 96)
        val classDefsOff = u32(b, 100)
        if (stringIdsSize <= 0 || stringIdsOff + stringIdsSize * 4 > b.size) return
        if (classDefsSize <= 0 || classDefsOff + classDefsSize * 32 > b.size) return

        // 字符串池：标记出「命中目标串」的下标 -> needle 序号
        val hitOf = IntArray(stringIdsSize) { -1 }
        var hitCount = 0
        for (i in 0 until stringIdsSize) {
            val dataOff = u32(b, stringIdsOff + i * 4)
            if (dataOff <= 0 || dataOff >= b.size) continue
            val start = skipUleb(b, dataOff)
            if (start >= b.size) continue
            var end = start
            while (end < b.size && b[end].toInt() != 0) end++
            var n = 0
            while (n < needleBytes.size) {
                if (indexOf(b, start, end, needleBytes[n]) >= 0) {
                    hitOf[i] = n
                    hitCount++
                    break
                }
                n++
            }
        }
        if (hitCount == 0) return

        val scratch = IntArray(1)
        for (ci in 0 until classDefsSize) {
            val cd = classDefsOff + ci * 32
            val classIdx = u32(b, cd)
            val classDataOff = u32(b, cd + 24)
            if (classDataOff == 0 || classDataOff >= b.size) continue

            val descIdx = u32(b, typeIdsOff + classIdx * 4)
            if (descIdx < 0 || descIdx >= stringIdsSize) continue
            val descriptor = str(b, stringIdsOff, descIdx) ?: continue

            var o = classDataOff
            o = uleb(b, o, scratch); val staticFields = scratch[0]
            o = uleb(b, o, scratch); val instanceFields = scratch[0]
            o = uleb(b, o, scratch); val directMethods = scratch[0]
            o = uleb(b, o, scratch); val virtualMethods = scratch[0]

            // 字段表只是占位，跳过（每项两个 uleb128）
            repeat(staticFields + instanceFields) {
                o = uleb(b, o, scratch)
                o = uleb(b, o, scratch)
            }

            // 方法表：method_idx 是「相对上一个的增量」，两张表各自从 0 重新计数
            var running = 0
            var mi = 0
            val total = directMethods + virtualMethods
            while (mi < total) {
                if (mi == directMethods) running = 0
                o = uleb(b, o, scratch); running += scratch[0]
                o = uleb(b, o, scratch)                       // access flags
                o = uleb(b, o, scratch); val codeOff = scratch[0]
                if (codeOff > 0) {
                    val found = scanCode(b, codeOff, hitOf, needles)
                    if (found.isNotEmpty()) {
                        val m = resolveMethod(b, stringIdsOff, typeIdsOff, protoIdsOff, methodIdsOff, running)
                        if (m != null) {
                            out.add(
                                Hit(
                                    descriptor = descriptor,
                                    className = dotted(descriptor),
                                    method = m.first,
                                    paramCount = m.second.size,
                                    params = m.second,
                                    ret = m.third,
                                    needles = found,
                                ),
                            )
                        }
                    }
                }
                mi++
            }
        }
    }

    /** 扫一段 code_item，返回其中出现的目标字符串（needle 原文） */
    private fun scanCode(b: ByteArray, codeOff: Int, hitOf: IntArray, needles: List<String>): Set<String> {
        if (codeOff + 16 > b.size) return emptySet()
        val insnsSize = u32(b, codeOff + 12)
        if (insnsSize <= 0 || codeOff + 16 + insnsSize * 2 > b.size) return emptySet()

        val start = codeOff + 16
        var ins = start
        var i = 0
        var found: MutableSet<String>? = null

        while (i < insnsSize) {
            val op = b[ins].toInt() and 0xFF
            if (op == 0x1a) {
                val idx = u16(b, ins + 2)
                if (idx < hitOf.size && hitOf[idx] >= 0) {
                    (found ?: HashSet<String>().also { found = it }).add(needles[hitOf[idx]])
                }
            } else if (op == 0x1b) {
                val idx = u32(b, ins + 2)
                if (idx >= 0 && idx < hitOf.size && hitOf[idx] >= 0) {
                    (found ?: HashSet<String>().also { found = it }).add(needles[hitOf[idx]])
                }
            }
            val len = INSNS_LEN[op]
            ins += len * 2
            i += len
        }
        return found ?: emptySet()
    }

    /** method_idx -> (方法名, 参数类型, 返回类型) */
    private fun resolveMethod(
        b: ByteArray,
        stringIdsOff: Int,
        typeIdsOff: Int,
        protoIdsOff: Int,
        methodIdsOff: Int,
        methodIdx: Int,
    ): Triple<String, List<String>, String>? {
        val mBase = methodIdsOff + methodIdx * 8
        if (mBase + 8 > b.size) return null
        val protoIdx = u16(b, mBase + 2)
        val nameIdx = u32(b, mBase + 4)
        val name = str(b, stringIdsOff, nameIdx) ?: return null

        val pBase = protoIdsOff + protoIdx * 12
        if (pBase + 12 > b.size) return null
        val retTypeIdx = u32(b, pBase + 4)
        val paramsOff = u32(b, pBase + 8)

        val ret = typeName(b, stringIdsOff, typeIdsOff, retTypeIdx) ?: "?"
        val params = ArrayList<String>(4)
        if (paramsOff > 0 && paramsOff + 4 <= b.size) {
            val n = u32(b, paramsOff)
            for (k in 0 until n) {
                val tIdx = u16(b, paramsOff + 4 + k * 2)
                params.add(typeName(b, stringIdsOff, typeIdsOff, tIdx) ?: "?")
            }
        }
        return Triple(name, params, ret)
    }

    private fun typeName(b: ByteArray, stringIdsOff: Int, typeIdsOff: Int, typeIdx: Int): String? {
        if (typeIdx < 0) return null
        val off = typeIdsOff + typeIdx * 4
        if (off + 4 > b.size) return null
        return str(b, stringIdsOff, u32(b, off))?.let { dotted(it) }
    }

    // ------------------------------------------------------------------ 小工具

    /** 读字符串池第 idx 项；这里不做 MUTF-8 严格还原，ASCII 场景足够 */
    private fun str(b: ByteArray, stringIdsOff: Int, idx: Int): String? {
        if (idx < 0) return null
        val idOff = stringIdsOff + idx * 4
        if (idOff + 4 > b.size) return null
        val dataOff = u32(b, idOff)
        if (dataOff <= 0 || dataOff >= b.size) return null
        val start = skipUleb(b, dataOff)
        if (start >= b.size) return null
        var end = start
        while (end < b.size && b[end].toInt() != 0) end++
        return String(b, start, end - start, Charsets.UTF_8)
    }

    /** `Lcom/foo/Bar;` -> `com.foo.Bar` */
    private fun dotted(desc: String): String {
        if (desc.isEmpty()) return desc
        if (desc[0] == '[') return dotted(desc.substring(1)) + "[]"
        if (desc[0] == 'L' && desc.endsWith(";")) {
            return desc.substring(1, desc.length - 1).replace('/', '.')
        }
        return when (desc) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "S" -> "short"
            "C" -> "char"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> desc
        }
    }

    private fun u8(b: ByteArray, o: Int): Int = if (o in b.indices) b[o].toInt() and 0xFF else 0

    private fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

    private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

    /** 跳过 uleb128，返回新的偏移（不需要值的时候用） */
    private fun skipUleb(b: ByteArray, off: Int): Int {
        var o = off
        while (o < b.size && (b[o].toInt() and 0x80) != 0) o++
        return o + 1
    }

    /** 读 uleb128，值写进 out[0]，返回新的偏移 */
    private fun uleb(b: ByteArray, off: Int, out: IntArray): Int {
        var result = 0
        var shift = 0
        var o = off
        while (o < b.size) {
            val x = b[o].toInt() and 0xFF
            o++
            result = result or ((x and 0x7F) shl shift)
            if ((x and 0x80) == 0) break
            shift += 7
        }
        out[0] = result
        return o
    }

    /** 在 hay[from, to) 里找 needle，返回下标；找不到返回 -1 */
    private fun indexOf(hay: ByteArray, from: Int, to: Int, needle: ByteArray): Int {
        if (needle.isEmpty()) return from
        val last = to - needle.size
        var i = from
        while (i <= last) {
            if (hay[i] == needle[0]) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun matchesAt(hay: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at + needle.size > hay.size) return false
        for (j in 1 until needle.size) {
            if (hay[at + j] != needle[j]) return false
        }
        return true
    }

    /** 一次遍历判断「整份 dex 是否含任意目标串」，省掉逐个 needle 各扫一遍 */
    private fun containsAny(hay: ByteArray, needles: List<ByteArray>): Boolean {
        var i = 0
        val size = hay.size
        while (i < size) {
            val c = hay[i]
            var n = 0
            while (n < needles.size) {
                val nd = needles[n]
                if (nd[0] == c && matchesAt(hay, i, nd)) return true
                n++
            }
            i++
        }
        return false
    }
}
