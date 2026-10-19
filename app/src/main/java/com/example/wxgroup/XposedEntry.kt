package com.example.wxgroup

import android.app.Application
import android.app.Instrumentation
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口（类名必须和 assets/xposed_init 一致）。
 *
 *   1. 拿到微信主进程的 Application（只注入主进程，:push / :appbrand 不碰）
 *   2. GroupHook —— 会话列表的分组过滤（索引投影）+ 顶部标签栏
 *   3. AntiRecall —— 防撤回：拦下撤回通知，把消息半透明留在原地
 *
 * 全模块不往 logcat 写任何东西 —— 少一个被宿主发现的口子。
 */
class XposedEntry : IXposedHookLoadPackage {

    private val started = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HOST) return
        val process = runCatching { Application.getProcessName() }.getOrNull()
            ?: lpparam.processName
        if (process != HOST) return
        hookApplication(lpparam)
    }

    /**
     * 拿 Application 的三个入口都挂上，谁先回调谁生效（started 去重）。
     * 只挂 makeApplication 在 Android 14+ 会失效 —— ActivityThread 改成直接调
     * makeApplicationInner，所以按名字前缀一并挂上。
     */
    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        val appHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.result as? Application
                    ?: param.args.getOrNull(0) as? Application
                    ?: param.thisObject as? Application
                    ?: return
                start(app)
            }
        }

        runCatching {
            Class.forName("android.app.LoadedApk", false, lpparam.classLoader)
                .declaredMethods
                .filter { it.name.startsWith("makeApplication") }
                .forEach { runCatching { XposedBridge.hookMethod(it, appHook) } }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                appHook,
            )
        }
    }

    /** 在微信主进程里拿到 Application 之后，把功能装起来（只会成功一次） */
    private fun start(app: Application) {
        if (!started.compareAndSet(false, true)) return
        // 聊天分组
        GroupStore.attach(app)
        GroupHook.install()
        // 防撤回：dex 扫描比较重，内部自己丢后台线程，不挡启动
        AntiRecall.install(app, app.classLoader)
        // 禁用置顶聊天折叠：同样是按字符串反查 + 后台扫描
        PinnedFold.install(app, app.classLoader)
    }

    companion object {
        private const val HOST = "com.tencent.mm"
    }
}
