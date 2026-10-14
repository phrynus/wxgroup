# 微信会话分组

LSPosed / Xposed 模块。在微信会话列表顶部加一条分组标签栏：

```
未读   群聊   好友   其他   全部
```

只做这一件事，不依赖 DexKit，也不需要任何 native 库。

---

## 核心原理：索引投影

**完全不碰微信的数据，只改它「读」列表的两个口子。**

```
原始列表： [0=A, 1=B, 2=C, 3=D]      ← 微信自己的数据，一个字都不动
可见索引： [0, 2]                     ← 「群聊」过滤后剩下 A、C

getCount()  →  2                       after 钩子改返回值
getView(0)  →  取原始第 0 项            before 钩子改参数
getView(1)  →  取原始第 2 项
getItem(1)  →  取原始第 2 项            （点击也要翻译，否则打开错会话）
```

挂载点是 `ListView#setAdapter` —— Android 框架方法，永远不会被混淆，
比去反射微信内部的 Activity / 字段稳得多。

改完之后，微信自己的已读上报、置顶、长按菜单全部照常工作，因为它的数据始终是完整的。

### 两条必须守住的铁律

否则会 `IllegalStateException` 闪退（`ListView.layoutChildren` 会校验 `mItemCount`）：

1. **`getCount()` 只能查表，不能重算。** 一旦它在两次布局之间凭空变了个数，
   ListView 就认为「数据变了但没人通知」。
2. **`visible` 只在主线程改，改完立刻 `notifyDataSetChanged()`。**
   绝不允许出现「count 已经变了、ListView 还不知道」的窗口。

### 为什么不是「改写 SQL」

也试过拦截会话列表查询、把 `from rconversation` 之后换成带分组条件的 SQL，但实测走不通：

- 微信收到新消息是**直接在内存列表里插入会话**，不重新查库，SQL 的 `WHERE` 拦不住；
- 切分组必须让微信重新查一次库，而唯一的加载入口 `MainUI.v0(Bundle)` 是一次性初始化方法，
  重复调用会把列表建重，表现为「第一次正常，之后混乱」。

所以回到索引投影：切分组只是换过滤条件 + 重绑，瞬间完成、不黑屏、新消息也会被正确过滤。

---

## 分组规则

所有分组都排除 `parentRef = hidden_conv_parent`（微信折叠起来的会话）。

| 分组 | 判定 |
| --- | --- |
| 未读 | `unReadCount + unReadMuteCount > 0`，且不是公众号 / 系统号 |
| 群聊 | ID 以 `@chatroom` 或 `@im.chatroom` 结尾 |
| 好友 | 不是群聊、不是公众号、不是系统号，且 ID 不含 `@`（`@micromsg.qq.com` 除外） |
| 其他 | 以上都不是：公众号、服务号、微信支付、系统号、企业微信外部联系人 |
| 全部 | 不过滤 |

公众号 / 服务号同时按 **ID 特征**和**标志位**判定：

- ID：`gh_` 开头（且不是 `gh_xxx@app`）、`officialaccounts`、`service_officialaccounts`
- 标志位：`verifyFlag & 8`，或 `attrflag & 0x200000 / 0x800000 / 0x1000000`

> 只靠标志位不够 —— 实测「微信支付」和不少 `gh_` 服务号的标志位并不总是有效，会漏到「好友」里。

---

## 抗混淆

微信的类名每个版本都变（`iq5.a1`、`fj5.b0`…），所以代码里**不看类名**：

- 入口用框架方法 `ListView#setAdapter` + 「ListView 类名含 `conversation`」
- 条目识别靠**数据库字段名**：`field_username` / `field_unReadCount` /
  `field_verifyFlag` / `field_attrflag` / `field_parentRef`
- 取字段的反射方案按类缓存，分类结果按 username 缓存

---

## 目录结构

```
app/src/main/java/com/example/wxgroup/
├── XposedEntry.kt   入口：只注入微信主进程，拿到 Application 后启动
├── GroupHook.kt     ★ 索引投影 + 标签栏注入 + 切分组
├── TabsBar.kt       标签栏 UI（纯代码构建，不吃 XML 资源）
├── GroupModel.kt    分组定义、匹配规则、选中项持久化
└── ConvItem.kt      ★ 抗混淆：按字段名定位会话对象
```

---

## 编译

| 项 | 值 |
| --- | --- |
| Gradle | 8.9 |
| JDK | 17 |
| AGP | 8.7.3 |
| Kotlin | 2.1.0 |
| compileSdk / minSdk / targetSdk | 34 / 28 / 34 |

```powershell
# ⚠️ 项目路径必须是纯 ASCII —— 含中文会被 AGP 直接拒绝
#    （或在 gradle.properties 里加 android.overridePathCheck=true）
gradle :app:assembleDebug
```

> 本机默认 JDK 不是 17 时，先设 `JAVA_HOME`，或取消 `gradle.properties`
> 里 `org.gradle.java.home` 那行的注释并改成自己的 JDK 17 路径。

产物：`app/build/outputs/apk/debug/app-debug.apk`。

依赖只有一个，且是 `compileOnly`，不会打进 APK：

```kotlin
compileOnly("de.robv.android.xposed:api:82")
```

---

## 安装

```powershell
adb install -r app-debug.apk
```

然后在 LSPosed 里启用模块、作用域勾选微信（`com.tencent.mm`），强制停止微信后重新打开。

日志（`adb logcat -s WxGroup`）正常只有两行：

```
启动完成，当前分组 = 未读
已接管会话列表（ConversationListView）
```

---

## 已知限制

| 现象 | 原因 / 处理 |
| --- | --- |
| 没有「已接管会话列表」 | 本机微信可能用了 RecyclerView 版列表，本版本只支持 ListView |
| 分组内容不对 | 字段名不匹配，把实际的 `field_xxx` 加进 `ConvItem` 的候选名数组 |
| 点击会话打开错人 | 该版本不通过 `adapter.getItem()` 取数据，删掉 `GroupHook` 里 hook `getItem` 的那一行 |
| 分类不准 | 在 `ConvItem.SYSTEM_ACCOUNTS` 里补 ID，或调整 `isOfficialName` |

---

## 免责声明

仅用于学习 Android 逆向与 Xposed 框架原理。请遵守当地法律法规与软件许可协议，
不要用于绕过付费、窃取数据或侵犯他人权益。
