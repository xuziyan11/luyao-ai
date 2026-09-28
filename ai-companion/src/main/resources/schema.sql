-- 路瑶 AI 建表脚本（H2 文件库）
-- 启动时由 spring.sql.init 自动执行

-- 对话历史表（短期记忆）
CREATE TABLE IF NOT EXISTS chat_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,          -- 会话/用户标识
    role        VARCHAR(16)  NOT NULL,          -- user / assistant
    content     TEXT         NOT NULL,          -- 消息原文（assistant 含 [affinity:+N] 标记）
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- NOTE: index creation is intentionally omitted here because some local MySQL
-- installations reject CREATE INDEX IF NOT EXISTS or duplicate-index re-runs
-- during startup. The app does not require these indexes to function correctly.

-- 长期记忆表（提取出的用户关键事实）
CREATE TABLE IF NOT EXISTS long_term_memory (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL,
    fact        TEXT         NOT NULL,          -- 如 "喜欢猫" "住在杭州" "工作是设计师"
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 好感度表
CREATE TABLE IF NOT EXISTS affinity (
    session_id     VARCHAR(64)  PRIMARY KEY,
    affinity_value INT          NOT NULL DEFAULT 0,   -- 0~100，重置后从 0 开始
    updated_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 微信 ClawBot 绑定表：每个微信号一行，保存扫码后微信返回的凭证
CREATE TABLE IF NOT EXISTS wechat_binding (
    ilink_user_id   VARCHAR(128) PRIMARY KEY,        -- 微信侧用户标识，作为 sessionId 复用记忆/好感度
    bot_token       TEXT         NOT NULL,           -- 发送消息的令牌
    ilink_bot_id    VARCHAR(128) NOT NULL,           -- 微信侧 bot 标识
    baseurl         VARCHAR(256) NOT NULL,           -- 微信回传的接入域名（后续调用的 HOST）
    context_token   TEXT,                             -- 上下文令牌，每次收发消息会更新
    get_updates_buf TEXT,                             -- 长轮询翻页符，类似 Kafka offset
    nickname        VARCHAR(128),                     -- 可选备注名
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);


-- 账号表：手机号 / 微信 openid 登录，每个账号一行。
-- 首次登录时由 AccountService 自动创建该账号的独立数据表
-- （chat_history_a{id} / long_term_memory_a{id} / affinity_a{id}），不同账号数据物理隔离。
CREATE TABLE IF NOT EXISTS account (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_type      VARCHAR(8)   NOT NULL,           -- phone / wechat
    phone           VARCHAR(20)  UNIQUE,             -- 手机号（phone 登录时填写）
    wechat_openid   VARCHAR(128) UNIQUE,             -- 微信 openid（wechat 登录时填写）
    nickname        VARCHAR(64),                     -- 展示名（手机号打码或微信昵称）
    chat_token      VARCHAR(36)  NOT NULL,           -- 聊天会话标识中的防冒用令牌
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    last_login_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 动态配置表：管理后台在线修改人设/模型参数/TTS 默认值，即时生效无需重启
CREATE TABLE IF NOT EXISTS app_config (
    cfg_key     VARCHAR(64) PRIMARY KEY,
    cfg_value   TEXT,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 外部 API 调用日志：DeepSeek / TTS 调用的延迟、Token 用量、成败记录，
-- 供管理后台日志监控、失败率告警、Token 统计与延迟分位数分析
CREATE TABLE IF NOT EXISTS api_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_type         VARCHAR(16)  NOT NULL,          -- deepseek / tts / tts_clone
    session_id       VARCHAR(64),                     -- 关联的聊天会话（可为空）
    ok               TINYINT(1)   NOT NULL,
    error            VARCHAR(500),
    latency_ms       BIGINT       NOT NULL DEFAULT 0,
    prompt_tokens    INT          NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens     INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- 定时主动陪伴提醒：每账号可配置多条（早安/晚安/吃饭/纪念日等）。
-- 到点后由 ReminderService 扫描触发，AI 主动发消息（仅当网页在线时推送）。
CREATE TABLE IF NOT EXISTS reminder (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      BIGINT       NOT NULL,           -- 所属账号
    type            VARCHAR(16)  NOT NULL,           -- daily=每日定时 / anniversary=纪念日
    title           VARCHAR(50)  NOT NULL,           -- 名称：早安 / 晚安 / 在一起100天…
    time_of_day     CHAR(5)      NULL,               -- daily 触发时间 HH:mm
    month_day       CHAR(5)      NULL,               -- anniversary 触发日期 MM-dd（当天 09:00 触发）
    prompt_hint     VARCHAR(200) NULL,               -- 可选：给 AI 的提醒背景（如"她今天考试"）
    enabled         TINYINT      NOT NULL DEFAULT 1,
    last_fired_date VARCHAR(10)  NULL,               -- yyyy-MM-dd，防当天重复触发
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- AI 情绪日记主表（旧式匿名会话用；账号会话路由到 diary_a{id}）
CREATE TABLE IF NOT EXISTS diary (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    diary_date  DATE         NOT NULL,
    weather     VARCHAR(60)  NULL,                   -- 当地天气（日记时间背景）
    mood        VARCHAR(20)  NULL,                   -- 当日主导情绪标签
    mood_curve  VARCHAR(120) NULL,                   -- 从早到晚的心情变化
    events      TEXT         NULL,                   -- 关键事件（JSON 数组）
    blessing    VARCHAR(255) NULL,                   -- 路遥寄语（结尾祝福）
    content     TEXT         NULL,                   -- 完整日记正文
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_diary_date (diary_date)
);
