# OSCVideoPlayer v2.0

Android TV 视频播放器, 通过 OSC (UDP 8000) 协议远程控制.
专为展览展示/数字标牌场景设计, 兼容全志/瑞芯微等低端设备.

## 功能

- OSC 命令全面控制播放/播放列表/电源/定时
- 硬解加速, 自动回退软解 (Media3 DefaultRenderersFactory)
- 视频扫描 (内置存储 Movies/Downloads/DCIM + USB 自动发现)
- 播放列表 (单曲循环/列表循环/随机播放)
- 播放速度调节 (0.25x - 4.0x)
- 字幕加载
- 文字叠加显示 (OSC /tct 命令)
- 定时播放/停止
- 显示器电源定时开关
- 看门狗 (自动检测卡顿并恢复)
- 截图
- 全屏沉浸式播放

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
| `/playlist/mode/0` | 不循环 |
| `/playlist/mode/1` | 单曲循环 |
| `/playlist/mode/2` | 列表循环 |
| `/playlist/mode/3` | 随机播放 |
| `/playlist/list` | 查看列表 |

### 显示
| 命令 | 说明 |
|------|------|
| `/fullscreen` | 全屏 |
| `/tct/文字/字号/位置` | 文字叠加 (位置: 0居中,1左上,2右上,3左下,4右下) |
| `/subtitle/路径` | 加载字幕文件 |
| `/subtitle/0` | 关闭字幕 |
| `/screenshot` | 截图 (保存到默认目录/.screenshots/) |

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
- 视频扫描目录: /storage/emulated/0/Movies + /storage/emulated/0/Downloads + /storage/emulated/0/DCIM
- USB 存储自动发现 (/storage/ 下非 emulated 目录)
- 启动时播放第一个名为 "hello.*" 的视频
- 默认目录可通过 `/config/dir` 运行时修改

## 低端设备兼容

- `DefaultRenderersFactory.setEnableDecoderFallback(true)`: 优先硬解, 硬解失败自动回退软解
- 看门狗: 每 15s 检测播放器状态, 检测到卡顿/缓冲 >30s 自动恢复
- 视频扫描深度限制: 最多 8 层目录
- 跳过 `.` 开头和 `Android` 目录

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

## 遥控器按键

- **菜单键** - 打开菜单 (搜索/信息/关于/设置默认桌面)
- **返回键** - 退出应用
- **HOME键** - 返回系统桌面
- **INFO键** - 打开关于页面
- **DEL键** - 删除当前视频

## 稳定性

- 前台服务保持 OSC 服务器持续运行
- WorkManager 每 30 分钟健康检查 (仅在进程死亡时启动)
- Watchdog 监控播放器状态, 自动恢复
- 电池优化豁免请求
- 屏幕事件自启动
