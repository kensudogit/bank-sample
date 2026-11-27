package com.example.bank.testsupport;

import com.opensymphony.xwork2.ActionContext;
import com.opensymphony.xwork2.config.ConfigurationManager;
import com.opensymphony.xwork2.util.XWorkTestCaseHelper;
import org.apache.struts2.ServletActionContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Action を Struts の外で動かすための最小限の Struts コンテナ（struts2-core 同梱の XWorkTestCaseHelper を使う）。
 * getText などは ActionContext を必要とするため、テストの前に start、後に stop を呼ぶ。
 * メッセージの束は読み込まないので、getText はキーをそのまま返す（テストはコードと結果名で確かめる）。
 */
public final class StrutsTestSupport {
    private final ConfigurationManager configuration;
    private final Map<String, String> responseHeaders = new HashMap<>();

    private StrutsTestSupport(ConfigurationManager configuration) { this.configuration = configuration; }

    /** @return コンテナを起動して ActionContext をこのスレッドに結び付けたもの */
    public static StrutsTestSupport start() {
        try {
            return new StrutsTestSupport(XWorkTestCaseHelper.setUp());
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the Struts test container", e);
        }
    }

    /** 要求と応答を ActionContext に設定する（ServletActionContext.getRequest() が返す値）。 */
    public StrutsTestSupport with(HttpServletRequest request) {
        ServletActionContext.setRequest(request);
        ServletActionContext.setResponse(FakeHttp.response(responseHeaders));
        return this;
    }

    /** @return 応答に設定されたヘッダー（ステータスは ":status"） */
    public Map<String, String> responseHeaders() { return responseHeaders; }

    /** @return 現在の応答 */
    public HttpServletResponse response() { return ServletActionContext.getResponse(); }

    /** コンテナを止め、ActionContext を外す。 */
    public void stop() {
        try {
            XWorkTestCaseHelper.tearDown(configuration);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            ActionContext.clear();
        }
    }
}
