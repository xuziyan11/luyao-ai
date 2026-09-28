package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * wechat_binding 表的访问层。
 * 用 JdbcTemplate 简单实现，不引入 JPA。
 */
@Slf4j
@Repository
public class WechatBindingRepository {

    private final JdbcTemplate jdbc;

    public WechatBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Binding(
            String ilinkUserId,
            String botToken,
            String ilinkBotId,
            String baseurl,
            String contextToken,
            String getUpdatesBuf,
            String nickname
    ) {}

    /** 插入或更新绑定（按 ilink_user_id 主键 upsert）。 */
    public void upsert(Binding b) {
        String sql = """
                INSERT INTO wechat_binding
                  (ilink_user_id, bot_token, ilink_bot_id, baseurl, context_token, get_updates_buf, nickname)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  bot_token = VALUES(bot_token),
                  ilink_bot_id = VALUES(ilink_bot_id),
                  baseurl = VALUES(baseurl),
                  context_token = VALUES(context_token),
                  get_updates_buf = VALUES(get_updates_buf),
                  nickname = VALUES(nickname),
                  updated_at = CURRENT_TIMESTAMP
                """;
        try {
            jdbc.update(sql,
                    b.ilinkUserId(), b.botToken(), b.ilinkBotId(),
                    b.baseurl(), b.contextToken(), b.getUpdatesBuf(), b.nickname());
        } catch (Exception e) {
            // 兼容 H2：H2 用 MERGE 语法而不是 ON DUPLICATE KEY UPDATE
            log.warn("MySQL upsert 失败，尝试 H2 兼容写法: {}", e.getMessage());
            jdbc.update("DELETE FROM wechat_binding WHERE ilink_user_id = ?", b.ilinkUserId());
            jdbc.update("""
                    INSERT INTO wechat_binding
                      (ilink_user_id, bot_token, ilink_bot_id, baseurl, context_token, get_updates_buf, nickname)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    b.ilinkUserId(), b.botToken(), b.ilinkBotId(),
                    b.baseurl(), b.contextToken(), b.getUpdatesBuf(), b.nickname());
        }
    }

    /** 只更新 context_token 与 get_updates_buf（每次收发消息后会变）。 */
    public void updateCursors(String ilinkUserId, String contextToken, String getUpdatesBuf) {
        jdbc.update("""
                UPDATE wechat_binding SET
                  context_token = COALESCE(?, context_token),
                  get_updates_buf = COALESCE(?, get_updates_buf),
                  updated_at = CURRENT_TIMESTAMP
                WHERE ilink_user_id = ?
                """,
                contextToken, getUpdatesBuf, ilinkUserId);
    }

    public List<Binding> findAll() {
        return jdbc.query("""
                SELECT ilink_user_id, bot_token, ilink_bot_id, baseurl,
                       context_token, get_updates_buf, nickname
                FROM wechat_binding
                """,
                (rs, n) -> new Binding(
                        rs.getString("ilink_user_id"),
                        rs.getString("bot_token"),
                        rs.getString("ilink_bot_id"),
                        rs.getString("baseurl"),
                        rs.getString("context_token"),
                        rs.getString("get_updates_buf"),
                        rs.getString("nickname")));
    }

    public Binding find(String ilinkUserId) {
        List<Binding> list = jdbc.query("""
                SELECT ilink_user_id, bot_token, ilink_bot_id, baseurl,
                       context_token, get_updates_buf, nickname
                FROM wechat_binding WHERE ilink_user_id = ?
                """,
                (rs, n) -> new Binding(
                        rs.getString("ilink_user_id"),
                        rs.getString("bot_token"),
                        rs.getString("ilink_bot_id"),
                        rs.getString("baseurl"),
                        rs.getString("context_token"),
                        rs.getString("get_updates_buf"),
                        rs.getString("nickname")),
                ilinkUserId);
        return list.isEmpty() ? null : list.get(0);
    }
}
