package com.companion.service;

import com.companion.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天主流程服务：从 ChatWebSocketHandler 抽出来，便于 WebSocket / iLink 多通道复用。
 * <p>
 * 数据流：
 * 接收用户消息 → 存历史 → 读好感度/记忆 → 生成 system prompt → 组装上下文
 * → 调 DeepSeek → 提取好感度变化 → 拆分消息 → 存历史 → 返回拆分后的多条消息。
 * <p>
 * 流式回调可选：WebSocket 通道传入 chunk 回调用于实时推送；iLink 通道不传，只用最终拆分结果。
 */
@Slf4j
@Service
public class ChatService {

    private final LlmService llmService;
    private final MemoryService memoryService;
    private final AffinityService affinityService;
    private final MessageSplitter messageSplitter;
    private final PromptTemplates promptTemplates;
    private final AiEventExtractService eventExtractService;
    private final SceneModeService sceneModeService;

    public ChatService(LlmService llmService,
                       MemoryService memoryService,
                       AffinityService affinityService,
                       MessageSplitter messageSplitter,
                       PromptTemplates promptTemplates,
                       AiEventExtractService eventExtractService,
                       SceneModeService sceneModeService) {
        this.llmService = llmService;
        this.memoryService = memoryService;
        this.affinityService = affinityService;
        this.messageSplitter = messageSplitter;
        this.promptTemplates = promptTemplates;
        this.eventExtractService = eventExtractService;
        this.sceneModeService = sceneModeService;
    }

    /**
     * 处理用户消息，返回拆分后的多条 AI 回复。
     *
     * @param sessionId      会话标识（WebSocket 用 ws id，iLink 用 ilink_user_id）
     * @param userText       用户原文
     * @param chunkCallback  流式回调，可为 null（iLink 通道不传）
     * @param channel        通道："web"=网页端；"wechat"=微信 iLink 通道（5-10字多条）
     * @param clientEpochMillis  用户端本地时间（epoch 毫秒）；为 null 时回退服务端时间
     * @param clientTzOffsetMinutes 用户端相对 UTC 的东偏分钟数（如 UTC+8 为 +480）；为 null 时回退服务端时区
     * @return 拆分后的多条消息，已清除 [affinity:+N] 标记；若出错返回兜底单条
     */
    public List<String> chat(String sessionId, String userText, Consumer<String> chunkCallback, String channel,
                             Long clientEpochMillis, Integer clientTzOffsetMinutes) {
        try {
            log.info("开始处理: sessionId={}, channel={}", sessionId, channel);

            // 1. 保存用户消息
            memoryService.saveMessage(sessionId, "user", userText);

            // 1.5 关键词判断 + 事件摘要提取
            if (eventExtractService.hitKeywords(userText)) {
                String summary = eventExtractService.extractEventSummary(userText);
                if (!"none".equals(summary)) {
                    memoryService.saveLongTermFact(sessionId, summary);
                    log.info("保存记忆事实: {}", summary);
                }
            }

            // 2. 读取好感度与长期记忆，生成 system prompt（叠加当前场景模式人设）
            int affinity = affinityService.getAffinity(sessionId);
            List<String> facts = memoryService.getLongTermMemoryFacts(sessionId);
            String systemPrompt = promptTemplates.buildSystemPrompt(affinity, facts, channel, clientEpochMillis, clientTzOffsetMinutes);
            Long modeAccountId = AccountTables.extractAccountId(sessionId);
            if (modeAccountId != null) {
                systemPrompt += sceneModeService.promptSegment(sceneModeService.currentMode(modeAccountId));
            }

            // 3. 读取最近对话历史，组装 messages
            List<LlmService.ChatMessage> history = memoryService.getRecentMessages(sessionId);
            List<LlmService.ChatMessage> messages = new ArrayList<>();
            messages.add(new LlmService.ChatMessage("system", systemPrompt));
            messages.addAll(history);

            // 4. 调用 DeepSeek（流式，带 sessionId 用于管理后台 Token/延迟统计）
            final Consumer<String> cb = chunkCallback;
            String rawReply = llmService.chatStream(messages, sessionId, cb == null ? s -> {} : cb);

            // 5. 提取并更新好感度
            int newAffinity = affinityService.extractAndApply(sessionId, rawReply);
            log.info("回复完成: sessionId={}, affinity={}", sessionId, newAffinity);

            // 6. 保存完整 AI 回复到历史
            memoryService.saveMessage(sessionId, "assistant", rawReply);

            // 7. 拆分为多条消息
            List<String> parts = messageSplitter.split(rawReply);
            if (parts.isEmpty()) {
                parts = List.of("（路瑶走神了，再说一次嘛）");
            }

            // 8. 异步触发长期记忆提取（每 5 轮）
            memoryService.maybeExtractLongTermMemory(sessionId);

            return parts;

        } catch (Exception e) {
            log.error("处理消息异常: sessionId={}", sessionId, e);
            return List.of("路瑶好像走神了，稍等再试试~");
        }
    }

    /** 不带流式回调的便捷重载，iLink 通道用。 */
    public List<String> chat(String sessionId, String userText) {
        return chat(sessionId, userText, null, "web", null, null);
    }

    /** 指定通道但不带用户端时间的便捷重载（iLink 等通道用，时间取服务端）。 */
    public List<String> chat(String sessionId, String userText, Consumer<String> chunkCallback, String channel) {
        return chat(sessionId, userText, chunkCallback, channel, null, null);
    }

    /** 带流式回调、默认 web 通道。 */
    public List<String> chat(String sessionId, String userText, Consumer<String> chunkCallback) {
        return chat(sessionId, userText, chunkCallback, "web", null, null);
    }
}
