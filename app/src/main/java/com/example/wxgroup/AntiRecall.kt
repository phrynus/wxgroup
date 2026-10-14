package com.example.wxgroup

import android.app.Application
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * ★ 防撤回：撤回通知拦下来，原消息留着，并且**半透明显示**。
 *
 * 思路完全是「只改微信读的口子」，一个字都不动它的数据：
 *
 *   [对方撤回]  微信的 sysmsg XML 解析出来是个 Map
 *      `.sysmsg.$type`        = "revokemsg"        ← 撤回标志
 *      `.sysmsg.revokemsg.newmsgid` = 原消息 msgSvrId
 *      钩住解析结果，把 `$type` 置成 null —— 微信一看没有类型就跳过处理，
 *      原消息压根不会被替换成「xxx 撤回了一条消息」。
 *
 *   [自己撤回]  同一个 sysmsg 通道，服务器同样会推回来（自己也在会话成员里），
 *      所以不用额外钩撤回场景，一条路就够；对自己发的消息也一视同仁。
 *
 *   [半透明]    钩住聊天条目的绑定回调，从参数里扒出消息对象，
 *      拿 `field_msgSvrId` 去 [RecallStore] 查，命中就把整行 alpha 调到 0.4。
 *
 * 难点是「怎么找到这些方法」：微信的类名方法名全被混淆了（8.0.79 实测），
 * 但日志 tag 这类字符串常量不会。所以用 [DexScan] 按字符串反查 ——
 * 和 WAuxv / WeKit 用的是同一种办法，只是没引 DexKit。
 */
internal object AntiRecall {

    // ------------------------------------------------------------ 反查用字符串
    // 这些是微信自己打日志用的常量，混淆不会动它们

    /** XmlParser 的日志 tag */
    private const val NEEDLE_XML = "MicroMsg.SDK.XmlParser"

    /** 聊天条目绑定的日志 tag（两个都出现在同一个方法里） */
    private const val NEEDLE_BIND_A = "MicroMsg.MvvmChattingItem"
    private const val NEEDLE_BIND_B = "[onBindView]"

    private val NEEDLES = listOf(NEEDLE_XML, NEEDLE_BIND_A, NEEDLE_BIND_B)

    // ------------------------------------------------------------ sysmsg 键名

    private const val ROOT_TAG = "sysmsg"
    private const val REVOKE_TAG = "revokemsg"
    private const val TYPE_KEY = ".sysmsg.\$type"
    private const val NEW_MSG_ID_KEY = ".sysmsg.revokemsg.newmsgid"

    /** 撤回消息的半透明度：越接近 0 越淡 */
    private const val DIM_ALPHA = 0.4f

    /** 行 View -> 它当前显示的消息 svrId，用来在撤回发生时把在屏的行立刻变淡 */
    private val bindings = Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 被我们调暗过的行；回收复用时才知道要不要还原 alpha */
    private val dimmed = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** androidx 的 ViewHolder；模块没引 androidx，所以反射拿，拿不到就算了 */
    private val viewHolderClass: Class<*>? by lazy {
        runCatching { Class.forName("androidx.recyclerview.widget.RecyclerView\$ViewHolder") }
            .getOrNull()
    }

    // ------------------------------------------------------------------ 安装

