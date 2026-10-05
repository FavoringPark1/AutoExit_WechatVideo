#!/system/bin/sh
# 禁用微信里所有「视频号」组件（需要 root 或 Shizuku/rish 提供的 shell 权限）
# 用法：  adb shell sh /data/local/tmp/lock-finder.sh
#    或：  rish 然后在 rish shell 里  sh /sdcard/lock-finder.sh

PKG=com.tencent.mm

LIST=$(dumpsys package $PKG \
  | tr ' ,{}[]' '\n\n\n\n\n\n\n' \
  | grep -i 'finder' \
  | grep "^$PKG/" \
  | sed 's/[^A-Za-z0-9._$/].*//' \
  | sort -u)

COUNT=$(echo "$LIST" | grep -c .)
echo "找到 $COUNT 个视频号组件"

echo "$LIST" | while read -r C; do
  [ -z "$C" ] && continue
  pm disable-user --user 0 "$C"
done

am force-stop $PKG
echo "完成。打开微信试试「发现 → 视频号」。"
