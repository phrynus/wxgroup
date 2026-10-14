package com.example.wxgroup

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections

/**
 * 被撤回消息的账本。
 *
 * 只记「服务端消息 ID」就够了 —— 撤回通知里的 `newmsgid` 就是被撤回那条消息的
 * `msgSvrId`（参考实现也是拿它去 `message` 表按 `msgSvrId` 反查 talker 的），
 * 而渲染时消息对象身上正好有 `field_msgSvrId`，两边一对就能匹配上，
 * 所以**完全不用碰微信的数据库**。
 *
 * 上限 [MAX] 条，超了丢最老的 —— 撤回记录没有长期保留的价值。
 */
internal object RecallStore {

    private const val NAME = "wxgroup"
    private const val KEY = "recall_ids"
    private const val MAX = 4000

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 用 LinkedHashSet 保住写入顺序，方便超限时丢最老的 */
    @Volatile
    private var ids: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun attach(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        // getStringSet 返回的集合不许改，所以拷一份
        ids = Collections.synchronizedSet(LinkedHashSet(sp.getStringSet(KEY, null) ?: emptySet()))
        prefs = sp
    }

    fun isRecalled(serverId: Long): Boolean =
        serverId != 0L && ids.contains(serverId.toString())

    /** @return true = 这是一条新记录；false = 早就记过了 */
    fun record(serverId: Long): Boolean {
        if (serverId == 0L) return false
        val key = serverId.toString()
        synchronized(ids) {
            if (!ids.add(key)) return false
            while (ids.size > MAX) {
                val first = ids.firstOrNull() ?: break
                ids.remove(first)
            }
            prefs?.edit()?.putStringSet(KEY, HashSet(ids))?.apply()
        }
        return true
    }
}
