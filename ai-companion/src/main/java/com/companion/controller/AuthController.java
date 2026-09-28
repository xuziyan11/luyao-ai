package com.companion.controller;

import com.companion.service.AccountService;
import com.companion.service.SmsService;
import com.companion.service.WechatOAuthService;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 账号认证：手机验证码登录、微信扫码登录（配置就绪即可用）、会话查询与退出。
 * 与管理后台联动：禁用账号拒绝登录；管理员签发的一次性恢复码可在验证码栏使用。
 */
@Controller
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final SmsService smsService;
    private final AccountService accountService;
    private final WechatOAuthService wechatOAuthService;
    private final com.companion.service.AdminService adminService;

    @Value("${companion.auth.dev-mode:true}")
    private boolean devMode;

    public AuthController(SmsService smsService, AccountService accountService,
                          WechatOAuthService wechatOAuthService,
                          com.companion.service.AdminService adminService) {
        this.smsService = smsService;
        this.accountService = accountService;
        this.wechatOAuthService = wechatOAuthService;
        this.adminService = adminService;
    }

    // ---------- 手机验证码 ----------

    @PostMapping("/sms/send")
    @ResponseBody
    public Map<String, Object> sendSms(@RequestParam String phone) {
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            return Map.of("ok", false, "message", "手机号格式不正确");
        }
        SmsService.SendResult r = smsService.send(phone.trim());
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", r.ok());
        resp.put("message", r.message());
        if (r.devCode() != null) {
            resp.put("devCode", r.devCode());
        }
        return resp;
    }

    @PostMapping("/sms/login")
    @ResponseBody
    public Map<String, Object> smsLogin(@RequestParam String phone, @RequestParam String code,
                                        HttpSession session) {
        // 短信验证码或管理员签发的一次性恢复码均可登录（用户忘记验证码时管理员快速恢复）
        boolean codeOk = phone != null && code != null
                && (smsService.verify(phone.trim(), code.trim())
                    || adminService.verifyRecoveryCode(phone.trim(), code.trim()));
        if (!codeOk) {
            return Map.of("ok", false, "message", "验证码错误或已过期");
        }
        AccountService.AccountInfo acc = accountService.findOrCreateByPhone(phone.trim());
        if (accountService.isDisabled(acc.id())) {
            return Map.of("ok", false, "message", "账号已被禁用，如有疑问请联系管理员");
        }
        bindSession(session, acc);
        log.info("手机号登录成功: accountId={}, phone={}", acc.id(), AccountService.maskPhone(phone));
        return Map.of("ok", true, "account", accountPayload(acc));
    }

    // ---------- 账号密码 ----------

    /** 手机号 + 密码登录（密码由验证码登录后设置）。 */
    @PostMapping("/password/login")
    @ResponseBody
    public Map<String, Object> passwordLogin(@RequestParam String phone, @RequestParam String password,
                                             HttpSession session) {
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            return Map.of("ok", false, "message", "手机号格式不正确");
        }
        if (password == null || password.isEmpty()) {
            return Map.of("ok", false, "message", "请输入密码");
        }
        AccountService.AccountInfo acc = accountService.verifyPassword(phone.trim(), password);
        if (acc == null) {
            return Map.of("ok", false, "message", "手机号或密码错误（未设置密码请先用验证码登录）");
        }
        if (accountService.isDisabled(acc.id())) {
            return Map.of("ok", false, "message", "账号已被禁用，如有疑问请联系管理员");
        }
        bindSession(session, acc);
        log.info("密码登录成功: accountId={}, phone={}", acc.id(), AccountService.maskPhone(phone));
        return Map.of("ok", true, "account", accountPayload(acc));
    }

    /** 设置 / 修改密码：要求当前会话已登录（验证码登录后引导设置）。 */
    @PostMapping("/password/set")
    @ResponseBody
    public Map<String, Object> setPassword(@RequestParam String password, HttpSession session) {
        Long accountId = session == null ? null : (Long) session.getAttribute("accountId");
        if (accountId == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            return Map.of("ok", false, "message", "密码长度需为 6-64 位");
        }
        accountService.setPassword(accountId, password);
        log.info("账号密码已设置: accountId={}", accountId);
        return Map.of("ok", true, "message", "密码设置成功");
    }

    // ---------- 微信扫码 ----------

    /** 微信登录能力状态：configured=false 时前端展示说明而非扫码入口。 */
    @GetMapping("/wechat/status")
    @ResponseBody
    public Map<String, Object> wechatStatus() {
        return Map.of("configured", wechatOAuthService.isConfigured(), "devMode", devMode);
    }

    /** 跳转到微信扫码授权页（仅在配置完整时可用）。 */
    @GetMapping("/wechat/qrcode")
    public Object wechatQrcode(HttpSession session) {
        if (!wechatOAuthService.isConfigured()) {
            return new RedirectView("/login?wechat=unconfigured");
        }
        String state = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        session.setAttribute("wechatState", state);
        return new RedirectView(wechatOAuthService.buildAuthorizeUrl(state));
    }

    /** 微信授权回调：code 换 openid，建号/登录后进入聊天页。 */
    @GetMapping("/wechat/callback")
    public Object wechatCallback(@RequestParam(required = false) String code,
                                 @RequestParam(required = false) String state,
                                 HttpSession session) {
        if (code == null || state == null || !state.equals(session.getAttribute("wechatState"))) {
            return new RedirectView("/login?wechat=badstate");
        }
        session.removeAttribute("wechatState");
        WechatOAuthService.WechatUser user = wechatOAuthService.fetchUser(code);
        if (user == null) {
            return new RedirectView("/login?wechat=failed");
        }
        AccountService.AccountInfo acc = accountService.findOrCreateByWechat(user.openid(), user.nickname());
        if (accountService.isDisabled(acc.id())) {
            return new RedirectView("/login?wechat=disabled");
        }
        bindSession(session, acc);
        log.info("微信登录成功: accountId={}, nickname={}", acc.id(), acc.displayName());
        // 登录态仅存 sessionStorage，需经登录页 JS 写入后再进聊天页
        return new RedirectView("/login?wechat=ok");
    }

    /** 开发模式专用：模拟微信登录，用于无开放平台资质时体验完整账号流程。 */
    @PostMapping("/wechat/mock-login")
    @ResponseBody
    public Map<String, Object> wechatMockLogin(@RequestParam(required = false) String nickname,
                                               HttpSession session) {
        if (!devMode) {
            return Map.of("ok", false, "message", "当前为正式模式，请使用真实微信扫码登录");
        }
        String name = (nickname == null || nickname.isBlank()) ? "微信用户(测试)" : nickname.trim();
        String mockOpenid = "mock_" + Integer.toHexString(name.hashCode());
        AccountService.AccountInfo acc = accountService.findOrCreateByWechat(mockOpenid, name);
        if (accountService.isDisabled(acc.id())) {
            return Map.of("ok", false, "message", "账号已被禁用，如有疑问请联系管理员");
        }
        bindSession(session, acc);
        log.info("模拟微信登录: accountId={}, nickname={}", acc.id(), acc.displayName());
        return Map.of("ok", true, "account", accountPayload(acc));
    }

    // ---------- 会话 ----------

    /** 当前登录账号信息，前端启动时调用。禁用账号会话立即失效。 */
    @GetMapping("/me")
    @ResponseBody
    public Map<String, Object> me(HttpSession session) {
        Long accountId = session == null ? null : (Long) session.getAttribute("accountId");
        if (accountId == null) {
            return Map.of("loggedIn", false);
        }
        AccountService.AccountInfo acc = accountService.findById(accountId);
        if (acc == null) {
            return Map.of("loggedIn", false);
        }
        if (accountService.isDisabled(accountId)) {
            session.invalidate();
            return Map.of("loggedIn", false, "disabled", true);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("loggedIn", true);
        resp.put("account", accountPayload(acc));
        return resp;
    }

    @PostMapping("/logout")
    @ResponseBody
    public Map<String, Object> logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
        return Map.of("ok", true);
    }

    // ---------- 内部 ----------

    private void bindSession(HttpSession session, AccountService.AccountInfo acc) {
        session.setAttribute("accountId", acc.id());
        session.setAttribute("accountSessionId", acc.sessionId());
    }

    private Map<String, Object> accountPayload(AccountService.AccountInfo acc) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", acc.id());
        payload.put("type", acc.loginType());
        payload.put("displayName", acc.displayName());
        payload.put("sessionId", acc.sessionId());
        payload.put("hasPassword", accountService.hasPassword(acc.id()));
        return payload;
    }
}
