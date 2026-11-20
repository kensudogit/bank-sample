package com.example.bank.support;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * パスワードのハッシュ化（PBKDF2-HMAC-SHA256、ソルト 16 バイト）。
 * 形式: {@code pbkdf2_sha256$<反復回数>$<salt base64>$<hash base64>}（反復回数を含むため将来の引き上げに対応できる）。
 */
public final class PasswordHasher {
    public static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String PREFIX = "pbkdf2_sha256";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 存在しない利用者でも同じ計算時間をかけ、利用者 ID の有無を応答時間から推測させないためのダミー */
    private static final String DUMMY = hash("dummy-password-for-timing");

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    /** 定数時間比較で照合する。stored が null（利用者なし）でも同等の計算を行う。 */
    public static boolean verify(String password, String stored) {
        String target = stored == null ? DUMMY : stored;
        String[] parts = target.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] expected = b64.decode(parts[3]);
        byte[] actual = pbkdf2(password, b64.decode(parts[2]), Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual) && stored != null;
    }

    /** 初期パスワード（紛らわしい文字 0/O/1/l/I を除く英数字 + 記号）。 */
    public static String randomPassword(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789#%+";
        StringBuilder sb = new StringBuilder(length);
        while (true) {
            sb.setLength(0);
            for (int i = 0; i < length; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
            if (meetsPolicy(sb.toString())) return sb.toString();
        }
    }

    /** パスワードポリシー: 10〜64 文字、英字と数字を各 1 文字以上含む。 */
    public static boolean meetsPolicy(String password) {
        if (password == null || password.length() < 10 || password.length() > 64) return false;
        boolean letter = false;
        boolean digit = false;
        for (char ch : password.toCharArray()) {
            if (ch < 0x21 || ch > 0x7e) return false;   // printable ASCII only
            if (Character.isLetter(ch)) letter = true;
            if (Character.isDigit(ch)) digit = true;
        }
        return letter && digit;
    }

    /** 監査ログのハッシュチェーン用 SHA-256（16 進小文字）。 */
    public static String sha256Hex(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }
}
