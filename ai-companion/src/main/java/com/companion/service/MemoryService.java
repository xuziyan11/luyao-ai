package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.companion.service.AccountTables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记忆系统：对话历史（短期记忆）与长期记忆的读写。
 * <p>
 * - 短期记忆：chat_history 表，保留最近 N 轮对话作为 LLM 上下文。
 * - 长期记忆：long_term_memory 表，定期从对话中提取用户关键事实并注入 prompt。
 */
@Slf4j
@Service
public class MemoryService {

    private final JdbcTemplate jdbc;
    private final LlmService llmService;

    @Value("${companion.memory.short-term-rounds:20}")
    private int shortTermRounds;

    @Value("${companion.memory.long-term-extract-interval:5}")
    private int extractInterval;

    public MemoryService(JdbcTemplate jdbc, LlmService llmService) {
        this.jdbc = jdbc;
        this.llmService = llmService;
    }

    /** 保存一条对话消息（role: user/assistant） */
    public void saveMessage(String sessionId, String role, String content) {
        jdbc.update("INSERT INTO " + AccountTables.chatHistory(sessionId) + "(session_id, role, content) VALUES (?, ?, ?)",
                sessionId, role, content);
    }

    /** 读取指定会话的全部历史消息，按时间正序返回。 */
    public List<LlmService.ChatMessage> getAllMessages(String sessionId) {
        return jdbc.query(
                "SELECT role, content FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ? ORDER BY id ASC",
                (rs, rowNum) -> new LlmService.ChatMessage(rs.getString("role"), rs.getString("content")),
                sessionId);
    }

    /** 单条历史消息喂给 LLM 时的最大字符数，超出截断，防止偶尔的长消息撑爆上下文 */
    private static final int MAX_MSG_CHARS = 80;

