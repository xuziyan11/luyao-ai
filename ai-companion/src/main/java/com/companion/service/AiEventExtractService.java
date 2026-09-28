package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件摘要提取服务：关键词过滤 + DeepSeek 摘要。
 * <p>
 * 1. 先判断用户消息是否命中关键词（下班、累、疲惫、睡觉、失眠、出门、难受、不舒服、加班、心情不好等）。
 * 2. 只有命中关键词，才调用 DeepSeek 做事件摘要（temperature=0.1）。
 * 3. 返回 "none" 则不保存记忆；返回事件摘要则存入长期记忆。
 * 4. 普通闲聊直接跳过摘要接口，不调用 DeepSeek。
 */
@Slf4j
@Service
public class AiEventExtractService {

    /** 关键词列表：命中任一关键词才触发摘要 */
    private static final List<String> KEYWORDS = List.of(
            "下班", "累", "疲惫", "疲", "困", "睡觉", "失眠", "睡不着",
            "出门", "出门了", "到家", "回去了", "难受", "不舒服", "不舒服",
            "加班", "加班了", "心情不好", "心情差", "郁闷", "烦", "心烦",
            "难过", "伤心", "开心", "高兴", "生病", "感冒", "头疼",
            "头痛", "胃疼", "肚子疼", "发烧", "想哭", "委屈", "压力",
            "压力大", "焦虑", "紧张", "害怕", "担心", "想家", "回家",
            "上班", "迟到", "被骂", "挨批", "辞职", "面试", "升职",
            "发工资", "花钱", "买东西", "吃", "喝了", "聚餐",
            "分手", "吵架", "和朋友", "约会", "表白", "喜欢你"
    );

    private final LlmService llmService;

    public AiEventExtractService(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * 判断用户消息是否命中关键词。
     */
    public boolean hitKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        for (String kw : KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 调用 DeepSeek 做事件摘要。
     *
     * @param userMessage 用户原始消息
     * @return 事件摘要文本，或 "none" 表示无可提取事件
     */
    public String extractEventSummary(String userMessage) {
        try {
            String systemPrompt = """
                    你是一个事件摘要助手。请识别用户消息中包含的事件信息。
                    规则：
                    1. 如果用户消息中有明确的事件（如：下班了、加班、不舒服、失眠、出门了、心情不好等），输出一句简短的事实描述。
                    2. 事实描述不超过20个字，只陈述客观事实，不加主观评价。
                    3. 如果用户消息中没有有效事件信息（如纯闲聊、打招呼、表情符号等），只输出：none
                    4. 只输出摘要内容或 none，不要输出任何解释或多余文字。
                    """;

            LlmService.ChatMessage sys = new LlmService.ChatMessage("system", systemPrompt);
            LlmService.ChatMessage user = new LlmService.ChatMessage("user", userMessage);

            String result = llmService.chat(List.of(sys, user), 0.1, 100);
            if (result == null || result.isBlank()) {
                return "none";
            }

            String trimmed = result.trim();
            if ("none".equalsIgnoreCase(trimmed)) {
                return "none";
            }

            log.info("事件摘要提取成功: '{}' -> '{}'", userMessage, trimmed);
            return trimmed;

        } catch (Exception e) {
            log.warn("事件摘要提取失败，跳过: {}", e.getMessage());
            return "none";
        }
    }
}
