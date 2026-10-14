package com.example.wxgroup

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话条目的反射访问器 —— 本模块「抗混淆」的全部秘密。
 *
 * 微信的会话类名每个版本都会变（`iq5.a1`、`fj5.b0` 之类），
 * 但**数据库字段名基本固定**，所以这里只按字段名定位，完全不依赖混淆类名：
 *
 *   field_username         会话 ID（群聊 @chatroom 结尾，公众号 gh_ 开头）
 *   field_unReadCount      未读数
 *   field_unReadMuteCount  免打扰未读数
 *   field_verifyFlag       rcontact.verifyFlag（& 8 = 企业号 / 公众号）
 *   field_attrflag         rconversation.attrflag（几个官方 / 服务标记位）
 *   field_parentRef        hidden_conv_parent = 折叠会话
 *
 * 分组的判定规则参考 WAuxv 的聊天分组 SQL，把 where 条件等价改写成字段判断。
 */
object ConvItem {

    // ------------------------------------------------------------ 字段名候选

    private val USERNAME_NAMES = arrayOf("field_username", "username")

    private val UNREAD_NAMES = arrayOf(
        "field_unReadCount", "unReadCount",
        "field_unReadMuteCount", "unReadMuteCount",
    )

    private val VERIFY_FLAG_NAMES = arrayOf("field_verifyFlag", "verifyFlag")
    private val ATTR_FLAG_NAMES = arrayOf("field_attrflag", "attrflag")
    private val PARENT_REF_NAMES = arrayOf("field_parentRef", "parentRef")

    // ------------------------------------------------------------ 判断常量

    private const val CHATROOM = "@chatroom"
    private const val IM_CHATROOM = "@im.chatroom"

    /** 微信折叠 / 隐藏会话的 parentRef 值，任何分组都不收 */
    const val HIDDEN_PARENT = "hidden_conv_parent"

    private const val VERIFY_OFFICIAL = 8                       // rcontact.verifyFlag & 8
    private const val ATTR_OFFICIAL_1 = 2097152                 // attrflag 0x200000
    private const val ATTR_OFFICIAL_2 = 8388608                 // attrflag 0x800000
    private const val ATTR_OFFICIAL_3 = 16777216                // attrflag 0x1000000

    /** 微信内置功能号 / 系统号：没有真实联系人，归到「好友」分组里展示 */
    private val SYSTEM_ACCOUNTS = setOf(
        "officialaccounts", "service_officialaccounts",
        "appbrandcustomerservicemsg", "opencustomerservicemsg",
        "filehelper", "qqmail", "fmessage", "tmessage", "qmessage", "qqsync",
        "floatbottle", "lbsapp", "shakeapp", "medianote", "qqfriend", "newsapp",
        "blogapp", "facebookapp", "topstoryapp", "masssendapp", "feedsapp",
        "voipapp", "cardpackage", "voicevoipapp", "voiceinputapp", "linkedinplugin",
        "notifymessage", "appbrand_notify_message", "conversationboxservice",
        "schedule_message", "downloaderapp", "weixin", "exhelper", "weibo",
    )

    // ------------------------------------------------------------ 分类常量
    // 一条会话只会属于其中一种，所以都是单一位标记；
    // CAT_OFFICIAL_OR_SYSTEM 只是「公众号 / 服务号 / 微信官方号」的合并掩码，
    // 用于未读分组把这几类排除掉。

    /** 群聊 */
    const val CAT_CHATROOM = 1

    /** 好友私聊 */
    const val CAT_FRIEND = 2

    /** 微信自带的官方号 / 系统号：文件传输助手、微信团队、服务通知、微信支付… */
    const val CAT_SYSTEM = 4

    /** 公众号 / 服务号 / 开放平台号 */
    const val CAT_OFFICIAL = 8

    /** 公众号 / 服务号 / 微信官方号的合并掩码（未读分组要排除它们） */
    const val CAT_OFFICIAL_OR_SYSTEM = CAT_SYSTEM or CAT_OFFICIAL

