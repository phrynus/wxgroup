package com.example.wxgroup

/**
 * 分组定义 + 匹配规则。
 *
 * 标签栏顺序 = 枚举顺序：`未读 / 群聊 / 好友 / 全部`，默认选中「未读」。
 *
 * 「好友」= 好友私聊 + 微信自带官方号 / 系统号（文件传输助手、微信团队…），
 * 但**不含公众号 / 服务号** —— 那两类只会在「全部」里出现。
 *
 * 判定规则参考 WAuxv 的聊天分组 SQL，等价改写成对会话对象的字段判断。
 * 为什么不用改写 SQL 那套：微信收到新消息时是**直接在内存列表里插入会话**，
 * 不会重新查库，写在 SQL 里的 WHERE 拦不住这种内存插入，
 * 而且切分组时也没有安全的「重新查库」入口（重复调 MainUI.v0 会把列表建重）。
 */
enum class GroupKind(val key: String, val title: String) {

    /** 未读（默认）：有未读，且不是公众号 / 系统号（文件传输助手 / QQ 邮箱提醒例外） */
    UNREAD("unread", "未读"),

    /** 群聊：`@chatroom`（普通群）或 `@im.chatroom`（开放平台群） */
    GROUP_CHAT("group", "群聊"),

    /** 好友私聊 + 微信自带官方号 / 系统号（不含公众号 / 服务号） */
    FRIEND("friend", "好友"),

    /** 全部：不过滤 */
    ALL("all", "全部");

    /** 这条会话属于当前分组吗？（单条查询用；批量扫描请用下面的重载） */
    fun matches(item: Any?): Boolean {
        if (this == ALL) return true
        if (item == null) return false

        val facts = ConvItem.factsOf(item)
        // cat == 0 表示判定失败（字段对不上 / 折叠会话），具体分组都不收
        return facts.cat != 0 && matches(facts)
    }

    /**
     * 用已经取好的判定要素判断 —— 扫描时一条会话只反射一次，
     * 各个分组复用同一份 [ConvItem.Facts]。
     */
    internal fun matches(f: ConvItem.Facts): Boolean = when (this) {
        // 未读：有未读，且不是公众号 / 服务号 / 系统号；
        // 白名单里的系统号（文件传输助手、QQ 邮箱提醒）例外收下
        UNREAD -> f.unread > 0 &&
            ((f.cat and ConvItem.CAT_OFFICIAL_OR_SYSTEM) == 0 || f.allowUnread)

        GROUP_CHAT -> (f.cat and ConvItem.CAT_CHATROOM) != 0
        // 好友：真好友 + 微信自带官方号 / 系统号
        FRIEND -> (f.cat and (ConvItem.CAT_FRIEND or ConvItem.CAT_SYSTEM)) != 0
        ALL -> true
    }

    companion object {
        val DEFAULT = UNREAD

        fun byKey(key: String?): GroupKind =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 当前选中的分组，存在自己的 SharedPreferences 里 */
object GroupStore {

    private const val NAME = "wxgroup"
    private const val KEY = "group"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /** 默认「未读」；过滤时会被频繁读，所以用 volatile */
    @Volatile
    var current: GroupKind = GroupKind.DEFAULT
        private set

    fun attach(context: android.content.Context) {
        if (prefs != null) return
        val sp = context.applicationContext
            .getSharedPreferences(NAME, android.content.Context.MODE_PRIVATE)
        prefs = sp
        current = GroupKind.byKey(sp.getString(KEY, null))
    }

    fun select(kind: GroupKind) {
        if (kind == current) return
        current = kind
        prefs?.edit()?.putString(KEY, kind.key)?.apply()
    }
}
