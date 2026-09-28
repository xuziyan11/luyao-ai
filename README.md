# 路瑶 AI（Luyao AI）

基于 DeepSeek API 的拟人化 AI 情感陪伴应用。不是一个问答机器人，而是一个有记忆、有情绪、会主动关心你、甚至会闹小脾气的角色。

- 记住你说过的事，在后续对话里自然提起
- 好感度系统：从陌生人 → 朋友 → 恋人，不同阶段说话方式不同
- 回复像微信聊天：短句、口语化、多条消息、带打字延迟
- 多屏 SPA 前端（对话 / 记忆 / 我的 / 日记）
- 场景模式、定时主动陪伴提醒、AI 日记自动生成
- 火山引擎 TTS 语音合成、微信 iLink 接入、独立管理后台

## 技术栈

| 层 | 技术 |
|----|------|
| 语言 / 框架 | Java 21 + Spring Boot 3.2 |
| 实时通信 | WebSocket（聊天）+ 微信 iLink 长轮询 |
| 数据库 | MySQL（账号物理隔离：每账号独立表） |
| 大模型 | DeepSeek Chat API |
| 语音 | 火山引擎 TTS / ASR（豆包） |
| 构建 | Maven（内置 Maven Wrapper `./mvnw`） |
| 测试 | H2 内存库（无需真实数据库 / 密钥） |

## 架构要点

- **单 JVM 双端口**：主站 `8083`（路瑶网站 / 聊天）、管理后台 `8084`（独立站点，端口隔离，共享同一 Spring 上下文与数据库）。
- **账号物理隔离**：每个账号一张独立表（`chat_history_a{id}` / `long_term_memory_a{id}` / `affinity_a{id}` / `diary_a{id}`）。
- **聊天通道**：浏览器走 WebSocket；微信侧走 iLink 轮询，复用同一套 `ChatService`。
- **时间注入**：前端在「点发送」那一刻取本地时间，随请求传给后端并注入 system prompt 的「当前现实时间」，贴合用户时区。

## 目录结构

```
项目根目录/
├── ai-companion/                      # Spring Boot 主工程
│   ├── pom.xml
│   ├── src/main/java/com/companion/
│   ├── src/main/resources/
│   │   ├── application.yml.template   # 配置模板（安全，可入库）
│   │   ├── schema.sql                 # 建表脚本
│   │   └── static/index.html          # 前端聊天页
│   └── README.md                      # 开发环境（VS Code）详细配置
├── deploy/                            # 服务端部署脚本与 env 模板（真实密钥不入库）
└── luyao-ai-intro/                    # 项目介绍资料
```

## 快速开始

### 1. 前置要求
- JDK 21（推荐 Eclipse Temurin）
- Maven 3.9+（或直接用内置 `./mvnw`）
- MySQL 8.x（本地或远程；应用启动时按 `schema.sql` 自动建表）

### 2. 配置
真实密钥**一律通过环境变量注入**，不要写进仓库（见 `.gitignore`）。

```bash
cp ai-companion/src/main/resources/application.yml.template \
   ai-companion/src/main/resources/application.yml
```

然后设置环境变量（最少需要以下项，其余见 `application.yml.template` 注释）：

```bash
export MYSQL_PASSWORD=your_mysql_password
export COMPANION_PASSWORD=your_app_password
export ADMIN_PASSWORD=your_admin_password
export DEEPSEEK_API_KEY=sk-xxxx
export VOLCANO_TTS_APIKEY=xxxx
```

### 3. 运行
```bash
cd ai-companion
./mvnw spring-boot:run
```
- 聊天界面：http://localhost:8083/
- 管理后台：http://localhost:8084/admin （密码由 `ADMIN_PASSWORD` 决定）

### 4. 测试
测试使用 H2 内存库，并已在 `@TestPropertySource` 中注入占位密钥，**无需真实数据库或密钥**：
```bash
cd ai-companion
./mvnw test
```

## 环境变量清单

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MYSQL_USERNAME` | 数据库用户名 | `root` |
| `MYSQL_PASSWORD` | 数据库密码 | 无（必填） |
| `COMPANION_PASSWORD` | 主站登录 / 绑定口令 | 无（必填） |
| `ADMIN_PASSWORD` | 管理后台密码 | 无（必填） |
| `ADMIN_PORT` | 管理后台端口 | `8084` |
| `COMPANION_DEV_MODE` | 开发模式（验证码随接口返回、允许模拟微信登录） | `true` |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | 无（必填） |
| `VOLCANO_TTS_APPID` / `VOLCANO_TTS_TOKEN` / `VOLCANO_TTS_APIKEY` / `VOLCANO_TTS_CLUSTER` / `VOLCANO_TTS_VOICE` | 火山引擎 TTS 配置 | 见模板 |
| `WECHAT_APPID` / `WECHAT_SECRET` / `WECHAT_REDIRECT_URI` | 微信扫码登录（需企业主体 + 备案域名） | 空 |
| `ILINK_ENABLED` | 启用微信 iLink 轮询 | `true` |

## 部署（服务端）

1. 打可执行 jar：
   ```bash
   cd ai-companion
   ./mvnw -o clean package -Dmaven.test.skip=true
   ```
2. 在服务器上通过 `EnvironmentFile`（如 `/opt/luyao/luyao.env`）注入上述环境变量，用 systemd 启动 `ai-companion` 服务即可（主站 8083 / 后台 8084 对外可达）。

## 安全说明

- `application.yml` 仅引用 `${ENV_VAR}`，**不含任何明文密钥**，已被 `.gitignore` 排除，不进仓库。
- `application.yml.template` 是安全模板（纯占位），可入库、克隆后可复制使用。
- `deploy/` 下的真实 `luyao.env`、安全审查报告等含密钥文件均已 gitignore。
- 提交历史中的作者邮箱统一使用 GitHub `noreply` 地址，不暴露私人邮箱。

## CI

每次 push / PR 到 `main` 会经由 GitHub Actions（`.github/workflows/ci.yml`）自动编译并运行测试。
