# BiliDiy 云端后端（PHP 7.4+）

App 通过 `api.php` 拉取云公告、云更新、登录 Cookie 与锁定状态；管理员通过 `admin.php` 修改这些内容。

## 目录结构

```
BILIBILI/
├── api.php              # App 调用的配置接口
├── admin.php            # 管理后台（密码登录，桌面/手机响应式）
├── lib/
│   └── storage.php      # 独立文件存储与旧 store.php 自动迁移
├── data/
│   ├── config.php       # 公告 / 更新 / Cookie / 锁定 / 管理员密码
│   ├── profiles.php     # 设备账户
│   ├── members.php      # 会员
│   ├── codes.php        # 激活码
│   └── history/
│       └── <device>.php # 每个账户一份观看记录，不设上限
└── download/            # 后台上传 APK 后的存放目录（自动创建）
```

旧版 `data/store.php` 会保留为备份；首次访问新代码时自动拆分迁移到上面的独立文件，`config.php` 最后写入，失败可重试。

## 部署

1. 已有站点升级：上传 `api.php`、`admin.php`、`lib/storage.php`，**不要**上传本地 `data/`；首次访问会自动从线上 `data/store.php` 拆分迁移。
2. 全新部署：上传 `api.php`、`admin.php`、`lib/storage.php` 和 `data/`（其中 `config.php` 含配置），确认 `data/` 与 `data/history/` 可写（755 即可）。
3. 浏览器打开 `admin.php`：
   - 已有站点迁移后直接用原密码登录。
   - 全新安装用 `data/config.php` 里的 `setup_token` 初始化管理员密码（初始化后 token 自动失效）。
4. 验证接口：

```bash
curl "https://your-domain.example/BILIBILI/api.php?action=ping"
curl "https://your-domain.example/BILIBILI/api.php?action=config&key=YOUR_CLIENT_KEY"
```

## 接口

`GET api.php?action=config&key=<client_key>` → 

```json
{
  "code": 0,
  "announcement": {"enabled": true, "id": "a-...", "title": "...", "content": "..."},
  "update": {"enabled": false, "version_code": 25, "version_name": "3.4.0", "url": "", "changelog": "", "force": false},
  "lock": {"locked": false, "title": "服务已暂停", "message": "..."},
  "cookie": "SESSDATA=...; bili_jct=..."
}
```

`GET api.php?action=ping` 用于健康检查。

`POST api.php?action=profile`（设备账户上报，App 启动时调用；无密码）

- 表单字段：`key`、`device_id`（`^[A-Za-z0-9_-]{8,64}$`）、`nickname`（≤16 字，空则“匿名用户”）、`version`、`model`
- `key` 也可放 `X-Bili-Key` 请求头或 JSON body；非 POST 返回 405，key 错误 403，device_id 不合法 400
- 成功：`{"code":0,"ok":true,"profile":{...},"total":N}`，写入 `data/profiles.php`（最多 500 台，按上报时间倒序截断）
- 后台「设备账户」卡片只展示最近 20 台，不做删除/改名

## 会员 / 激活码 / 云端观看记录

3.6.0 新增以下接口，均为 **POST + client_key**，`device_id` 需符合 `^[A-Za-z0-9_-]{8,64}$`。

| action | 字段 | 说明 |
| --- | --- | --- |
| `status` | `key`、`device_id` | 查询会员状态、云端记录条数、服务器时间 |
| `activate` | `key`、`device_id`、`code` | 激活码一次性激活；404 无效 / 409 已用 / 410 过期 |
| `history_get` | `key`、`device_id` | 拉取该设备的云端观看记录 |
| `history_save` | `key`、`device_id`、`items`（JSON 字符串或数组） | 按 `key` 合并，保留较新 `at`；按账户写入独立文件，不设条数上限；`cat` 为 `video/anime/movie` |
| `history_clear` | `key`、`device_id` | 清空该设备云端记录 |

会员结构：`data/members.php` 的 `members[device_id] = {expire_at, plan, source, updated_at}`。
激活码结构：`data/codes.php` 的 `codes[CODE] = {plan, days, created_at, code_expire_at, used_by, used_at}`，格式 `BD-XXXX-XXXX`，一次性使用，生成后 365 天内有效。

后台「会员管理」卡片可以：

- 按 `一月 / 一季 / 一年` 生成 1–20 个激活码；
- 输入设备 ID 直接开通 / 续期；
- 取消指定设备会员；
- 查看最近 20 个激活码的使用状态。

后台「云端观看记录」按账户汇总，可按「全部 / 视频 / 番剧 / 电影」筛选，显示总数不设上限；表格只分页展示最近 80 条，完整记录在 `data/history/`；可按设备清空。

> App 的会员激活只控制 App 内门禁，不等同于 B 站大会员；会员片段能否拿到完整流仍取决于云端 Cookie 是否有对应权限。

## 更新已有站点时

只上传 `api.php`、`admin.php`、`lib/storage.php`；**不要**用本地 `data/` 覆盖线上文件。首次访问新代码会从线上 `data/store.php` 自动拆分迁移到 `data/config.php`、`profiles.php`、`members.php`、`codes.php` 与 `data/history/<device>.php`，原 `store.php` 保留作回退。

## 安全说明

- `client_key` 编译在 App 内，只能挡住随手抓包，不能当作真正的密钥；Cookie 仍可能被逆向提取，建议使用小号/可随时失效的 Cookie。
- 后台密码只存 `password_hash`，不要复用其他站点密码。
- 建议给 `admin.php` 额外加一层 Nginx Basic Auth 或 IP 白名单。
- 锁定功能只能阻止普通用户，改包/断网仍可绕过；它是「运营开关」而不是强 DRM。


