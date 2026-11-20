package com.example.bank.action;

import com.example.bank.domain.BankException;
import com.example.bank.domain.TxType;
import com.example.bank.service.BankService;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/transactions?accountNo=1000001&from=2026-01-01&to=2026-03-31&types=DEPOSIT,WITHDRAWAL&page=1&size=50
 * 取引履歴の検索（期間・種別・ページ）。期首／期末残高と入出金合計を含む。
 */
public class TransactionsAction extends BaseApiAction {
    private String accountNo;
    private String from;
    private String to;
    private String types;
    private String page;
    private String size;

    private LocalDate fromDate;
    private LocalDate toDate;
    private List<TxType> typeList;

    public TransactionsAction() { this(BankService.getDefault()); }

    public TransactionsAction(BankService service) { super(service); }

    @Override
    public void validate() {
        accountNo = InputRules.trim(accountNo);
        if (!InputRules.isAccountNo(accountNo)) addFieldError("accountNo", getText("validation.accountNo"));
        fromDate = InputRules.toDate(InputRules.trim(from));
        toDate = InputRules.toDate(InputRules.trim(to));
        if (InputRules.emptyToNull(from) != null && !InputRules.isReasonableDate(fromDate)) {
            addFieldError("from", getText("validation.date"));
        }
        if (InputRules.emptyToNull(to) != null && !InputRules.isReasonableDate(toDate)) {
            addFieldError("to", getText("validation.date"));
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            addFieldError("to", getText("validation.dateRange"));
        }
        typeList = InputRules.toTxTypes(types);
        if (typeList == null) addFieldError("types", getText("validation.txTypes"));
        page = InputRules.emptyToNull(page) == null ? "1" : page.trim();
        size = InputRules.emptyToNull(size) == null ? "50" : size.trim();
        if (!InputRules.isPositiveInt(page)) addFieldError("page", getText("validation.page"));
        if (!InputRules.isPositiveInt(size) || Integer.parseInt(size) > BankService.MAX_PAGE_SIZE) {
            addFieldError("size", getText("validation.size"));
        }
    }

    @Override
    public String execute() {
        try {
            return ok(service.searchTransactions(accountNo, fromDate, toDate, typeList,
                    Integer.parseInt(page), Integer.parseInt(size)));
        } catch (BankException e) {
            return fail(e);
        }
    }

    @StrutsParameter public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    @StrutsParameter public void setFrom(String from) { this.from = from; }
    @StrutsParameter public void setTo(String to) { this.to = to; }
    @StrutsParameter public void setTypes(String types) { this.types = types; }
    @StrutsParameter public void setPage(String page) { this.page = page; }
    @StrutsParameter public void setSize(String size) { this.size = size; }
}
