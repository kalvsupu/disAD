# disAD

**免 Root 的本地广告拦截 · Root-free local ad blocker**

`Android 8.0+` · `APK < 1 MB` · `纯本地，不联网不上传` · `MIT`

一个不依赖 root、不 hook、不修改任何第三方 App 的开屏广告拦截工具。只有两层能力：**本地 DNS 过滤**（VpnService）和**无障碍辅助点掉「跳过」**。

> A root-free splash-ad blocker for Android. No root, no hooking, no patching of other apps. It has exactly two layers: **local DNS filtering** (VpnService) and **accessibility-assisted tapping** of "Skip" buttons.

---

## 它怎么工作

### 第一层：本地 DNS 过滤

- 用 Android 的 `VpnService` 在本地起一个 DNS 转发器，**只接管 53 端口的 DNS 查询**，其它流量一个字节都不碰；
- 同时把手机当前网络使用的 DNS 服务器逐个挂上 `/32` 路由 —— 这样 App 用哪个 DNS 都跑不掉；
- 命中规则的域名直接拒绝；没命中的原样转发给你配置的上游解析器，**不修改任何报文内容**；
- 上游不可用时返回 **SERVFAIL** 并设 1.2 秒超时 —— 让系统解析器快速失败并回落到下一个 DNS，而不是让 App 干等。

广告域名解析被拒绝后，广告 SDK 拿不到配置与素材，开屏广告往往根本不会被创建。

### 第二层：无障碍辅助点「跳过」

DNS 拦不住的那部分，交给无障碍服务在界面上点掉。它只认这几种情况：

| 规则 | 条件 |
|---|---|
| 右上角文字 | 文字含「跳过 / 关闭广告 / skip」，长度 ≤ 8 字 |
| 跳过控件 id | `*:id/skip`、`*:id/skip_btn`、`*:id/tv_skip` 等 |
| 顶部条带文字 | 屏幕顶部 22% 以内，且确认有广告痕迹或像启动页 |
| 右下角条带文字 | 屏幕右下角，且确认有广告痕迹或像启动页（B站这类） |
| 关闭控件 | 已在界面上确认是广告，才去找「关闭」按钮 / 关闭类 id |
| 角落图形按钮 | 已确认是广告、且上面全都没找到时，点角落那块区域里**真实存在的可点击小控件**（治纯图形的跳过键） |

几条硬性约束：

- **不做任何屏幕坐标猜测。** 所有点击都落在真实存在的控件上；
- 文字必须**短**（≤ 8 字），且只允许出现在顶部或右下角条带里 —— 正文、聊天内容不会落进来；
- **不可见**（被遮挡 / 已滑出屏幕）的控件一律不点；
- 只在「拦截运行中」时才动作：点「停止拦截」会把 DNS 拦截和无障碍一起停掉；
- 笔记、相册、文件管理、短信、联系人这类"文字就是用户内容"的 App 已被主动排除。

---

## 功能

- **作用范围**（三个系别，独立开关）：百度系 · 其他（等于全局）· 拼多多系
- **额外作用 App 包名**：想只给某个 App 生效，填包名即可
- **老年人模式**：确认屏幕上确实有广告时，才按「关闭广告 / 跳过 / 残忍拒绝 / 以后再说 / 不再提示」这类**广告专属词**关闭弹窗（不含「取消 / 关闭」这种所有对话框都有的词）
- **状态卡片**：实时显示 DNS 拦截次数、自动跳过次数、最近一次动作
- **域名记录**：本机记录每一次域名查询（域名 / 次数 / 来源 App / 是否被拦）
- **自动学习**：把「低出现率 + 自带广告特征词」的域名加入拦截。**内置基础设施保护列表**，腾讯 / B站 / 厂商 / 推送 SDK 等域名永不自动添加
- **规则自测**：输入任意域名，直接告诉你「会拦截（命中哪条规则）」还是「会放行」
- **自定义域名 / 白名单**：随时可加、可改、可回滚
- **拦截应用商店跳转**：广告拉起应用商店时不会真的跳过去
- **开机自动启动**
- **摇一摇广告指引**：这类广告靠运动传感器触发、不经过网络，两层都够不着 —— 但收回那个 App 的「运动与方向」权限即可让它失效

