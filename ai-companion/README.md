# 路瑶 AI

基于 DeepSeek API 的拟人化情感陪伴智能体。

> 不是问答机器人，是一个有记忆、有情绪、会主动关心你、甚至会闹小脾气的角色。能记住你说过的事，在后续对话中自然提起；有好感度系统，从陌生人到朋友再到恋人，不同阶段说话方式不同；回复像微信聊天一样：短句、口语化、多条消息、有打字延迟。

技术栈：Java 17 + Spring Boot 3.2 + WebSocket + H2 + OkHttp + Jackson + DeepSeek API。

---

## 一、VS Code 开发环境准备

### 1. JDK
- 安装 **OpenJDK 17**（推荐 Eclipse Temurin 发行版：https://adoptium.net/temurin/releases/?version=17 ）。
- 安装后验证：`java -version`，应输出 `17.x.x`。
- VS Code 中 `Cmd/Ctrl + Shift + P` → `Java: Configure Java Runtime` 可检查并配置 JDK。
- 如果你本机装的是 **JDK 26**，项目也能正常编译（`pom.xml` 已把 Lombok 升到 **1.18.46**，这是首个正式支持 JDK 26 的 Lombok 版本；旧版会在 JDK 26 上静默失败导致 `log` 找不到符号）。

### 2. Maven
- macOS：`brew install maven`
- Windows：下载 apache-maven 并配置 `MAVEN_HOME`、`PATH`
- 验证：`mvn -version`
- 本项目也内置了 Maven Wrapper，可直接用 `./mvnw`（macOS/Linux）或 `mvnw.cmd`（Windows）替代 `mvn`。
  > 若使用 `mvnw`，需先生成 wrapper：在项目根目录执行 `mvn -N wrapper:wrapper`（需先装一次 mvn）。

### 3. VS Code 必装插件

| 插件 | 作用 |
|------|------|
| **Extension Pack for Java**（Microsoft） | Java 语言支持、补全、调试、Maven 集成 |
| **Spring Boot Extension Pack**（VMware） | Spring Boot 专用支持、应用导航、属性提示 |
| **YAML**（Red Hat） | `application.yml` 语法高亮与校验 |

### 4. VS Code 推荐补充插件（开发更顺手）

| 插件 | 作用 |
|------|------|
| **Lombok Annotations Support for VS Code**（Suboptimum） | 让 VS Code 识别 `@Slf4j`/`@Data` 等 Lombok 注解，避免报错 |
| **IntelliCode**（Microsoft） | AI 辅助代码补全 |
| **Database Client**（cweijan，`cweijan.dbclient-jdbc`） | 连接 H2 数据库，可视化查看 `chat_history`/`long_term_memory`/`affinity` 三张表 |
| **Spring Boot Dashboard**（VMware） | 一键启动/停止/调试 Spring Boot 应用 |
| **Thunder Client** | 轻量级 HTTP 接口测试（可选） |
| **Markdown All in One** | README/文档编辑预览 |

> Lombok 插件务必安装，否则 `@Slf4j` 的 `log` 字段在编辑器里会标红（编译不影响，但影响阅读体验）。

### 5. 开启 Lombok 注解处理（重要）
VS Code 中：
1. 打开设置 → 搜索 `java.compile.nullAnalysis.mode`（与本项无关，但顺带确认）。
2. 确认 **Lombok Annotations Support** 插件已安装并启用。
3. 若 `log` 仍报红：`Cmd/Ctrl + Shift + P` → `Java: Clean Java Language Server Workspace` → 选择 `Restart and delete`，重启后即可。

---

## 二、配置 DeepSeek API Key

1. 访问 https://platform.deepseek.com ，注册账号后创建 API Key。
2. 在终端中设置环境变量（不要把真实 Key 写进仓库）：
   ```bash
   export DEEPSEEK_API_KEY="sk-你的真实key"
   ```
3. 然后启动项目：
   ```bash
   cd ai-companion
   ./mvnw spring-boot:run
   ```
   或在 IDE 中配置环境变量 `DEEPSEEK_API_KEY`。

---

## 三、运行

```bash
cd ai-companion
mvn spring-boot:run          # 或 ./mvnw spring-boot:run
```

启动后：
- 聊天界面：http://localhost:8080/
- H2 控制台：http://localhost:8080/h2-console
  - JDBC URL：`jdbc:h2:file:./data/companion`
  - 用户名：`sa`，密码留空

VS Code 中也可直接在 `CompanionApplication.java` 上方点击 **Run | Debug** 运行。

---

## 四、项目结构

```
ai-companion/
├── pom.xml
├── README.md
├── src/main/java/com/companion/
│   ├── CompanionApplication.java        # 启动类
│   ├── config/
│   │   └── WebSocketConfig.java         # WebSocket 配置，注册 /chat
│   ├── controller/
│   │   ├── ChatWebSocketHandler.java    # 聊天核心逻辑
│   │   └── PageController.java          # 页面路由
│   ├── service/
│   │   ├── LlmService.java              # DeepSeek API 调用
│   │   ├── MemoryService.java           # 对话历史 + 长期记忆
│   │   ├── AffinityService.java         # 好感度读写
│   │   └── MessageSplitter.java          # 多消息拆分
│   └── prompt/
│       └── PromptTemplates.java         # 人设提示词模板
├── src/main/resources/
│   ├── application.yml
│   ├── schema.sql                       # 建表脚本
│   └── static/
│       └── index.html                   # 前端聊天页面
```

---

## 五、核心机制

- **好感度系统**：初始 40，范围 0~100。AI 每轮回复末尾输出 `[affinity:+N]`，后端正则提取并更新数据库，阶段切换后下轮自动用新的人设 prompt。
- **三阶段人设**：陌生人(0-19) / 朋友(20-49) / 恋人(50-100)，说话方式与亲密度递进。
- **记忆系统**：短期记忆保留最近 20 轮对话注入上下文；长期记忆每 5 轮由 LLM 提取用户关键事实存库，并注入 system prompt。
- **多消息拆分**：AI 回复用 `|||` 分隔，前端按 `800ms + 字数×30ms` 间隔逐条渲染，模拟真人打字。

---

## 六、可扩展方向

语音对话（TTS/ASR）、发送照片（文生图）、主动消息（Spring 定时任务）、多用户体系、微信接入（WxJava）、情感分析等。
