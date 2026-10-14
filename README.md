# ScreenRecorder

基于 **Java 21 + JavaFX 21 + FFmpeg** 的屏幕录制应用，提供深浅色主题、自定义无边框界面、硬件编码、音频混音、素材库和内置预览／裁剪。

> **Windows x64 与 Linux x86_64 共用 Java 源码，原生引擎和打包流程按平台分离。** Windows 已有自包含安装流程；Linux 已提供项目专用引擎与 AppImage，并完成本地 X11 双屏环境验证。Wayland 已接入 Portal + PipeWire，但尚未完成真实桌面录制验收；macOS 发行适配尚未完成。不能把 Linux 测试通过等同于 Windows/macOS 回归通过。

## 目录

- [功能与限制](#功能与限制)
- [平台支持状态](#平台支持状态)
- [开发与运行](#开发与运行)
- [原生引擎与辅助程序](#原生引擎与辅助程序)
- [测试](#测试)
- [打包](#打包)
- [Linux / AppImage](#linux--appimagewindows-与-linux-共用源码)
- [项目结构](#项目结构)
- [数据与日志](#数据与日志)
- [常见问题](#常见问题)
- [许可与发行](#许可与发行)

## 功能与限制

### 画面录制

- 显示器、桌面、自定义区域和指定窗口录制，支持多显示器区域选择。
- Windows 优先使用 GPU Desktop Duplication（`ddagrab`），兼容回退为 GDI（`gdigrab`）。
- 定制 `desktop_coords` 补丁根据物理桌面坐标选择 DXGI 输出，避免将 JavaFX 显示器序号误作 DXGI 输出序号。
- **GPU 捕获矩形必须完全位于当前 DXGI 适配器可访问的单个输出内。** 跨显示器／整个虚拟桌面等不满足条件的捕获会回退到 GDI。
- **窗口录制是窗口所在的可见桌面矩形**，不是后台或逐进程捕获。遮挡、移动、缩放或最小化会影响画面。
- 可配置帧率、分辨率、倒计时、光标和独立录制边框。
- 支持自动码率或显式 **1–200 Mbps** 码率；具体格式和编码能力取决于实际 FFmpeg 构建。
- Windows 编码器包括 NVENC、AMF、Quick Sync，以及 x264/x265 软件编码；硬件编码会通过实际试编码验证，驱动不可用时不会仅凭编译列表认定支持。

### 音频

- Windows 麦克风通过 **DirectShow** 枚举和采集。
- 从 **1.0.12** 起，Windows 系统声音优先使用 **WASAPI loopback**：直接显示已启用的耳机、扬声器、显示器音频等播放端点，不要求启用立体声混音或安装虚拟声卡。
- 系统声音应选择 **Windows 实际播放声音的输出设备**。选择扬声器不会自动采集另一副耳机的声音。
- 麦克风与系统声音独立选择、独立增益，混合为单轨；可设置单声道。
- 插拔设备后使用录制页的 **刷新音频设备**。录制中不支持自动迁移到新输出设备；更换输出后应停止、刷新、重新选择并开始新录制。
- 若没有枚举到 WASAPI 输出端点，保留原有 DirectShow 回环输入作为兼容回退。普通麦克风不应当作系统播放声音来源。
- FFmpeg 输入顺序保持音频在前、桌面视频在后，避免此前验证过的桌面／音频初始化卡顿问题。

### 界面与预览

- 深浅色主题、自定义强调色、快捷键、系统托盘及窗口尺寸保存。
- 录制悬浮栏提供计时、暂停／恢复、停止和确认丢弃。
- 计时满一小时后由 `分:秒` 切换为 `时:分:秒`，不存在 `99:59` 显示上限。
- 设置中的 **不将录制悬浮栏录入视频** 默认勾选，下次录制生效。
  - Windows 使用 `SetWindowDisplayAffinity` 原生窗口排除，需要 Windows 10 2004+ 和兼容采集方式。
  - Windows 若排除调用失败，本次悬浮栏会隐藏，可从主窗口或托盘停止；API 成功也不代表所有采集后端都保证兼容。
  - Linux/macOS 尚未实现此项排除能力。Linux 不再因默认勾选排除选项而隐藏悬浮栏；控制条可能进入成片，不希望录入时请关闭“录制时显示悬浮栏”。
- 素材库、基于 FFmpeg 的有界流式音视频预览、拖动定位、独立前后跳转及全屏预览；不依赖 JavaFX Media。
- 原文件 FFprobe 元数据读取；裁剪／导出支持相应的无损或重编码路径，导出位置由设置控制。

## 平台支持状态

| 功能 | Windows x64 | Linux x86_64 | macOS |
|---|---|---|---|
| JavaFX 主体与设置 | 原有实现；本轮修改尚未真机回归 | 已完成本地 X11 界面验证 | 可复用，需本机验证 |
| 屏幕采集 | 定制 `ddagrab` / `gdigrab` | X11 `x11grab` 已实测；Wayland Portal + PipeWire 待桌面验收 | 存在 AVFoundation 路径，未完成发行验证 |
| 麦克风 | DirectShow | PulseAudio / PipeWire-Pulse | AVFoundation 路径 |
| 系统播放声音 | WASAPI loopback，兼容回环输入回退 | PulseAudio 输出 monitor 源，兼容 PipeWire-Pulse；目标设备仍需实测 | 现有路径依赖 BlackHole 等虚拟回环设备 |
| 悬浮栏捕获排除 | Windows 原生桥接，依赖系统及后端 | 未实现；可关闭悬浮栏 | 未实现 |
| 托盘 | AWT SystemTray | GTK/AppIndicator 助手；依赖桌面托盘协议支持 | 需本机验证 |
| 随包原生引擎／发行流程 | `ffmpeg/win` 与 Windows 安装脚本 | `ffmpeg/linux` 与 AppImage 构建脚本 | 未提供完整发行流程 |

**Linux 特别说明：** WASAPI、DirectShow、Windows DLL/EXE 不用于 Linux。X11 和 Wayland 使用不同采集路径；AppImage 内置应用运行组件，但不能替代系统图形会话、桌面门户、音频服务、glibc 或显卡驱动。详见 [Linux / AppImage](#linux--appimagewindows-与-linux-共用源码)。

### 平台隔离与 Windows 回归范围

本轮 Linux 适配保留 Windows 专属实现，不替换 Windows 原生组件：

- Windows 引擎及辅助程序仍位于 `ffmpeg/win`，Linux 独立使用 `ffmpeg/linux`；Maven 仅复制当前平台的引擎。
- Windows 保留 AWT 托盘、原有区域选择窗口、透明圆角主窗口、全屏倒计时样式和 Win32 悬浮栏捕获排除。
- Linux 使用 GTK/AppIndicator 托盘、区域选择弹出层、普通无装饰直角主窗口和紧凑倒计时卡片。

**共用代码仍有变化，因此不能宣称 Windows 完全不受影响：** 标题栏文字／图标／空白区域可拖动，最大化拖动恢复后重新启用缩放边缘，倒计时及悬浮栏按录制目标定位，悬浮栏停止录制在后台执行以避免阻塞界面。

截至本轮本地验证，Linux 测试为 **44 项、0 失败、0 错误、3 项跳过**；此结果不代表 Windows 真机验收。Windows 仍需验证主窗口拖动／八方向缩放／最大化恢复、多显示器区域选择与倒计时、托盘、悬浮栏排除，以及录制停止封装、暂停恢复和音频采集。本轮亦未完成真实 Wayland 录制、旧版 glibc 兼容性或干净机器发行验收。

## 开发与运行

### 技术栈

| 组件 | 当前配置 |
|---|---|
| Java | 21，编译目标 `release 21` |
| JavaFX | 21.0.2：controls、graphics、base、swing；没有 javafx-media 依赖 |
| Jackson | 2.17.2 |
| JUnit | 5.10.2 |
| Maven | 3.8+ |
| 随包 FFmpeg | Windows：定制 9.0.2 GPL shared；Linux：定制 8.0.1 GPL，x264/x265 软件编码 |
| Windows 原生辅助程序 | C/C++，Win32 JNI 桥接与 WASAPI loopback |
| Linux 原生辅助程序 | C，Portal/PipeWire 接入、X11 窗口枚举与 GTK/AppIndicator 托盘 |

### 快速开始（Windows）

安装 JDK 21 和 Maven，确认 `JAVA_HOME`、`java -version`、`mvn -version` 正确，然后在仓库根目录执行：

```powershell
git clone https://github.com/Ganyu-1202/ScreenRecorder.git
cd ScreenRecorder
mvn clean compile
mvn javafx:run
```

仓库已经包含完整 `ffmpeg/win/`。从含 `pom.xml` 的项目根目录启动时会使用项目引擎；**用户在设置中显式指定的 FFmpeg 路径仍优先**。无须为正常 Windows 开发启动另行下载 FFmpeg。

应用仍保留缓存引擎、PATH 查找及下载／本地安装路径作为回退；这些不是当前离线安装包的正常运行前提。通用 FFmpeg 不一定包含本项目的定制 GPU 参数和原生辅助程序。

### IDE

导入 Maven 项目，使用入口：

- JavaFX 入口：`org.firefly.screenrec.ScreenRecorderApp`
- 可执行 JAR／安装包入口：`org.firefly.screenrec.Launcher`

优先使用 Maven 自动管理 JavaFX 依赖与平台分类器。手动配置模块路径时需准备与平台匹配的 JavaFX SDK，并补齐 Jackson 等类路径依赖；不要再添加本项目没有使用的 `javafx.media`。

## 原生引擎与辅助程序

**请整体保留并跟踪 `ffmpeg/win/`，不要只复制 ffmpeg.exe。** 当前目录包含：

- `ffmpeg.exe`、`ffprobe.exe` 和配套 shared DLL；
- `wasapi-loopback.exe`：枚举 Windows 播放端点，采集所选端点的原生音频，通过本地命名管道交给 FFmpeg；
- `screenrec-capture.dll`：在应用进程内调用 Win32 窗口捕获排除 API；
- 许可证、构建配方、FFmpeg 补丁及原生文件 SHA-256 清单 `checksums.json`。

FFmpeg 含 `tools/ddagrab-desktop.patch` 与 `tools/gdigrab-cursor.patch`；后者**没有被证明能消除 GDI 实际桌面光标闪烁**。可用的单输出 GPU 路径是当前避免该问题的方案。

Maven 在 `process-resources` 阶段仅把项目 `ffmpeg/` 下当前平台的引擎目录复制到构建目录。Windows 安装脚本也从项目目录获取引擎，不依赖构建缓存中的另一份 FFmpeg。

重新编译辅助程序需 Windows x64 MinGW-w64 工具链：

```powershell
# 设置 JAVA_HOME 为 JDK 根目录；Gcc/Cxx 可以传入完整可执行文件路径
.\tools\build-capture-exclusion.ps1 -Gcc gcc
.\tools\build-wasapi.ps1 -Cxx g++
```

源码位于 `native/windows/`。脚本会更新对应二进制校验值。定制 FFmpeg 的构建脚本为 `tools/build-custom-ffmpeg.sh`，使用 MSYS2 和本地依赖缓存；其中路径和工具链配置需要根据构建机器调整，不是通用的一键跨平台构建脚本。

## 测试

```powershell
mvn test

# Windows 本机原生集成测试：提供完整项目引擎路径
mvn test "-Dpreview.ffmpeg=D:/path/to/ScreenRecorder/ffmpeg/win/ffmpeg.exe"
```

未配置 `preview.ffmpeg` 时，部分原生集成测试会跳过。真实桌面采集测试需要可用的图形会话和适配的显示器布局；不要把普通单元测试通过等同于所有平台音画录制验证通过。

当前 WASAPI 已验证播放端点枚举、耳机静音时连续音频输出、FFmpeg 解码，以及短时 GPU 视频＋回环音频封装。**实际播放音质、反复暂停／恢复、长时间音画同步、设备拔出和其他电脑兼容性仍需进一步验证。**

## 打包

### 可执行 JAR

```powershell
mvn -Pfatjar clean package
java -jar target/ScreenRecorder.jar
```

产物为 `target/ScreenRecorder.jar`。Fat JAR 包含依赖和构建平台匹配的 JavaFX 资源，但仍需要 Java 运行时及外置原生引擎；它不是一个通用于所有系统和架构的独立文件。

单独分发时保持目录结构：

```text
发行目录/
├── ScreenRecorder.jar
└── ffmpeg/
    └── win/                 # 完整引擎、DLL、辅助程序与许可证
```

### Windows 自包含安装包

```powershell
mvn -Pfatjar clean package
.\tools\build-installer.ps1 -Version 1.0.12

# 或明确指定其他构建目录的 JAR
.\tools\build-installer.ps1 -Version 1.0.12 -JarPath D:\path\to\ScreenRecorder.jar
```

当前脚本使用 `jlink` 裁剪运行时并通过 `jpackage` 生成 EXE 安装包及应用镜像，携带 Java、JavaFX、FFmpeg 和两个原生辅助程序，正常运行无须用户另装 Java／FFmpeg。

**打包前提与限制：**

- 当前脚本中的 `jpackage` 路径指向本机 JDK，需按机器修改。
- `tools/packaging-cache/temurin-jdk/` 需有打包用 JDK，`tools/packaging-cache/wix/` 需有兼容 WiX 工具链；它们不纳入版本控制。
- 安装包为 per-machine，安装／升级通常需要系统授权；正常录制不要求管理员权限。
- 使用固定升级 UUID；安装与升级验证时应确认保留用户设置和录制文件。
- 输出在 `target/installer-<时间戳>/image/` 和 `output/`。
- 当前安装包未签名，尚未完成干净虚拟机和所有设备场景验证。

### Linux / macOS

Linux 已提供自包含 AppImage 构建流程，产物为 `target/ScreenRecorder-x86_64.AppImage`，详见 [Linux / AppImage](#linux--appimagewindows-与-linux-共用源码)。正常使用无需另外安装 Java 或 FFmpeg，但仍依赖兼容的系统基础服务。

Linux `.deb`/`.rpm`、macOS `.dmg`/`.app` 尚未提供完整发行流程。macOS 仍需准备匹配架构的 JDK、JavaFX、FFmpeg 并验证权限和音频回环。生成包文件不代表所有平台功能和兼容性已验收。

## 项目结构

```text
ScreenRecorder/
├── pom.xml
├── README.md
├── LICENSE
├── ffmpeg/win/                       # 版本控制内的完整 Windows 原生运行组件
├── ffmpeg/linux/                     # Linux FFmpeg、辅助程序、许可证及校验清单
├── native/windows/                   # 捕获排除 JNI 与 WASAPI C/C++ 源码
├── native/linux/                     # Portal、X11 窗口枚举与 AppIndicator C 源码
├── .github/workflows/linux-appimage.yml # Linux AppImage CI 配方
├── tools/
│   ├── build-linux-ffmpeg.sh
│   ├── build-appimage.sh
│   ├── bundle-linux-libs.py
│   ├── build-installer.ps1
│   ├── build-capture-exclusion.ps1
│   ├── build-wasapi.ps1
│   ├── build-custom-ffmpeg.sh
│   ├── ddagrab-desktop.patch
│   ├── gdigrab-cursor.patch
│   └── packaging-cache/              # 忽略：本地工具链、源代码及构建缓存
├── src/main/java/org/firefly/screenrec/
│   ├── Launcher.java
│   ├── ScreenRecorderApp.java
│   ├── core/                         # 录制、音频、设置、媒体、屏幕信息
│   ├── ui/                           # 页面、组件、悬浮栏、主题、托盘
│   └── util/
├── src/main/resources/org/firefly/screenrec/
│   ├── css/
│   └── icons/
├── src/test/java/org/firefly/screenrec/
└── target/                           # 忽略：Maven 与安装包产物
```

核心录制逻辑尽量独立于界面，但并非严格零 JavaFX 依赖：可观察录制状态、显示器几何和截图等使用 JavaFX 类型。

## 数据与日志

| 路径 | 用途 |
|---|---|
| `~/.screenrecorder/settings.json` | 用户配置 |
| `~/.screenrecorder/logs/session.log` | 启动、设备枚举、录制命令与性能日志 |
| `~/.screenrecorder/encoder-cache.properties` | 硬件编码器探测缓存 |
| `~/.screenrecorder/ffmpeg/` | 旧有／回退下载引擎，不是随包引擎位置 |

Windows 的 `~` 对应 `%USERPROFILE%`。默认录制输出为 `~/Videos/ScreenRecorder`，若 `Videos` 不存在则回退到 `~/ScreenRecorder`；可在设置中修改。

```powershell
# Windows 实时跟踪日志
Get-Content "$env:USERPROFILE\.screenrecorder\logs\session.log" -Wait
```

```bash
# Linux / macOS
tail -f ~/.screenrecorder/logs/session.log
```

请优先使用软件停止录制。不要为解除文件占用而直接终止电脑上所有 FFmpeg 进程；其他应用也可能使用 FFmpeg。

## 常见问题

### 耳机／扬声器有声音，但成片无声

在录制页打开系统声音开关，并选择 Windows 当前实际使用的播放设备。刷新后重新选择；更换输出设备不会自动切换已有录制。检查日志中的 `WASAPI playback endpoint`、`WASAPI error` 和录制音频输入。不要把耳麦麦克风误作耳机播放输出。

### 系统声音列表为空

确认播放设备已连接且在 Windows 中启用，点击刷新。确认 `wasapi-loopback.exe` 与实际使用的 FFmpeg 位于同一目录，未被防病毒软件隔离。显式设置的外部 FFmpeg 目录可能缺少辅助程序。旧版仅依赖 DirectShow 回环输入，不能用旧版能力判断新版 WASAPI 支持情况。

### 麦克风没有设备或没有声音

检查连接、驱动、设备启用状态及 Windows 允许桌面应用访问麦克风的权限，然后刷新。能枚举出设备不保证它没有被静音或能实际采到声音。日志记录设备名称和分类，便于排查。

### 悬浮栏仍出现在成片／录制时没有悬浮栏

确认“录制时显示悬浮栏”和“不将录制悬浮栏录入视频”的设置。Windows 排除依赖系统版本和采集后端；调用失败时程序会隐藏悬浮栏，日志记录原因。Linux 未实现捕获排除，默认排除选项不会隐藏控制条；不希望控制条进入成片时请关闭显示选项。兼容性需以短录制实际成片验证，不能仅凭 API 返回成功认定。

### GPU 区域捕获失败或实际鼠标闪烁

跨输出、跨适配器或通用 FFmpeg 缺少 `desktop_coords` 时，可能回退为 GDI。当前 GDI 实际鼠标闪烁仍未解决；尽量使用可用的单输出 GPU 捕获。GPU 捕获与硬件编码是不同概念，GPU 采集也可以搭配 x264 软件编码。

### 启动／设备名称乱码

检查日志中的实际引擎路径。程序会跳过无法解析的 PATH 项，但不会修改系统 PATH。某些设备名称在原生工具输出中可能含替换字符，需要进一步核对原始名称、输出编码与驱动，不能仅据乱码断言是系统问题。

### MP4 无法播放或缺少 moov atom

正常停止通过 FFmpeg stdin 的 `q` 完成刷新和封装。异常退出可能留下不完整 MP4；MKV 对部分中断场景更有韧性，但不是绝对不会损坏。检查停止日志并保留异常文件用于诊断。

## 许可与发行

仓库 `LICENSE` 包含 **GNU GPL Version 3** 文本；主项目授权以仓库许可证及相关声明为准，本文不另行改变授权范围或指定“或更新版本”。

FFmpeg、x264/x265、Java/JavaFX 及其他第三方组件保留各自许可。当前定制 FFmpeg 是包含 GPL 组件的构建。

**公开分发原生二进制前仍需完善许可合规：** 提供精确对应构建的完整源代码、第三方对应源码、修改补丁、必要构建材料及许可证／声明；只附上游链接、补丁和许可证文本不等于已经履行全部义务。源码构建缓存被 Git 忽略，也不等于已为二进制接收者提供对应源码。代码签名和公开发行合规仍需单独完成。

## Linux / AppImage（Windows 与 Linux 共用源码）

### 直接运行

构建产物为 `target/ScreenRecorder-x86_64.AppImage`：

```bash
chmod +x ScreenRecorder-x86_64.AppImage
./ScreenRecorder-x86_64.AppImage
# 不支持 FUSE 的环境：
./ScreenRecorder-x86_64.AppImage --appimage-extract-and-run
# 查看内置引擎版本，无需系统 FFmpeg / Java：
./ScreenRecorder-x86_64.AppImage --appimage-extract-and-run --engine-version
```

AppImage 内置 Java 21 runtime、JavaFX 原生库、项目专用 FFmpeg / ffprobe、
X11 窗口助手、Wayland Portal 助手、GTK/AppIndicator 托盘助手及客户端库、GStreamer 必要插件、PipeWire 客户端模块、
PulseAudio 客户端与递归解析的共享库。不需要用户安装 FFmpeg、Java、pactl 或 GStreamer。
Windows 的 `ffmpeg/win` 和 Windows 原生后端保持独立；Linux 引擎位于 `ffmpeg/linux`。
Maven 仅复制当前平台的引擎。AppImage 在只读挂载目录查找内置引擎，录制及设置仍写入用户目录。

### X11 / Wayland 的行为差异

* **X11**：FFmpeg `x11grab`；全屏、显示器、鼠标拖选区域、可见窗口矩形；窗口枚举使用内置 Xlib 助手。
  区域选择为每块显示器创建独立弹出层，避免窗口管理器自动移位／裁剪后导致选择层重叠；已验证两块显示器分别拖选、Enter 确认及物理坐标回传。
  一次区域选择限于一块显示器，不支持跨两屏连续拖选。
* **Wayland**：强制 ScreenCast Portal + PipeWire，不能误用 XWayland 的 `DISPLAY`，也不需要 root / KMS 权限。
  每次录制由系统弹出屏幕 / 窗口授权选择框，授权成功后启动编码。
  当前一次选择一个屏幕或窗口；“全屏”在 Wayland 下指系统选择的一块屏幕，并非跨屏拼接。
  区域录制使用相对于授权屏幕的**物理像素坐标裁剪**，目前通过坐标表单选择，不使用不可靠的 JavaFX Robot 截取 Wayland 桌面。
  所选区域必须落在授权屏幕范围内。窗口类型是否可选由桌面的 Portal 后端决定。
* 音频使用 PulseAudio 协议，兼容 PulseAudio 或 PipeWire-Pulse；支持麦克风与输出监视源混音。
* 编码、停止封装、预览、媒体库及编辑继续使用原有共用实现。默认提供 x264/x265 软件编码。
  可在构建时添加硬件 SDK 编译参数；不能将显卡驱动装进 AppImage。
* Wayland 不允许应用全局枚举窗口、任意读取屏幕、强制窗口位置或排除自己的浮窗。
  浮动控制条可能进入录制画面，可在设置中关闭；全局快捷键、托盘可用性取决于桌面环境。
  因此不能宣称所有 Windows 特有的窗口排除/桌面行为完全等价。
* Linux 托盘使用独立 GTK/AppIndicator 助手，避免 AWT XEmbed 在 GNOME 上的图标异常；
  助手及客户端库随 AppImage 打包。桌面仍需支持 StatusNotifier/AppIndicator 托盘协议。
* Linux 主窗口使用普通无装饰直角窗口，保留自绘标题栏，避免透明顶层窗口的拖动／缩放兼容性问题。
  标题文字、图标和空白区域均可拖动；已通过实际鼠标输入验证非最大化窗口拖动和右侧边缘缩放。
* Linux 倒计时使用目标屏幕/窗口/区域中心的紧凑卡片，避免全屏无边框窗口被窗口管理器移位。
  Windows 保留原有全屏倒计时与浮窗录制排除。

操作系统必须提供运行中的图形会话、D-Bus、匹配桌面的 xdg-desktop-portal ScreenCast 后端、
PipeWire 服务（Wayland）、PulseAudio 或 PipeWire-Pulse 服务（音频）以及系统驱动。
这些是桌面基础服务，不是需要用户另装的应用编码依赖；将它们打包进 AppImage 不能替代桌面服务。
JavaFX 21 Linux UI 使用 X11，Wayland 桌面还需提供 XWayland（录制数据仍来自原生 PipeWire）。

### 从源码构建（x86_64）

推荐在 Ubuntu 22.04 构建以降低 glibc 基线；本地 Ubuntu 24.04 构建的二进制不能保证在更旧系统运行。
构建机器需要 Java **JDK** 21、Maven、编译工具及开发头文件：

```bash
sudo apt-get install build-essential pkg-config nasm libx264-dev libx265-dev \
  libpulse-dev libxcb1-dev libxcb-shm0-dev libxcb-xfixes0-dev libglib2.0-dev libx11-dev \
  gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-pipewire pulseaudio-utils libgtk-3-dev libayatana-appindicator3-1
./tools/build-linux-ffmpeg.sh
# 固定或校验 FFmpeg 源码包：FFMPEG_VERSION=8.0.1 FFMPEG_SOURCE_SHA256=... ./tools/build-linux-ffmpeg.sh
# 从可信来源获取 appimagetool，设置其路径（不会自动执行未知系统工具下载）：
APPIMAGETOOL=/absolute/path/to/appimagetool ./tools/build-appimage.sh
mvn test
# X11 实际抓屏、MP4 停止封装测试（可用 xvfb-run 包装）
mvn -Dscreenrec.linux.integration=true test
```

`tools/build-linux-ffmpeg.sh` 从 FFmpeg 8.0.1 源码构建定制引擎，开启 XCB 抓屏、PulseAudio、x264/x265；
不复用系统的 FFmpeg 可执行文件。FFmpeg 自身库静态链接，外部依赖由 AppImage 脚本自动收集。
打包过程不打包 glibc 和显卡驱动；FFmpeg 配置、校验值及许可证保存在项目引擎目录。
分发时也需要满足 FFmpeg/x264/x265 等 GPL 组件的对应源码提供义务；仅附带许可证不等于完成全部义务。
CI 配方见 `.github/workflows/linux-appimage.yml`，Wayland 授权流程仍需 GNOME/KDE/其他目标桌面人工验收。
