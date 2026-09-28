package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短信验证码服务。
 * <p>
 * 当前为开发模式实现：验证码生成后打印到服务端日志，并随接口返回（devCode），
 * 方便在未接入短信渠道时完成登录联调。
 * 接入真实短信渠道（阿里云 / 腾讯云短信）时，在 sendRealSms() 中调用对应 API，
 * 并把 companion.auth.dev-mode 设为 false 即可，其余逻辑不变。
 */
@Slf4j
@Service
public class SmsService {

    public record SendResult(boolean ok, String message, String devCode) {
    }

    private record CodeEntry(String code, long expireAt, long lastSentAt, int attempts) {
    }

    private static final long CODE_TTL_MS = 5 * 60_000L;
    private static final long RESEND_INTERVAL_MS = 60_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Value("${companion.auth.dev-mode:true}")
    private boolean devMode;

    /** 发送验证码（60 秒内不允许重复发送）。 */
    public SendResult send(String phone) {
        long now = System.currentTimeMillis();
        CodeEntry prev = codes.get(phone);
        if (prev != null && now - prev.lastSentAt() < RESEND_INTERVAL_MS) {
            long wait = (RESEND_INTERVAL_MS - (now - prev.lastSentAt())) / 1000 + 1;
            return new SendResult(false, "发送太频繁，请 " + wait + " 秒后再试", null);
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        codes.put(phone, new CodeEntry(code, now + CODE_TTL_MS, now, 0));
        sendRealSms(phone, code);
        return new SendResult(true, "验证码已发送，5 分钟内有效", devMode ? code : null);
    }

    /** 校验验证码：5 分钟有效，最多试 5 次，成功后一次性作废。 */
    public boolean verify(String phone, String code) {
        CodeEntry entry = codes.get(phone);
        if (entry == null || System.currentTimeMillis() > entry.expireAt()) {
            return false;
        }
        if (entry.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        if (!entry.code().equals(code)) {
            codes.put(phone, new CodeEntry(entry.code(), entry.expireAt(), entry.lastSentAt(), entry.attempts() + 1));
            return false;
        }
        codes.remove(phone);
        return true;
    }

    /**
     * TODO: 真实短信发送。接入时替换为阿里云 / 腾讯云短信 API 调用，
     * 签名与模板审核通过后即可对外发送。
     */
    private void sendRealSms(String phone, String code) {
        log.info("[短信验证码] 手机号 {} 的验证码是 {}（5 分钟内有效）", phone, code);
    }
}
