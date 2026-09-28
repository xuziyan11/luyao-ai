#!/bin/bash
set -e

# 路瑶 AI 部署脚本
# 用法: 在服务器上执行 bash deploy.sh

INSTALL_DIR=/opt/luyao
LOG_DIR=/var/log/luyao
JAR_NAME=ai-companion-1.0.0.jar

echo "=== 1. 创建目录 ==="
mkdir -p $INSTALL_DIR $LOG_DIR

echo "=== 2. 复制文件 ==="
cp $JAR_NAME $INSTALL_DIR/
cp luyao.env $INSTALL_DIR/ 2>/dev/null || echo "提示: 请先创建 luyao.env（参考 luyao.env.template）"

echo "=== 3. 安装 systemd 服务 ==="
cp ai-companion.service /etc/systemd/system/

echo "=== 4. 重新加载 systemd ==="
systemctl daemon-reload

echo "=== 5. 启用并启动服务 ==="
systemctl enable ai-companion
systemctl restart ai-companion

echo "=== 6. 检查状态 ==="
sleep 5
systemctl status ai-companion --no-pager || true

echo ""
echo "=== 部署完成 ==="
echo "查看日志: journalctl -u ai-companion -f"
echo "          tail -f /var/log/luyao/app.log"
echo "服务管理: systemctl {start|stop|restart|status} ai-companion"
