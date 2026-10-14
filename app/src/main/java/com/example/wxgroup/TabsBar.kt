package com.example.wxgroup

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 会话列表顶部的分组标签栏。
 *
 * 参考 WAuxv 的 ModuleTabLayout：扁平文字标签 + 底部一条滑动指示条，
 * 配色改用微信自己的色板；纯代码构建，不吃任何 XML 资源，
 * 所以注入到微信里不会和宿主资源冲突。
 *
 * 标签**等宽平分**整行（不横向滚动），指示条用位移动画滑到选中的那个下面。
 *
 *   品牌绿（浅色） #07C160      微信按钮 / 强调色
 *   品牌绿（深色） #3EB575
 *   次要文字灰     #888888 / #B2B2B2
 *   列表背景       #EDEDED / #111111
 */
class TabsBar(context: Context) : LinearLayout(context) {

    /** 点中某个分组时回调 */
    var onSelect: ((GroupKind) -> Unit)? = null

    private val row = LinearLayout(context)
    private val indicator = View(context)
    private val tabs = ArrayList<View>(GroupKind.entries.size)

    /** 上次对齐的分组下标，用来判断这次要不要走滑动动画 */
    private var lastSyncedIndex = -1

    private val indicatorHeight: Int get() = dp(3)
    private val indicatorWidth: Int get() = dp(18)

    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val colorSelected: Int
        get() = if (dark) 0xFF3EB575.toInt() else 0xFF07C160.toInt()

    private val colorNormal: Int
        get() = if (dark) 0xFFB2B2B2.toInt() else 0xFF888888.toInt()

    private val colorBarBg: Int
        get() = if (dark) 0xFF111111.toInt() else 0xFFEDEDED.toInt()

    private val colorDivider: Int
        get() = if (dark) 0xFF2E2E2E.toInt() else 0xFFE5E5E5.toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(colorBarBg)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // 底部留出指示条的高度，避免压住文字
        row.setPadding(0, dp(4), 0, dp(4) + indicatorHeight)

        indicator.background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(colorSelected)
        }

        // 指示条和标签行放同一个 FrameLayout 里，指示条按标签位置平移
        val content = FrameLayout(context)
        content.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            indicator,
            FrameLayout.LayoutParams(
                indicatorWidth,
                indicatorHeight,
                Gravity.BOTTOM or Gravity.START,
            ),
        )

        addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        addView(
            View(context).apply { setBackgroundColor(colorDivider) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )
    }

    /** 重建全部标签（初始化 / 切分组时调用） */
    fun rebuild() {
        row.removeAllViews()
        tabs.clear()

        val selectedId = GroupStore.current
        for (kind in GroupKind.entries) {
            val tab = buildTab(kind, kind == selectedId)
            tabs.add(tab)
            row.addView(tab)
        }
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        val index = selectedIndex()
        if (index < 0) return

        if (lastSyncedIndex != index) {
            // 换分组：首次直接就位，之后走滑动动画
            syncIndicator(lastSyncedIndex >= 0)
            lastSyncedIndex = index
        } else if (changed) {
            // 只是宽高变了（旋转 / 字体 / 从聊天页返回重新布局）：只重新就位，不起动画
            syncIndicator(false)
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun selectedIndex(): Int = GroupKind.entries.indexOf(GroupStore.current)

    private fun buildTab(kind: GroupKind, selected: Boolean): View {
        val label = TextView(context).apply {
            text = kind.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (selected) colorSelected else colorNormal)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            // 宽度 0 + weight 1：每个标签等宽平分整行；文字居中
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
            setOnClickListener {
                if (GroupStore.current != kind) onSelect?.invoke(kind)
            }
        }
        return label
    }

    /** 把指示条移到选中标签的正下方居中 */
    private fun syncIndicator(animate: Boolean) {
        val tab = tabs.getOrNull(selectedIndex()) ?: return
        if (tab.width == 0) return

        val target = tab.left + (tab.width - indicatorWidth) / 2f
        if (animate) {
            indicator.animate().translationX(target).setDuration(160L).start()
        } else {
            indicator.animate().cancel()
            indicator.translationX = target
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
