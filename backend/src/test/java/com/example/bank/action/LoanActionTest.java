package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.LoanService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 融資の API：申込（顧客は本人名義に置き換え）、一覧と照会の本人確認、審査と決裁の入力、存在しない融資。 */
class LoanActionTest extends ActionTestBase {
    private LoanAction loan(LoginUser user) {
        return as(user, new LoanAction(new LoanService(() -> db, clock)));
    }

    private LoanAction cardApplication(LoginUser user, String customerId) {
        LoanAction a = loan(user);
        a.setCustomerId(customerId);
        a.setProduct("CARD");
        a.setAmount("500000");
        a.setAnnualIncome("6000000");
        return a;
    }

    private static long id(BaseApiAction a, String key) {
        return new BigDecimal(((Map<?, ?>) data(a)).get(key).toString()).longValue();
    }

    @Test
    void customerApplicationIsAlwaysInTheirOwnName() {
        LoanAction a = cardApplication(YAMADA, "CU0002");
        assertEquals("success", a.apply());
        long appId = id(a, "applicationId");
        assertEquals("CU0001", new LoanService(() -> db, clock).applicationOwner(appId));
        assertEquals("CARD CU0002 500000", a.getAuditTarget());
    }

    @Test
    void listsAndLookupsAreScoped() {
        LoanAction mine = cardApplication(TELLER, "CU0002");
        mine.apply();
        long appId = id(mine, "applicationId");

        LoanAction yamadaList = loan(YAMADA);
        assertEquals("success", yamadaList.applications());
        assertTrue(((List<?>) data(yamadaList)).isEmpty());
        LoanAction yamadaGet = loan(YAMADA);
        yamadaGet.setApplicationId(String.valueOf(appId));
        assertEquals("notFound", yamadaGet.application());
        assertEquals("LOAN_NOT_FOUND", errorCode(yamadaGet));
        LoanAction tellerGet = loan(TELLER);
        tellerGet.setApplicationId(String.valueOf(appId));
        assertEquals("success", tellerGet.application());
    }

    @Test
    void screeningAndDecision() {
        LoanAction apply = cardApplication(TELLER, "CU0003");
        apply.apply();
        String appId = String.valueOf(id(apply, "applicationId"));
        LoanAction screen = loan(TELLER);
        screen.setApplicationId(appId);
        assertEquals("success", screen.screen());

        LoanAction self = loan(TELLER);
        self.setApplicationId(appId);
        self.setDecision("APPROVE");
        assertEquals("unprocessable", self.decide());
        assertEquals("SEGREGATION_OF_DUTIES", errorCode(self));
        LoanAction badDecision = loan(SUPERVISOR);
        badDecision.setApplicationId(appId);
        badDecision.setDecision("MAYBE");
        assertEquals("badRequest", badDecision.decide());
        LoanAction approve = loan(SUPERVISOR);
        approve.setApplicationId(appId);
        approve.setDecision("APPROVE");
        assertEquals("success", approve.decide());
    }

    @Test
    void inputValidation() {
        LoanAction noAmount = cardApplication(TELLER, "CU0003");
        noAmount.setAmount(null);
        assertEquals("badRequest", noAmount.apply());
        LoanAction badProduct = cardApplication(TELLER, "CU0003");
        badProduct.setProduct("CAR");
        assertEquals("badRequest", badProduct.apply());
        LoanAction badStatus = loan(TELLER);
        badStatus.setStatus("UNKNOWN");
        assertEquals("badRequest", badStatus.loans());
        LoanAction negative = loan(TELLER);
        negative.setLoanId("1");
        negative.setAmount("0");
        negative.setIdempotencyKey(key());
        assertEquals("badRequest", negative.repay());
    }

    @Test
    void unknownLoan() {
        LoanAction a = loan(TELLER);
        a.setLoanId("999999");
        assertEquals("notFound", a.loan());
        assertEquals("loan 999999", a.getAuditTarget());
    }
}
