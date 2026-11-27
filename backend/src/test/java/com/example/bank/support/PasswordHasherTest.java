package com.example.bank.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** パスワードのハッシュ化・照合・ポリシー、監査ログ用の SHA-256。 */
class PasswordHasherTest {
    @Test
    void hashHasFormatAndRandomSalt() {
        String a = PasswordHasher.hash("BankDemo#2026");
        String b = PasswordHasher.hash("BankDemo#2026");
        assertTrue(a.startsWith("pbkdf2_sha256$" + PasswordHasher.ITERATIONS + "$"), a);
        assertEquals(4, a.split("\\$").length);
        assertNotEquals(a, b);
    }

    @Test
    void verifyAcceptsOnlyTheRightPassword() {
        String stored = PasswordHasher.hash("BankDemo#2026");
        assertTrue(PasswordHasher.verify("BankDemo#2026", stored));
        assertFalse(PasswordHasher.verify("BankDemo#2027", stored));
    }

    @Test
    void verifyFailsForMissingUserAndMalformedHash() {
        // 利用者がいなくても計算はするが、必ず失敗する
        assertFalse(PasswordHasher.verify("dummy-password-for-timing", null));
        assertFalse(PasswordHasher.verify("x", "plain-text"));
        assertFalse(PasswordHasher.verify("x", "md5$1$a$b"));
    }

    @Test
    void verifyUsesTheStoredIterationCount() {
        // 反復回数を変えた後でも、古いハッシュ（保存された回数）で照合できる
        String stored = PasswordHasher.hash("BankDemo#2026");
        String[] p = stored.split("\\$");
        assertTrue(PasswordHasher.verify("BankDemo#2026", p[0] + "$" + p[1] + "$" + p[2] + "$" + p[3]));
        assertFalse(PasswordHasher.verify("BankDemo#2026", p[0] + "$1000$" + p[2] + "$" + p[3]));
    }

    @Test
    void policy() {
        assertTrue(PasswordHasher.meetsPolicy("abcdefghi1"));
        assertFalse(PasswordHasher.meetsPolicy("abcdefgh1"));          // 9 文字
        assertFalse(PasswordHasher.meetsPolicy("abcdefghij"));         // 数字なし
        assertFalse(PasswordHasher.meetsPolicy("1234567890"));         // 英字なし
        assertFalse(PasswordHasher.meetsPolicy("abcdefghi 1"));        // 空白
        assertFalse(PasswordHasher.meetsPolicy("abcdefghiあ1"));       // ASCII 以外
        assertFalse(PasswordHasher.meetsPolicy(null));
        assertFalse(PasswordHasher.meetsPolicy(new String(new char[65]).replace('\0', 'a') + "1"));
    }

    @Test
    void randomPasswordMeetsPolicyAndAvoidsConfusingCharacters() {
        for (int i = 0; i < 50; i++) {
            String p = PasswordHasher.randomPassword(12);
            assertEquals(12, p.length());
            assertTrue(PasswordHasher.meetsPolicy(p), p);
            assertFalse(p.matches(".*[0O1lI].*"), p);
        }
    }

    @Test
    void sha256Hex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PasswordHasher.sha256Hex(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PasswordHasher.sha256Hex("abc"));
    }
}
