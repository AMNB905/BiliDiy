# BiliDiy 3.9.39 全源包

BiliDiy 是一个 Android 端 B 站「搜索 + 播放」客户端，使用 Kotlin + Media3/ExoPlayer。本包包含 Android 全源、PHP 后端源码、正式签名 APK 和公开证书。

## 目录

- `Android/`：Android 工程源码，可直接用 Android Studio / Gradle 打开。
- `Backend/`：PHP 后端源码；`Backend/data/` 只保留 `.htaccess` 与 `config.example.php`，不含线上真实配置。
- `APK/`：`BiliDiy-3.9.39-release.apk`、`BiliDiy-3.9.39-cert.pem` 与 `SHA256SUMS.txt`。

## 3.9.39 更新

- **全浅色配色**：品牌粉改为柔和玫粉 `#C74671`，深粉改为 `#A63D63`；背景 `#FFFAFC`、卡片白色、浅粉容器；正文改为柔和深灰 `#3A3235`，不再使用近黑正文与深洋红主色。界面填充不再使用深色。
- **底栏去掉搜索 tab**：浮岛从 5 项改为 4 项——首页 / 功能 / 本地 / 我的；搜索不再作为底栏页面。
- **顶部固定搜索框**：首页与搜索页的搜索框都在内容滚动区之外，列表滚动时保持固定，不随内容移动。
- **搜索页取消分类 tab 栏**：删除视频 / 番剧 / 影视三个横排分类 chip，搜索类型收进搜索框内的「视频 ▾」菜单。
- **保留功能**：本机历史 / B站历史拆分、社交 2.0、好友在线、会员分享、B站大会员 > BiliDiy 激活 > 好友分享的权益优先级，以及杜比 / FLAC / AAC 多音轨回退。

## 构建 Android

需要 JDK 17、Android SDK platform 35、build-tools 35.0.0：

```bat
cd Android
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

release 输出为 `app/build/outputs/apk/release/app-release-unsigned.apk`。工程不包含签名私钥，使用自己的 PKCS#8 私钥和 X.509 证书签名：

```bat
zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --key your-key.pk8 --cert your-cert.pem ^
  --out app-release.apk app-release-aligned.apk
```

## 后端部署

1. 将 `Backend/` 上传到 PHP 7.4+ 站点目录。
2. 复制 `data/config.example.php` 为 `data/config.php`，修改 `client_key`、`admin_hash`、`setup_token`。
3. 确保 `data/` 可写但不可通过 HTTP 直接访问；`data/.htaccess` 已提供拒绝访问规则。
4. `u_f4ff6904bfb5.php` 是受保护部署接收端，源码内 `DEPLOY_TOKEN` 为占位符，部署前替换为自己的随机 Token，不要提交真实 Token。

## 版本

- `versionCode=73`
- `versionName=3.9.39`
- `applicationId=com.gdk.biliapp`
- minSdk 26 / targetSdk 35
- 签名证书 SHA-256：`33a8bef95a2c25ef003e49ba27a63fd79e136671d58bb405a80c917db301df9c`
- APK SHA-256：`04c9f7cd16cbd9c3d518e489f476fd867843a7d22fe8d0c3f00b639bbb7f203f`
