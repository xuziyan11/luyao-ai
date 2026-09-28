package com.companion.service;

import com.companion.controller.ChatWebSocketHandler;
import com.companion.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时主动陪伴提醒：
 * - 每日定时（早安/晚安/吃饭/自定义）：到点后 30 分钟内，若网页在线则由 AI 主动发消息问候；
 * - 纪念日（MM-dd）：当天 09:00 起，在线时发送祝福（可附 prompt_hint 背景，如"她今天考试"）；
 * - 长时间未对话：超过 4 小时没聊天且当前 9:00-23:00，在线时发一条轻量问候（每天最多 1 次）。
 *
 * 限制（与设计一致）：网页 H5 休眠后无法主动推送，仅页面保持打开时生效；不在线不占用 LLM 调用。
 */
@Slf4j
@Service
public class ReminderService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 每日定时提醒的补发窗口：到点后 30 分钟内上线仍可收到 */
    private static final int DAILY_WINDOW_MINUTES = 30;
    /** 超过多少小时未对话触发轻量问候 */
    private static final int NUDGE_IDLE_HOURS = 4;

    private final JdbcTemplate jdbc;
    private final ChatWebSocketHandler wsHandler;
    private final LlmService llmService;
    private final PromptTemplates promptTemplates;
    private final AffinityService affinityService;
    private final MemoryService memoryService;
    private final SceneModeService sceneModeService;
    private final MessageSplitter messageSplitter;

    /** 久未问候防重：accountId -> 已触发日期（内存即可，重启清零无妨） */
    private final Map<Long, String> nudgeFired = new ConcurrentHashMap<>();

    public ReminderService(JdbcTemplate jdbc,
                           ChatWebSocketHandler wsHandler,
                           LlmService llmService,
                           PromptTemplates promptTemplates,
                           AffinityService affinityService,
                           MemoryService memoryService,
                           SceneModeService sceneModeService,
                           MessageSplitter messageSplitter) {
        this.jdbc = jdbc;
        this.wsHandler = wsHandler;
        this.llmService = llmService;
        this.promptTemplates = promptTemplates;
        this.affinityService = affinityService;
        this.memoryService = memoryService;
        this.sceneModeService = sceneModeService;
        this.messageSplitter = messageSplitter;
    }

    // ---------- CRUD ----------

    public List<Map<String, Object>> list(long accountId) {
        return jdbc.queryForList(
                "SELECT id, type, title, time_of_day, month_day, prompt_hint, enabled, last_fired_date "
                        + "FROM reminder WHERE account_id = ? ORDER BY time_of_day, month_day, id", accountId);
    }

    public long add(long accountId, String type, String title, String timeOfDay, String monthDay, String hint) {
        boolean isDaily = "daily".equals(type);
        if (title == null || title.isBlank() || title.length() > 50) {
            throw new IllegalArgumentException("名称不能为空且不超过 50 字");
        }
        if (isDaily && (timeOfDay == null || !timeOfDay.matches("^([01]\\d|2[0-3]):[0-5]\\d$"))) {
            throw new IllegalArgumentException("时间格式应为 HH:mm");
        }
        if (!isDaily && (monthDay == null || !monthDay.matches("^(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$"))) {
            throw new IllegalArgumentException("纪念日格式应为 MM-dd");
        }
        jdbc.update("INSERT INTO reminder(account_id, type, title, time_of_day, month_day, prompt_hint) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                accountId, isDaily ? "daily" : "anniversary", title.trim(),
                isDaily ? timeOfDay : null, isDaily ? null : monthDay,
                (hint == null || hint.isBlank()) ? null : hint.trim());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0 : id;
    }

    public void setEnabled(long id, long accountId, boolean enabled) {
        jdbc.update("UPDATE reminder SET enabled = ? WHERE id = ? AND account_id = ?",
                enabled ? 1 : 0, id, accountId);
    }

    public void delete(long id, long accountId) {
        jdbc.update("DELETE FROM reminder WHERE id = ? AND account_id = ?", id, accountId);
    }

    // ---------- 定时扫描（每分钟） ----------

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        try {
            scanReminders();
        } catch (Exception e) {
            log.warn("提醒扫描异常: {}", e.getMessage());
        }
        try {
            scanIdleNudge();
        } catch (Exception e) {
            log.warn("久未问候扫描异常: {}", e.getMessage());
        }
    }

    private void scanReminders() {
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DAY);
        LocalTime nowTime = now.toLocalTime();

        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT id, account_id, type, title, time_of_day, month_day, prompt_hint FROM reminder "
                        + "WHERE enabled = 1 AND (last_fired_date IS NULL OR last_fired_date <> ?)", today);
        for (Map<String, Object> r : due) {
            String type = (String) r.get("type");
            boolean dueNow;
            if ("daily".equals(type)) {
                LocalTime at = LocalTime.parse((String) r.get("time_of_day"), HM);
                // 到点后 30 分钟补发窗口内都视为到期
                dueNow = !nowTime.isBefore(at) && nowTime.isBefore(at.plusMinutes(DAILY_WINDOW_MINUTES));
            } else {
                // 纪念日：当天 09:00 之后任意时间在线即触发
                dueNow = today.substring(5).equals((String) r.get("month_day"))
                        && !nowTime.isBefore(LocalTime.of(9, 0));
            }
            if (!dueNow) {
                continue;
            }

            long id = ((Number) r.get("id")).longValue();
            long accountId = ((Number) r.get("account_id")).longValue();
            String sessionId = sessionIdOf(accountId);
            if (sessionId == null || !wsHandler.isOnline(sessionId)) {
                continue; // 不在线：等下一分钟再试（窗口内），不消耗 LLM
            }
            String title = (String) r.get("title");
            String hint = (String) r.get("prompt_hint");
            String scene = "anniversary".equals(type)
                    ? "今天是「" + title + "」纪念日" + (hint != null ? "（背景：" + hint + "）" : "")
                      + "，请主动送上你的祝福，像平时聊天一样自然，可以提前准备一点小心意。"
                    : "现在到了「" + title + "」时间（" + nowTime.format(HM) + "）"
                      + (hint != null ? "（背景：" + hint + "）" : "")
                      + "，请主动发一条问候关心对方。";
            if (fireProactive(accountId, sessionId, scene)) {
                jdbc.update("UPDATE reminder SET last_fired_date = ? WHERE id = ?", today, id);
            }
        }
    }

    /** 长时间未对话：在线且白天时段，发一条轻量问候（每天每账号最多 1 次）。 */
    private void scanIdleNudge() {
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        if (hour < 9 || hour >= 23) {
            return;
        }
        String today = now.format(DAY);
        List<Map<String, Object>> accounts = jdbc.queryForList("SELECT id, chat_token FROM account");
        for (Map<String, Object> acc : accounts) {
            long accountId = ((Number) acc.get("id")).longValue();
            if (today.equals(nudgeFired.get(accountId))) {
                continue;
            }
            String sessionId = "a" + accountId + "_" + acc.get("chat_token");
            if (!wsHandler.isOnline(sessionId)) {
                continue;
            }
            try {
                java.sql.Timestamp last = jdbc.query(
                        "SELECT MAX(created_at) FROM chat_history_a" + accountId,
                        rs -> rs.next() ? rs.getTimestamp(1) : null);
                if (last == null) {
                    continue; // 从未聊过，不打扰
                }
                long idleHours = (System.currentTimeMillis() - last.getTime()) / 3_600_000L;
                if (idleHours < NUDGE_IDLE_HOURS) {
                    continue;
                }
                String scene = "对方已经 " + idleHours + " 小时没和你说话了，你有点想ta。"
                        + "主动发一条轻量的问候，自然撒娇或关心都可以，不要提具体隔了多久。";
                if (fireProactive(accountId, sessionId, scene)) {
                    nudgeFired.put(accountId, today);
                }
            } catch (Exception e) {
                log.warn("久未问候检查失败 accountId={}: {}", accountId, e.getMessage());
            }
        }
    }

    // ---------- 主动消息生成与推送 ----------

    /**
     * 生成一条 AI 主动消息：走与正常聊天相同的人设（好感度/记忆/场景模式），
     * 存入聊天历史并推送到在线网页。返回是否成功。
     */
    private boolean fireProactive(long accountId, String sessionId, String sceneTask) {
        try {
            int affinity = affinityService.getAffinity(sessionId);
            List<String> facts = memoryService.getLongTermMemoryFacts(sessionId);
            String systemPrompt = promptTemplates.buildSystemPrompt(
                    affinity, facts, "web")
                    + sceneModeService.promptSegment(sceneModeService.currentMode(accountId))
                    + "\n【主动陪伴任务】" + sceneTask + "\n"
                    + "这是由你主动发起的消息，不是回复。用 ||| 拆成 2-3 条短句，像随手发的微信，不要提到「任务」「系统」这类词。";
            String raw = llmService.chat(List.of(
                    new LlmService.ChatMessage("system", systemPrompt),
                    new LlmService.ChatMessage("user", "（现在轮到你主动开口了）")), 0.85, 120, sessionId);
            if (raw == null || raw.isBlank()) {
                return false;
            }
            memoryService.saveMessage(sessionId, "assistant", raw);
            List<String> parts = messageSplitter.split(raw);
            if (parts.isEmpty()) {
                parts = List.of(messageSplitter.stripAffinityMarker(raw));
            }
            for (String part : parts) {
                if (part != null && !part.isBlank()) {
                    wsHandler.pushMessage(sessionId, part);
                }
            }
            log.info("主动陪伴消息已推送: accountId={}, scene={}", accountId, sceneTask);
            return true;
        } catch (Exception e) {
            log.warn("主动消息生成失败 accountId={}: {}", accountId, e.getMessage());
            return false;
        }
    }

    private String sessionIdOf(long accountId) {
        try {
            String token = jdbc.query("SELECT chat_token FROM account WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, accountId);
            return token == null ? null : "a" + accountId + "_" + token;
        } catch (Exception e) {
            return null;
        }
    }
}
