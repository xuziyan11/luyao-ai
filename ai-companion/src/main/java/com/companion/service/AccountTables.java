package com.companion.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 账号独立表路由：登录账号的 sessionId 形如 a{账号id}_{token}，
 * 其聊天历史 / 长期记忆 / 好感度读写会路由到对应的
 * chat_history_a{id} / long_term_memory_a{id} / affinity_a{id}，
 * 未登录的旧式 sessionId（随机 UUID、ilink_user_id 等）仍走原表，互不干扰。
 */
public final class AccountTables {

    private static final Pattern ACCOUNT_SESSION = Pattern.compile("^a(\\d+)_[A-Za-z0-9-]{4,64}$");

    private AccountTables() {
    }

    public static boolean isAccountSession(String sessionId) {
        return sessionId != null && ACCOUNT_SESSION.matcher(sessionId).matches();
    }

    /**
     * 表名后缀：账号会话返回 _a{id}，否则空串（使用原表）。
     * 表名完全由服务端从 sessionId 中的数字生成，不含用户输入，注入安全。
     */
    private static String suffix(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        Matcher m = ACCOUNT_SESSION.matcher(sessionId);
        return m.matches() ? "_a" + m.group(1) : "";
    }

    public static String chatHistory(String sessionId) {
        return "chat_history" + suffix(sessionId);
    }

    public static String longTermMemory(String sessionId) {
        return "long_term_memory" + suffix(sessionId);
    }

    public static String affinity(String sessionId) {
        return "affinity" + suffix(sessionId);
    }

    public static String diary(String sessionId) {
        return "diary" + suffix(sessionId);
    }

    /** 从账号会话 sessionId（a{id}_token）提取账号 id；非账号会话返回 null。 */
    public static Long extractAccountId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Matcher m = ACCOUNT_SESSION.matcher(sessionId);
        return m.matches() ? Long.parseLong(m.group(1)) : null;
    }
}
