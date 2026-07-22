#!/bin/bash
# OSCPlayer 一键部署脚本
# 用法: ./deploy.sh <设备IP> [APK路径]
# 示例: ./deploy.sh 10.0.0.92

set -e
IP=$1
APK=${2:-app/build/outputs/apk/normal/debug/app-normal-debug.apk}

if [ -z "$IP" ]; then
    echo "用法: $0 <设备IP> [APK路径]"
    echo "示例: $0 10.0.0.92"
    exit 1
fi

echo "=== 连接 $IP ==="
adb connect $IP:5555 2>/dev/null || adb connect $IP:4444 2>/dev/null || {
    echo "连接失败, 请手动 adb connect"
    exit 1
}
DEVICE=$(adb devices | grep "$IP" | head -1 | awk '{print $1}')

echo "=== 安装 APK ==="
adb -s "$DEVICE" install -r "$APK"

echo "=== 设置 ADB 端口 5555 (持久化) ==="
adb -s "$DEVICE" shell "mount -o rw,remount /vendor 2>/dev/null; echo 'on boot
    setprop service.adb.tcp.port 5555' > /vendor/etc/init/adb_port.rc; chmod 644 /vendor/etc/init/adb_port.rc; mount -o ro,remount /vendor 2>/dev/null; echo OK"

echo "=== 设置 reboot 为 setuid root ==="
adb -s "$DEVICE" root
adb -s "$DEVICE" wait-for-device
adb -s "$DEVICE" shell "mount -o rw,remount / 2>/dev/null; chmod 6755 /system/bin/reboot; mount -o ro,remount / 2>/dev/null; echo OK"

echo "=== 设置显示开关权限 (fb0/blank) ==="
adb -s "$DEVICE" shell "chmod 666 /sys/class/graphics/fb0/blank 2>/dev/null; echo OK"

echo "=== 冻结无用系统应用 ==="
FREEZE_LIST=(
    com.dangbei.tvlauncher
    com.dangbei.screencast
    com.dangbei.migu
    com.dangbei.smartkey
    com.dangbei.game
    com.dangbei.mall
    com.dangbei.music
    com.dangbei.video
    com.dangbei.weather
    com.dangbei.wallpaper
    com.peasun.voicehid
    com.peasun.smartkey
)
for pkg in "${FREEZE_LIST[@]}"; do
    adb -s "$DEVICE" shell "pm disable $pkg 2>/dev/null && echo frozen: $pkg || true"
done

echo "=== 禁用系统 OTA ==="
adb -s "$DEVICE" shell "pm disable com.android.updater 2>/dev/null; pm disable com.dangbei.tvlauncher/.update.UpdateActivity 2>/dev/null; echo OK"

echo "=== 启动 SuperSU 守护进程 ==="
adb -s "$DEVICE" unroot
adb -s "$DEVICE" wait-for-device
adb -s "$DEVICE" shell "/system/xbin/su --auto-daemon 2>/dev/null; echo OK"

echo ""
echo "=== 部署完成! ==="
echo "设备: $IP"
echo ""
echo "重启设备后生效:"
echo "  adb -s $DEVICE shell reboot"
echo ""
echo "部署另一台:"
echo "  $0 <下一台IP>"