---

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 把放行的 DNS 查询转发给你配置的上游解析器。**除此之外不发起任何网络连接** |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 拦截需要常驻，前台服务 + 通知栏状态 |
| `POST_NOTIFICATIONS` | 显示拦截状态；无障碍被系统关闭时提醒你 |
| `RECEIVE_BOOT_COMPLETED` | 开机自动启动（可关） |
| `ACCESS_NETWORK_STATE` | 读取当前网络的 DNS 服务器地址 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 可选：加入电池白名单，避免后台被杀 |
| 无障碍服务（非普通权限） | 读取控件属性以点掉「跳过」；**不读取、不记录任何界面文字** |

没有申请：存储、位置、相机、麦克风、通讯录、`QUERY_ALL_PACKAGES`。

---

## 已知边界

**两种广告拦不住，原因不是规则写得不够多，而是它们绕开了我们仅有的两层手段。**

- **腾讯系**（微信 / QQ / QQ音乐等）：走自建 HTTPDNS（系统 DNS 看不到查询）+ 自研长连接与私有协议（根本不产生域名解析）+ 自营广告与业务同域。
  实测：一次微信会话 + 一个小程序 + 2~3 个广告，系统 DNS 只看到 21 个域名，其中真正的广告域名只有 1 个。
- **微信内的所有广告**：微信在前台时，无障碍树里只有 **1 个节点**（根节点，0 个子节点），遍历所有窗口结果一致 —— 连"屏幕上有没有『跳过』按钮"都无从得知。
- **摇一摇广告**：传感器触发，不经过网络。

**完整原理、实测数据，以及"为什么不做截图 + OCR 兜底"的评估结论 → [TECHNICAL.md](TECHNICAL.md)**

> 另注：App 会把广告素材缓存在本地，已经下载好的广告**断网也会再显示一次**。装上之后建议先清一次目标 App 的缓存。

---

## 构建

需要 **JDK 17** 和 **Android SDK 34**。

```bash
./gradlew assembleRelease          # 产出 app/build/outputs/apk/release/
```

Windows：`gradlew.bat assembleRelease`

**签名**：仓库里**不含任何密钥**，所以 release 产出的是**未签名包**，请自行签名：

```bash
apksigner sign --ks your-release.jks app-release-unsigned.apk
```

如果你在 `app/lite-debug.keystore` 放了自己的密钥，构建脚本会自动启用一套固定签名（`app/build.gradle.kts` 里有条件判断）。

**路径含中文时**：Android Gradle Plugin 不接受非 ASCII 路径，可先建一个 ASCII 路径的链接再构建（`gradle.properties` 里有具体命令）。

---

## 项目结构

```
app/src/main/java/com/baidukiller/lite/
├── MainActivity.kt                    界面与交互
├── HelpText.kt                        使用说明文案（与界面分离）
├── Prefs.kt                           偏好读写、作用范围、升级迁移
├── SkipAdAccessibilityService.kt      第二层：无障碍辅助点「跳过」
├── AutoLearn.kt                       自动学习（含基础设施保护列表）
├── DnsLog.kt                          域名记录与候选打分
├── BlockStats.kt                      统计计数
├── BootReceiver.kt                    开机自启
├── MarketInterceptActivity.kt         拦截应用商店跳转
├── dns/BlockList.kt                   规则匹配（标签前缀 / 精确 / 点分段）
├── dns/DnsMessage.kt                  DNS 报文解析
├── dns/UpstreamResolver.kt            上游转发
├── net/PacketUtil.kt                  IP/UDP 报文构造
└── vpn/AdBlockVpnService.kt           第一层：VpnService + DNS 拦截

app/src/main/assets/
├── blocklist_default.txt              内置规则（百度系 / 第三方广告平台 / 实测补充）
├── blocklist_strict.txt               更激进的通用广告端点（常驻）
└── blocklist_pdd.txt                  拼多多系素材域名（由开关控制）
```

