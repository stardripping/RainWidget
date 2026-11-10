# 降雨提醒（RainWidget）

一个 Android 桌面小组件：**未来 12 小时内有雨时显示出来**。

- 有雨：蓝色卡片，显示几点开始下、几点停、最高降水概率、累计降水量，以及 12 小时降水概率柱状条
- 无雨：显示「12 小时内无雨」，也可以设置为完全空白（只在有雨时出现）
- 点按小组件立即刷新，点按底部地点行打开设置
- 后台约每 30 分钟自动刷新（WorkManager）

天气数据来自 [Open-Meteo](https://open-meteo.com/)，免费、无需 API Key。

## 判断规则

某小时满足任一条件即视为有雨：

- 降水概率 ≥ 阈值（默认 50%，设置页可调 10%–90%）
- 预报降水量 ≥ 0.5 mm

## 位置

设置页里「使用当前定位」（只需粗略定位权限），或直接搜索城市名。

## 构建

需要 JDK 17+ 和 Android SDK（compileSdk 35）。在项目根目录创建 `local.properties`：

```
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

然后：

```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构

| 文件 | 作用 |
|---|---|
| `Weather.kt` | 请求 Open-Meteo、解析逐小时预报、判断是否有雨、城市搜索 |
| `RainWidget.kt` | 生成小组件的 RemoteViews |
| `RainWidgetProvider.kt` | 小组件生命周期与点按刷新 |
| `RefreshWorker.kt` | 后台定时刷新 |
| `SettingsActivity.kt` | 设置页：定位、城市搜索、阈值、无雨时隐藏 |
| `Prefs.kt` | SharedPreferences 存储 |

## 三星等国产系统

如果小组件长时间不更新，请在系统设置里把本应用的电池使用改为「不受限制」。
