# 开发笔记

给想改这个项目的人。这里记录的是**踩过的坑和为什么这么写**，不是 API 文档。

---

## 1. 整体结构

```
app/
├─ AndroidManifest.xml
├─ res/
│  ├─ layout/activity_main.xml        单页管理界面
│  ├─ values/strings.xml              所有"用户可见"的名字都在这里
│  └─ xml/accessibility_service_config.xml
└─ src/com/family/anxin/
   ├─ MainActivity.java        密码锁 / 状态 / 诊断面板 / 拦截方式 / Shizuku 深度锁定
   ├─ FinderBlockService.java  无障碍服务：判定 + 动作 + 时间线记录
   ├─ GuardService.java        可选的常驻前台服务（默认不启动）
   ├─ BootReceiver.java        开机自启（只在开了后台常驻时才拉起服务）
   ├─ FinderTargets.java       枚举微信 finder 组件 / 判定窗口类名
   ├─ Diag.java                诊断报告 + 时间线缓冲
   ├─ Sh.java                  Shizuku → shell 命令
   └─ Prefs.java               本机配置
```

单进程应用。诊断页直接读 `FinderBlockService` 的静态字段，不需要 IPC。

---

## 2. 判定：怎么知道"现在是视频号"

两级判定：

**一级 · 窗口类名**（主力）

```java
cls.toLowerCase().contains("finder")
```

`TYPE_WINDOW_STATE_CHANGED` 事件的 `getClassName()` 就是 Activity 类名。
微信视频号（含直播）都在 `com.tencent.mm.plugin.finder` 插件里，实测命中：

```
com.tencent.mm.plugin.finder.ui.FinderShareFeedRelUI
com.tencent.mm.plugin.finder.ui.FinderHomeUI
...
```

**二级 · 页面内容特征**（兜底，可能误判，可关）

类名认不出时，遍历当前窗口节点树找特征：同时出现精确文本「推荐」和「关注/朋友」。
视频号首页顶部的 tab 就是这个组合，「发现」页和公众号文章都不会同时出现这两个短文本。

> ⚠️ 注意区分两个方法：`isFinderWindow()`（判定窗口，用 `contains`）和
> `isFinderComponent()`（枚举组件，用严格前缀 `com.tencent.mm.plugin.finder.`）。
> 混用会导致枚举出 351 个组件、把隐私设置页面也禁掉（见第 4 节）。

---

## 3. 动作：为什么不能"检测到就立刻按返回"

**这是这个项目里最容易踩的坑。**

最直觉的写法是：检测到视频号 → 立刻 `performGlobalAction(GLOBAL_ACTION_BACK)`。
**它会看起来完全没效果**，因为那一刻页面还在**入场动画**里、按键焦点还没交过去，
返回键要么被动画吃掉，要么打到了上一个页面上。

所以最终的动作流程是"**发一次 → 验证 → 不行再发**"：

```
命中
 └─ 等 400ms（让页面停稳，拿到按键焦点）
     └─ 按返回（第 1 次：performGlobalAction）
         └─ 等 900ms
             └─ 检查 lastWechatClass 现在是什么
                 ├─ 已经不是 finder  → 成功，结束
                 ├─ 还是 finder，次数 < 3
                 │    └─ 第 2、3 次改用 Shizuku `input keyevent 4`
                 │       （不同注入通道，微信对无障碍按键的拦截对它无效）
                 └─ 3 次都出不去 → 按桌面键（兜底）
```

### 回桌面模式为什么必须先按一次返回

只按 `GLOBAL_ACTION_HOME` 的话，微信的**任务栈顶部还是视频号页面**。
长辈重新打开微信 → 直接落回视频号 → 又被踢回桌面 → **无限循环**。
先返回把页面从任务栈里弹掉，再回桌面，重新打开就落在群聊了。

两条模式的动作差异：

| 模式 | 动作 | 是否验证 |
|---|---|---|
| 退回上一页 | 返回 → 验证 → 重试 | 是 |
| 直接回桌面 | 返回一次 → 450ms → 桌面键 | 否（已验证有效，保持简单） |

