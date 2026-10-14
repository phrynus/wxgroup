package com.example.wxgroup

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 从聊天条目的绑定参数里，把「消息对象」扒出来。
 *
 * 和 [ConvItem] 一个思路：**不看类名，只看数据库字段名**。
 * 微信的聊天条目绑定回调形如 `onBindView(holder, item, …)`，
 * 真正带 `field_talker` / `field_msgSvrId` / `field_isSend` 的对象
 * 可能是 `item` 本身，也可能埋在它下面一两层，所以这里做一次浅层搜索。
 */
internal object MsgInfo {

    private const val F_TALKER = "field_talker"
    private const val F_SVR = "field_msgSvrId"
    private const val F_SEND = "field_isSend"

    internal class Fields(val talker: Field?, val serverId: Field?, val isSend: Field?) {
        val usable: Boolean get() = talker != null && serverId != null && isSend != null
    }

    /** 类 -> 字段方案；一次搜索要问很多次，必须缓存 */
    private val cache = ConcurrentHashMap<Class<*>, Fields>()

    private fun fieldsOf(cls: Class<*>): Fields = cache.computeIfAbsent(cls) { build(it) }

    private fun build(cls: Class<*>): Fields {
        var talker: Field? = null
        var serverId: Field? = null
        var isSend: Field? = null

        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 8) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                when (f.name) {
                    F_TALKER -> if (talker == null) talker = f
                    F_SVR -> if (serverId == null) serverId = f
                    F_SEND -> if (isSend == null) isSend = f
                }
            }
            c = c.superclass
        }
        for (f in arrayOf(talker, serverId, isSend)) {
            try {
                f?.isAccessible = true
            } catch (t: Throwable) {
                // 忽略：读的时候还会再兜一次
            }
        }
        return Fields(talker, serverId, isSend)
    }

    /** 服务端消息 ID；找不到返回 0 */
    fun serverIdOf(root: Any?): Long {
        val msg = find(root, 2) ?: return 0L
        return longField(msg, fieldsOf(msg.javaClass).serverId)
    }

    fun talkerOf(root: Any?): String? {
        val msg = find(root, 2) ?: return null
        val f = fieldsOf(msg.javaClass).talker ?: return null
        return try {
            f.get(msg) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun longField(o: Any, f: Field?): Long {
        if (f == null) return 0L
        return try {
            when (val v = f.get(o)) {
                is Long -> v
                is Number -> v.toLong()
                else -> 0L
            }
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 在 [node] 及其非基本类型字段里找消息对象，最多往下钻 [depth] 层。
     * depth 用完就返回 null，所以不会因为对象图成环而无限递归。
     */
    private fun find(node: Any?, depth: Int): Any? {
        if (node == null || depth < 0) return null
        if (fieldsOf(node.javaClass).usable) return node
        if (node is String || node is Number || node is Boolean || node.javaClass.isArray) return null

        var c: Class<*>? = node.javaClass
        var level = 0
        while (c != null && c != Any::class.java && level++ < 4) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                val v = try {
                    f.isAccessible = true
                    f.get(node)
                } catch (t: Throwable) {
                    null
                }
                if (v != null) find(v, depth - 1)?.let { return it }
            }
            c = c.superclass
        }
        return null
    }
}
