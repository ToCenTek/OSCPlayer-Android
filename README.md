# OSCVideoPlayer v2.0

Android TV 视频播放器, 通过 OSC (UDP 8000) 协议远程控制.
专为展览展示/视频墙/数字标牌场景设计, 兼容 Allwinner/Rockchip/Amlogic 等设备.
采用 MIT 开源许可.

## 功能

- OSC 命令全面控制播放/播放列表/电源/定时
- **多节点自动同步** — 固定基准 + 双向比例调速, 持续收敛
- 硬解加速, 自动回退软解 (Media3 DefaultRenderersFactory)
- 视频扫描 (默认 /Movies, 可通过 `/config/dir` 运行时修改)
- USB/SD 卡自动发现, 全盘扫描, 只读不删
- 播放列表 (单曲循环/列表循环/随机播放/播完停止)
- 播放速度调节 (0.25x - 4.0x)
- 字幕加载
- 文字叠加显示 (OSC `/tct` 命令)
- 定时播放/停止
- 显示器电源定时开关
- 看门狗 (自动检测卡顿并恢复)
- 全屏沉浸式播放
- HTTP 文件管理 (端口 8080)
- 前台服务保持 OSC 服务器持续运行
- WorkManager 每 30 分钟健康检查

## 多盒自动同步

多个节点播放同一视频时, 自动检测漂移并微调速度, 保持帧级同步.

### 工作原理

1. **固定基准**: 首个上报的节点为基准, 可通过容器 `"I am benchmark"` 开关手动指定. 基准永不调速, 音频始终纯净
2. **时间投影**: 将基准位置投影到当前时刻, 消除心跳时间偏差
3. **双向调速**: 从盒快了就降速, 慢了就提速, 连续 2 次超过阈值(33ms)才触发
4. **自动播放**: `/Alignment/ready` 全部上报后延时 500ms 自动 Go

### 相关命令

| 地址 | 参数 | 说明 |
|------|------|------|
| `/alignment/prepare` | 整数: 索引, 整数: 位置(ms) | 对齐准备, 所有节点 seek 到指定位置 |
| `/alignment/go` | - | 开始播放 (脚本已自动, 无需手动) |
| `/alignment/seek` | 整数: 位置(ms) | 直接跳转 |
| `/member join` | - | 加入组播组 |
| `/member leave` | - | 离开组播组 |
| `/member` | - | 查询组播状态 |

### 同步提示

调速时播放器屏幕会弹出 Toast (如 `×1.005` / `×0.995`), 可在设置中关闭或通过 `/config/speedtoast 0` 关闭.

## 遥控器按键

| 按键 | 功能 |
|------|------|
| UP / CH+ | 上一个视频 |
| DOWN / CH- | 下一个视频 |
| LEFT / REWIND | 快退 10 秒 |
| RIGHT / F-FWD | 快进 10 秒 |
| OK / ENTER / PLAY-PAUSE | 暂停/播放 |
| MENU | 主菜单 |
| SETTINGS / F1-F12 | 系统设置 (含同步提示开关) |
| INFO | 关于页面 |
| HOME | 返回系统桌面 |
| BACK | 退出应用 |

## OSC 命令

### 播放控制
| 地址 | 参数 | 说明 |
|------|------|------|
| `/play` | 字符串: 文件名(可选) | 播放视频 |
| `/stop` | - | 停止播放, 黑屏 |
| `/playpause` | - | 切换暂停 |
| `/pause` | 整数: 0/1 | 0=恢复, 1=暂停 |
| `/volume` | 浮点数: 0-N | 设置系统音量 |
| `/mute` | 整数: 0/1 | 0=取消静音, 1=静音 |
| `/seek` | 整数: 毫秒 | 跳转 |
| `/speed` | 浮点数: 0.25-4.0 | 设置播放速度 |

### 播放列表
| 地址 | 参数 | 说明 |
|------|------|------|
| `/playlist/add` | 字符串: 文件名 | 添加到播放列表 |
| `/playlist/remove` | 整数: 索引 | 移除 |
| `/playlist/clear` | - | 清空 |
| `/playlist/next` | - | 下一首 |
| `/playlist/prev` | - | 上一首 |
| `/playlist/index` | 整数: 索引 | 跳转 |
| `/playlist/mode` | 整数: 0-3 | 0=播完停止, 1=单曲循环, 2=全部循环, 3=随机 |
| `/playlist/loop` | 整数: 0-3 | 同上, 回复 `0: once` 等 |
| `/playlist/get` | - | 上报播放列表 (格式: `索引: 文件名`) |

