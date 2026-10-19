package com.example.wxgroup

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * ★ 核心：会话列表的分组过滤（索引投影）。
 *
 * 做法：**不改微信的数据，只改它「读」的两个口子**。
 *
 *   原始列表： [0=A, 1=B, 2=C, 3=D]        ← 微信自己的数据，一个字都不动
 *   可见索引： [0, 2]                       ← 「群聊」过滤后剩下 A、C
 *
 *   getCount() ──► 返回 2                   after 钩子改返回值
 *   getView(0) ──► 实际取原始第 0 项          before 钩子改参数
 *   getView(1) ──► 实际取原始第 2 项
 *   getItem(1) ──► 实际取原始第 2 项          （点击时也要翻译，否则打开错会话）
 *
 * 为什么不用「改写 SQL」那套：微信收到新消息时是**直接在内存列表里插入会话**的，
 * 不会重新查库，SQL 里的 WHERE 拦不住；而且切分组也没有安全的重新查库入口。
 *
 * ⚠️ 这里有两条必须守住的铁律，否则会炸：
 *
 *   1. `getCount()` **只能查表，不能重算**。
 *      ListView 会在 layoutChildren() 里对 `mItemCount != getCount()`，
 *      一旦 getCount 在两次布局之间凭空变了个数，就会抛
 *      IllegalStateException("The content of the adapter has changed but
 *      ListView did not receive a notification")，表现就是微信闪退重启。
 *
 *   2. `visible` 只在**主线程**改，而且改完**立刻** notifyDataSetChanged()。
 *      绝不允许出现「count 已经变了、ListView 还不知道」的窗口。
 */
object GroupHook {

    private const val TAB_TAG = "wxgroup_tabs"

    /** adapter 未初始化时 notify 会抛异常，最多重试几次 */
    private const val RETRIES = 8
    private const val RETRY_DELAY_MS = 120L

    /** 已 hook 过的 adapter 类名，避免重复 hook 同一个方法 */
    private val hookedClasses = HashSet<String>()

    // ------------------------------------------------------------------ 热点状态
    // 会话列表只有一个，且只在 UI 线程访问，所以用 volatile 单槽就够；
    // 位置翻译是每渲染一行都要走的，必须做到无锁。

    @Volatile private var adapter: Any? = null
    @Volatile private var getItem: Method? = null
    @Volatile private var list: ListView? = null
    @Volatile private var bar: TabsBar? = null

    /** 可见项对应的原始索引；null = 当前不过滤 */
    @Volatile private var visible: IntArray? = null

    /** 我们自己触发的 notify 要忽略，否则会和 notify 钩子来回递归 */
    @Volatile private var selfNotify = false

    private var installed = false
    private var warmedUp = false

    private val main = Handler(Looper.getMainLooper())

    /**
     * 重入保护：扫描时要调 adapter.getItem()，那一瞬间必须「不过滤」，
     * 否则会把自己过滤后的结果再过滤一遍。用 ThreadLocal 避免多线程互相干扰。
     */
    private val bypass = object : ThreadLocal<Boolean>() {
        override fun initialValue() = false
    }

    // ------------------------------------------------------------------ 安装

    fun install() {
        if (installed) return
        installed = true
        XposedBridge.hookAllMethods(ListView::class.java, "setAdapter", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val lv = param.thisObject as? ListView ?: return
                val a = param.args.getOrNull(0) ?: return
                onAdapterSet(lv, a)
            }
        })
    }

    /**
     * 会话列表判定：只认类名带 conversation 的 ListView（微信叫 ConversationListView）。
     * 不能用「Activity 是不是 LauncherUI」判断 —— 通讯录 / 发现 / 我 这几个 tab
     * 用的是 PullDownListView，也在同一个 Activity 里，会误伤。
     */
    private fun onAdapterSet(lv: ListView, a: Any) {
        if (!lv.javaClass.name.contains("conversation", ignoreCase = true)) return
        if (a !is BaseAdapter) return

        val cls = a.javaClass
        val item = findMethod(cls, "getItem", 1, Any::class.java) ?: return
        val count = findMethod(cls, "getCount", 0, Int::class.javaPrimitiveType) ?: return
        val view = findMethod(cls, "getView", 3, View::class.java) ?: return

        adapter = a
        getItem = item
        list = lv
        // 先置 null：ListView 第一次布局时返回原始数量，随后 applyFilter 会补上过滤
        visible = null

        if (hookedClasses.add(cls.name)) {
            try {
                XposedBridge.hookMethod(count, countHook)
                // getView / getItem / getItemViewType 都要把「可见位置」翻译回「原始位置」
                XposedBridge.hookMethod(view, translateHook)
                XposedBridge.hookMethod(item, translateHook)
                findMethod(cls, "getItemViewType", 1, Int::class.javaPrimitiveType)
                    ?.let { XposedBridge.hookMethod(it, translateHook) }
                findMethod(cls, "notifyDataSetChanged", 0, null)
                    ?.let { XposedBridge.hookMethod(it, changedHook) }
            } catch (t: Throwable) {
                // 静默：本模块不往 logcat 写任何东西
            }
        }

        lv.post { injectBar(lv) }
        warmUp(a)
        // 刚 setAdapter 时微信的 adapter 内部还没初始化（mvvmList 是 lateinit），
        // 这时调 notifyDataSetChanged() 会抛异常，所以先等一下再算，失败会自动重试
        main.postDelayed({ applyFilter(RETRIES, rebind = false) }, 150L)
    }

    // ------------------------------------------------------------------ 三个钩子

    /** getCount：**只查表**，绝不在这里重算（见类注释的铁律 1） */
    private val countHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (bypass.get()) return
            if (param.thisObject !== adapter) return

            val v = visible ?: return          // 「全部」或还没算过：原样返回
            param.result = v.size
        }
    }

    /** 可见位置 -> 原始位置（每渲染一行都会走，所以只做一次数组下标访问） */
    private val translateHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (bypass.get()) return
            if (param.thisObject !== adapter) return

            val v = visible ?: return
            val pos = param.args.getOrNull(0) as? Int ?: return
            if (pos >= 0 && pos < v.size) {
                param.args[0] = v[pos]
            }
        }
    }

    /** 微信通知数据变化 -> 下一帧重算一次（重算 + notify 都放到主线程） */
    private val changedHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.thisObject !== adapter) return
            if (selfNotify) return
            main.post { applyFilter(RETRIES, rebind = false) }
        }
    }

    // ------------------------------------------------------------------ 重算 / 刷新

    /**
     * 重算映射 -> 通知 ListView ->（切分组时）强制重绑。
     * 微信 adapter 未初始化时 notify 会抛异常，所以失败要隔一会儿重试。
     *
     * @param rebind 只有用户主动切分组才需要强制重绑；
     *               新消息进来时绝对不能调 setSelection(0)，否则列表会跳回顶部。
     */
    private fun applyFilter(retries: Int, rebind: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { applyFilter(retries, rebind) }
            return
        }

        if (refresh()) {
            if (rebind) forceRebind()
            return
        }
        if (retries > 0) {
            main.postDelayed({ applyFilter(retries - 1, rebind) }, RETRY_DELAY_MS)
        }
    }

    /**
     * 重算「可见 -> 原始」映射，并立刻通知 ListView。**只在主线程调用**。
     *
     * 关键：notify 失败（adapter 内部还没初始化）时必须把 `visible` 回滚，
     * 否则会留下「count 变了但 ListView 不知道」的窗口，一滑动就抛
     * IllegalStateException 闪退。
     *
     * @return 是否成功通知
     */
    private fun refresh(): Boolean {
        val a = adapter ?: return false
        val g = getItem ?: return false

        val kind = GroupStore.current
        val raw = rawCount(a)

        // 「全部」就是不过滤，连扫都不用扫
        val next: IntArray? = when {
            kind == GroupKind.ALL -> null
            raw <= 0 -> IntArray(0)
            else -> {
                val buf = IntArray(raw)
                var n = 0
                bypass.set(true)
                try {
                    for (i in 0 until raw) {
                        val item = try {
                            g.invoke(a, i)
                        } catch (t: Throwable) {
                            null
                        } ?: continue

                        // 一条会话只反射一次（分类 + 未读数一次取齐）
                        val facts = ConvItem.factsOf(item)
                        if (facts.cat != 0 && kind.matches(facts)) buf[n++] = i
                    }
                } finally {
                    bypass.set(false)
                }
                buf.copyOf(n)
            }
        }

        val old = visible
        visible = next
        selfNotify = true
        return try {
            (a as? BaseAdapter)?.notifyDataSetChanged()
            true
        } catch (t: Throwable) {
            // 回滚，保证 getCount() 与 ListView 的 mItemCount 始终一致
            visible = old
            false
        } finally {
            selfNotify = false
        }
    }

    /**
     * 只 notifyDataSetChanged() 时，ListView 常常不会立刻重绑已经可见的那几行，
     * 表现为「切了分组要手动滑一下才刷新」。
     * setSelection(0) 让布局模式变成 LAYOUT_SPECIFIC 并 requestLayout，
     * invalidateViews() 把 mDataChanged 置 true —— 两个一起才能强制重绑。
     */
    private fun forceRebind() {
        list?.let {
            it.setSelection(0)
            it.invalidateViews()
        }
    }

    /** 取微信自己的原始条数（bypass 让 getCount 钩子不插手） */
    private fun rawCount(a: Any): Int {
        bypass.set(true)
        return try {
            (a as? BaseAdapter)?.count ?: 0
        } catch (t: Throwable) {
            0
        } finally {
            bypass.set(false)
        }
    }

    // ------------------------------------------------------------------ 切换分组

    /**
     * 点标签栏时调用。
     * 只换过滤条件，不重查库、不重建 Activity —— 所以是瞬间完成、也不黑屏。
     */
    fun select(kind: GroupKind) {
        if (GroupStore.current == kind) return
        GroupStore.select(kind)
        bar?.rebuild()

        // 重算放到主线程队列里：在它跑之前 getCount 一直返回旧值，
        // 和 ListView 的 mItemCount 始终一致，不会出现「count 变了但没人通知」的窗口
        main.post { applyFilter(RETRIES, rebind = true) }
    }

    // ------------------------------------------------------------------ 注入标签栏

    private fun injectBar(lv: ListView) {
        if (lv.findViewWithTag<View>(TAB_TAG) != null) return
        val tabs = TabsBar(lv.context).apply {
            tag = TAB_TAG
            layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT,
            )
            onSelect = { select(it) }
        }
        lv.addHeaderView(tabs)
        bar = tabs
        tabs.rebuild()
    }

    // ------------------------------------------------------------------ 预热

    /**
     * 首次扫描要 100ms+，因为反射调用点还没被 JIT 编译。
     * 先在后台把热路径跑几百遍，JIT 编译结果是全局的，
     * 之后主线程的首次扫描就从上百毫秒降到十几毫秒。
     */
    private fun warmUp(a: Any) {
        if (warmedUp) return
        warmedUp = true
        val g = getItem ?: return
        Thread({
            try {
                val sample = g.invoke(a, 0) ?: return@Thread
                val kind = GroupStore.current
                for (i in 0 until 400) kind.matches(sample)
            } catch (t: Throwable) {
                // 预热失败无所谓
            }
        }).apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ 工具

    /** 沿继承链找方法（不依赖任何混淆信息） */
    private fun findMethod(
        cls: Class<*>,
        name: String,
        paramCount: Int,
        returnType: Class<*>?,
    ): Method? {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 12) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (m.parameterTypes.size != paramCount) continue
                if (returnType != null && !returnType.isAssignableFrom(m.returnType)) continue
                return m
            }
            c = c.superclass
        }
        return null
    }
}
