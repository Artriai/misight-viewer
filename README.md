# MiSight 查看器

简单的 Android 离线数据库查看器。支持导入 MiSight SQLite 副本，或通过 root 读取本机主库和备份库。应用没有 INTERNET 权限，不内置分析统计 SDK。

## 功能

- 记录数、事件类型和时间范围概览。
- 分页浏览，按事件类型、日期、包名、字段内容筛选。
- 中文辅助标签，查看和复制完整原始字段。
- 将全部匹配记录导出为 JSON，导出不受当前页限制。
- root 读取前后校验主库和 WAL 的 SHA-256，最多重试三次，使用应用私有目录内的副本。
- 提供三条人工构造的演示数据；源码和测试不包含真实手机数据库。

时间显示为北京时间。事件标签用于辅助阅读，部分字段、操作编号和时间单位仍需结合系统版本判断。本地记录不等于已经上传。

## 使用

安装 Actions 构建的 debug APK，打开后选“导入数据库”或“读取本机 · Root”。导入副本不需要 root；本机读取会向 `su` 请求授权。

本机读取路径仅限：

- `/data/miuilog/misight/event.db`
- `/mnt/rescue/misight/event.db`

查看器不修改系统数据库或服务，不负责停止采集。用户手动导入时应提供一致性快照；活跃数据库仅复制主文件可能漏掉 WAL 中的记录。单文件和 WAL 的读取上限各为 256 MB。

## 构建

GitHub Actions 使用 JDK 17、Gradle 8.11.1、AGP 8.9.2 和 Android SDK 35。推送到 main 或手动运行 `Build Android APK` 即可编译。

```sh
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

构建产物 `misight-viewer-debug` 内是可直接安装的 APK。设备测试 APK 单独保存在 `misight-viewer-device-tests`。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w io.github.artriai.misightviewer.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试覆盖只读查询、跨页稳定性、筛选组合、秒/毫秒日期、完整导出、损坏输入、旧版表结构及演示数据。测试只使用人工创建的数据库。

## 版本

0.1.0 首版：Android 8.0 及以上。debug APK 使用构建环境的调试证书；后续不同构建环境的证书可能变化，需要重新安装。本项目尚未配置稳定的发布签名。