---

## 4. 深度锁定：用 Shizuku 禁用微信组件

思路：`pm disable-user --user 0 com.tencent.mm/<组件>` 把 finder 插件的所有 Activity 禁掉，
这是**系统级持久设置**，重启、微信升级、甚至卸载本 App 都不会解除。

### 4.1 坑：组件匹配不能太宽

一开始用 `contains("finder")` 枚举，在真机上匹配到 **351 个 Activity**，其中一堆与视频号无关：

```
com.tencent.mm.ui.contact.privacy.FinderBlockListUI   ← 隐私设置页面
```

正确做法是只认微信插件前缀：

```java
cls.startsWith("com.tencent.mm.plugin.finder.")
```

微信的插件都在 `com.tencent.mm.plugin.<name>`，视频号插件就是 `plugin.finder`。

### 4.2 坑：华为/荣耀直接拒绝

在 HUAWEI / HarmonyOS 4（Android 12）上实测：

```
java.lang.SecurityException: Shell cannot change component state for
  com.tencent.mm/....FinderBlockListUI to 3
    at PackageManagerService.setEnabledSetting(PackageManagerService.java:28687)
    at com.android.server.pm.HwPackageManagerService.setComponentEnabledSetting(...)
```

抛异常的是 AOSP 的 `PackageManagerService.setEnabledSetting`，华为只是包了一层。
**Shizuku 拿到的是 adb 权限（uid 2000），和电脑 adb 完全等价，绕不过去；只有 root（uid 0）才可能。**

自己验证：

```bash
adb shell pm disable-user --user 0 com.tencent.mm/com.tencent.mm.plugin.finder.ui.FinderHomeUI
adb shell pm enable     --user 0 com.tencent.mm/com.tencent.mm.plugin.finder.ui.FinderHomeUI
```

所以这条路径在部分机型上是废的，App 里会明确把原因打出来，而不是只报"失败"。
**第一层（无障碍）才是主力。**

### 4.3 Shizuku 集成

**`Shizuku.newProcess` 在 API 13.1.5 里是 private。** 必须走 binder：

```java
IShizukuService service = IShizukuService.Stub.asInterface(
        new ShizukuBinderWrapper(Shizuku.getBinder()));
IRemoteProcess rp = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream());
```

需要的 Maven 依赖（除了 `api` 和 `provider`，还有传递依赖）：

```
dev.rikka.shizuku:api:13.1.5
dev.rikka.shizuku:provider:13.1.5
dev.rikka.shizuku:aidl:13.1.5
dev.rikka.shizuku:shared:13.1.5
androidx.annotation:annotation:1.3.0      # 编译期可以不要
```

`AndroidManifest.xml` 里必须有：

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

### 4.4 坑：单进程应用拿不到 binder

真实症状：**Shizuku 明明在运行、也授权了，但 App 里 `Shizuku.pingBinder()` 一直是 false。**

反编译 `ShizukuProvider` 13.1.5 后确认：

- `attachInfo()` **只做校验**（`multiprocess` 必须 false、`exported` 必须 true），不请求 binder；
- `onCreate()` **只初始化 Sui**，也不请求 binder；
- `pingBinder()` 就是 `binder != null && binder.pingBinder()`，而 `binder` 字段
  **只能由 Shizuku 管理器主动推送**来设置（`call("sendBinder", ...)`）。

也就是说**单进程应用自己没法主动去拿 binder**。如果 App 是在 Shizuku 启动**之后**才安装的，
管理器可能还没扫到它。

解决办法：
1. 用 `Shizuku.addBinderReceivedListenerSticky()` 粘性监听（binder 一到立刻刷新，不要只靠 `onResume` 采样一次）；
2. 拿不到 binder 时给出明确指引："重启 Shizuku"；
3. 更推荐直接用电脑 adb，一次性、可靠。

---

## 5. 构建链：不用 Gradle 手搓 APK

只用 Google 官方的 `build-tools` + `android.jar`：

