package com.example.bank.action;

import com.example.bank.domain.LoginUser;
import com.example.bank.service.DepositProductService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 預金商品の API：定期預金・通知預金・外貨の入力の検証、顧客は本人の口座だけ、冪等キー。 */
class DepositActionTest extends ActionTestBase {
    private DepositAction deposit(LoginUser user) {
        return as(user, new DepositAction(new DepositProductService(() -> db, clock)));
    }

    @Test
    void timeDepositRatesAndOpen() {
        DepositAction rates = deposit(YAMADA);
        assertEquals("success", rates.timeRates());
        assertEquals(5, ((List<?>) data(rates)).size());

        DepositAction open = deposit(YAMADA);
        String k = key();
        open.setSourceAccountNo("1000002");
        open.setAmount("1000000");
        open.setTermMonths("12");
        open.setInstruction("PAYOUT");
        open.setIdempotencyKey(k);
        assertEquals("success", open.timeOpen());
        assertEquals(k, open.getRequestKey());
        assertEquals("1000002 1000000", open.getAuditTarget());
        String no = (String) ((Map<?, ?>) data(open)).get("accountNo");

        DepositAction get = deposit(YAMADA);
        get.setAccountNo(no);
        assertEquals("success", get.timeGet());
    }

    @Test
    void timeDepositInputAndScope() {
        DepositAction badInstruction = deposit(YAMADA);
        badInstruction.setSourceAccountNo("1000002");
        badInstruction.setAmount("100000");
        badInstruction.setTermMonths("12");
        badInstruction.setInstruction("KEEP");
        badInstruction.setIdempotencyKey(key());
        assertEquals("badRequest", badInstruction.timeOpen());

        DepositAction others = deposit(YAMADA);
        others.setSourceAccountNo("1000003");
        others.setAmount("100000");
        others.setTermMonths("12");
        others.setInstruction("PAYOUT");
        others.setIdempotencyKey(key());
        assertEquals("notFound", others.timeOpen());
    }

    @Test
    void noticesAreScoped() {
        DepositAction other = deposit(YAMADA);
        other.setAccountNo("1000003");
        assertEquals("notFound", other.notices());
        DepositAction own = deposit(YAMADA);
        own.setAccountNo("1000001");
        assertEquals("success", own.notices());
        DepositAction badId = deposit(TELLER);
        badId.setAccountNo("1000001");
        badId.setNoticeId("x");
        assertEquals("badRequest", badId.noticeCancel());
    }

    @Test
    void fxRatesUpdateAndExchangeValidation() {
        DepositAction update = deposit(SUPERVISOR);
        update.setCurrency("USD");
        update.setTtm("150.00");
        update.setSpread("1.00");
        assertEquals("success", update.fxRateUpdate());
        DepositAction badCurrency = deposit(SUPERVISOR);
        badCurrency.setCurrency("GBP");
        badCurrency.setTtm("1");
        badCurrency.setSpread("0");
        assertEquals("badRequest", badCurrency.fxRateUpdate());

        DepositAction badAmount = deposit(YAMADA);
        badAmount.setFromAccountNo("1000001");
        badAmount.setToAccountNo("1000002");
        badAmount.setAmount("1.234");
        badAmount.setIdempotencyKey(key());
        assertEquals("badRequest", badAmount.fxExchange());

        DepositAction rates = deposit(YAMADA);
        assertEquals("success", rates.fxRates());
        Map<?, ?> usd = ((List<?>) data(rates)).stream().map(r -> (Map<?, ?>) r).filter(r -> "USD".equals(r.get("currency")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(0, new BigDecimal("150.00").compareTo(new BigDecimal(usd.get("ttm").toString())));
    }
}
