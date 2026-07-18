# OSCVideoPlayer v2.0

Android TV 视频播放器, 通过 OSC (UDP 8000) 协议远程控制.
专为展览展示/数字标牌场景设计, 兼容Allwinner/Rockchip/Amlogic等设备.
采用MIT开源许可, 同行放心大胆使用.

## 功能

- OSC 命令全面控制播放/播放列表/电源/定时
- 硬解加速, 自动回退软解 (Media3 DefaultRenderersFactory)
- 视频扫描 (默认 /Movies, 可通过 `/config/dir` 运行时修改)
- USB/SD 卡自动发现, 全盘扫描 (视频/音频/图片), 只读不删
- 播放列表 (单曲循环/列表循环/随机播放/播完停止)
- 播放速度调节 (0.25x - 4.0x)
- 字幕加载
- 文字叠加显示 (OSC `/tct` 命令)
- 定时播放/停止
- 显示器电源定时开关
- 看门狗 (自动检测卡顿并恢复)
- 截图 (TextureView.getBitmap(), 不碰硬件解码器, 无黑屏无闪屏; 回退 PixelCopy/decorView; 保存到 /Movies/.screenshots/)
- 全屏沉浸式播放
- HTTP 文件管理 (端口 8080): 上传/播放/暂停/下载/删除/信息查看
- HTTP 截图画廊: 缩略图网格 + 全屏查看/下载/删除, HTTP GET `/screenshot` 触发截图
- 菜单半透明窄条 (20% 宽度), 截图时可透见画面
- 双遥控适配: 标准 Menu/Settings/F1-F12 全支持, 菜单开关
- 前台服务保持 OSC 服务器持续运行
- WorkManager 每 30 分钟健康检查

## 遥控器按键

| 按键 | 功能 |
|------|------|
| **UP / CH+** | 上一个视频 |
| **DOWN / CH-** | 下一个视频 |
| **LEFT / REWIND** | 快退 10 秒 |
| **RIGHT / F-FWD** | 快进 10 秒 |
| **OK / ENTER / PLAY-PAUSE** | 暂停/播放 |
| **PLAY** | 恢复播放 |
| **PAUSE** | 暂停 |
| **STOP** | 停止 |
| **MENU** | 打开/关闭主菜单 (搜索/信息/列表/模式/循环/截图/上传地址/开机视频/删除/系统设置/关于) |
| **SETTINGS / F1-F12 / 齿轮键** | 打开/关闭系统设置 (定时开关/电源/显示/默认桌面/返回桌面/恢复/退出) |
| **INFO** | 打开关于页面 |
| **HOME** | 返回系统桌面 (自动找到真实桌面 launcher) |
| **BACK** | 退出应用 |

## HTTP 文件管理

播放器内置 HTTP 服务器 (端口 8080), 浏览器访问即可管理文件:

| 路径 | 说明 |
|------|------|
| `/` | 上传页面 (拖放/选择文件, 实时进度条) |
| `/files` | 文件管理 (按内部存储/USB 分组, 播放/暂停/下载/删除/信息) |
| `/files?state` | JSON 获取当前播放状态 |
| `/files?play=路径` | 播放指定文件 |
| `/files?toggle=路径` | 切换播放/暂停 |
| `/files?dl=路径` | 下载文件 |
| `/files?del=路径` | 删除文件 (USB/SD 禁止删除) |
| `/files?info=路径` | 文件详细信息 |
| `/screenshot` | 触发截图并重定向到截图画廊 |
| `/screenshots` | 截图画廊 (缩略图网格, 全屏查看/下载/删除) |
| `/screenshots/文件.png` | 返回截图图片文件 |
| `/screenshots/delete/名称` | 删除截图 |

## USB / SD 卡支持

- 自动检测 `/storage/` 下所有非 emulated 挂载点
- 全盘扫描视频 (mp4/mkv/avi 等)、音频 (mp3/flac/wav 等)、图片 (jpg/png 等)
- 只读不删, 删除 USB/SD 文件返回 403
- 支持 root 写入 (安装模式), 通过 `su -c chmod 777` 确保可写
- 搜索页面支持 USB 文件上传到内部存储 / 下载到 USB

## OSC 命令

### 播放控制
| 命令 | 说明 |
|------|------|
| `/play[文件名]` | 播放视频 (不指定文件名则播 hello.* 或第一个) |
| `/stop` | 停止播放, 回到 hello 视频 |
| `/stop/秒数` | 跳转到指定秒数并暂停 |
| `/stop/帧数` | 跳转到指定帧并暂停 |
| `/pause` | 切换暂停 |
| `/pause/0` | 恢复播放 |
| `/pause/1` | 暂停 |
| `/volume/0.0-1.0` | 设置音量 |
| `/seek/秒数` | 跳转到指定时间 |
| `/speed/0.25-4.0` | 设置播放速度 |
| `/loop/0` | 关闭循环 |
| `/loop/1` | 开启循环 |

