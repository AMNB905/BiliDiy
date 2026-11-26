# BiliDiy

Android 端 B 站「搜索 + 播放」客户端，Kotlin + Media3/ExoPlayer。**本仓库只包含 Android App 源码**；后端源码与历史发版包不在仓库内。

## 目录

- `Android/`：Android 工程源码，可直接用 Android Studio / Gradle 打开。

## 内置更新

App 的「检查更新」直接读 GitHub Releases（公开仓库 `AMNB905/BiliDiy` 的 `releases/latest`），不经过任何自建接口：

- 版本号取 release 正文里的 `versionCode: NN` 标记；
- 安装包取该 release 的 `.apk` 资产（通过 assets API 下载）；
- `forceBelow: NN` 表示低于该版本强制更新；
- 安装前校验包名、版本号与签名证书一致。

发版时在 release 正文保留这两行标记即可，示例：

```
versionCode: 77
forceBelow: 0

- 放映节目册重做界面
```

## 构建

需要 JDK 17、Android SDK platform 35、build-tools 35：

```bat
cd Android
gradlew.bat assembleDebug
gradlew.bat assembleRelease
```

release 输出为 `app/build/outputs/apk/release/app-release-unsigned.apk`，用 PKCS#8 私钥 + X.509 证书签名（证书 SHA-256 见下）：

```bat
zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign --key New_Key_*.pk8 --cert New_Key_*.x509.pem --out BiliDiy-release.apk app-release-aligned.apk
```

国内构建时 `Android/settings.gradle.kts` 已配置阿里云 Maven 镜像。

## 3.9.43 更新

- **放映节目册**：整套界面重做成一本影展节目册——暖纸底 `#F5EFE3`、墨黑 `#17120D`、深绯红 `#9C1B3B`；标题用宋体，场次信息用黑体，时长用等宽；封面 1dp 墨框，刊头双栏线，搜索框方角，启动页是节目册封面。深色模式是「夜场」。
- 圆角统一收到 2dp，去掉投影、渐变、光斑与胶囊按钮；底栏仍用标准 M3 NavigationBar，选中指示器改方角。
- 版本号 3.9.43 / versionCode 77。
- 对比度：正文 16.2:1、副文 7.3:1、主色 6.96:1。

## 3.9.42 更新

- 版本号升级到 3.9.42（versionCode 76），界面与更新链路沿用 3.9.40。
- 用于验证「3.9.40 → GitHub 检查更新 → 后台下载 → 覆盖安装」整条链路。

## 3.9.40 更新

- **粉墨（Pink Ink）设计系统**：纸白底 + 墨粉主色，删掉粉色渐变 masthead、呼吸光斑与浮岛底栏，改用标准 M3 底栏；深色模式同一套 token 全覆盖。
- **内置更新改走 GitHub Releases**：设置页「检查更新」与启动静默检查都读 `releases/latest`，下载交给系统 DownloadManager，安装前校验签名。
- 仓库精简为只含 App 源码。

## 版本

- `versionCode=77` / `versionName=3.9.43`
- `applicationId=com.gdk.biliapp`，minSdk 26 / targetSdk 35
- 签名证书 SHA-256：`33a8bef95a2c25ef003e49ba27a63fd79e136671d58bb405a80c917db301df9c`

