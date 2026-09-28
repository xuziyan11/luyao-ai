package com.companion.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * iLink 长轮询任务：为每个已绑定的微信账号起一个独立的轮询线程，
 * 拉到消息后调 {@link ChatService}，再用打字延迟逐条发送出去。
 * <p>
 * 设计要点：
 * - 每个绑定账号一个独立线程，互不影响；
 * - 单账号串行处理收到的消息（避免同一会话上下文乱序）；
 * - 打字延迟复用项目原本的 800ms + 字数×30ms 公式。
 */
@Slf4j
@Service
public class IlinkPollingTask {

    private final IlinkClient ilinkClient;
    private final WechatBindingRepository bindingRepo;
    private final ChatService chatService;

    @Value("${companion.ilink.enabled:false}")
    private boolean enabled;

    @Value("${companion.ilink.typing-base-ms:800}")
    private long typingBaseMs;

    @Value("${companion.ilink.typing-per-char-ms:30}")
    private long typingPerCharMs;

    /** 每个账号一个轮询 future，便于停止/重启 */
    private final Map<String, Future<?>> pollers = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();
    private ExecutorService executor;

    private static final long EMPTY_POLL_BACKOFF_MS = 1000L;

    /** 句末标点：遇到这些字符时断句，拆成单条发送 */
    private static final String SENTENCE_ENDS = "。！？!?~…\n";

