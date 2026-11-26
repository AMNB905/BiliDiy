# Design

<!-- impeccable:design-schema 1 -->

**World:** 粉墨（Pink Ink）— 品牌粉只当墨用，纸白做底，封面当插图；没有光斑，没有渐变，没有浮岛。
**Mode:** Operate（用户是来完成一件事的：找到、播放、留住）。
**Platform:** android（Material 3 管结构与交互，品牌只从色彩、字体层级、形状三处进入）。

## Thesis

现有 3.9.39 是「浅色 + 粉色光斑 + 悬浮岛底栏」的拼装感：装饰比内容响。
重做成一本印刷物：纸底、黑字、一支粉墨；封面图是唯一的大色块，界面其余部分退到灰阶。
Android 用户熟悉的 M3 组件全部保留（导航栏、顶栏、卡片、chip、sheet），因为它们就是可用性。

## Color

色彩角色，不写裸 hex 到布局里。浅色 / 深色两套同名 token（`values/colors.xml`、`values-night/colors.xml`）。

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `bg` | `#FDF9F7` | `#141110` | 页面底（暖纸 / 深墨） |
| `surface` | `#FFFFFF` | `#1B1715` | 卡片、栏底 |
| `surfaceContainer` | `#F6EFEC` | `#231E1B` | 搜索框、chip、输入区 |
| `surfaceContainerHigh` | `#EFE5E1` | `#2C2622` | 选中底、按下底 |
| `line` | `#E8DCD6` | `#3A322E` | 1dp 分隔线与描边 |
| `textMain` | `#241F1D` | `#F2EAE5` | 标题、正文 |
| `textSecondary` | `#6B5D58` | `#B4A49C` | 副标题、元信息 |
| `primary` | `#C0265C` | `#FF8FAE` | 唯一的强调色：选中、链接、进度、主按钮底 |
| `onPrimary` | `#FFFFFF` | `#3A0A1C` | 主色上的字 |
| `primaryContainer` | `#FBE4EB` | `#4A2030` | 选中 chip 底、导航指示器 |
| `onPrimaryContainer` | `#8A1A44` | `#FFD9E3` | 容器上的字 |
| `brandDeco` | `#FB7299` | `#FB7299` | 只做填充与图形，绝不用于文字 |
| `accent` | `#FFC53D` | `#FFC53D` | 弹幕黄，只用于「直播 / 热门」小角标，配深色字 |
| `ok` / `warn` / `bad` | `#2E7D48` / `#9A6B10` / `#C0392B` | 同左提亮版 | 状态，仅用于图标与短标签 |

实测对比度（浅色）：primary on surface 5.72、textSecondary on bg 6.02、textMain on bg 15.57；
深色：primary on bg 8.76、textSecondary on bg 7.81。`brandDeco` 只有 2.64，所以它不是文字色。

## Type

系统字（Android 上就是 Roboto + 设备中文字体），靠层级和字重说话，不引入第三方字体。

| Role | Size / Line | Weight | Tracking |
|---|---|---|---|
| Display | 30 / 38 | 700 | -0.02em |
| Headline | 24 / 31 | 700 | -0.01em |
| TitleLarge | 20 / 26 | 600 | 0 |
| Title | 17 / 23 | 600 | 0 |
| Body | 15 / 22 | 400 | 0 |
| BodySmall | 13 / 19 | 400 | 0 |
| Label | 12 / 16 | 500 | +0.02em |

规则：一行正文只承担一件事；标题最多两行；时间、时长、集数用等宽数字（`tnum`）。

## Shape & elevation

- 圆角：封面 12、卡片 16、面板 20、底栏/搜索 28（顶部）、chip 与按钮 999。
- 不做投影：层级用「纸白 surface + 1dp line」表达；底栏只用一条上边线。
- 卡片只有一层：列表项不再套 MaterialCardView 里再放一层卡片。

## Components

- **顶栏**：56dp，纸底，左品牌标记 + 标题（TitleLarge），右侧一个图标动作；滚动时不换成大标题（单页只有一个头）。
- **搜索**：52dp 胶囊，`surfaceContainer` 填充，无投影；聚焦时 2dp `primary` 描边；类型切换在框内尾部。
- **底栏**：M3 `NavigationBar` 80dp，`surface` 底 + 顶部 1dp `line`，四项（首页 / 功能 / 本地 / 我的），选中项是 `primaryContainer` 指示器 + `primary` 图标与文字。不再用悬浮岛。
- **视频卡**：封面 12 圆角（16:9），右下时长角标（4dp 圆角、`#000000B3` 底、白字 11sp），标题 2 行 Title，元信息 1 行 BodySmall；列表直接用纸底，不画外框。
- **Chip**：36dp 高，`surfaceContainer` 底；选中 `primaryContainer` + `onPrimaryContainer` 字。
- **按钮**：Filled（`primary` 底、`onPrimary` 字、48dp、胶囊）；Tonal（`primaryContainer`）；Text（`textSecondary`，动作动词开头）。
- **Sheet**：顶部 28 圆角，纸白，只有一个抓手。
- **空态 / 错误**：两行封顶——第一行说发生了什么，第二行说怎么办，最多一个按钮。

## Motion

- 时长：进入 200ms、退出 160ms，`FastOutSlowIn`。
- 页面切换：内容淡入 + 8dp 上移；返回反向。
- 卡片按下用 Material ripple（`primaryContainer` 20%），不做缩放把戏。
- 唯一编排过的时刻：进入播放页时，封面 240ms 交叉淡入到视频首帧，避免黑屏闪一下。
- 系统开启「移除动画」时全部退化为直切。

## Don't

- 不要径向光斑、渐变 masthead、玻璃模糊——它们是装饰，不是层级。
- 不要浮岛式底栏、卡片套卡片、满屏粉色底。
- 不要把 `brandDeco` / `#FB7299` 当文字色。
- 不要为了「显得设计过」加眉标（kicker）、编号（01/02）、渐变文字。

## Verification

- 构建：`gradle :app:assembleDebug`（JDK 17 + SDK 35）。本次无可用的模拟器/真机，截图验证缺席，已如实记录。
- 深色：`values-night/colors.xml` 与主题同名替换；切换系统深色即生效。
- 对比度：见上表，均 ≥ 4.5:1（大字 ≥ 3:1）。

## Implementation status

- 已落地：语义色 + 深色同名覆盖、字号层级、16dp 卡片/12dp 封面、标准 M3 底栏、首页顶栏、列表行、搜索页、设置页、好友页、番剧页、启动页；`MainActivity` 已移除浮岛与光呼吸。
- 构建：`gradle :app:assembleDebug` 通过（Gradle 8.13 + JDK 17 + SDK 35，`app-debug.apk` 约 9.7MB）。
- 已知缺口：没有真机/模拟器，无法提供 `adb exec-out screencap` 截图；深色与动效需要在设备上再过一眼。
- Maven 源：`settings.gradle.kts` 增加阿里云镜像（本机访问 Maven Central 超时）。