### 配置
| 地址 | 参数 | 说明 |
|------|------|------|
| `/config/dir` | 字符串: 路径 | 设置默认目录 |
| `/config/watchdog` | 整数: 0/1 | 开关播放卡顿检测 (30秒检查, 60秒卡住=卡顿) |
| `/config/heartbeat` | `[0|1][/<秒>]` | 开关心跳上报, 可选设置上报间隔秒数(默认1秒) |
| `/config/reload` | - | 重新扫描视频 |
| `/config/surface` | 整数: 0/1 | 0=调试模式(TextureView), 1=性能模式(SurfaceView) |
| `/config/keepalive/alarm` | 整数: 秒 | AlarmReceiver 间隔 |
| `/config/keepalive/workmanager` | 整数: 分钟 | WorkManager 间隔 |

### 立体模式
| 地址 | 参数 | 说明 |
|------|------|------|
| `/3d` | - | 查询当前立体模式 |
| `/3d off` | - | 关闭立体 |
| `/3d left` | - | SBS 左眼 |
| `/3d right` | - | SBS 右眼 |
| `/3d ou_top` | - | OU 上半 |
| `/3d ou_bottom` | - | OU 下半 |
| `/config/speedtoast` | 整数: 0/1 | 开关同步提示 Toast |

### 电源
| 地址 | 参数 | 说明 |
|------|------|------|
| `/power/on` | - | 开显示器 |
| `/power/off` | - | 关显示器 |
| `/power/restart` | - | 重启播放器 |
| `/power/exit` | - | 退出应用 |
| `/power/shutdown` | - | 关机 |
| `/power/reboot` | - | 重启设备 |

### 系统
| 地址 | 参数 | 说明 |
|------|------|------|
| `/discover` | - | 返回 IP 和 MAC |

| `/port` | 整数: 端口 | 设置回复端口 |
| `/help` | - | 帮助 |

## 构建

```bash
cd OSCPlayer_Android
./gradlew assembleNormalDebug
```

APK: `app/build/outputs/apk/normal/debug/app-normal-debug.apk`

## 安装

```bash
adb connect <设备IP>:5555
adb install -r app-normal-debug.apk
```

## scrcpy — 电脑上操作节点

[scrcpy](https://github.com/Genymobile/scrcpy) 通过 ADB 在电脑上显示并控制 Android 设备屏幕, 无需遥控器.

```bash
# 安装 (macOS)
brew install scrcpy

# 连接节点后启动
adb connect 10.0.0.92:5555
scrcpy

# 常用选项:
scrcpy --max-size 1024          # 限制分辨率, 减少卡顿
scrcpy --bit-rate 4M            # 限制码率
scrcpy --max-fps 15             # 限制帧率 (无线调试推荐)
scrcpy --turn-screen-off        # 镜像时关闭节点屏幕
scrcpy --stay-awake             # 防止节点休眠
scrcpy --window-title "Y8 #1"   # 多节点时区分窗口

# 多节点同时控制 (开两个终端)
scrcpy --window-title "Box 92" --max-size 800  # 第一个
scrcpy --serial 10.0.0.45:5555 --window-title "Box 45" --max-size 800  # 第二个
```

用鼠标就能操作播放器菜单、设置、搜索等一切功能, 配合 ADB 无线调试, 开发调试非常方便. 注意 texture_view 模式下 scrcpy 才能看到画面 (系统设置中切换).

## FAQ

### 开机自启?

播放器有 `BootReceiver` 监听系统开机广播, 会自动启动. 不需要设为桌面应用.

播放器已从 `CATEGORY_HOME` 移除, 不再是桌面应用, 所以开机不会再弹出"选择主屏幕应用".

### 能删掉当贝桌面吗?

不推荐. 当贝桌面是进入系统设置的入口. 删除后可通过以下方式进入系统设置:

- **OSC 命令**: `/settings` 直接打开 Android 系统设置
- **播放器菜单**: `系统设置 -> 系统设置界面`
- **ADB**: `adb shell am start -a android.settings.SETTINGS`