    /**
     * 将一段文本按句末标点拆分为多个单句。
     * 每个句子保留其结束标点，空句被过滤。
     */
    private List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (SENTENCE_ENDS.indexOf(c) != -1) {
                String s = sb.toString().trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
                sb.setLength(0);
            }
        }
        // 尾部无句末标点的剩余部分
        String tail = sb.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    public static long calculatePollBackoffMs(boolean hasMessages, String getUpdatesBuf, long elapsedMs) {
        if (hasMessages) {
            return 0L;
        }
        if (getUpdatesBuf != null && !getUpdatesBuf.isBlank()) {
            return 0L;
        }
        return Math.max(EMPTY_POLL_BACKOFF_MS, elapsedMs > 0 ? Math.min(elapsedMs, EMPTY_POLL_BACKOFF_MS) : EMPTY_POLL_BACKOFF_MS);
    }

    @Autowired
    public IlinkPollingTask(IlinkClient ilinkClient,
                            WechatBindingRepository bindingRepo,
                            ChatService chatService) {
        this.ilinkClient = ilinkClient;
        this.bindingRepo = bindingRepo;
        this.chatService = chatService;
    }

    @PostConstruct
    public void start() {
        // executor 必须先建出来，否则后续扫码触发 startPoller 时会 NPE
        ensureExecutor();
        if (!enabled) {
            log.info("iLink 通道未启用（companion.ilink.enabled=false），仅初始化 executor，跳过已绑定账号的轮询");
            return;
        }
        // 启动时把已绑定的账号都拉起轮询
        for (WechatBindingRepository.Binding b : bindingRepo.findAll()) {
            startPoller(b);
        }
        log.info("iLink 轮询任务已启动，已绑定 {} 个微信账号", pollers.size());
    }

    @PreDestroy
    public void stop() {
        pollers.keySet().forEach(this::stopPoller);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    /** 确保 executor 可用：未初始化或已关闭时新建一个，避免扫码后 startPoller 出现 NPE。 */
    private synchronized void ensureExecutor() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ilink-poller");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 新绑定一个微信号后调用，启动它的轮询线程。 */
    public void startPoller(WechatBindingRepository.Binding b) {
        ensureExecutor();
        // 同一账号重复扫码：先停旧的再启新的
        stopPoller(b.ilinkUserId());
        AtomicBoolean running = new AtomicBoolean(true);
        runningFlags.put(b.ilinkUserId(), running);
        Future<?> f = executor.submit(() -> pollLoop(b, running));
        pollers.put(b.ilinkUserId(), f);
        log.info("启动轮询: {}", b.ilinkUserId());
    }

    public void stopPoller(String ilinkUserId) {
        AtomicBoolean running = runningFlags.get(ilinkUserId);
        if (running != null) {
            running.set(false);
        }
        Future<?> f = pollers.remove(ilinkUserId);
        if (f != null) {
            f.cancel(true);
        }
        runningFlags.remove(ilinkUserId);
        log.info("停止轮询: {}", ilinkUserId);
    }

    private void pollLoop(WechatBindingRepository.Binding b, AtomicBoolean running) {
        String userId = b.ilinkUserId();
        String baseurl = b.baseurl();
        String botToken = b.botToken();
        String getUpdatesBuf = b.getUpdatesBuf();
        // 内存里缓存 context_token，避免每次发送都查库；收到/发送时同步刷新到库
        String contextToken = b.contextToken();
        long lastPollStartedAt = System.currentTimeMillis();

        log.info("轮询线程进入: userId={}, baseurl={}, getUpdatesBufPresent={}", userId, baseurl,
                getUpdatesBuf != null && !getUpdatesBuf.isBlank());

        while (running.get()) {
            long pollStartedAt = System.currentTimeMillis();
            try {
                log.debug("发起 getupdates: userId={}, getUpdatesBufPresent={}", userId,
                        getUpdatesBuf != null && !getUpdatesBuf.isBlank());
                IlinkClient.GetUpdatesResult result =
                        ilinkClient.getUpdates(baseurl, botToken, getUpdatesBuf);
                long elapsedMs = System.currentTimeMillis() - pollStartedAt;
                boolean hasMessages = !result.messages().isEmpty();
                if (result.getUpdatesBuf() != null) {
                    getUpdatesBuf = result.getUpdatesBuf();
                    bindingRepo.updateCursors(userId, null, getUpdatesBuf);
                }
                long backoffMs = calculatePollBackoffMs(hasMessages, getUpdatesBuf, elapsedMs);
                if (backoffMs > 0L) {
                    log.info("轮询空结果，userId={} 进入 {}ms 退避，避免长轮询忙循环", userId, backoffMs);
                    Thread.sleep(backoffMs);
                }
                for (IlinkClient.InboundMessage msg : result.messages()) {
                    // msgType 1=text（按 OpenClaw 源码 item_list.type=1 即文本）；
                    // 协议其他类型暂未对接，但只要 extractText 拿到内容就处理
                    if (msg.content() == null || msg.content().isBlank()) {
                        log.info("跳过空消息: type={} from={}", msg.msgType(), msg.fromUser());
                        continue;
                    }
                    String userText = msg.content();
                    log.info("收到微信消息: from={}, type={}, text={}", userId, msg.msgType(), userText);

                    // 微信端用绑定的 ilinkUserId 作为 sessionId，
                    // 绑定后的浏览器也会用同一个 ilinkUserId，从而实现两端记录互通
                    String sessionId = b.ilinkUserId();
                    List<String> replies = chatService.chat(sessionId, userText, null, "wechat");

                    // 将每条回复进一步按句末标点拆分为单句，实现一句一句发
                    List<String> sentenceParts = new ArrayList<>();
                    for (String reply : replies) {
                        sentenceParts.addAll(splitSentences(reply));
                    }
                    if (sentenceParts.isEmpty()) {
                        sentenceParts.add("（路瑶走神了，再说一次嘛）");
                    }

                    // 用打字延迟逐条发送，模拟真人节奏
                    if (msg.contextToken() != null && !msg.contextToken().isBlank()) {
                        contextToken = msg.contextToken();
                    }
                    for (int i = 0; i < sentenceParts.size(); i++) {
                        String part = sentenceParts.get(i);
                        // 第一条前先发"正在输入"，模拟打字
                        if (i == 0) {
                            ilinkClient.sendTyping(baseurl, botToken, userId);
                        }
                        int delayMs = (int) (typingBaseMs + part.length() * typingPerCharMs);
                        Thread.sleep(delayMs);
                        // 多条消息之间也补一次 typing
                        if (i > 0) {
                            ilinkClient.sendTyping(baseurl, botToken, userId);
                        }
                        String newCtx = ilinkClient.sendText(baseurl, botToken, userId, part, contextToken);
                        if (newCtx != null && !newCtx.isBlank()) {
                            contextToken = newCtx;
                        }
                    }
                    bindingRepo.updateCursors(userId, contextToken, null);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("轮询异常: user={}, msg={}，10s 后重试", userId, e.getMessage());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("轮询线程退出: {}", userId);
    }
}
