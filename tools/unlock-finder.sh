#!/system/bin/sh
# 恢复微信里所有「视频号」组件（撤销 lock-finder.sh 的效果）

PKG=com.tencent.mm

LIST=$(dumpsys package $PKG \
  | tr ' ,{}[]' '\n\n\n\n\n\n\n' \
  | grep -i 'finder' \
  | grep "^$PKG/" \
  | sed 's/[^A-Za-z0-9._$/].*//' \
  | sort -u)

echo "$LIST" | while read -r C; do
  [ -z "$C" ] && continue
  pm enable "$C"
done

am force-stop $PKG
echo "已恢复。"