```powershell
# 1. 编译资源
aapt2 compile --dir app/res -o build/res.zip

# 2. 链接资源，顺便生成 R.java
aapt2 link -o build/base.apk -I android.jar `
    --manifest app/AndroidManifest.xml -R build/res.zip `
    --java build/gen --min-sdk-version 21 --target-sdk-version 33 `
    --version-code 5 --version-name 1.4

# 3. 编译 Java
javac -source 8 -target 8 -bootclasspath android.jar -cp "android.jar;<shizuku jars>" `
    -d build/classes <sources> build/gen/**/R.java

# 4. 生成 dex
d8 --release --min-api 21 --lib android.jar --output build/dex <classes> <jars>

# 5. 把 classes.dex 塞进 APK（用 .NET ZipArchive Update 模式）
# 6. 对齐 + 签名
zipalign -p -f 4 build/base.apk build/aligned.apk
apksigner sign --ks keystore.jks --out out/app.apk build/aligned.apk
```

### 踩过的 4 个坑

**① `platform-34_r03.zip` 在 Google CDN 上 404。**
API 34 现在只有 `platform-34-extNN_rNN.zip` 这种带扩展级别的包
（本项目用的 `platform-34-ext12_r01.zip`）。`build-tools_r34-windows.zip` 还是正常的。

**② JDK 23 编译出的匿名内部类，build-tools 34 的 R8 8.2.2 直接 NPE。**

```
Error in MainActivity$1.class:
java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
```

原因：JDK 22+ 给匿名内部类生成的 `InnerClasses` 属性里 `outer_class_info_index = 0`
（`javap -v` 里显示成 `#21;`，而不是老版本的 `#21= #a of #b;`），R8 8.2.2 解析不了。

解决：**用 build-tools 37 的 d8（R8 9.2.4）**，aapt2 / zipalign / apksigner 继续用 r34。

**③ `javac -source 8 -target 8` 必须配 `-bootclasspath android.jar`**，
否则 java.* 会从 JDK 23 的运行时解析，容易误用 Android 上没有的 API。
JDK 23 会提示 source/target 8 已过时，加 `-Xlint:-options` 消掉即可。

**④ `d8` 的 `--output` 目录必须先存在**，否则报
`Invalid output: ... Output must be a .zip or .jar archive or an existing directory`。

### 依赖下载地址（都是官方源，不需要 sdkmanager）

| 组件 | 地址 |
|---|---|
| build-tools r34 | `https://dl.google.com/android/repository/build-tools_r34-windows.zip` |
| build-tools r37 | `https://dl.google.com/android/repository/build-tools_r37_windows.zip` |
| platform 34 (ext12) | `https://dl.google.com/android/repository/platform-34-ext12_r01.zip` |
| Shizuku API | `https://repo1.maven.org/maven2/dev/rikka/shizuku/{api,provider,aidl,shared}/13.1.5/` |

`.aar` 里的 `classes.jar` 直接用 `tar -xf xxx.aar classes.jar` 取出来即可（zip 就是 tar 能读的）。

---

## 6. 诊断时间线

`Diag` 维护一个最多 60 行的环形缓冲（存在 SharedPreferences 里），窗口变化和自身的动作混在同一条时间线上：

```
15:14:33  com.tencent.mm.plugin.finder.ui.FinderShareFeedRelUI
15:14:33  >> 命中 FinderShareFeedRelUI [类名] → 退出流程
15:14:33  >> 按返回 第1次（当前 FinderShareFeedRelUI）
15:14:34  com.tencent.mm.ui.chatting.ChattingUI
15:14:34  >> 检查 → ChattingUI（已退出）
```

`>>` 是本应用的动作。这样用户发一张诊断截图，就能直接看出卡在哪一步 ——
是没收到事件、还是按键被吃掉、还是判定规则不匹配。**强烈建议保留这个设计。**

---

## 7. 隐私边界

- 只记录 `com.tencent.mm` 的窗口类名；其它应用**只计数、不记录名字**。
- 没有 `INTERNET` 权限，从系统层面就无法联网。
- 后台常驻（前台服务）默认关闭，避免给长辈手机留常驻通知。

改代码时请保持这几点，否则"给老人装的东西"这个前提就不成立了。
