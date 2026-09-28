package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 轻量数据库迁移：schema.sql 的 CREATE TABLE IF NOT EXISTS 无法给已有表加列，
 * 这里在应用完全启动后补齐缺失列（幂等，可重复执行）。
 */
@Slf4j
@Component
public class DbMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final AccountService accountService;

    public DbMigration(JdbcTemplate jdbc, AccountService accountService) {
        this.jdbc = jdbc;
        this.accountService = accountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // account.status：active / disabled，管理后台禁用账号用
        addColumnIfMissing("account", "status",
                "ALTER TABLE account ADD COLUMN status VARCHAR(8) NOT NULL DEFAULT 'active'");
        // account.password_hash：账号密码登录（PBKDF2 哈希，NULL 表示尚未设置密码）
        addColumnIfMissing("account", "password_hash",
                "ALTER TABLE account ADD COLUMN password_hash VARCHAR(160) NULL");
        // account.scene_mode：场景模式（daily/sleep/vent/joke），默认日常闲聊
        addColumnIfMissing("account", "scene_mode",
                "ALTER TABLE account ADD COLUMN scene_mode VARCHAR(20) NOT NULL DEFAULT 'daily'");

        // 存量账号补建独立数据表（如 diary_a{id} 等新表类型；CREATE IF NOT EXISTS 幂等）
        ensureAccountTables();
    }

    private void ensureAccountTables() {
        try {
            List<Long> ids = jdbc.queryForList("SELECT id FROM account", Long.class);
            for (Long id : ids) {
                accountService.createAccountTables(id);
            }
            if (!ids.isEmpty()) {
                log.info("数据库迁移: {} 个存量账号的独立数据表已检查/补齐", ids.size());
            }
        } catch (Exception e) {
            log.warn("存量账号独立表补齐失败: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        try {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (cnt != null && cnt == 0) {
                jdbc.execute(ddl);
                log.info("数据库迁移: {}.{} 列已添加", table, column);
            }
        } catch (Exception e) {
            log.warn("数据库迁移检查失败 {}.{}: {}", table, column, e.getMessage());
        }
    }
}
