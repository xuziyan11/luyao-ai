package com.companion.controller;

import com.companion.service.AccountTables;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话合并接口：把旧 sessionId 的数据（聊天历史、长期记忆、好感度）迁移到新 sessionId。
 * 用于让微信端 ilink_user_id / 网页端旧随机 UUID 与登录账号共享同一个路瑶。
 * <p>
 * 登录账号的数据在独立表（*_a{id}）中，源表与目标表不同时采用「复制 + 删除」跨表迁移。
 */
@RestController
@RequestMapping("/api/session")
@Slf4j
public class SessionMergeController {

    private final JdbcTemplate jdbc;

    public SessionMergeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 合并旧会话到新会话。
     *
     * @param oldSessionId  源 sessionId（如网页端 localStorage 里的旧随机 UUID）
     * @param newSessionId  目标 sessionId（如登录账号的 a{id}_{token}）
     */
    @PostMapping("/merge")
    public ResponseEntity<?> merge(@RequestParam String oldSessionId,
                                   @RequestParam String newSessionId) {
        if (oldSessionId == null || oldSessionId.isBlank() || newSessionId == null || newSessionId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "sessionId 不能为空"));
        }
        if (oldSessionId.equals(newSessionId)) {
            return ResponseEntity.ok(Map.of("success", true, "message", "已是同一个会话", "noChange", true));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("oldSessionId", oldSessionId);
        result.put("newSessionId", newSessionId);

        try {
            // 1. 合并聊天历史
            int movedHistory = moveRows(AccountTables.chatHistory(oldSessionId),
                    AccountTables.chatHistory(newSessionId),
                    "session_id, role, content, created_at", oldSessionId, newSessionId);
            result.put("movedHistory", movedHistory);

            // 2. 合并长期记忆
            int movedMemory = moveRows(AccountTables.longTermMemory(oldSessionId),
                    AccountTables.longTermMemory(newSessionId),
                    "session_id, fact, created_at", oldSessionId, newSessionId);
            result.put("movedMemory", movedMemory);

            // 3. 合并好感度：取两者中较大的值写入目标表，然后删除旧记录
            String oldAffTable = AccountTables.affinity(oldSessionId);
            String newAffTable = AccountTables.affinity(newSessionId);
            Integer oldAff = jdbc.query(
                    "SELECT affinity_value FROM " + oldAffTable + " WHERE session_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, oldSessionId);
            Integer newAff = jdbc.query(
                    "SELECT affinity_value FROM " + newAffTable + " WHERE session_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, newSessionId);

            int finalAffinity;
            if (oldAff != null) {
                finalAffinity = newAff == null ? oldAff : Math.max(oldAff, newAff);
                if (newAff != null) {
                    jdbc.update("UPDATE " + newAffTable
                                    + " SET affinity_value = ?, updated_at = CURRENT_TIMESTAMP WHERE session_id = ?",
                            finalAffinity, newSessionId);
                } else {
                    jdbc.update("INSERT INTO " + newAffTable + "(session_id, affinity_value) VALUES (?, ?)",
                            newSessionId, finalAffinity);
                }
            } else {
                finalAffinity = newAff == null ? 0 : newAff;
            }
            jdbc.update("DELETE FROM " + oldAffTable + " WHERE session_id = ?", oldSessionId);
            result.put("finalAffinity", finalAffinity);

            log.info("会话合并完成: {} -> {}, 历史 {}条, 记忆 {}条, 最终好感度 {}",
                    oldSessionId, newSessionId, movedHistory, movedMemory, finalAffinity);

            result.put("success", true);
            result.put("message", "合并成功");
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("会话合并失败: {} -> {}", oldSessionId, newSessionId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "合并失败: " + e.getMessage()));
        }
    }

    /**
     * 把 oldSessionId 的数据行搬到 newSessionId。
     * 源表与目标表相同（都在原表）时直接 UPDATE；不同（迁入账号独立表）时 INSERT SELECT + DELETE。
     */
    private int moveRows(String fromTable, String toTable, String columns,
                         String oldSessionId, String newSessionId) {
        if (fromTable.equals(toTable)) {
            return jdbc.update("UPDATE " + toTable + " SET session_id = ? WHERE session_id = ?",
                    newSessionId, oldSessionId);
        }
        // 把 SELECT 列表中的 session_id 列换成占位符，其余列原样搬运
        String selectColumns = columns.replaceFirst("session_id", "?");
        int copied = jdbc.update(
                "INSERT INTO " + toTable + "(" + columns + ") SELECT " + selectColumns
                        + " FROM " + fromTable + " WHERE session_id = ?",
                newSessionId, oldSessionId);
        jdbc.update("DELETE FROM " + fromTable + " WHERE session_id = ?", oldSessionId);
        return copied;
    }

    /** 查询某个 sessionId 的数据量，用于调试。 */
    @GetMapping("/{sessionId}/stats")
    public Map<String, Object> stats(@PathVariable String sessionId) {
        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", sessionId);
        try {
            Integer historyCount = jdbc.query(
                    "SELECT COUNT(*) FROM " + AccountTables.chatHistory(sessionId) + " WHERE session_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : 0, sessionId);
            Integer memoryCount = jdbc.query(
                    "SELECT COUNT(*) FROM " + AccountTables.longTermMemory(sessionId) + " WHERE session_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : 0, sessionId);
            Integer affinity = jdbc.query(
                    "SELECT affinity_value FROM " + AccountTables.affinity(sessionId) + " WHERE session_id = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, sessionId);
            result.put("historyCount", historyCount == null ? 0 : historyCount);
            result.put("memoryCount", memoryCount == null ? 0 : memoryCount);
            result.put("affinity", affinity == null ? "无" : affinity);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }
}
