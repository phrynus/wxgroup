# 微信会话分组

LSPosed / Xposed 模块，作用在微信（`com.tencent.mm`）的会话列表上，包含两个独立功能：

1. **会话分组** —— 在会话列表顶部注入一条等宽标签栏 `未读 / 群聊 / 好友 / 全部`。
   切换分组只改变「显示哪些会话」，微信自身的数据一个字节都不动。
2. **撤回消息保留** —— 拦下撤回通知，让被撤回的消息内容留在本地并半透明显示，
   而不是被替换成「xxx 撤回了一条消息」。

不依赖 DexKit，也没有 native 代码；唯一的编译期依赖是 Xposed API，且为 `compileOnly`，不会打进 APK。

> 非官方项目，与腾讯公司及微信无任何关联，未获其授权或认可。

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

| 分组 | 判定                                                                                           |
| ---- | ---------------------------------------------------------------------------------------------- |
| 未读 | `unReadCount + unReadMuteCount > 0`，且不是公众号 / 服务号 / 系统号                            |
| 群聊 | ID 以 `@chatroom` 或 `@im.chatroom` 结尾                                                       |
| 好友 | 真好友 + 微信自带官方号 / 系统号（文件传输助手、微信团队、服务通知…），**不含公众号 / 服务号** |
| 全部 | 不过滤（公众号 / 服务号 只在这里出现）                                                         |

各类只占一个位标记：`CAT_CHATROOM` / `CAT_FRIEND` / `CAT_SYSTEM` / `CAT_OFFICIAL`，
分类顺序是「群聊 → 公众号（含容器 `officialaccounts`）→ 微信自带官方号 → 标志位 → 开放平台号 → 好友」，
所以带着官方标志位的系统号不会因此被误判成公众号。

`refresh()` 里扫一遍原始列表，每条会话只反射一次得到一个 `ConvItem.Facts`（分类 + 未读数），
所以扫一趟就把过滤做完了：

```
Facts(cat, unread) -> 当前分组 matches(cat, unread) -> 写进 visible 映射
```

> 选中「全部」时直接跳过扫描，`visible` 置 null，`getCount()` 原样返回。

公众号 / 服务号同时按 **ID 特征**和**标志位**判定：

- ID：`gh_` 开头（且不是 `gh_xxx@app`）、`officialaccounts`、`service_officialaccounts`
- 标志位：`verifyFlag & 8`，或 `attrflag & 0x200000 / 0x800000 / 0x1000000`

> 只靠标志位不够 —— 实测「微信支付」和不少 `gh_` 服务号的标志位并不总是有效，会漏到「好友」里。

---

## 撤回消息保留

思路和分组一样：**不改微信的数据，只改它读的口子**。

撤回是这么发生的：服务器推一条 sysmsg XML，微信解析成一个 Map，
看到 `.sysmsg.$type == "revokemsg"` 就去把原消息替换成「xxx 撤回了一条消息」。

```
XmlParser 解析结果（after 钩子）
  .sysmsg.$type                = "revokemsg"      ← 把这一项置成 null
  .sysmsg.revokemsg.newmsgid   = 原消息 msgSvrId   ← 拿它记账
```

把 `$type` 抹掉之后，微信就认为这条 sysmsg 没有类型、跳过处理，
原消息**一个字都不会被改**，所以已读上报、置顶、长按菜单全都照常。

`newmsgid` 就是被撤回那条消息的 `msgSvrId`，所以只需记下它：

```
RecallStore: SharedPreferences<Set<String>> 存 svrId，上限 4000 条
```

> 之所以不用像参考实现那样去查 `message` 表拿 talker，是因为渲染时
> 消息对象身上本来就有 `field_msgSvrId`，直接对 ID 就够了 —— 完全不碰数据库。

半透明显示则是钩聊天条目的绑定回调：

