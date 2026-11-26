# B站账号绑定与 Cookie 获取说明

## 获取方式

客户端不要求用户手动粘贴 Cookie，也不读取 B站 App 的私有数据。流程是：

1. 设置页「B站账号」→「绑定」，打开 `BiliLoginActivity`。
2. WebView 加载 B站官方登录页 `https://passport.bilibili.com/login`。
3. 用户在官方页面完成登录（密码 / 短信 / 扫码由 B站页面决定）。
4. 登录成功后，客户端通过 `CookieManager.getCookie()` 读取 `www.bilibili.com`、`passport.bilibili.com`、`api.bilibili.com` 下的 Cookie。
5. 检查是否存在 `SESSDATA`，再调用 `x/web-interface/nav` 校验 `data.isLogin`、`mid`、`uname`。
6. 校验通过后，把 Cookie 与账号资料用 Android Keystore AES-GCM 加密写入本机；WebView 登录会话随即清掉。

## 大会员识别

- `nav` 返回的 `data.vipStatus == 1` 且 `data.vipDueDate > 当前时间` 时，视为有效 B站大会员。
- 有效大会员可直接播放会员专享 PGC 内容，不再要求 App 激活；设置页会显示大会员到期时间。
- 会员权益优先级：B站大会员 > App 激活 > 好友分享。

## 观看历史拆分

- 本机观看历史只写入设备本机的 `bili_history`，首页「本机继续观看」与用户中心「本机历史」只读这一份。
- B站账号观看历史独立写入 `bili_account_history`，按当前绑定 B站 UID 归属，最多保留 300 条；换绑 UID 会清空旧账号的独立缓存。
- 同步 B站历史时只会替换 B站独立历史，不再合并到本机历史；启动时也不再拉取云端设备历史反向合并。
- 用户中心提供「本机历史 / B站账号」切换；B站来源的「同步」只刷新本机 B站缓存，不删除 B站服务器上的观看记录。

## 好友会员分享

- 好友按 App 登录用户名添加，需要对方接受申请；好友关系不等于 B站好友。
- 大会员账号可以在「社交中心」里给好友分享 1–365 天会员，同一好友重复分享会顺延到期时间。
- 好友列表会展示在线 / 刚刚活跃 / N 分钟前活跃等状态；在线状态由服务端 `friend_list` 的 `last_at` 计算，5 分钟内活跃视为在线。
- 「邀请好友」通过系统分享面板发送 App 账号 `@username`、昵称和下载地址，不携带任何 B站 Cookie。
- 分享时客户端只把播放必需的 Cookie 字段交给服务端（`SESSDATA`、`buvid3`、`buvid4`、`b_nut`、`bili_ticket`、`buvid_fp`、`buvid_fp_plain`、`_uuid`），不保存 `bili_jct`。
- 服务端用 AES-256-GCM 加密保存分享 Cookie；被分享账号播放时只调用 `share_playurl` 拿视频流，不接触分享者的 `SESSDATA`。
- 分享播放只支持 PGC 会员专享 `ep_id`，只提供播放；不提供搜索、点赞、收藏、下载等业务。
- 解绑 B站账号或删除好友会停止自己发出的相关分享。

## 安全边界

- 不调用账号密码登录接口，不在 App 内保存 B站密码。
- 默认情况下 Cookie 只保存在本机，不上传到 BiliDiy 服务端，也不写日志。只有用户主动「分享会员」时，才把过滤后的播放 Cookie（不含 `bili_jct`）交给服务端加密保存，用于给好友代理播放。
- WebView 关闭文件访问、混合内容、多窗口，只允许停留在 `bilibili.com` / `hdslb.com` / `biligame.com` 域名。
- 绑定账号的 Cookie 与云端下发的「播放用 Cookie」分开保存；绑定后个人 Cookie 优先，解绑后自动回落到云端 Cookie。
- 解绑会删除本机 Cookie、追番列表与 B站独立历史缓存，本机观看历史保留。

## 同步的接口

- 账号校验：`GET /x/web-interface/nav`
- 观看历史：`GET /x/web-interface/history/cursor?type=all&ps=20`
  - 分页使用上一页 `data.cursor` 的 `max`、`business`、`view_at`。
  - 稿件：`history.oid` / `bvid`；番剧影视：`history.epid`。
- 追番/追剧：`GET /x/space/bangumi/follow/list?type=1&type=2&follow_status=0&vmid=<mid>`
  - `type=1` 番剧，`type=2` 影视。

## 已知限制

- B站可能对 WebView 登录做风控；如果官方登录页提示环境异常，需要在官方页面内完成验证码，或改用 B站官方扫码登录。
- Cookie 会过期或被用户主动退出，过期后重新绑定即可。
- 同步只读取当前账号可见的数据，不会删除 B站上的记录。
