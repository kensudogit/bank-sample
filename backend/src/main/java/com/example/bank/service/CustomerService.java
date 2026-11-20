package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.NewCustomer;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
import com.example.bank.repository.OperationRequestRepository;
import com.example.bank.repository.TransactionRepository;
import com.example.bank.support.DataSources;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.function.Supplier;

/**
 * 口座開設と本人確認（KYC）。
 * <ol>
 *   <li>申込（顧客登録）: 本人確認書類の種別と番号（下 4 桁のみ保存）を受け付け、KYC 状態は PENDING</li>
 *   <li>本人確認: 担当者が書類を確認し VERIFIED（確認済）または REJECTED（謝絶）を記録</li>
 *   <li>口座開設: VERIFIED の顧客のみ。同じ種別の口座は 1 顧客 1 口座まで</li>
 * </ol>
 */
public class CustomerService {
    /** 申込可能な年齢（未成年は親権者の同意手続きが必要なため本サンプルでは対象外） */
    public static final int MIN_AGE = 18;

    private static final CustomerService DEFAULT = new CustomerService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final CustomerRepository customers = new CustomerRepository();
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();

    public CustomerService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public static CustomerService getDefault() { return DEFAULT; }

    public List<Customer> listCustomers() {
        return Transactions.readOnly(dataSource.get(), customers::findAll);
    }

    /** 顧客と保有口座。 */
    public Customer getCustomer(String customerId) {
        return Transactions.readOnly(dataSource.get(), c ->
                findCustomer(c, customerId).withAccounts(accounts.findByCustomer(c, customerId)));
    }

    /** 口座開設の申込（顧客登録）。本人確認は未了（PENDING）で登録される。 */
    public Customer register(NewCustomer input, String idempotencyKey) {
        LocalDate today = LocalDate.now(clock);
        if (input.birthDate.isAfter(today)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if (Period.between(input.birthDate, today).getYears() < MIN_AGE) {
            throw new BankException(ErrorCode.AGE_RESTRICTION, String.valueOf(MIN_AGE));
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "CUSTOMER", now, requests);
            // 名寄せ: 同じカナ氏名・生年月日の顧客（謝絶済みを除く）がいれば既存顧客として扱う
            if (customers.existsByKanaAndBirthDate(c, input.nameKana, input.birthDate)) {
                throw new BankException(ErrorCode.CUSTOMER_ALREADY_EXISTS);
            }
            String customerId = customers.nextCustomerId(c);
            customers.insert(c, customerId, input, now);
            return findCustomer(c, customerId);
        });
    }

    /**
     * 本人確認の結果を記録する。
     *
     * @param decision VERIFIED（確認済）または REJECTED（謝絶）
     */
    public Customer decideKyc(String customerId, String decision, String note) {
        if (!"VERIFIED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            Customer customer = customers.lockById(c, customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            if (!"PENDING".equals(customer.getKycStatus())) {
                throw new BankException(ErrorCode.KYC_ALREADY_DECIDED, customerId);
            }
            customers.decideKyc(c, customerId, decision, note, LocalDateTime.now(clock));
            return findCustomer(c, customerId);
        });
    }

    /**
     * 口座開設（本人確認済みの顧客のみ）。
     *
     * @param initialDeposit 開設時の入金額（0 は入金なし）
     */
    public Account openAccount(String customerId, String accountType, BigDecimal initialDeposit, String idempotencyKey) {
        if (!"ORDINARY".equals(accountType) && !"SAVINGS".equals(accountType)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        BigDecimal deposit = initialDeposit == null ? BigDecimal.ZERO : initialDeposit;
        if (deposit.signum() > 0) BankService.checkAmount(deposit);
        else if (deposit.signum() < 0) throw new BankException(ErrorCode.VALIDATION_ERROR);

        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "OPEN", now, requests);
            // Lock the customer row so two concurrent openings cannot both pass the one-per-type check
            Customer customer = customers.lockById(c, customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            if (!customer.isVerified()) throw new BankException(ErrorCode.KYC_NOT_VERIFIED, customerId);
            if (accounts.countOpenByCustomerAndType(c, customerId, accountType) > 0) {
                throw new BankException(ErrorCode.ACCOUNT_TYPE_LIMIT);
            }
            String accountNo = accounts.nextAccountNo(c);
            accounts.insert(c, accountNo, customerId, accountType, deposit, now);
            if (deposit.signum() > 0) {
                transactions.insert(c, accountNo, TxType.DEPOSIT, deposit, deposit, null, "口座開設時入金",
                        idempotencyKey, now);
            }
            return accounts.findById(c, accountNo).orElseThrow(() -> new SQLException("account not created"));
        });
    }

    private Customer findCustomer(Connection c, String customerId) throws SQLException {
        return customers.findById(c, customerId)
                .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
    }
}