    /** 分类缓存上限，超了就整体清空（换账号 / 聊天室很多时兜底） */
    private const val CAT_CACHE_MAX = 8192

    private val CATEGORY = ConcurrentHashMap<String, Int>()

    // ------------------------------------------------------------ 字段方案

    /**
     * 数值字段包装：int 型字段直接用 `Field.getInt` 读，不再走 `get()`。
     * `get()` 每次都要把结果装箱成 Integer，一次扫描 400+ 会话 × 4 个数值字段
     * 就是上千个临时对象，是卡顿的主要来源之一。
     */
    internal class NumField(private val field: Field, private val primitive: Boolean) {
        fun read(item: Any): Int = try {
            if (primitive) field.getInt(item) else (field.get(item) as? Number)?.toInt() ?: 0
        } catch (t: Throwable) {
            0
        }

        companion object {
            fun of(f: Field) = NumField(f, f.type == Integer.TYPE)
        }
    }

    /** 一个会话类需要访问的字段集合 */
    internal class Plan(
        val username: Field?,
        val unread: List<NumField>,
        val verifyFlag: NumField?,
        val attrFlag: NumField?,
        val parentRef: Field?,
    )

    // ConcurrentHashMap.get 是无锁的；不用锁是因为 matches() 会被调用几百次
    private val plans = ConcurrentHashMap<Class<*>, Plan>()

    /** 按类缓存字段方案（沿继承链找字段这件事只做一次） */
    internal fun planOf(cls: Class<*>): Plan {
        plans[cls]?.let { return it }
        return plans.computeIfAbsent(cls) { buildPlan(it) }
    }

