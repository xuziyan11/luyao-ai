package com.companion.controller;

import com.companion.service.AccountService;
import com.companion.service.AdminService;
import com.companion.service.ConfigService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 API：仅经管理端口（默认 8084）+ 管理会话可访问（见 AdminAuthFilter）。
 * 覆盖四大模块：用户管理、参数动态配置、日志与监控、资源与额度。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final ConfigService configService;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final VoiceCallWebSocketHandler voiceCallWebSocketHandler;
    private final AccountService accountService;

    // 管理后台登录密码：生产环境必须通过环境变量 ADMIN_PASSWORD 注入（见 application.yml.template）。
    // 此处不提供默认密码，缺失该配置时应用启动即报错，避免弱口令上线。
    @Value("${companion.admin.password}")
    private String adminPassword;

    /** 允许后台在线修改的配置键白名单（防止任意键注入）。 */
    private static final List<String> EDITABLE_KEYS = List.of(
            "prompt.custom_persona",
            "deepseek.temperature", "deepseek.top_p", "deepseek.max_tokens",
            "tts.default_voice", "tts.speed", "tts.presets",
            "alert.fail_rate_pct"
    );

    public AdminController(AdminService adminService, ConfigService configService,
                           ChatWebSocketHandler chatWebSocketHandler,
                           VoiceCallWebSocketHandler voiceCallWebSocketHandler,
                           AccountService accountService) {
        this.adminService = adminService;
        this.configService = configService;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.voiceCallWebSocketHandler = voiceCallWebSocketHandler;
        this.accountService = accountService;
    }

    // ---------- 管理登录 ----------

    @PostMapping("/login")
    public Map<String, Object> login(@RequestParam String password, HttpSession session) {
        if (!adminPassword.equals(password)) {
            return Map.of("ok", false, "message", "管理密码错误");
        }
        session.setAttribute("adminAuthed", Boolean.TRUE);
        log.info("管理后台登录成功");
        return Map.of("ok", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/check")
    public Map<String, Object> check() {
        return Map.of("ok", true);
    }

    // ---------- 概览 ----------

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        int onlineTotal = chatWebSocketHandler.onlineCount() + voiceCallWebSocketHandler.onlineCount();
        Map<String, Object> m = adminService.overview(onlineTotal);
        m.put("alerts", adminService.alerts());
        m.put("chatOnline", chatWebSocketHandler.onlineCount());
        m.put("voiceOnline", voiceCallWebSocketHandler.onlineCount());
        return m;
    }

    // ---------- 模块一：用户管理 ----------

    @GetMapping("/users")
    public Map<String, Object> users() {
        return Map.of("ok", true, "users", adminService.listUsers());
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestParam String phone,
                                           @RequestParam String password,
                                           HttpSession session) {
        // 参数校验：手机号 11 位、密码 6-64 位
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            return Map.of("ok", false, "message", "手机号格式不正确（11 位数字）");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            return Map.of("ok", false, "message", "密码长度需 6-64 位");
        }
        AccountService.AccountInfo acc = accountService.createWithPassword(phone.trim(), password);
        if (acc == null) {
            return Map.of("ok", false, "message", "该手机号已存在");
        }
        log.info("管理操作: 新建用户 accountId={} phone={}", acc.id(), AccountService.maskPhone(phone));
        return Map.of("ok", true, "id", acc.id(), "message", "用户创建成功");
    }

    @PostMapping("/users/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable long id,
                                             @RequestParam String password,
                                             HttpSession session) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            return Map.of("ok", false, "message", "密码长度需 6-64 位");
        }
        AccountService.AccountInfo acc = accountService.findById(id);
        if (acc == null) {
            return Map.of("ok", false, "message", "账号不存在");
        }
        accountService.setPassword(id, password);
        log.info("管理操作: 重置密码 accountId={}", id);
        return Map.of("ok", true, "message", "密码已重置");
    }

    @PostMapping("/users/{id}/status")
    public Map<String, Object> setStatus(@PathVariable long id, @RequestParam boolean disabled) {
        adminService.setAccountStatus(id, disabled);
        return Map.of("ok", true);
    }

    /** 重置登录：为手机号账号签发一次性恢复码（10 分钟内可用于登录页验证码栏）。 */
    @PostMapping("/users/{id}/recovery-code")
    public Map<String, Object> recoveryCode(@PathVariable long id) {
        String code = adminService.issueRecoveryCode(id);
        if (code == null) {
            return Map.of("ok", false, "message", "该账号不是手机号账号，无法签发恢复码");
        }
        return Map.of("ok", true, "code", code, "message", "恢复码 10 分钟内有效，仅可使用一次");
    }

    // ---------- 模块二：参数动态配置 ----------

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> resp = new HashMap<>();
        for (String key : EDITABLE_KEYS) {
            resp.put(key, configService.get(key, ""));
        }
        return resp;
    }

    /** 在线修改配置：立即写库并刷新缓存，主站 5 秒内生效，无需重启。 */
    @PostMapping("/config")
    public Map<String, Object> setConfig(@RequestParam Map<String, String> params) {
        Map<String, String> applied = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (EDITABLE_KEYS.contains(e.getKey())) {
                configService.set(e.getKey(), e.getValue());
                applied.put(e.getKey(), e.getValue());
            }
        }
        log.info("管理操作: 更新动态配置 {}", applied.keySet());
        return Map.of("ok", true, "applied", applied.keySet());
    }

    // ---------- 模块三：日志与监控 ----------

    @GetMapping("/logs/chat")
    public Map<String, Object> chatLogs(@RequestParam long accountId,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return adminService.chatLogs(accountId, Math.max(1, page), Math.min(200, Math.max(1, size)));
    }

    @GetMapping("/logs/api")
    public Map<String, Object> apiLogs(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) Boolean ok,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return adminService.apiLogs(type, ok, Math.max(1, page), Math.min(200, Math.max(1, size)));
    }

    /** WebSocket 在线连接实时监控（聊天 + 语音通话）。 */
    @GetMapping("/monitor/ws")
    public Map<String, Object> wsMonitor() {
        List<Map<String, Object>> chatUsers = chatWebSocketHandler.onlineUsers();
        List<Map<String, Object>> voiceUsers = voiceCallWebSocketHandler.onlineUsers();
        // 给聊天连接打上 type 标签，方便前端区分
        chatUsers.forEach(u -> u.putIfAbsent("type", "chat"));
        List<Map<String, Object>> all = new java.util.ArrayList<>();
        all.addAll(chatUsers);
        all.addAll(voiceUsers);
        all.sort((a, b) -> Long.compare((Long) b.get("connectedAt"), (Long) a.get("connectedAt")));
        return Map.of("ok", true, "count", all.size(), "users", all);
    }

    // ---------- 模块四：资源与额度 ----------

    @GetMapping("/stats/tokens")
    public Map<String, Object> tokenStats(@RequestParam(defaultValue = "week") String range) {
        return adminService.tokenStats(range);
    }

    @GetMapping("/stats/latency")
    public Map<String, Object> latencyStats() {
        return Map.of("ok", true, "stats", adminService.latencyStats());
    }

    @GetMapping("/monitor/system")
    public Map<String, Object> systemMetrics() {
        return adminService.systemMetrics();
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        return Map.of("ok", true, "alerts", adminService.alerts());
    }
}
