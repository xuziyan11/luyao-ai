package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 账号体系：手机号 / 微信 openid 登录，首次登录自动建号，
 * 并为每个账号创建独立的聊天历史、长期记忆、好感度表，
 * 保证不同账号之间的数据物理隔离、互不互通。
 */
@Slf4j
@Service
public class AccountService {

    /** 账号概要信息。sessionId 即前端聊天使用的会话标识，含防冒用 token。 */
    public record AccountInfo(long id, String loginType, String displayName, String sessionId) {
    }

    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 手机号登录：已有账号直接返回，否则创建账号 + 独立数据表。 */
    public AccountInfo findOrCreateByPhone(String phone) {
        List<AccountInfo> found = findBy("phone", phone);
        if (!found.isEmpty()) {
            touch(found.get(0).id());
            return found.get(0);
        }
        return create("phone", phone, maskPhone(phone));
    }

    /** 管理后台新建用户：按手机号创建账号并设置初始密码。手机号已存在则返回 null。 */
    public AccountInfo createWithPassword(String phone, String rawPassword) {
        if (!findBy("phone", phone).isEmpty()) {
            return null;
        }
        AccountInfo acc = create("phone", phone, maskPhone(phone));
        setPassword(acc.id(), rawPassword);
        return acc;
    }

    /** 微信登录：按 openid 查找或创建。 */
    public AccountInfo findOrCreateByWechat(String openid, String nickname) {
        List<AccountInfo> found = findBy("wechat_openid", openid);
        if (!found.isEmpty()) {
            touch(found.get(0).id());
            return found.get(0);
        }
        String name = (nickname == null || nickname.isBlank()) ? "微信用户" : nickname;
        return create("wechat", openid, name);
    }

    public AccountInfo findById(long id) {
        List<AccountInfo> found = jdbc.query(
                "SELECT id, login_type, nickname, chat_token FROM account WHERE id = ?",
                (rs, n) -> new AccountInfo(rs.getLong("id"), rs.getString("login_type"),
                        rs.getString("nickname"),
                        "a" + rs.getLong("id") + "_" + rs.getString("chat_token")),
                id);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<AccountInfo> findBy(String column, String value) {
        return jdbc.query(
                "SELECT id, login_type, nickname, chat_token FROM account WHERE " + column + " = ?",
                (rs, n) -> new AccountInfo(rs.getLong("id"), rs.getString("login_type"),
                        rs.getString("nickname"),
                        "a" + rs.getLong("id") + "_" + rs.getString("chat_token")),
                value);
    }

    private AccountInfo create(String type, String principal, String displayName) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String principalColumn = "phone".equals(type) ? "phone" : "wechat_openid";
        jdbc.update("INSERT INTO account(login_type, " + principalColumn + ", nickname, chat_token) VALUES (?, ?, ?, ?)",
                type, principal, displayName, token);
        Long id = jdbc.query("SELECT id FROM account WHERE " + principalColumn + " = ?",
                rs -> rs.next() ? rs.getLong(1) : null, principal);
        if (id == null) {
            throw new IllegalStateException("账号创建后读取失败");
        }
        createAccountTables(id);
        log.info("新账号创建: id={}, type={}, name={}, 独立数据表 chat_history_a{}/long_term_memory_a{}/affinity_a{} 已建立",
                id, type, displayName, id, id, id);
        return new AccountInfo(id, type, displayName, "a" + id + "_" + token);
    }

    /** 为账号创建独立数据表（已存在则跳过），结构与主表一致。 */
    public void createAccountTables(long accountId) {
        String s = "_a" + accountId;
        jdbc.execute("CREATE TABLE IF NOT EXISTS chat_history" + s + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "session_id VARCHAR(64) NOT NULL,"
                + "role VARCHAR(16) NOT NULL,"
                + "content TEXT NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS long_term_memory" + s + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "session_id VARCHAR(64) NOT NULL,"
                + "fact TEXT NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS affinity" + s + " ("
                + "session_id VARCHAR(64) PRIMARY KEY,"
                + "affinity_value INT NOT NULL DEFAULT 0,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS diary" + s + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "diary_date DATE NOT NULL,"
                + "weather VARCHAR(60) NULL,"
                + "mood VARCHAR(20) NULL,"
                + "mood_curve VARCHAR(120) NULL,"
                + "events TEXT NULL,"
                + "blessing VARCHAR(255) NULL,"
                + "content TEXT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "UNIQUE KEY uq_diary_date (diary_date))");
    }

    private void touch(long id) {
        jdbc.update("UPDATE account SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    // ---------- 账号状态（管理后台禁用/启用，status 列由 DbMigration 兜底添加） ----------

    /** 账号是否被禁用。查询失败时放行（不影响主流程）。 */
    public boolean isDisabled(long accountId) {
        try {
            String status = jdbc.query("SELECT status FROM account WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : "active", accountId);
            return "disabled".equalsIgnoreCase(status);
        } catch (Exception e) {
            return false;
        }
    }

    public void setStatus(long accountId, String status) {
        jdbc.update("UPDATE account SET status = ? WHERE id = ?", status, accountId);
    }

    // ---------- 账号密码（PBKDF2 哈希，JDK 自带，无额外依赖） ----------

    private static final int PBKDF2_ITERATIONS = 31000;
    private static final int PBKDF2_KEY_BITS = 256;

    /** 手机号 + 密码校验：返回账号信息；账号不存在 / 未设密码 / 密码错误均返回 null。 */
    public AccountInfo verifyPassword(String phone, String rawPassword) {
        List<AccountInfo> found = findBy("phone", phone);
        if (found.isEmpty()) {
            return null;
        }
        AccountInfo acc = found.get(0);
        String stored = passwordHashOf(acc.id());
        if (stored == null || !pbkdf2Verify(rawPassword, stored)) {
            return null;
        }
        touch(acc.id());
        return acc;
    }

    /** 该账号是否已设置过密码。 */
    public boolean hasPassword(long accountId) {
        return passwordHashOf(accountId) != null;
    }

    /** 设置 / 修改密码（覆盖旧密码）。 */
    public void setPassword(long accountId, String rawPassword) {
        jdbc.update("UPDATE account SET password_hash = ? WHERE id = ?",
                pbkdf2Hash(rawPassword), accountId);
    }

    private String passwordHashOf(long accountId) {
        try {
            return jdbc.query("SELECT password_hash FROM account WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, accountId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 哈希格式：pbkdf2$iterations$saltHex$hashHex */
    private static String pbkdf2Hash(String rawPassword) {
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + toHex(salt) + "$" + toHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    private static boolean pbkdf2Verify(String rawPassword, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = fromHex(parts[2]);
            byte[] expected = fromHex(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(password, salt, iterations, keyBits);
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String maskPhone(String phone) {
        if (phone != null && phone.length() == 11) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        return phone;
    }
}
