import java.security.SecureRandom;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 一次性工具：生成 AccountService 使用的 PBKDF2 密码哈希。
 * 用法: java GenHash <明文密码>
 * 输出: pbkdf2$31000$<hex盐>$<hex哈希>
 */
public class GenHash {
    private static final int ITERATIONS = 31000;
    private static final int KEY_BITS = 256;

    public static void main(String[] args) throws Exception {
        String password = args.length > 0 ? args[0] : "061110";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        System.out.println("pbkdf2$" + ITERATIONS + "$" + toHex(salt) + "$" + toHex(hash));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
