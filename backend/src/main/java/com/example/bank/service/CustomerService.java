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

    /**
     * @param dataSource 接続の取得元
     * @param clock      年齢判定・登録日時の取得元
     */
    public CustomerService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** @return アプリケーション既定のインスタンス */
    public static CustomerService getDefault() { return DEFAULT; }

    /**
     * 全顧客の一覧（KYC 状態つき）。
     *
     * @return 顧客一覧
     */
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
        return register(input, idempotencyKey, null);
    }

    /** @param registeredBy 申込を受け付けた行員（本人確認の判定者と同じ人にはできない） */
    public Customer register(NewCustomer input, String idempotencyKey, String registeredBy) {
        LocalDate today = LocalDate.now(clock);
        // 年齢は申込日時点で判定する（法人は設立日なので年齢制限の対象外）
        if (input.birthDate.isAfter(today)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if (!"CORPORATE".equals(input.customerType) && Period.between(input.birthDate, today).getYears() < MIN_AGE) {
            throw new BankException(ErrorCode.AGE_RESTRICTION, String.valueOf(MIN_AGE));
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            // 同じ申込の二重送信で顧客が 2 人登録されないよう、最初に冪等キーを登録する
            BankService.registerRequest(c, idempotencyKey, "CUSTOMER", now, requests);
            // 名寄せ: 同じカナ氏名・生年月日の顧客（謝絶済みを除く）がいれば既存顧客として扱う
            if (customers.existsByKanaAndBirthDate(c, input.nameKana, input.birthDate)) {
                throw new BankException(ErrorCode.CUSTOMER_ALREADY_EXISTS);
            }
            // 名寄せは SELECT による判定なので、同じ人の申込が完全に同時に来ると両方通る可能性がある（サンプルでは許容）
            String customerId = customers.nextCustomerId(c);
            customers.insert(c, customerId, input, registeredBy, now);
            return findCustomer(c, customerId);
        });
    }

    /**
     * 本人確認の結果を記録する。
     *
     * @param decision VERIFIED（確認済）または REJECTED（謝絶）
     */
    public Customer decideKyc(String customerId, String decision, String note) {
        return decideKyc(customerId, decision, note, null);
    }

    /**
     * @param decidedBy 判定する行員。申込を受け付けた行員と同一人物は不可（職務分離）
     */
    public Customer decideKyc(String customerId, String decision, String note, String decidedBy) {
        if (!"VERIFIED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        return Transactions.inTransaction(dataSource.get(), c -> {
            // 顧客行をロックして状態を確認する。同時に 2 人の行員が判定しても、後の判定は KYC_ALREADY_DECIDED になる
            Customer customer = customers.lockById(c, customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            if (!"PENDING".equals(customer.getKycStatus())) {
                throw new BankException(ErrorCode.KYC_ALREADY_DECIDED, customerId);
            }
            // 受付者と判定者を分け、1 人の行員だけで架空の顧客を登録・承認できないようにする（職務分離）
            if (decidedBy != null && decidedBy.equals(customer.getRegisteredBy())) {
                throw new BankException(ErrorCode.SEGREGATION_OF_DUTIES);
            }
            customers.decideKyc(c, customerId, decision, note, decidedBy, LocalDateTime.now(clock));
            return findCustomer(c, customerId);
        });
    }

    /**
     * 口座開設（本人確認済みの顧客のみ）。
     *
     * @param initialDeposit 開設時の入金額（0 は入金なし）
     */
    public Account openAccount(String customerId, String accountType, BigDecimal initialDeposit, String idempotencyKey) {
        return openAccount(customerId, accountType, "JPY", initialDeposit, idempotencyKey);
    }

    /**
     * 口座開設（本人確認済みの顧客のみ）。
     * <ul>
     *   <li>普通・貯蓄・通知: 同じ種別は 1 顧客 1 口座。通知預金は最低 50,000 円の預入が必要</li>
     *   <li>当座: 法人のみ（当座貸越は融資の契約で設定）</li>
     *   <li>外貨: 通貨（USD / EUR）ごとに 1 口座。残高 0 で開設し、円口座からの両替で入金する</li>
     *   <li>定期: 資金口座からの振替で作成する（{@link DepositProductService#createTimeDeposit}）</li>
     * </ul>
     *
     * @param initialDeposit 開設時の入金額（円口座のみ。0 は入金なし）
     */
    public Account openAccount(String customerId, String accountType, String currency, BigDecimal initialDeposit,
                               String idempotencyKey) {
        if (!OPENABLE_TYPES.contains(accountType)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        String ccy = "FOREIGN".equals(accountType) ? (currency == null ? "" : currency) : "JPY";
        if (!"JPY".equals(ccy) && !"USD".equals(ccy) && !"EUR".equals(ccy)) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if ("FOREIGN".equals(accountType) && "JPY".equals(ccy)) throw new BankException(ErrorCode.CURRENCY_MISMATCH, ccy);
        BigDecimal deposit = initialDeposit == null ? BigDecimal.ZERO : initialDeposit;
        // 入力チェックはトランザクションの前に済ませ、不正な要求で DB を待たせない
        if (deposit.signum() > 0) BankService.checkAmount(deposit);
        else if (deposit.signum() < 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
        if ("FOREIGN".equals(accountType) && deposit.signum() > 0) throw new BankException(ErrorCode.CURRENCY_MISMATCH, ccy);
        if ("NOTICE".equals(accountType) && deposit.compareTo(DepositProductService.NOTICE_MIN) < 0) {
            throw new BankException(ErrorCode.MINIMUM_AMOUNT, "50,000");
        }

        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "OPEN", now, requests);
            // 「同じ種別の口座は 1 つまで」は件数を数えて判定するため、顧客行をロックして同時開設の両方が通らないようにする
            // Lock the customer row so two concurrent openings cannot both pass the one-per-type check
            Customer customer = customers.lockById(c, customerId)
                    .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
            if (!customer.isVerified()) throw new BankException(ErrorCode.KYC_NOT_VERIFIED, customerId);
            if ("CHECKING".equals(accountType) && !customer.isCorporate()) {
                throw new BankException(ErrorCode.CORPORATE_ONLY);
            }
            if (accounts.countOpenByCustomerTypeCurrency(c, customerId, accountType, ccy) > 0) {
                throw new BankException(ErrorCode.ACCOUNT_TYPE_LIMIT);
            }
            String accountNo = accounts.nextAccountNo(c);
            accounts.insert(c, accountNo, customerId, accountType, ccy, deposit, now);
            if (deposit.signum() > 0) {
                // 開設時の入金も明細として残す（残高と明細の合計が一致し、元帳検証で不整合にならない）
                transactions.insert(c, accountNo, TxType.DEPOSIT, deposit, deposit, null, "口座開設時入金",
                        idempotencyKey, now);
            }
            return accounts.findById(c, accountNo).orElseThrow(() -> new SQLException("account not created"));
        });
    }

    /** 口座開設で選べる種別（定期預金は専用の手続き） */
    public static final java.util.List<String> OPENABLE_TYPES =
            java.util.Arrays.asList("ORDINARY", "SAVINGS", "CHECKING", "NOTICE", "FOREIGN");

    // 照会用（ロックなし）。存在しなければ業務エラーにする
    private Customer findCustomer(Connection c, String customerId) throws SQLException {
        return customers.findById(c, customerId)
                .orElseThrow(() -> new BankException(ErrorCode.CUSTOMER_NOT_FOUND, customerId));
    }
}