规则引擎采用三级匹配（标签前缀 / 精确标签 / 点分段序列），目的是避免误伤：例如关键字 `gdtimg`（广点通图床）**不会**命中 `i.gtimg.cn`（QQ音乐图片 CDN）—— 差一个字母就能毁掉一个 App，这类边界靠实测反着验证。

---

## 隐私

- **没有任何统计、上报、埋点代码。** 除"把放行的 DNS 查询转发给你设置的上游解析器"之外，不发起任何网络连接；
- 不采集任何数据，没有服务器，没有账号；
- **界面控件的文字内容永远不会出现在界面、通知栏或日志里** —— 代码里有白名单强制过滤，即使以后写错了也带不出去；
- 域名记录只保存在本机私有目录，随时可清空；
- 全部代码与规则都在本地，可自行审计。

---

## 免责声明

1. 本软件是一个本地 DNS 过滤与无障碍辅助工具，**不修改任何第三方 App**，不破解、不修改会员权益，也不绕过任何付费内容；
2. 仅供个人学习与自用，请勿用于商业用途，请遵守你所在地区的法律法规；
3. 是否使用、如何使用由使用者自行决定，使用产生的一切后果由使用者自行承担。

## License

[MIT](LICENSE)

---

# English

**disAD** is a root-free splash-ad blocker for Android 8.0+. It has exactly two layers and nothing else.

**Layer 1 — local DNS filtering.** A local `VpnService` forwards DNS queries on port 53 only; every other packet is left untouched. It also installs `/32` routes for the DNS servers your network already uses, so apps cannot sidestep it by picking a different resolver. Blocked domains are refused; everything else is forwarded **byte-for-byte** to an upstream resolver you configure. When the upstream is unavailable it returns **SERVFAIL** with a 1.2 s timeout, so the system resolver fails fast instead of hanging.

**Layer 2 — accessibility-assisted tapping.** For ads DNS cannot reach, an accessibility service taps the "Skip"/"Close" control. It only acts on short (≤ 8 chars) skip-like labels, known skip view-ids, close controls when an ad is confirmed on screen, and — as a last resort on a confirmed ad — a **real clickable control** sitting in the top-right or bottom-right corner band. Constraints: **no screen-coordinate guessing**, invisible nodes are never touched, it acts only while interception is running, and apps whose on-screen text *is* user content (notes, gallery, files, SMS, contacts) are excluded.

**Features.** Scope switches (Baidu family / everything else / Pinduoduo), per-app scope, elder mode, live stats, on-device domain log, conservative auto-learning with a built-in infrastructure protection list, rule self-test, custom domains and whitelist, app-store jump interception, boot autostart, and guidance for sensor-triggered "shake" ads.

**Known limits.** Tencent apps (WeChat, QQ, QQ Music) use self-hosted HTTPDNS, proprietary long connections and same-domain first-party ads; inside WeChat the accessibility tree exposes a single node, so nothing can be tapped at all. Shake-to-jump ads are sensor-triggered and never touch the network. **Full analysis, measurements, and why we deliberately do not fall back to screenshot + OCR → [TECHNICAL.md](TECHNICAL.md)**

**Build.** JDK 17 + Android SDK 34: `./gradlew assembleRelease`. The repository contains no signing key, so the release output is **unsigned** — sign it yourself. Android Gradle Plugin rejects non-ASCII project paths; see `gradle.properties` for a junction/symlink workaround.

**Privacy.** No analytics, no telemetry, no servers, no accounts. The only network activity is forwarding allowed DNS queries to your upstream resolver. On-screen text from other apps is never displayed, stored or logged — a hard-coded allow-list enforces this in code.

**Disclaimer.** Provided for personal, educational use. It does not modify any third-party app, does not crack or bypass paid content, and comes with no warranty; you are responsible for how you use it.
