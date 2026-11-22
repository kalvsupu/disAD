# disAD · 技术边界说明

**两种广告拦不住，原因不是规则写得不够多，而是它们绕开了我们仅有的两层手段。**

本项目有三层能力：本地 DNS 过滤（VpnService）、无障碍辅助点击、以及可选的本地 OCR 兜底（截图 + 几何候选 + 仅识别）。前两条边界是**方案本身的极限**，不是待修的缺陷；第三条（OCR）是对"无障碍失明"的补位，仍在实测中。

> **Two kinds of ads stay out of reach — not because our rules are incomplete, but because these apps bypass both of the only two layers this project has.** Those layers are local DNS filtering (VpnService), accessibility-assisted tapping, and an optional on-device OCR fallback (screenshot + geometric candidates + recognition only). The first two limits below are properties of the approach, not bugs waiting to be fixed; OCR is the mitigation for the accessibility blind spot and is still being measured.

---

## 1. DNS 层为什么对腾讯系无效

- **自建 HTTPDNS**：腾讯系 App 不走系统 DNS，而是把域名通过 HTTPS 直接问自家入口（微信 `dns.weixin.qq.com.cn`、QQ音乐 `httpdns.kg.qq.com`），拿到 IP 后直连。系统 DNS 层**看不到这条查询**。
- **自研长连接 / 私有协议**：微信、QQ音乐的大量业务流量走腾讯自研网关（WNS 一类）。长连接建好后，后续请求**根本不产生域名解析**，DNS 层连参与的机会都没有。
- **自营广告与业务同域**：一部分广告是腾讯自己投放的，接口与正常功能共用域名，无法在不影响功能的前提下区分。
- **实测数字**：一次微信会话 + 一个小程序 + 2~3 个广告，系统 DNS 只看到 **21 个域名**，其中真正的广告域名**只有 1 个**。QQ音乐一次会话解析 90 个域名，可拦的广告域名约 10 个，其余 80% 是它的本体（`y.qq.com` 家族、音频流、图片 CDN、登录客服）。
- **我们故意不拦它们的 HTTPDNS 入口**（`dns.weixin.qq.com.cn` / `httpdns.kg.qq.com`）：实测拦掉会明显影响 App 正常使用，而它们有回落机制，收益极低、代价不小。
- **我们仍然拦掉了一部分**已知的腾讯系广告域名：`ad.tencentmusic.com`、`tmead.y.qq.com`、`p.l.qq.com`、`*.gdt.qq.com`（广点通家族）等。

> **Why DNS filtering cannot reach Tencent apps**
>
> - **Self-hosted HTTPDNS.** These apps never ask the system resolver; they ask their own HTTPS endpoint (`dns.weixin.qq.com.cn`, `httpdns.kg.qq.com`) and connect to the returned IP directly. The system DNS layer never sees the lookup.
> - **Proprietary long connections.** Much of WeChat's and QQ Music's traffic runs over Tencent's own gateway (WNS-style). Once the connection is established, later requests produce **no DNS lookups at all**.
> - **First-party ads share domains with normal features**, so they cannot be separated without breaking the app.
> - **Measured:** one WeChat session + one mini-program + 2–3 ads produced only **21 DNS lookups**, of which exactly **one** was an ad domain. QQ Music produced 90 lookups; ~10 were blockable ad domains and the other 80% were the app itself (`y.qq.com`, audio streams, image CDNs, login/support).
> - **We deliberately do not block their HTTPDNS endpoints** (`dns.weixin.qq.com.cn`, `httpdns.kg.qq.com`): in testing this degraded normal app usage while gaining almost nothing, since those apps fall back to other paths.
> - **What we do block** among known Tencent ad domains: `ad.tencentmusic.com`, `tmead.y.qq.com`, `p.l.qq.com`, `*.gdt.qq.com`, and others.

---

## 2. 无障碍为什么进不去微信