    /**
     * 读取最近 N 轮对话（每轮 = user + assistant，共 N*2 条），按时间正序返回。
     * 会清理 [affinity:xxx] 标记并截断超长消息以节省 token。
     */
    public List<LlmService.ChatMessage> getRecentMessages(String sessionId) {
        int limit = shortTermRounds * 2;
        List<LlmService.ChatMessage> desc = jdbc.query(
                "SELECT role, content FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                (rs, rowNum) -> {
                    String role = rs.getString("role");
                    String content = rs.getString("content");
                    // 去掉好感度标记，节省 token
                    if (content != null) {
                        content = content.replaceAll("\\[affinity:[^\\]]*\\]", "").trim();
                    }
                    // 截断超长消息
                    if (content != null && content.length() > MAX_MSG_CHARS) {
                        content = content.substring(0, MAX_MSG_CHARS) + "…";
                    }
                    return new LlmService.ChatMessage(role, content);
                },
                sessionId, limit);
        if (desc.isEmpty()) {
            return Collections.emptyList();
        }
        // 数据库按 id 倒序取最近 N 条，这里反转为时间正序后再喂给 LLM
        List<LlmService.ChatMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return asc;
    }

    /** 读取该会话的长期记忆事实，仅取最新 8 条，避免 prompt 膨胀；不带时间戳以节省 token */
    public List<String> getLongTermMemoryFacts(String sessionId) {
        return jdbc.query(
                "SELECT fact FROM " + AccountTables.longTermMemory(sessionId) + " WHERE session_id = ? ORDER BY id DESC LIMIT 8",
                (rs, rowNum) -> rs.getString("fact"),
                sessionId);
    }

    /** 保存一条长期记忆事实 */
    public void saveLongTermFact(String sessionId, String fact) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        jdbc.update("INSERT INTO " + AccountTables.longTermMemory(sessionId) + "(session_id, fact) VALUES (?, ?)", sessionId, fact.trim());
    }

    /** 读取该会话的全部长期记忆（含 id 与时间，按时间倒序），供「记忆档案」页面展示 */
    public List<java.util.Map<String, Object>> getLongTermMemoryList(String sessionId) {
        return jdbc.query(
                "SELECT id, fact, created_at FROM " + AccountTables.longTermMemory(sessionId) + " WHERE session_id = ? ORDER BY id DESC",
                (rs, rowNum) -> {
                    java.util.Map<String, Object> item = new java.util.HashMap<>();
                    item.put("id", rs.getLong("id"));
                    item.put("fact", rs.getString("fact"));
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    item.put("createdAt", ts == null ? null : ts.getTime());
                    return item;
                },
                sessionId);
    }

    /** 删除一条长期记忆（必须属于该会话，防止越权删除） */
    public boolean deleteLongTermFact(long id, String sessionId) {
        int affected = jdbc.update(
                "DELETE FROM " + AccountTables.longTermMemory(sessionId) + " WHERE id = ? AND session_id = ?", id, sessionId);
        return affected > 0;
    }

    /** 累计对话条数（user + assistant） */
    public int countMessages(String sessionId) {
        Integer count = jdbc.query(
                "SELECT COUNT(*) FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                sessionId);
        return count == null ? 0 : count;
    }

    /** 深夜聊天次数：23:00 ~ 次日 05:00 之间的用户消息条数 */
    public int countNightMessages(String sessionId) {
        Integer count = jdbc.query(
                "SELECT COUNT(*) FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ? AND role = 'user' "
                        + "AND (HOUR(created_at) >= 23 OR HOUR(created_at) < 5)",
                rs -> rs.next() ? rs.getInt(1) : 0,
                sessionId);
        return count == null ? 0 : count;
    }

    /** 第一次对话的时间戳（毫秒），无记录返回 null，用于计算「加入天数」 */
    public Long getFirstMessageTime(String sessionId) {
        return jdbc.query(
                "SELECT MIN(created_at) FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    java.sql.Timestamp ts = rs.getTimestamp(1);
                    return ts == null ? null : ts.getTime();
                },
                sessionId);
    }

    /**
     * 最近 N 天每天的消息条数（含今天，按日期正序，无消息的日子补 0），
     * 用于好感度档案页的近 7 天柱状图。
     */
    public List<Integer> getDailyMessageCounts(String sessionId, int days) {
        java.util.Map<String, Integer> byDay = new java.util.HashMap<>();
        jdbc.query(
                "SELECT DATE(created_at) AS d, COUNT(*) AS c FROM " + AccountTables.chatHistory(sessionId) + " "
                        + "WHERE session_id = ? AND created_at >= ? GROUP BY DATE(created_at)",
                rs -> {
                    while (rs.next()) {
                        byDay.put(rs.getString("d"), rs.getInt("c"));
                    }
                    return null;
                },
                sessionId, java.sql.Timestamp.valueOf(java.time.LocalDate.now()
                        .minusDays(days - 1L).atStartOfDay()));
        List<Integer> result = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            String key = today.minusDays(i).toString();
            result.add(byDay.getOrDefault(key, 0));
        }
        return result;
    }

    /** 当前已完成的轮数（assistant 消息条数） */
    public int getAssistantRoundCount(String sessionId) {
        Integer count = jdbc.query(
                "SELECT COUNT(*) FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ? AND role = 'assistant'",
                rs -> rs.next() ? rs.getInt(1) : 0,
                sessionId);
        return count == null ? 0 : count;
    }

    /**
     * 每 {@link #extractInterval} 轮触发一次长期记忆提取：
     * 让 LLM 从近期对话中总结"关于对方的新信息"，并存入 long_term_memory 表。
     */
    public void maybeExtractLongTermMemory(String sessionId) {
        try {
            int rounds = getAssistantRoundCount(sessionId);
            if (rounds == 0 || rounds % extractInterval != 0) {
                return;
            }
            List<LlmService.ChatMessage> recent = getRecentMessages(sessionId);
            if (recent.isEmpty()) {
                return;
            }
            StringBuilder dialogue = new StringBuilder();
            for (LlmService.ChatMessage m : recent) {
                String speaker = "assistant".equals(m.role()) ? "路瑶" : "对方";
                // 去掉 ||| 与好感度标记，便于 LLM 阅读对话
                String text = m.content().replaceAll("\\[affinity:[+-]?\\d+\\]", "")
                        .replace("|||", " ").trim();
                dialogue.append(speaker).append("：").append(text).append('\n');
            }

            String systemPrompt = """
                    你是一个信息抽取助手。请从下面的对话中提取"关于对方（用户）的关键事实"，
                    例如：喜欢猫、住在郑州、工作是编程师、胃不太好、最近在加班等。
                    只输出还未出现过的新事实，每条事实一行，不要任何编号、解释或多余文字。
                    如果没有可提取的新事实，就只输出：无
                    """;
            LlmService.ChatMessage sys = new LlmService.ChatMessage("system", systemPrompt);
            LlmService.ChatMessage user = new LlmService.ChatMessage("user", "对话内容：\n" + dialogue);

            String result = llmService.chat(List.of(sys, user), 0.3, 200);
            if (result == null || result.isBlank() || result.trim().startsWith("无")) {
                log.debug("本轮无可提取的长期记忆");
                return;
            }
            for (String line : result.split("\\r?\\n")) {
                String fact = line.trim().replaceAll("^[\\-•·\\d+.、）)]+\\s*", "");
                if (!fact.isEmpty() && !fact.equals("无")) {
                    saveLongTermFact(sessionId, fact);
                    log.info("提取长期记忆: {}", fact);
                }
            }
        } catch (Exception e) {
            log.warn("长期记忆提取失败，不影响主流程: {}", e.getMessage());
        }
    }
}
