package com.companion.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 消息拆分器：把 AI 原始回复拆分成多条独立的微信式消息。
 * <p>
 * 规则：
 * 1. 清除末尾的 [affinity:+N] 好感度标记；
 * 2. 按 ||| 分隔符拆分；
 * 3. 去除每段首尾空白，过滤掉空段。
 */
@Component
public class MessageSplitter {

    /** 匹配 [affinity:+12] / [affinity:-3] / [affinity:+0] 这类标记 */
    private static final Pattern AFFINITY_PATTERN =
            Pattern.compile("\\[affinity\\s*:\\s*[+-]?\\d+\\s*\\]", Pattern.CASE_INSENSITIVE);

    /** 匹配中文全角括号（xxx）和英文半角括号(xxx)内容，含嵌套场景 */
    private static final Pattern PAREN_PATTERN =
            Pattern.compile("[（(][^（()）]*[）)]");

    /**
     * 清除回复中的 [affinity:xxx] 标记和 (xxx) / （xxx）括号描述。
     */
    public String stripAffinityMarker(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return "";
        }
        // 先去掉 [affinity:xxx] 标记
        String cleaned = AFFINITY_PATTERN.matcher(rawReply).replaceAll("");
        // 再去掉括号里的动作/语气描述（如 "（揉揉眼睛）"、"(温柔地)"）
        cleaned = PAREN_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /**
     * 按 ||| 拆分为多条消息，并清除好感度标记。
     */
    public List<String> split(String rawReply) {
        List<String> messages = new ArrayList<>();
        if (rawReply == null || rawReply.isBlank()) {
            return messages;
        }
        // 先去掉好感度标记，再按分隔符拆分
        String cleaned = stripAffinityMarker(rawReply);
        if (cleaned.isEmpty()) {
            return messages;
        }
        for (String part : cleaned.split("\\|\\|\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                messages.add(trimmed);
            }
        }
        // 极端情况：拆分后为空（例如整条回复只有一个标记），兜底返回空，由上层处理
        return messages;
    }
}
