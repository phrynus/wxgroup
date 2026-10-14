package com.example.wxgroup

/**
 * 分组定义 + 匹配规则。
 *
 * 标签栏顺序 = 枚举顺序：`未读 / 群聊 / 好友 / 其他 / 全部`，默认选中「未读」。
 *
 * 判定规则参考 WAuxv 的聊天分组 SQL，等价改写成对会话对象的字段判断。
 * 为什么不用改写 SQL 那套：微信收到新消息时是**直接在内存列表里插入会话**，
 * 不会重新查库，写在 SQL 里的 WHERE 拦不住这种内存插入，
 * 而且切分组时也没有安全的「重新查库」入口（重复调 MainUI.v0 会把列表建重）。
 */
enum class GroupKind(val key: String, val title: String) {

    /** 未读（默认）：有未读，且不是公众号 / 系统号 */
    UNREAD("unread", "未读"),

    /** 群聊：`@chatroom`（普通群）或 `@im.chatroom`（开放平台群） */
    GROUP_CHAT("group", "群聊"),

    /** 好友私聊 */
    FRIEND("friend", "好友"),

    /** 其他：既不是群聊也不是好友 —— 公众号 / 服务号 / 系统号 / 开放平台号 */
    OTHER("other", "其他"),

    /** 全部：不过滤 */
    ALL("all", "全部");

    /** 这条会话属于当前分组吗？ */
    fun matches(item: Any?): Boolean {
        if (this == ALL) return true
        if (item == null) return false

        val plan = ConvItem.planOf(item.javaClass)
        val username = ConvItem.stringOf(item, plan.username) ?: return false

        // 微信折叠起来的会话不归任何分组
        if (ConvItem.stringOf(item, plan.parentRef) == ConvItem.HIDDEN_PARENT) return false

        // 分类结果按 username 缓存，所以这里通常只是一次 map 查询
        val cat = ConvItem.categoryOf(item, plan, username)

        return when (this) {
            UNREAD -> ConvItem.sumIntOf(item, plan.unread) > 0 &&
                (cat and ConvItem.CAT_OFFICIAL_OR_SYSTEM) == 0

            GROUP_CHAT -> (cat and ConvItem.CAT_CHATROOM) != 0
            FRIEND -> (cat and ConvItem.CAT_FRIEND) != 0
            OTHER -> (cat and ConvItem.CAT_OTHER) != 0
            ALL -> true
        }
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