```
绑定行（after 钩子）
  args 里找第一个「带 field_talker/field_msgSvrId 的对象」 -> 拿 svrId
  args 里找第一个「能当 ViewHolder 用的」-> 取出 itemView
  RecallStore 命中 -> 下一帧把 itemView 的 alpha 设为 0.4，没命中则还原 1.0
```

两个必须注意的点（都是实测踩出来的）：

1. **itemView 不能用「哪个字段是 View 就先拿哪个」来找**。
   ViewHolder 里往往还挂着一个指向父列表（RecyclerView）的字段，
   先撞上它就会把**整个列表**当成一行来调暗。所以优先取
   `androidx ViewHolder.itemView`，其次取声明类型**正好是 `android.view.View`** 的字段，
   最后才兜底，且把列表 / 滚动容器本身排除掉。
2. **alpha 要延到下一帧（`postOnAnimation`）再设**。
   微信绑定完之后自己还会动这一行（复用、条目动画），当场设会被覆盖掉。
   设之前再确认一次这行显示的仍是那条消息，避免 View 复用错位。

撤回发生时，屏幕上已经显示的那几行也会立即重新上色，不用手动滑一下。

---

## 抗混淆

微信的类名每个版本都变（`iq5.a1`、`tn5.g`…），所以代码里**不看类名**：

- 分组入口用框架方法 `ListView#setAdapter` + 「ListView 类名含 `conversation`」
- 会话识别靠**数据库字段名**：`field_username` / `field_unReadCount` /
  `field_verifyFlag` / `field_attrflag` / `field_parentRef`
- 消息识别靠 **`field_talker` / `field_msgSvrId` / `field_isSend`**
- 撤回消息保留要钩的两个方法是**按字符串常量反查**的（见下），同样不依赖类名
- 取字段的反射方案按类缓存，分类结果按 username 缓存

### 按字符串反查方法（`DexScan`）

微信自己的类名被 R8 混淆了 —— 实测 8.0.79 上已经找不到
`com.tencent.mm.sdk.platformtools.XmlParser`（它变成了 `...platformtools.da`），
但**字符串常量不会被混淆**。所以拿微信自己打日志用的 tag 反查类：

| 目标             | 反查用的字符串常量                           | 实测命中（8.0.79）                    |
| ---------------- | -------------------------------------------- | ------------------------------------- |
| XmlParser 类     | `MicroMsg.SDK.XmlParser`                     | `com.tencent.mm.sdk.platformtools.da` |
| 聊天条目绑定方法 | `MicroMsg.MvvmChattingItem` + `[onBindView]` | `tn5.g#h`                             |

`DexScan` 是个**不依赖 DexKit 的迷你 dex 扫描器**（约 300 行）：

- 只解析用得上的那点结构：字符串池、type / proto / method 表、
  `class_def`、`class_data_item`、`code_item`
- 扫指令时按 dalvik 指令长度表走（`const-string` 是 0x1a / `const-string/jumbo` 是 0x1b），
  不按表走就会把操作数当成 opcode，查出一堆假类
- 先对整份 dex 做一次廉价字节预筛，没命中目标串就整份跳过 ——
  微信 18 个 dex 里实际只有 2 个需要真正解析
- 在后台线程跑（`WxGroupAntiRecall`），启动时约 2~3 秒完成，不挡启动

---

## 目录结构

```
app/src/main/java/com/example/wxgroup/
├── XposedEntry.kt   入口：只注入微信主进程，拿到 Application 后启动
├── GroupHook.kt     ★ 索引投影 + 标签栏注入 + 切分组
├── TabsBar.kt       标签栏 UI（等宽标签 + 滑动指示条，纯代码构建）
├── GroupModel.kt    分组定义、匹配规则、选中项持久化
├── ConvItem.kt      ★ 抗混淆：按字段名定位会话对象
├── AntiRecall.kt    ★ 撤回消息保留：XmlParser 拦截 + 半透明上色
├── DexScan.kt       ★ 迷你 dex 扫描器：按字符串常量反查类和方法
├── RecallStore.kt   被撤回消息的账本（svrId 集合）
└── MsgInfo.kt       抗混淆：按字段名从绑定参数里找出消息对象
```