    private fun buildPlan(cls: Class<*>): Plan {
        val fields = ArrayList<Field>(40)
        var c: Class<*>? = cls
        var depth = 0
        // 最多向上找 8 层父类，覆盖微信的多层继承
        while (c != null && c != Any::class.java && depth++ < 8) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching { f.isAccessible = true }
                fields.add(f)
            }
            c = c.superclass
        }

        fun pick(names: Array<String>): Field? =
            fields.firstOrNull { f -> names.any { it == f.name } }

        return Plan(
            username = pick(USERNAME_NAMES),
            unread = fields.filter { f -> UNREAD_NAMES.any { it == f.name } }.map { NumField.of(it) },
            verifyFlag = pick(VERIFY_FLAG_NAMES)?.let { NumField.of(it) },
            attrFlag = pick(ATTR_FLAG_NAMES)?.let { NumField.of(it) },
            parentRef = pick(PARENT_REF_NAMES),
        )
    }

    // ------------------------------------------------------------ 取值
    // 用 try/catch 而不是 runCatching：这是热路径，少一次闭包分配

    internal fun stringOf(item: Any, f: Field?): String? {
        if (f == null) return null
        return try {
            val v = f.get(item) as? String
            if (v.isNullOrEmpty()) null else v
        } catch (t: Throwable) {
            null
        }
    }

    internal fun intOf(item: Any, f: NumField?): Int = f?.read(item) ?: 0

    internal fun sumIntOf(item: Any, fs: List<NumField>): Int {
        var sum = 0
        for (i in fs.indices) sum += fs[i].read(item)
        return sum
    }

    // ------------------------------------------------------------ 分组判定要素

    /**
     * 一条会话参与分组判定所需的全部信息，一次反射取齐 ——
     * 扫描时一条会话只反射一次，分类和未读数一起拿到。
     */
    internal class Facts(val cat: Int, val unread: Int)

    /** 判定失败（字段对不上 / 折叠会话）：任何具体分组都不收，「全部」除外 */
    private val NO_FACTS = Facts(0, 0)

    /**
     * 取一条会话的判定要素。
     * 折叠会话（parentRef = hidden_conv_parent）直接返回 [NO_FACTS]，因为它不归任何具体分组。
     */
    internal fun factsOf(item: Any): Facts {
        val plan = planOf(item.javaClass)
        val username = stringOf(item, plan.username) ?: return NO_FACTS
        if (stringOf(item, plan.parentRef) == HIDDEN_PARENT) return NO_FACTS
        return Facts(categoryOf(item, plan, username), sumIntOf(item, plan.unread))
    }

    // ------------------------------------------------------------ 分类（带缓存）

    /**
     * 一条会话属于哪一类，只取决于 username + verifyFlag + attrFlag，
     * 这三样在整个登录期间都不会变，所以按 username 缓存。
     * 命中缓存后，一次扫描每个会话只剩「读 username + 读 parentRef + 查表」。
     */
    internal fun categoryOf(item: Any, plan: Plan, username: String): Int {
        CATEGORY[username]?.let { return it }

        val vf = intOf(item, plan.verifyFlag)
        val af = intOf(item, plan.attrFlag)

        val chatroom = isChatRoom(username)
        // 顺序要紧：先按 ID 认公众号 / 服务号容器，再认微信自带官方号，
        // 最后才看标志位 —— 有些系统号也带着官方标志位，不能因此被当成公众号。
        val officialName = isOfficialName(username)
        val system = isSystemAccount(username)

        val cat = when {
            chatroom -> CAT_CHATROOM
            // 公众号 / 服务号（含容器 officialaccounts / service_officialaccounts）
            officialName -> CAT_OFFICIAL
            // 微信自带官方号 / 系统号：归到「好友」分组展示
            system -> CAT_SYSTEM
            // 标志位兜底：ID 认不出但带着官方位的，仍当作公众号 / 服务号
            isOfficialLike(vf, af) -> CAT_OFFICIAL
            // 开放平台号（形如 xxx@openim）不是好友
            username.contains('@') && !username.endsWith("@micromsg.qq.com") -> CAT_OFFICIAL
            else -> CAT_FRIEND
        }

        if (CATEGORY.size >= CAT_CACHE_MAX) CATEGORY.clear()
        CATEGORY[username] = cat
        return cat
    }

    // ------------------------------------------------------------ 判定规则

    /** 群聊：`@chatroom` 或 `@im.chatroom` 结尾 */
    private fun isChatRoom(username: String): Boolean =
        username.endsWith(CHATROOM) || username.endsWith(IM_CHATROOM)

    /**
     * 公众号 / 服务号【按 ID 判定】。
     *
     * 光靠 verifyFlag / attrflag 不够 —— 实测本机微信上「微信支付」和各种 `gh_` 服务号
     * 的这几个标志位并不总是有效，会漏到「好友」里。所以加上 ID 特征：
     *   gh_ 开头（且不是 gh_xxx@app）就是公众号 / 服务号
     *   officialaccounts / service_officialaccounts 是公众号 / 服务号的容器
     */
    private fun isOfficialName(username: String): Boolean {
        val u = username.lowercase()
        if (u == "officialaccounts" || u == "service_officialaccounts") return true
        return u.startsWith("gh_") && !u.endsWith("@app")
    }

    /** 公众号 / 服务号【按标志位判定】：verifyFlag & 8，或 attrflag 里的几个官方位 */
    private fun isOfficialLike(verifyFlag: Int, attrFlag: Int): Boolean =
        (verifyFlag and VERIFY_OFFICIAL) != 0 ||
            (attrFlag and ATTR_OFFICIAL_1) != 0 ||
            (attrFlag and ATTR_OFFICIAL_2) != 0 ||
            (attrFlag and ATTR_OFFICIAL_3) != 0

    /**
     * 微信内置功能号 / 系统号。
     * 正常会话 ID 都是小写（wxid_xxx / xxx@chatroom），所以先直接查，
     * 只有真的含大写字母时才 lowercase —— 否则每次调用都会凭空造一个 String。
     */
    private fun isSystemAccount(username: String): Boolean {
        if (username in SYSTEM_ACCOUNTS) return true
        for (c in username) {
            if (c in 'A'..'Z') return username.lowercase() in SYSTEM_ACCOUNTS
        }
        return false
    }
}
