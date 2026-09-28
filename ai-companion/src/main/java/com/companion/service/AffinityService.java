package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 好感度系统：读写每个会话的好感度值，并从 AI 回复中提取变化值。
 * <p>
 * 好感度范围 0~100，初始 40（直接从朋友阶段开始）。
 * 从回复中正则提取 [affinity:+12] 这样的标记，解析变化值并更新数据库。
 */
@Slf4j
@Service
public class AffinityService {

    /** 匹配 [affinity:+12] / [affinity:-3] */
    private static final Pattern DELTA_PATTERN =
            Pattern.compile("\\[affinity\\s*:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;

    @Value("${companion.affinity.initial:0}")
    private int initial;

    @Value("${companion.affinity.min:0}")
    private int min;

    @Value("${companion.affinity.max:100}")
    private int max;

    public AffinityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 启动时把低于 {@code initial} 的旧好感度记录提升到 {@code initial}，
     * 确保旧 session 也能直接进入对应阶段（如朋友阶段），不会被历史 0 值卡住。
     * 高于 initial 的记录保持不变，不影响用户已积累的好感度。
     */
    @PostConstruct
    public void liftLowAffinityOnStartup() {
        try {
            int updated = jdbc.update(
                    "UPDATE affinity SET affinity_value = ? WHERE affinity_value < ?",
                    initial, initial);
            if (updated > 0) {
                log.info("启动时把 {} 个低好感度 session 提升至 {}", updated, initial);
            }
        } catch (Exception e) {
            log.warn("启动提升好感度失败，不影响主流程: {}", e.getMessage());
        }
    }

    /**
     * 读取会话好感度，不存在则初始化为默认值并返回。
     * 登录账号路由到独立表 affinity_a{id}，保证不同账号数据隔离。
     */
    public int getAffinity(String sessionId) {
        String table = AccountTables.affinity(sessionId);
        Integer value = jdbc.query(
                "SELECT affinity_value FROM " + table + " WHERE session_id = ?",
                rs -> rs.next() ? rs.getInt("affinity_value") : null,
                sessionId);
        if (value == null) {
            int init = initial;
            jdbc.update("INSERT INTO " + table + "(session_id, affinity_value) VALUES (?, ?)", sessionId, init);
            return init;
        }
        return value;
    }

    /**
     * 在当前好感度基础上叠加变化值，并钳制到 [min, max]。
     *
     * @return 更新后的好感度
     */
    public int applyDelta(String sessionId, int delta) {
        int current = getAffinity(sessionId);
        int next = Math.max(min, Math.min(max, current + delta));
        jdbc.update("UPDATE " + AccountTables.affinity(sessionId)
                        + " SET affinity_value = ?, updated_at = CURRENT_TIMESTAMP WHERE session_id = ?",
                next, sessionId);
        log.debug("好感度变化: {} {} -> {}", sessionId, delta >= 0 ? "+" + delta : delta, next);
        return next;
    }

    /**
     * 从 AI 原始回复中正则提取好感度变化值。
     */
    public Optional<Integer> extractDelta(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return Optional.empty();
        }
        Matcher m = DELTA_PATTERN.matcher(rawReply);
        if (m.find()) {
            try {
                return Optional.of(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException e) {
                log.warn("无法解析好感度变化值: {}", m.group(1));
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 一步完成：从回复提取变化值并应用，返回更新后的好感度。
     * 若回复中没有标记，则给一个默认 +1（正常闲聊底线），确保好感度不会一直卡住。
     */
    public int extractAndApply(String sessionId, String rawReply) {
        Optional<Integer> deltaOpt = extractDelta(rawReply);
        int delta = deltaOpt.orElse(1);
        if (deltaOpt.isEmpty()) {
            log.debug("未检测到好感度标记，使用默认增量 +1: {}", sessionId);
        }
        return applyDelta(sessionId, delta);
    }
}
