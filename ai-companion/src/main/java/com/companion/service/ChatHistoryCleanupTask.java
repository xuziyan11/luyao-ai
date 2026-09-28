package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理 chat_history 历史聊天数据，防止表无限膨胀。
 * <p>
 * - 默认保留最近 3 天数据，超出部分按 created_at 旧数据先删。
 * - 默认每天凌晨 3:00 执行一次。
 * - 通过 companion.cleanup.* 可调整保留天数与是否启用。
 */
@Slf4j
@Component
public class ChatHistoryCleanupTask {

    private final JdbcTemplate jdbc;

    @Value("${companion.cleanup.chat-history.enabled:true}")
    private boolean enabled;

    @Value("${companion.cleanup.chat-history.retain-days:3}")
    private int retainDays;

    public ChatHistoryCleanupTask(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 每天 03:00 执行一次清理。
     * cron：秒 分 时 日 月 周
     */
    @Scheduled(cron = "${companion.cleanup.chat-history.cron:0 0 3 * * ?}")
    public void cleanup() {
        if (!enabled) {
            log.debug("chat_history 清理任务未启用，跳过。");
            return;
        }
        try {
            int deleted = jdbc.update(
                    "DELETE FROM chat_history WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)",
                    retainDays);
            if (deleted > 0) {
                log.info("chat_history 清理完成：删除 {} 条超过 {} 天的旧数据", deleted, retainDays);
            } else {
                log.debug("chat_history 清理完成：无可删除数据（保留 {} 天）", retainDays);
            }
        } catch (Exception e) {
            log.warn("chat_history 清理失败，不影响主流程: {}", e.getMessage());
        }
    }
}
