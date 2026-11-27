package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.TestClock;
import com.example.bank.service.TestDatabase;
import com.example.bank.testsupport.DefaultDataSource;
import com.example.bank.testsupport.FakeHttp;
import com.example.bank.testsupport.StrutsTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Action のテストの共通部分：Struts のテスト用コンテナ、テストごとの新しい DB、固定した時計（2026-11-20 10:00・金曜）。
 * Struts と同じ順で、setXxx（要求パラメーター）→ validate → 実行メソッドを呼ぶ。
 * validate でエラーになった場合、Struts は実行メソッドを呼ばずに 400 を返す（{@link #assertInvalid}）。
 */
abstract class ActionTestBase {
    static final LoginUser TELLER = new LoginUser("teller1", "窓口 一郎", LoginUser.TELLER, null, false);
    static final LoginUser SUPERVISOR = new LoginUser("supervisor1", "役席 一郎", LoginUser.SUPERVISOR, null, false);
    static final LoginUser SUPERVISOR2 = new LoginUser("supervisor2", "役席 二郎", LoginUser.SUPERVISOR, null, false);
    /** 顧客 山田 太郎（口座 1000001・1000002） */
    static final LoginUser YAMADA = new LoginUser("yamada", "山田 太郎", LoginUser.CUSTOMER, "CU0001", false);

    protected StrutsTestSupport struts;
    protected TestDatabase db;
    protected TestClock clock;

    @BeforeEach
    void startStruts() {
        struts = StrutsTestSupport.start();
        struts.with(FakeHttp.request().build());
        db = TestDatabase.create();
        clock = new TestClock(LocalDateTime.of(2026, 11, 20, 10, 0));
        // 本人の口座かの確認などで BankService.getDefault() を使う Action のため、既定のサービスもこの DB に向ける
        DefaultDataSource.use(db);
    }

    @AfterEach
    void stopStruts() {
        DefaultDataSource.clear();
        struts.stop();
    }

    static String key() { return UUID.randomUUID().toString(); }

    /** ApiGuardInterceptor と同じく、ログイン中の利用者を設定する。 */
    static <A extends BaseApiAction> A as(LoginUser user, A action) {
        action.setLoginUser(user);
        return action;
    }

    /** @return エラー応答のコード（成功なら null） */
    @SuppressWarnings("unchecked")
    static String errorCode(BaseApiAction a) {
        Map<String, Object> response = a.getResponse();
        assertNotNull(response, "no response");
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        return error == null ? null : (String) error.get("code");
    }

    /** @return 成功応答の data */
    static Object data(BaseApiAction a) {
        Map<String, Object> response = a.getResponse();
        assertNotNull(response, "no response");
        assertNull(response.get("error"), () -> "unexpected error: " + response.get("error"));
        return response.get("data");
    }

    /** 入力の検証で field がエラーになり、応答が 400（VALIDATION_ERROR）になることを確かめる。 */
    static void assertInvalid(BaseApiAction a, String field) {
        a.validate();
        assertTrue(a.getFieldErrors().containsKey(field), () -> "expected an error on " + field + " but got " + a.getFieldErrors());
        assertEquals("VALIDATION_ERROR", errorCode(a));
    }

    /** 入力の検証が通ることを確かめる。 */
    static void assertValid(BaseApiAction a) {
        a.validate();
        assertFalse(a.hasErrors(), () -> "unexpected validation errors: " + a.getFieldErrors() + " " + a.getActionErrors());
    }
}
