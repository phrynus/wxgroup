package com.example.wxgroup

import android.app.Application
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * ★ 禁用置顶聊天折叠（移植自 WeKit 的 `DisablePinnedChatsCollapsing`）。
 *
 * 微信在置顶会话多到一屏放不下时，会在首页插一个「折叠置顶聊天」入口，
 * 把所有置顶会话收进去。本特性把这个入口彻底关掉，让置顶会话始终展开。
 *
 * 做法和 WeKit 一样 —— **三条路全堵死**：
 *
 *   1. `FolderHelper.addCollapseChatItem()` —— 「往列表里加折叠项」这一步，
 *      直接取消（`result = null`），这一项压根不会生成。
 *   2. `FolderHelper.checkIfShowFoldItem()` —— 「要不要显示折叠项」的判断，
 *      强制返回 `false`。
 *   3. `RecyclerFolderHelper.shouldShowFoldItem(int)` —— 较新微信的 MVVM 版列表
 *      （走 RecyclerView）里的同一判断，同样强制 `false`。
 *
 * 怎么定位这三个方法：和本项目其它地方（[AntiRecall]）一个套路 ——
 * **靠日志 tag 这类字符串常量反查**（[DexScan]），完全不依赖混淆后的类名 / 方法名。
 *
 * ───────────────────────────────────────────────────────────────────────
 * 与 WeKit 的一处差异（有意为之）
 *
 * WeKit 最后还会把数据库里残留的 `message_fold` 会话行直接删掉
 * （它自带 `WeDatabaseApi`，会反射拿到微信的 SQLite 句柄执行 DELETE）。
 *
 * 本模块的立身之本是「**不写微信的数据，只改它读的口子**」，所以这里不做删库，
 * 改为在 [ConvItem] 的会话投影里把 `message_fold` 这一行排除掉 —— 效果等价
 * （残留的折叠条目不再出现在分组列表里），但一个字节都不动数据库。
 * 折叠入口本身则由上面三条钩子关掉。
 * ───────────────────────────────────────────────────────────────────────
 *
 * 全模块不往 logcat 写任何东西 —— 少一个被宿主发现的口子。
 */
internal object PinnedFold {

    // ------------------------------------------------------------ 反查用字符串
    // 都是微信自己打日志用的常量，混淆不会动它们

    /** FolderHelper：加折叠项时打的日志（`addCollapseChatItem`） */
    private const val NEEDLE_FOLD_ITEM = "fold item exist"

    /** FolderHelper：判断是否显示折叠项时打的日志（`checkIfShowFoldItem`） */
    private const val NEEDLE_CHECK_SHOW = "checkIfShowFoldItem, ifShow:"

    /** RecyclerFolderHelper：MVVM 版列表里的日志（同一类里找 `shouldShowFoldItem(int)`） */
    private const val NEEDLE_RECYCLER = "performFoldItemClick: not ready"

    private val NEEDLES = listOf(NEEDLE_FOLD_ITEM, NEEDLE_CHECK_SHOW, NEEDLE_RECYCLER)

    private var installed = false

    // ------------------------------------------------------------------ 两个钩子

    /** 把方法「吃掉」：动画折叠项根本不会被加进列表 */
    private val dropHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
        }
    }

    /** 「要不要显示折叠项」永远回答 false；before / after 都设，防止宿主在方法体内回写 */
    private val forceFalse = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = false
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            param.result = false
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 在微信主进程里装钩子。dex 扫描较重，内部丢后台线程，不挡启动。
     */
    fun install(app: Application, cl: ClassLoader) {
        if (installed) return
        installed = true
        Thread({
            val hits = try {
                DexScan.scan(app, NEEDLES)
            } catch (t: Throwable) {
                return@Thread
            }
            runCatching { installHooks(cl, hits) }
        }).apply { isDaemon = true }.start()
    }

    private fun installHooks(cl: ClassLoader, hits: List<DexScan.Hit>) {
        // 1. addCollapseChatItem —— 直接吃掉，不让折叠项进列表
        for (h in hits.filter { NEEDLE_FOLD_ITEM in it.needles }) {
            hookHit(cl, h, dropHook)
        }

        // 2. checkIfShowFoldItem —— 判断结果永远 false
        for (h in hits.filter { NEEDLE_CHECK_SHOW in it.needles }) {
            hookHit(cl, h, forceFalse)
        }

        // 3. RecyclerFolderHelper —— 日志所在的方法未必是 shouldShowFoldItem 本身，
        //    所以在「含有这段日志的类」里按签名找 (int) -> boolean 的那个方法。
        for (h in hits.filter { NEEDLE_RECYCLER in it.needles }) {
            val cls = load(cl, h.className) ?: continue
            val m = findBooleanIntMethod(cls) ?: continue
            runCatching { XposedBridge.hookMethod(m, forceFalse) }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 按 [DexScan.Hit] 给出的 (类, 方法名, 参数个数) 找方法并挂钩子 */
    private fun hookHit(cl: ClassLoader, h: DexScan.Hit, hook: XC_MethodHook) {
        val cls = load(cl, h.className) ?: return
        val m = findMethod(cls, h.method, h.paramCount) ?: return
        runCatching { XposedBridge.hookMethod(m, hook) }
    }

    private fun load(cl: ClassLoader, name: String): Class<*>? = try {
        Class.forName(name, false, cl)
    } catch (t: Throwable) {
        null
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

    /** 找 `(int) -> boolean` 的方法（`shouldShowFoldItem(int)` 的签名特征） */
    private fun findBooleanIntMethod(cls: Class<*>): Method? {
        val intType = Int::class.javaPrimitiveType
        val boolType = Boolean::class.javaPrimitiveType
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && c != Any::class.java && depth++ < 12) {
            for (m in c.declaredMethods) {
                val pt = m.parameterTypes
                if (pt.size == 1 && pt[0] == intType && m.returnType == boolType) return m
            }
            c = c.superclass
        }
        return null
    }
}
