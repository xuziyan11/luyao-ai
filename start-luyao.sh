#!/bin/bash
# 路瑶服务 + Cloudflare Tunnel 启动脚本
# 使用 macOS 原生方式启动，不依赖 TRAE shell，避免被回收

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="/Users/xzy/Desktop/路瑶AI（deepseek API）WXAPP/ai-companion"
LOG_AI="/tmp/luyao-ai.log"
LOG_CF="/tmp/luyao-cf.log"
URL_FILE="/tmp/luyao-public-url.txt"

kill_port() {
  local port=$1
  local pids=$(lsof -ti:$port 2>/dev/null)
  [ -n "$pids" ] && kill -9 $pids 2>/dev/null
}

# 清理旧进程
kill_port 8083
pkill -9 -f cloudflared 2>/dev/null
sleep 2

# 启动 Spring Boot（后台，不依赖当前 shell）
cd "$PROJECT_DIR"
ILINK_ENABLED=true nohup ./mvnw spring-boot:run -q > "$LOG_AI" 2>&1 &
AI_PID=$!
echo "AI server starting, pid=$AI_PID"

# 等待 8083 起来
for i in {1..40}; do
  if lsof -ti:8083 >/dev/null 2>&1; then
    echo "8083 is up after ${i}s"
    break
  fi
  sleep 1
done

# 启动 cloudflared tunnel
nohup cloudflared tunnel --url http://127.0.0.1:8083 --no-autoupdate --protocol http2 > "$LOG_CF" 2>&1 &
CF_PID=$!
echo "Cloudflared starting, pid=$CF_PID"

# 等 URL 出现
for i in {1..20}; do
  URL=$(grep -oE "https://[a-zA-Z0-9.-]+\.trycloudflare\.com" "$LOG_CF" | head -1)
  if [ -n "$URL" ]; then
    echo "$URL" > "$URL_FILE"
    echo "============= PUBLIC URL ============="
    echo "$URL"
    echo "Copy above into 豆包浏览器地址栏"
    echo "====================================="
    exit 0
  fi
  sleep 2
done

echo "Timeout getting URL, check $LOG_CF"
tail -10 "$LOG_CF"