- **实测结果**：微信在前台时，无障碍树里**只有 1 个节点**（根节点，0 个子节点）。遍历所有窗口（`getWindows()`，含窗口内容与其它窗口）结果一致。
- 也就是说，我们**连"屏幕上有没有『跳过』按钮"都无从得知**，更谈不上点击。这也不是权限或配置能绕开的限制 —— 任何依赖无障碍读取界面的自动化在微信内都会失明。
- **因此微信内**的开屏广告、小程序自营广告、朋友圈广告**都无法被无障碍自动跳过**。
- 第三层（OCR）不依赖无障碍树，能"看见"画面：小程序开屏广告的「广告 ⌄ 跳过」在截图里是可见的；实测已能定位到候选块，识别召回率仍在调优。

> **Why accessibility automation cannot act inside WeChat**
>
> - **Measured:** with WeChat in the foreground, its accessibility tree contains exactly **one node** (the root, with zero children). Iterating every window (`getWindows()`, including window content and other windows) gives the same result.
> - So we cannot even learn **whether a "Skip" button exists on screen**, let alone tap it. No permission or configuration works around this: any automation that reads the screen through accessibility is blind inside WeChat.
> - **Consequently**, splash ads, mini-program ads and Moments ads inside WeChat **cannot be skipped by the accessibility layer**.
- The OCR layer does not depend on the accessibility tree: it can *see* the picture. The "广告 ⌄ 跳过" control in mini-program splash ads is visible in screenshots; candidate regions are already located, and recognition recall is still being tuned.

---

## 3. 那还剩什么能用

| 场景 | 是否有效 | 依靠哪一层 |
|---|---|---|
| 百度系开屏（地图 / 网盘 / 贴吧 / App） | ✅ | DNS 为主，界面层兜底 |
| 常见第三方广告 SDK 开屏 | ✅ | DNS |
| QQ音乐 / B站等常规开屏 | ✅ 多数 | DNS + 界面层点「跳过」 |
| 腾讯系走 HTTPDNS / 私有协议的部分 | ❌ | 方案边界 |
| 微信内所有广告 | ❌ | 方案边界 |
| 摇一摇广告 | ❌ | 传感器触发，不经过网络 |

摇一摇广告靠运动传感器触发，两层都够不着 —— 但可以**收回那个 App 的「运动与方向」权限**让它直接失效。

> **What still works**
>
> | Scenario | Works? | Layer |
> |---|---|---|
> | Baidu-family splash ads | ✅ | DNS, accessibility as fallback |
> | Common third-party ad SDK splash ads | ✅ | DNS |
> | QQ Music / Bilibili splash ads | ✅ mostly | DNS + accessibility "Skip" |
> | Tencent traffic over HTTPDNS / proprietary protocols | ❌ | approach limit |
> | Any ad inside WeChat | ❌ | approach limit |
> | Shake-to-jump ads | ❌ | sensor-triggered, never touches the network |
>
> Shake-to-jump ads are triggered by the motion sensor, out of reach of both layers — but revoking that app's "Motion & orientation" permission disables them outright.

---

## 附：这些数字是怎么来的

本文所有结论都来自**本机实测**，方法是一个只记录、不拦截的旁路工具（VpnService + 原样转发 DNS 报文，一个字节都不修改），以及一个只导出**控件属性**（类名 / id / 坐标 / 是否可点击 / 是否可见 / 文字长度）的界面结构记录器 —— 它不记录任何界面文字。

本程序不采集、不上传任何数据。网络活动只有三处，且全部由你显式开启：① 把放行的 DNS 查询转发给你设置的上游解析器；② 若你自己填了大模型 API（默认关闭），定期把**放行的域名列表**发给该接口做判断，结果只回到本机；③ 你主动点「导出并发送」时，才把自动学习名单交给系统邮件客户端。拦截记录与累计数只写在本机 `dnslog.tsv`，不联网。

> **Where these numbers come from**
>
> Every figure above comes from measurement on a real device, using a passive recorder (VpnService forwarding DNS packets byte-for-byte, modifying nothing) and a node-structure dumper that exports **widget attributes only** (class / id / bounds / clickable / visible / text length) and never any on-screen text.
>
> This project collects and uploads nothing on its own. There are exactly three network-related behaviors, all opt-in: (1) forwarding allowed DNS queries to the upstream resolver you configure; (2) if you supply your own LLM API key (off by default), periodically sending the **list of allowed domains** to that endpoint for classification — the result stays on-device; (3) handing the auto-learned list to your system mail app only when you tap "Export and send". Block records and counters are written to a local `dnslog.tsv` file and never leave the device.