### 播放列表
| 命令 | 说明 |
|------|------|
| `/playlist/add/文件名` | 添加到播放列表 |
| `/playlist/remove/索引` | 从列表移除 |
| `/playlist/clear` | 清空列表 |
| `/playlist/next` | 下一首 |
| `/playlist/prev` | 上一首 |
| `/playlist/jump/索引` | 跳转到指定项 |
| `/playlist/mode/0` | 播完停止 |
| `/playlist/mode/1` | 单曲循环 |
| `/playlist/mode/2` | 全部循环 |
| `/playlist/mode/3` | 随机播放 |
| `/playlist/list` | 查看列表 |

### 显示
| 命令 | 说明 |
|------|------|
| `/fullscreen` | 全屏 |
| `/tct/文字/字号/位置` | 文字叠加 (位置: 0居中,1左上,2右上,3左下,4右下) |
| `/subtitle/路径` | 加载字幕文件 |
| `/subtitle/0` | 关闭字幕 |
| `/screenshot` | 截图 (保存到默认目录/.screenshots/; 若 OSC UDP 不可达, 可用 HTTP GET `/screenshot`) |

### 信息
| 命令 | 说明 |
|------|------|
| `/info` | 当前视频信息 |
| `/status` | 完整系统状态 |
| `/list/videos` | 列出所有视频 |
| `/fps` | 获取帧率 |
| `/fps/帧率` | 设置帧率 |
| `/port/名称/端口` | 设置回复端口 |

### 配置
| 命令 | 说明 |
|------|------|
| `/config/dir` | 查看当前默认目录 |
| `/config/dir/路径` | 设置默认目录 |
| `/config/watchdog/1` | 开启看门狗 |
| `/config/watchdog/0` | 关闭看门狗 |
| `/config/heartbeat` | 看门狗心跳 |
| `/config/reload` | 重新扫描视频 |
| `/config/restart` | 重启播放器 |

### 定时
| 命令 | 说明 |
|------|------|
| `/schedule/start/HH:mm` | 设置定时播放开始 |
| `/schedule/stop/HH:mm` | 设置定时播放停止 |
| `/schedule/clear` | 清除定时 |

### 电源管理
| 命令 | 说明 |
|------|------|
| `/power/on` | 打开显示器 |
| `/power/off` | 关闭显示器 |
| `/power/schedule/on/HH:mm` | 定时开显示器 |
| `/power/schedule/off/HH:mm` | 定时关显示器 |
| `/power/schedule/clear` | 清除电源定时 |

### 文件管理
| 命令 | 说明 |
|------|------|
| `/rm/文件名` | 删除视频文件 |

### 系统
| 命令 | 说明 |
|------|------|
| `/stop/exit` | 退出应用 |
| `/shutdown` | 关闭设备 |
| `/reboot` | 重启设备 |
| `/help` | 显示帮助 |

## 默认行为

- 所有视频默认全屏循环播放
- 视频扫描目录: `/storage/emulated/0/Movies` (不是 Downloads/DCIM)
- USB/SD 存储自动发现 (`/storage/` 下非 emulated 目录)
- USB/SD 额外扫描音频和图片类型
- 启动时播放第一个名为 "hello.*" 的视频
- 默认目录可通过 `/config/dir` 运行时修改
- 截图保存到 `/storage/emulated/0/Movies/.screenshots/` (隐藏目录, 不进入播放列表), 截图时自动暂停播放器避免解码器冲突
- FileObserver 监控默认目录变化, 自动刷新扫描缓存

## 低端设备兼容

- `DefaultRenderersFactory.setEnableDecoderFallback(true)`: 优先硬解, 硬解失败自动回退软解
- 看门狗: 每 15s 检测播放器状态, 检测到卡顿/缓冲 >30s 自动恢复
- 视频扫描深度限制: 最多 8 层目录
- 跳过 `.` 开头和 `Android` 目录
- `switchingVideo` 标志位防止 stop/play 时序冲突

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

## 稳定性

- 前台服务保持 OSC 服务器持续运行
- WorkManager 每 30 分钟健康检查 (仅在进程死亡时启动)
- Watchdog 监控播放器状态, 自动恢复
- 电池优化豁免请求
- 屏幕事件自启动
- `isInitialized` 标志防止重复初始化