    fun install(app: Application, cl: ClassLoader) {
        RecallStore.attach(app)
        Thread({
            val hits = try {
                DexScan.scan(app, NEEDLES)
            } catch (t: Throwable) {
                return@Thread
            }
            installXmlParser(cl, hits)
            installChatItem(cl, hits)
        }).apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ XmlParser

    /**
     * 参考实现是「这个类里所有 (String, String) -> Map 的方法都挂上」——
     * 因为解析入口有好几个重载，只认名字容易漏。
     */
    private fun installXmlParser(cl: ClassLoader, hits: List<DexScan.Hit>) {
        val classes = hits.filter { NEEDLE_XML in it.needles }.map { it.className }.distinct()
        for (name in classes) {
            val c = try {
                Class.forName(name, false, cl)
            } catch (t: Throwable) {
                continue
            }
            for (m in c.declaredMethods) {
                val pt = m.parameterTypes
                if (pt.size >= 2 &&
                    pt[0] == String::class.java &&
                    pt[1] == String::class.java &&
                    Map::class.java.isAssignableFrom(m.returnType)
                ) {
                    runCatching { XposedBridge.hookMethod(m, xmlHook) }
                }
            }
        }
    }

    private val xmlHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // 任何异常都不能冒到宿主里去
            runCatching { onXmlParsed(param) }
        }
    }

    private fun onXmlParsed(param: XC_MethodHook.MethodHookParam) {
        val xml = param.args.getOrNull(0) as? String ?: return
        val rootTag = param.args.getOrNull(1) as? String ?: return
        if (rootTag != ROOT_TAG || !xml.contains(REVOKE_TAG)) return

        val map = param.result as? MutableMap<Any?, Any?> ?: return
        if (map[TYPE_KEY] != REVOKE_TAG) return

        val newMsgId = map[NEW_MSG_ID_KEY] as? String ?: return
        val serverId = newMsgId.toLongOrNull() ?: return

        // 关键一步：抹掉类型，微信就不会去处理这条撤回了，原消息一个字都不动
        map[TYPE_KEY] = null

        if (RecallStore.record(serverId)) dimVisibleRows()
    }

    // ------------------------------------------------------------------ 聊天条目

    private fun installChatItem(cl: ClassLoader, hits: List<DexScan.Hit>) {
        val hit = hits.firstOrNull { NEEDLE_BIND_A in it.needles && NEEDLE_BIND_B in it.needles }
            ?: return
        val c = try {
            Class.forName(hit.className, false, cl)
        } catch (t: Throwable) {
            return
        }
        val m: Method? = findMethod(c, hit.method, hit.paramCount) ?: return
        runCatching { XposedBridge.hookMethod(m, bindHook) }
    }

    private val bindHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching { onBind(param) }
        }
    }

    /**
     * 绑定参数里第一个「能当 ViewHolder 用的」就是行，第一个「带消息字段的」就是消息。
     * 不硬套 (holder, item) 的位置，免得不同版本参数顺序不一样。
     */
    private fun onBind(param: XC_MethodHook.MethodHookParam) {
        var row: View? = null
        var serverId = 0L
        for (a in param.args) {
            if (row == null) row = rowViewOf(a)
            if (serverId == 0L) serverId = MsgInfo.serverIdOf(a)
        }
        val view = row ?: return

        if (serverId == 0L) {
            clearDim(view)
            return
        }
        bindings[view] = serverId
        if (RecallStore.isRecalled(serverId)) applyDim(view, serverId) else clearDim(view)
    }

    /**
     * 把已撤回的那一行调成半透明。
     *
     * 必须延到**下一帧**再设：微信绑定完之后自己还会去动这一行
     * （复用、条目动画），在 afterHookedMethod 里当场设马上就会被覆盖掉。
     * 设之前再确认一次这行显示的仍是那条消息，防止复用到别的会话上。
     */
    private fun applyDim(view: View, serverId: Long) {
        dimmed[view] = true
        view.postOnAnimation {
            if (bindings[view] == serverId && RecallStore.isRecalled(serverId)) {
                view.alpha = DIM_ALPHA
            }
        }
    }

    /** 这一行不是被撤回的消息，把透明度还原（否则复用时会「传染」） */
    private fun clearDim(view: View) {
        if (dimmed.remove(view) != null) view.alpha = 1f
    }

    /** 撤回发生的瞬间，把已经在屏幕上的那几行也立刻调暗（否则要滑动一下才生效） */
    private fun dimVisibleRows(): Int {
        val snapshot: List<Pair<View, Long>>
        synchronized(bindings) {
            snapshot = bindings.entries.map { it.key to it.value }
        }
        var n = 0
        for ((view, id) in snapshot) {
            if (RecallStore.isRecalled(id)) {
                applyDim(view, id)
                n++
            }
        }
        return n
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 绑定参数 -> 这一行的 itemView。
     *
     * 坑：不能「哪个字段是 View 就用哪个」。ViewHolder 里往往还挂着一个指向父列表
     * （RecyclerView）的字段，先撞上它就会把整个列表当行来调暗 —— 实测 8.0.79 上
     * 就会先拿到 ScrollControlRecyclerView。所以查找顺序是：
     *
     *   ① androidx 标准 ViewHolder -> 直接读 itemView
     *   ② 名字叫 itemView、或声明类型**正好是 android.view.View** 的字段
     *      （`ViewHolder.itemView` 的经典声明，参考实现也是先找这个）
     *   ③ 兜底：类型是 View 子类，但把列表 / 滚动容器本身排除掉
     */
    private fun rowViewOf(holder: Any?): View? {
        if (holder == null) return null
        if (holder is View) return if (isListLike(holder)) null else holder

        viewHolderClass?.let { vh ->
            if (vh.isInstance(holder)) {
                val v = runCatching { vh.getMethod("itemView").invoke(holder) }.getOrNull()
                if (v is View) return v
            }
        }

        var fallback: View? = null
        var c: Class<*>? = holder.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 6) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (!View::class.java.isAssignableFrom(f.type)) continue
                val v = try {
                    f.isAccessible = true
                    f.get(holder)
                } catch (t: Throwable) {
                    null
                } as? View ?: continue

                if (f.name == "itemView" || f.type == View::class.java) return v
                if (fallback == null && !isListLike(v)) fallback = v
            }
            c = c.superclass
        }
        return fallback
    }

    /** 列表 / 滚动容器本身不算「一行」 */
    private fun isListLike(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        while (c != null && c != Any::class.java) {
            val n = c.name
            if (n.contains("RecyclerView") || n.contains("AbsListView") ||
                n.contains("ScrollView") || n.contains("ViewPager")
            ) {
                return true
            }
            c = c.superclass
        }
        return false
    }

    /** 沿继承链按 名字 + 参数个数 找方法 */
    private fun findMethod(cls: Class<*>, name: String, paramCount: Int): Method? {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 12) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == paramCount) return m
            }
            c = c.superclass
        }
        return null
    }
}