---

## 编译

| 项                              | 值           |
| ------------------------------- | ------------ |
| Gradle                          | 8.9          |
| JDK                             | 17           |
| AGP                             | 8.7.3        |
| Kotlin                          | 2.1.0        |
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

### release 包

```powershell
gradle :app:assembleRelease   # 产物：app/build/outputs/apk/release/app-release.apk
```

| 项   | 值                             |
| ---- | ------------------------------ |
| 版本 | 1.1（`versionCode = 2`）       |
| 体积 | ~70 KB（debug 包 ~2.3 MB）     |
| R8   | 开启 —— 收缩 + 混淆 + 资源压缩 |

混淆会顺带把模块自己的类名改掉（入口 `XposedEntry` 由 keep 规则保住原名），
宿主更难从类名认出这是哪个模块。

> 签名沿用**本机 debug key**，为的是能和已装的 debug 包签名一致、直接覆盖升级
> （不丢设置，也不用去 LSPosed 里重新勾一次）。
> 换成自己的正式签名只需改 `signingConfig`，但签名变了就必须卸载重装 ——
> 设置会清空，LSPosed 里的启用状态也要重勾。

---

## 安装

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

然后在 LSPosed 里启用模块、作用域勾选微信（`com.tencent.mm`），强制停止微信后重新打开。

**本模块不往 logcat 写任何东西** —— `adb logcat -s WxGroup` 应该是空的。
（排查问题时打过日志，后来全部移除了；自定义线程名也一并去掉，免得在
`/proc/<pid>/task/*/comm` 里留下痕迹。）

验证是否生效：让人撤回一条消息，原消息应该半透明地留在原地。

---

## 已知限制

| 现象                      | 原因 / 处理                                                                |
| ------------------------- | -------------------------------------------------------------------------- |
| 没有「已接管会话列表」    | 本机微信可能用了 RecyclerView 版列表，本版本只支持 ListView                |
| 分组内容不对              | 字段名不匹配，把实际的 `field_xxx` 加进 `ConvItem` 的候选名数组            |
| 点击会话打开错人          | 该版本不通过 `adapter.getItem()` 取数据，删掉 `GroupHook` 里 hook 它的那行 |
| 分类不准                  | 在 `ConvItem.SYSTEM_ACCOUNTS` 里补 ID，或调整 `isOfficialName`             |
| 撤回拦不住 / 拦住了没变灰 | 反查的字符串或反射签名变了；临时把 `AntiRecall` 里的日志加回来定位         |
| 该变灰的没变灰            | 消息对象没有 `field_msgSvrId`，把候选字段名补进 `MsgInfo`                  |

---

## 致谢

本项目在「如何定位被 R8 混淆的宿主方法」这一点上，参考了下面两个开源项目的思路与做法：

- [WeKit](https://github.com/Ujhhgtg/WeKit)
- [WASLDH](https://github.com/Ujhhgtg/WASLDH)

感谢作者的开源分享。

---

## 使用须知与免责声明

- 本项目是**个人学习 Android 逆向与 Xposed 原理**的练习产物，与腾讯公司及微信无关，
  未获得其授权、认可或支持。
- 修改微信客户端的运行行为**可能违反微信的用户协议**，由此带来的后果（包括但不限于
  账号被限制、封禁）由使用者自行承担。
- 本项目不包含破解付费内容、绕过风控、窃取或上传数据的功能；
  所有逻辑都在本机进程内运行，不向任何服务器发送数据。
- 请勿将本项目用于商业用途、二次分发或任何违法用途。
- 使用者应自行确认所在国家和地区的法律法规要求，并对自己的全部行为负责。
- 相关权利方如认为本项目存在不当之处，请联系后删除。
