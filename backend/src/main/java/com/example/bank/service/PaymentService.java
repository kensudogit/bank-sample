package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.BankException;
import com.example.bank.domain.Customer;
import com.example.bank.domain.ErrorCode;
import com.example.bank.domain.TxType;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
import com.example.bank.repository.OperationRequestRepository;
import com.example.bank.repository.ProductRepository;
import com.example.bank.repository.TransactionRepository;
import com.example.bank.support.BusinessCalendar;
import com.example.bank.support.DataSources;
import com.example.bank.support.Kana;
import com.example.bank.support.Transactions;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.example.bank.repository.ProductRepository.execute;
import static com.example.bank.repository.ProductRepository.insertReturningId;
import static com.example.bank.repository.ProductRepository.query;

/**
 * 為替業務: 他行宛振込（国内為替・全銀フォーマット）、他行からの振込の入金、口座振替（自動引落し）、海外送金。
 *
 * <ul>
 *   <li>他行宛振込: 手数料 3 万円未満 330 円・以上 440 円。15:00 以降・休業日の受付は翌営業日扱い。
 *       振込金と手数料を口座から引き落とし、全銀データ作成（総合振込フォーマット 120 バイト固定長）で送信済みにする</li>
 *   <li>被仕向（他行からの振込）: 口座番号と受取人名（カナ）が一致すれば入金、不一致は保留（HELD）にして役席が判断</li>
 *   <li>口座振替: 収納企業ごとの口座振替契約に対し、請求データを取り込み、引落日に口座から引き落とす（残高不足は不能）</li>
 *   <li>海外送金: 円口座（TTS で換算）または同じ通貨の外貨預金から送金。手数料 4,000 円。
 *       制裁リスト・高リスク国に該当すると保留し、役席が送金または謝絶（返金）を判断</li>
 * </ul>
 */
public class PaymentService {
    public static final String OUR_BANK_CODE = "9999";
    public static final LocalTime CUTOFF = LocalTime.of(15, 0);
    public static final BigDecimal FOREIGN_FEE = new BigDecimal("4000");
    private static final Pattern BIC = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Pattern IBAN_OR_ACCOUNT = Pattern.compile("^[A-Z0-9]{5,34}$");
    private static final Pattern SWIFT_TEXT = Pattern.compile("^[A-Z0-9 .,()/'+:?-]{1,70}$");

    private static final PaymentService DEFAULT = new PaymentService(DataSources::bank, Clock.systemDefaultZone());

    private final Supplier<DataSource> dataSource;
    private final Clock clock;
    private final AccountRepository accounts = new AccountRepository();
    private final TransactionRepository transactions = new TransactionRepository();
    private final CustomerRepository customers = new CustomerRepository();
    private final OperationRequestRepository requests = new OperationRequestRepository();
    private final ProductRepository products = new ProductRepository();
    private final AmlService aml;

    public PaymentService(Supplier<DataSource> dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.aml = new AmlService(dataSource, clock);
    }

    public static PaymentService getDefault() { return DEFAULT; }

    /** 他行宛振込の手数料。 */
    public static BigDecimal domesticFee(BigDecimal amount) {
        return amount.compareTo(new BigDecimal("30000")) < 0 ? new BigDecimal("330") : new BigDecimal("440");
    }

    // ===================== domestic outgoing =====================

    public List<Map<String, Object>> banks() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT * FROM bank_master ORDER BY bank_code"));
    }

    /**
     * 他行宛振込の受付。
     *
     * @param destType 預金種目 1: 普通 / 2: 当座 / 4: 貯蓄
     */
    public Map<String, Object> sendDomestic(String fromAccountNo, String bankCode, String branchCode, String destType,
                                            String destAccountNo, String destNameKana, BigDecimal amount,
                                            String idempotencyKey, String by) {
        BankService.checkAmount(amount);
        if (OUR_BANK_CODE.equals(bankCode)) throw new BankException(ErrorCode.VALIDATION_ERROR);   // 当行宛は振込（本支店）で
        String nameZ = Kana.toZengin(destNameKana).trim();
        if (nameZ.isEmpty() || nameZ.length() > 30) throw new BankException(ErrorCode.VALIDATION_ERROR);
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "REMIT", now, requests);
            if (query(c, "SELECT 1 FROM bank_master WHERE bank_code = ?", bankCode).isEmpty()) {
                throw new BankException(ErrorCode.BANK_NOT_FOUND, bankCode);
            }
            Account from = BankService.lockActive(c, accounts, fromAccountNo);
            BankService.requireYenTransactional(from);
            BigDecimal fee = domesticFee(amount);
            BigDecimal afterAmount = BankService.debit(from, amount);
            Account interim = withBalance(from, afterAmount);
            BigDecimal afterFee = BankService.debit(interim, fee);
            BankService.consumeNoticeIfRequired(c, from, amount, now.toLocalDate(), new com.example.bank.repository.NoticeRepository());

            LocalDate valueDate = valueDate(c, now);
            long id = insertReturningId(c, "INSERT INTO domestic_remittance (direction, account_no, bank_code, branch_code, "
                    + "dest_account_type, dest_account_no, dest_name_kana, amount, fee, status, value_date, idempotency_key, "
                    + "created_by, created_at) VALUES ('OUT', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)", "remit_id",
                    fromAccountNo, bankCode, branchCode, destType, destAccountNo, nameZ, amount, fee, valueDate,
                    idempotencyKey, by, now);
            accounts.updateBalance(c, fromAccountNo, afterFee, now, true);
            transactions.insert(c, fromAccountNo, TxType.REMIT_OUT, amount, afterAmount, null,
                    "他行振込 " + bankCode + "-" + branchCode + " " + nameZ, idempotencyKey, now);
            transactions.insert(c, fromAccountNo, TxType.FEE, fee, afterFee, null, "振込手数料", idempotencyKey, now);
            return remittanceView(c, id);
        });
    }

    /** 受付時刻と営業日から振込指定日を決める（15:00 以降・休業日は翌営業日）。 */
    private static LocalDate valueDate(Connection c, LocalDateTime now) throws SQLException {
        LocalDate today = now.toLocalDate();
        if (BusinessCalendar.isBusinessDay(c, today) && now.toLocalTime().isBefore(CUTOFF)) return today;
        return BusinessCalendar.next(c, today);
    }

    /** 送金前（PENDING）の他行宛振込の取消し: 振込金・手数料を返金する。 */
    public Map<String, Object> cancelDomestic(long remitId, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Map<String, Object> r = lockRemittance(c, remitId);
            if (!"OUT".equals(r.get("direction")) || !"PENDING".equals(r.get("status"))) {
                throw new BankException(ErrorCode.REMITTANCE_STATE_INVALID, String.valueOf(r.get("status")));
            }
            refund(c, (String) r.get("accountNo"), LoanService.num(r.get("amount")).add(LoanService.num(r.get("fee"))),
                    "他行振込 取消 No." + remitId, now);
            execute(c, "UPDATE domestic_remittance SET status = 'RETURNED', note = ? WHERE remit_id = ?",
                    "送信前に取消（" + by + "）", remitId);
            return remittanceView(c, remitId);
        });
    }

    /** 組戻し（送信済みの振込を相手行から戻してもらう）: 振込金のみ返金（手数料は返さない）。 */
    public Map<String, Object> returnDomestic(long remitId, String reason, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Map<String, Object> r = lockRemittance(c, remitId);
            if (!"OUT".equals(r.get("direction")) || (!"SENT".equals(r.get("status")) && !"SETTLED".equals(r.get("status")))) {
                throw new BankException(ErrorCode.REMITTANCE_STATE_INVALID, String.valueOf(r.get("status")));
            }
            refund(c, (String) r.get("accountNo"), LoanService.num(r.get("amount")), "組戻し No." + remitId, now);
            execute(c, "UPDATE domestic_remittance SET status = 'RETURNED', note = ? WHERE remit_id = ?",
                    clip("組戻し: " + reason + "（" + by + "）", 200), remitId);
            return remittanceView(c, remitId);
        });
    }

    private void refund(Connection c, String accountNo, BigDecimal amount, String memo, LocalDateTime now) throws SQLException {
        Account account = BankService.lockExisting(c, accounts, accountNo);
        if ("CLOSED".equals(account.getStatus())) throw new BankException(ErrorCode.ACCOUNT_NOT_ACTIVE, accountNo);
        BigDecimal balance = account.getBalance().add(amount);
        accounts.updateBalance(c, accountNo, balance, now, false);
        transactions.insert(c, accountNo, TxType.REMIT_IN, amount, balance, null, memo, null, now);
    }

    /**
     * 全銀データの作成: 振込指定日が基準日以前の未送信（PENDING）の振込を 1 ファイルにまとめ、送信済みにする。
     * 総合振込フォーマット（ヘッダー・データ・トレーラー・エンド、各 120 文字、改行 CRLF）。実際の送信は Shift_JIS。
     */
    public Map<String, Object> createZenginBatch(String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate today = now.toLocalDate();
            // Lock the target rows first (single table), then read them with the bank names
            List<Map<String, Object>> locked = query(c, "SELECT remit_id FROM domestic_remittance WHERE direction = 'OUT' "
                    + "AND status = 'PENDING' AND value_date <= ? ORDER BY remit_id FOR UPDATE", today);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> l : locked) {
                rows.add(query(c, "SELECT r.*, b.name_kana AS bank_name_kana FROM domestic_remittance r "
                        + "JOIN bank_master b ON b.bank_code = r.bank_code WHERE r.remit_id = ?", l.get("remitId")).get(0));
            }
            if (rows.isEmpty()) throw new BankException(ErrorCode.REMITTANCE_STATE_INVALID, "送信対象なし");
            String content = zenginFile(rows, today);
            long total = 0;
            for (Map<String, Object> r : rows) total += LoanService.num(r.get("amount")).longValue();
            long batchId = insertReturningId(c, "INSERT INTO zengin_batch (value_date, record_count, total_amount, content, "
                    + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)", "batch_id", today, rows.size(), total, content, by, now);
            for (Map<String, Object> r : rows) {
                execute(c, "UPDATE domestic_remittance SET status = 'SENT', batch_id = ? WHERE remit_id = ?", batchId, r.get("remitId"));
            }
            return query(c, "SELECT * FROM zengin_batch WHERE batch_id = ?", batchId).get(0);
        });
    }

    /** 全銀フォーマット（総合振込）のファイル内容。 */
    public static String zenginFile(List<Map<String, Object>> rows, LocalDate tradeDate) {
        StringBuilder sb = new StringBuilder();
        String mmdd = tradeDate.format(DateTimeFormatter.ofPattern("MMdd"));
        // Header: data kind 1, kind code 21 (総合振込), code 0, requester code, name, date, our bank/branch/account
        sb.append('1').append("21").append('0').append(Kana.padNumber(1, 10))
          .append(Kana.padRight("ｴｲｺﾞｳｷﾞﾝｺｳ", 40)).append(mmdd)
          .append(OUR_BANK_CODE).append(Kana.padRight("ｴｲｺﾞｳ", 15)).append("001").append(Kana.padRight("ﾎﾝﾃﾝ", 15))
          .append('1').append(Kana.padNumber(0, 7)).append(Kana.padRight("", 17)).append("\r\n");
        long total = 0;
        for (Map<String, Object> r : rows) {
            long amount = LoanService.num(r.get("amount")).longValue();
            total += amount;
            sb.append('2').append(r.get("bankCode")).append(Kana.padRight((String) r.get("bankNameKana"), 15))
              .append(r.get("branchCode")).append(Kana.padRight("", 15)).append(Kana.padRight("", 4))
              .append(r.get("destAccountType")).append(r.get("destAccountNo"))
              .append(Kana.padRight((String) r.get("destNameKana"), 30)).append(Kana.padNumber(amount, 10))
              .append('0').append(Kana.padNumber(LoanService.num(r.get("remitId")).longValue(), 10))
              .append(Kana.padRight("", 10)).append('7').append(' ').append(Kana.padRight("", 7)).append("\r\n");
        }
        sb.append('8').append(Kana.padNumber(rows.size(), 6)).append(Kana.padNumber(total, 12))
          .append(Kana.padRight("", 101)).append("\r\n");
        sb.append('9').append(Kana.padRight("", 119)).append("\r\n");
        return sb.toString();
    }

    /** 全銀センターからの決済完了の反映（送信済み → 決済済み）。 */
    public Map<String, Object> settleBatch(long batchId) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            int n = execute(c, "UPDATE domestic_remittance SET status = 'SETTLED' WHERE batch_id = ? AND status = 'SENT'", batchId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchId", batchId);
            m.put("settled", n);
            return m;
        });
    }

    public List<Map<String, Object>> zenginBatches() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT batch_id, value_date, record_count, total_amount, "
                + "created_by, created_at FROM zengin_batch ORDER BY batch_id DESC FETCH FIRST 50 ROWS ONLY"));
    }

    public Map<String, Object> zenginBatch(long batchId) {
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT * FROM zengin_batch WHERE batch_id = ?", batchId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.REMITTANCE_NOT_FOUND, String.valueOf(batchId));
            return rows.get(0);
        });
    }

    // ===================== domestic incoming =====================

    /** 被仕向データ（他行からの振込）1 件。 */
    public static final class Incoming {
        public String bankCode;
        public String remitterNameKana;
        public String accountNo;
        public String destType = "1";
        public String nameKana;
        public BigDecimal amount;
    }

    /** 他行からの振込を入金する。名義・口座が一致しないものは保留（HELD）。 */
    public List<Map<String, Object>> receiveIncoming(List<Incoming> items, String by) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Incoming in : items) {
            results.add(Transactions.inTransaction(dataSource.get(), c -> {
                LocalDateTime now = LocalDateTime.now(clock);
                if (in.amount == null || in.amount.signum() <= 0 || in.amount.scale() > 0) throw new BankException(ErrorCode.VALIDATION_ERROR);
                String nameZ = Kana.toZengin(in.nameKana).trim();
                String note = null;
                Account account = accounts.lockById(c, in.accountNo).orElse(null);
                if (account == null) note = "口座なし";
                else if (!account.isActive()) note = "取引不可の口座（" + account.getStatus() + "）";
                else if (!account.isJpy() || "TIME".equals(account.getAccountType())) note = "入金できない口座種別";
                else {
                    Customer cu = customers.findById(c, account.getCustomerId()).orElse(null);
                    if (cu == null || cu.getNameKana() == null
                            || !Kana.normalizeName(cu.getNameKana()).equals(Kana.normalizeName(in.nameKana))) {
                        note = "受取人名不一致";
                    }
                }
                long id = insertReturningId(c, "INSERT INTO domestic_remittance (direction, account_no, bank_code, branch_code, "
                        + "dest_account_type, dest_account_no, dest_name_kana, remitter_name_kana, amount, fee, status, value_date, "
                        + "note, created_by, created_at) VALUES ('IN', ?, ?, '000', ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)", "remit_id",
                        account == null ? null : in.accountNo, in.bankCode, in.destType, in.accountNo, nameZ,
                        clip(Kana.toZengin(in.remitterNameKana), 40), in.amount, note == null ? "CREDITED" : "HELD",
                        now.toLocalDate(), note, by, now);
                if (note == null) credit(c, account, in.amount, "振込 " + Kana.toZengin(in.remitterNameKana), now);
                return remittanceView(c, id);
            }));
        }
        return results;
    }

    /** 保留した被仕向の処理（役席）: 指定口座へ入金するか、仕向銀行へ返却する。 */
    public Map<String, Object> resolveHeld(long remitId, String accountNo, boolean credit, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Map<String, Object> r = lockRemittance(c, remitId);
            if (!"IN".equals(r.get("direction")) || !"HELD".equals(r.get("status"))) {
                throw new BankException(ErrorCode.REMITTANCE_STATE_INVALID, String.valueOf(r.get("status")));
            }
            if (credit) {
                Account account = BankService.lockActive(c, accounts, accountNo);
                BankService.requireYenTransactional(account);
                credit(c, account, LoanService.num(r.get("amount")), "振込（保留解除） " + r.get("remitterNameKana"), now);
                execute(c, "UPDATE domestic_remittance SET status = 'CREDITED', account_no = ?, note = ? WHERE remit_id = ?",
                        accountNo, clip(r.get("note") + " → " + by + " が入金", 200), remitId);
            } else {
                execute(c, "UPDATE domestic_remittance SET status = 'RETURNED', note = ? WHERE remit_id = ?",
                        clip(r.get("note") + " → " + by + " が返却", 200), remitId);
            }
            return remittanceView(c, remitId);
        });
    }

    private void credit(Connection c, Account account, BigDecimal amount, String memo, LocalDateTime now) throws SQLException {
        BigDecimal balance = account.getBalance().add(amount);
        accounts.updateBalance(c, account.getAccountNo(), balance, now, true);
        transactions.insert(c, account.getAccountNo(), TxType.REMIT_IN, amount, balance, null, clip(memo, 50), null, now);
    }

    public List<Map<String, Object>> listDomestic(String customerScope, String direction, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (customerScope != null) {
            where.append(" AND r.account_no IN (SELECT account_no FROM account WHERE customer_id = ?)");
            params.add(customerScope);
        }
        if (direction != null) {
            where.append(" AND r.direction = ?");
            params.add(direction);
        }
        if (status != null) {
            where.append(" AND r.status = ?");
            params.add(status);
        }
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT r.remit_id, r.direction, r.account_no, r.bank_code, "
                + "b.name AS bank_name, r.branch_code, r.dest_account_type, r.dest_account_no, r.dest_name_kana, "
                + "r.remitter_name_kana, r.amount, r.fee, r.status, r.value_date, r.batch_id, r.note, r.created_at "
                + "FROM domestic_remittance r LEFT JOIN bank_master b ON b.bank_code = r.bank_code" + where
                + " ORDER BY r.remit_id DESC FETCH FIRST 200 ROWS ONLY", params.toArray()));
    }

    private Map<String, Object> remittanceView(Connection c, long id) throws SQLException {
        return query(c, "SELECT r.*, b.name AS bank_name FROM domestic_remittance r "
                + "LEFT JOIN bank_master b ON b.bank_code = r.bank_code WHERE r.remit_id = ?", id).get(0);
    }

    private static Map<String, Object> lockRemittance(Connection c, long id) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT * FROM domestic_remittance WHERE remit_id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new BankException(ErrorCode.REMITTANCE_NOT_FOUND, String.valueOf(id));
        return rows.get(0);
    }

    // ===================== direct debit =====================

    public List<Map<String, Object>> billers() {
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT * FROM biller ORDER BY biller_code"));
    }

    public Map<String, Object> registerMandate(String accountNo, String billerCode, String payerNumber, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            Account account = BankService.lockActive(c, accounts, accountNo);
            BankService.requireYenTransactional(account);
            if ("NOTICE".equals(account.getAccountType())) throw new BankException(ErrorCode.ACCOUNT_TYPE_NOT_ALLOWED, accountNo);
            if (query(c, "SELECT 1 FROM biller WHERE biller_code = ?", billerCode).isEmpty()) {
                throw new BankException(ErrorCode.VALIDATION_ERROR);
            }
            if (!query(c, "SELECT 1 FROM direct_debit_mandate WHERE biller_code = ? AND payer_number = ?", billerCode,
                    payerNumber).isEmpty()) {
                throw new BankException(ErrorCode.DUPLICATE_REQUEST);
            }
            long id = insertReturningId(c, "INSERT INTO direct_debit_mandate (account_no, biller_code, payer_number, status, "
                    + "created_by, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)", "mandate_id", accountNo, billerCode,
                    payerNumber, by, LocalDateTime.now(clock));
            return mandateView(c, id);
        });
    }

    public Map<String, Object> cancelMandate(long mandateId) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            if (execute(c, "UPDATE direct_debit_mandate SET status = 'CANCELLED', cancelled_at = ? WHERE mandate_id = ? "
                    + "AND status = 'ACTIVE'", LocalDateTime.now(clock), mandateId) != 1) {
                throw new BankException(ErrorCode.MANDATE_NOT_FOUND, String.valueOf(mandateId));
            }
            return mandateView(c, mandateId);
        });
    }

    public String mandateOwner(long mandateId) {
        return Transactions.readOnly(dataSource.get(), c -> {
            List<Map<String, Object>> rows = query(c, "SELECT a.customer_id FROM direct_debit_mandate m "
                    + "JOIN account a ON a.account_no = m.account_no WHERE m.mandate_id = ?", mandateId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.MANDATE_NOT_FOUND, String.valueOf(mandateId));
            return (String) rows.get(0).get("customerId");
        });
    }

    /** 請求データ 1 件（収納企業から）。 */
    public static final class Billing {
        public String billerCode;
        public String payerNumber;
        public BigDecimal amount;
        public LocalDate dueDate;
    }

    /** 収納企業からの請求データの取込み。契約のない請求は取り込まずに結果で返す。 */
    public List<Map<String, Object>> importBilling(List<Billing> items) {
        List<Map<String, Object>> results = new ArrayList<>();
        Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            for (Billing b : items) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("billerCode", b.billerCode);
                r.put("payerNumber", b.payerNumber);
                r.put("amount", b.amount);
                List<Map<String, Object>> m = query(c, "SELECT mandate_id FROM direct_debit_mandate WHERE biller_code = ? "
                        + "AND payer_number = ? AND status = 'ACTIVE'", b.billerCode, b.payerNumber);
                if (b.amount == null || b.amount.signum() <= 0 || b.dueDate == null) {
                    r.put("result", "入力不備");
                } else if (m.isEmpty()) {
                    r.put("result", "契約なし");
                } else {
                    long id = insertReturningId(c, "INSERT INTO direct_debit_request (mandate_id, amount, due_date, status, "
                            + "created_at) VALUES (?, ?, ?, 'PENDING', ?)", "request_id", m.get(0).get("mandateId"),
                            b.amount, b.dueDate, now);
                    r.put("result", "取込");
                    r.put("requestId", id);
                }
                results.add(r);
            }
            return null;
        });
        return results;
    }

    /** 引落日の到来した請求を処理する（夜間バッチ）。結果コード 0: 振替済 / 1: 資金不足 / 2: 取引なし。 */
    public Map<String, Object> processDirectDebits(LocalDate asOf) {
        List<Map<String, Object>> due = Transactions.readOnly(dataSource.get(), c -> query(c,
                "SELECT request_id FROM direct_debit_request WHERE status = 'PENDING' AND due_date <= ? ORDER BY request_id", asOf));
        int paid = 0;
        int failed = 0;
        for (Map<String, Object> d : due) {
            long requestId = LoanService.num(d.get("requestId")).longValue();
            boolean ok = Transactions.inTransaction(dataSource.get(), c -> {
                LocalDateTime now = LocalDateTime.now(clock);
                query(c, "SELECT request_id FROM direct_debit_request WHERE request_id = ? FOR UPDATE", requestId);
                Map<String, Object> req = query(c, "SELECT r.*, m.account_no, m.status AS mandate_status, b.name AS biller_name "
                        + "FROM direct_debit_request r JOIN direct_debit_mandate m ON m.mandate_id = r.mandate_id "
                        + "JOIN biller b ON b.biller_code = m.biller_code WHERE r.request_id = ?", requestId).get(0);
                if (!"PENDING".equals(req.get("status"))) return false;
                String accountNo = (String) req.get("accountNo");
                Account account = accounts.lockById(c, accountNo).orElse(null);
                BigDecimal amount = LoanService.num(req.get("amount"));
                String code;
                if (account == null || !account.isActive() || !"ACTIVE".equals(req.get("mandateStatus"))) {
                    code = "2";
                } else if (account.getBalance().add(account.getOverdraftLimit()).compareTo(amount) < 0) {
                    code = "1";
                } else {
                    BigDecimal balance = account.getBalance().subtract(amount);
                    accounts.updateBalance(c, accountNo, balance, now, true);
                    transactions.insert(c, accountNo, TxType.DIRECT_DEBIT, amount, balance, null,
                            clip("口座振替 " + req.get("billerName"), 50), null, now);
                    code = "0";
                }
                execute(c, "UPDATE direct_debit_request SET status = ?, result_code = ?, processed_at = ? WHERE request_id = ?",
                        "0".equals(code) ? "PAID" : "FAILED", code, now, requestId);
                return "0".equals(code);
            });
            if (ok) paid++;
            else failed++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", asOf.toString());
        m.put("paid", paid);
        m.put("failed", failed);
        return m;
    }

    public List<Map<String, Object>> listMandates(String customerScope) {
        String where = customerScope == null ? "" : " WHERE a.customer_id = ?";
        Object[] params = customerScope == null ? new Object[0] : new Object[]{customerScope};
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT m.*, b.name AS biller_name, b.category, "
                + "a.customer_id FROM direct_debit_mandate m JOIN biller b ON b.biller_code = m.biller_code "
                + "JOIN account a ON a.account_no = m.account_no" + where + " ORDER BY m.mandate_id DESC", params));
    }

    public List<Map<String, Object>> listDebitRequests(String customerScope) {
        String where = customerScope == null ? "" : " WHERE a.customer_id = ?";
        Object[] params = customerScope == null ? new Object[0] : new Object[]{customerScope};
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT r.*, m.account_no, m.payer_number, "
                + "b.name AS biller_name FROM direct_debit_request r JOIN direct_debit_mandate m ON m.mandate_id = r.mandate_id "
                + "JOIN biller b ON b.biller_code = m.biller_code JOIN account a ON a.account_no = m.account_no" + where
                + " ORDER BY r.request_id DESC FETCH FIRST 200 ROWS ONLY", params));
    }

    private static Map<String, Object> mandateView(Connection c, long id) throws SQLException {
        return query(c, "SELECT m.*, b.name AS biller_name FROM direct_debit_mandate m JOIN biller b "
                + "ON b.biller_code = m.biller_code WHERE m.mandate_id = ?", id).get(0);
    }

    // ===================== foreign remittance =====================

    /** 海外送金の入力。 */
    public static final class ForeignOrder {
        public String accountNo;
        public String currency;
        public String amount;
        public String feeAccountNo;
        public String beneficiaryName;
        public String beneficiaryAccount;
        public String swiftBic;
        public String country;
        public String purpose;
    }

    public Map<String, Object> sendForeign(ForeignOrder o, String idempotencyKey, String by) {
        String name = upper(o.beneficiaryName);
        String acct = upper(o.beneficiaryAccount).replace(" ", "");
        String bic = upper(o.swiftBic);
        String country = upper(o.country);
        String purpose = upper(o.purpose);
        if (!SWIFT_TEXT.matcher(name).matches() || !IBAN_OR_ACCOUNT.matcher(acct).matches() || !BIC.matcher(bic).matches()
                || !country.matches("^[A-Z]{2}$") || !SWIFT_TEXT.matcher(purpose).matches()
                || (!"USD".equals(o.currency) && !"EUR".equals(o.currency))) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        BigDecimal minor;
        try {
            minor = DepositMath.toMinor(o.amount, o.currency);
        } catch (RuntimeException e) {
            throw new BankException(ErrorCode.VALIDATION_ERROR);
        }
        if (minor.signum() <= 0) throw new BankException(ErrorCode.VALIDATION_ERROR);

        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            BankService.registerRequest(c, idempotencyKey, "FX_REMIT", now, requests);
            BigDecimal[] rate = products.fxRate(c, o.currency);
            BigDecimal tts = rate[0].add(rate[1]);
            // Lock in account-number order (source and fee account may differ)
            String a1 = o.accountNo.compareTo(o.feeAccountNo) <= 0 ? o.accountNo : o.feeAccountNo;
            String a2 = a1.equals(o.accountNo) ? o.feeAccountNo : o.accountNo;
            Account first = BankService.lockActive(c, accounts, a1);
            Account second = a1.equals(a2) ? first : BankService.lockActive(c, accounts, a2);
            Account source = first.getAccountNo().equals(o.accountNo) ? first : second;
            Account feeAccount = first.getAccountNo().equals(o.feeAccountNo) ? first : second;
            if (!source.getCustomerId().equals(feeAccount.getCustomerId())) throw new BankException(ErrorCode.NOT_OWN_ACCOUNT, o.feeAccountNo);
            BankService.requireYenTransactional(feeAccount);

            BigDecimal debit;
            BigDecimal appliedRate = null;
            if ("FOREIGN".equals(source.getAccountType())) {
                if (!o.currency.equals(source.getCurrency())) throw new BankException(ErrorCode.CURRENCY_MISMATCH, o.accountNo);
                debit = minor;
            } else {
                BankService.requireYenTransactional(source);
                appliedRate = tts;
                debit = DepositMath.yenToBuy(minor, o.currency, tts);
            }
            BigDecimal yenEquivalent = "FOREIGN".equals(source.getAccountType())
                    ? DepositMath.yenFromSell(minor, o.currency, rate[0]) : debit;
            if (yenEquivalent.compareTo(BankService.MAX_AMOUNT_PER_OPERATION) > 0) throw new BankException(ErrorCode.LIMIT_EXCEEDED);

            BigDecimal sourceBalance = BankService.debit(source, debit);
            accounts.updateBalance(c, source.getAccountNo(), sourceBalance, now, true);
            String memo = "海外送金 " + o.currency + " " + DepositMath.fromMinor(minor, o.currency).toPlainString() + " " + country;
            transactions.insert(c, source.getAccountNo(), TxType.REMIT_OUT, debit, sourceBalance, null, clip(memo, 50),
                    idempotencyKey, now);
            Account feeNow = feeAccount == source ? withBalance(source, sourceBalance) : feeAccount;
            if (feeAccount != source) feeNow = BankService.lockExisting(c, accounts, feeAccount.getAccountNo());
            BigDecimal feeBalance = BankService.debit(feeNow, FOREIGN_FEE);
            accounts.updateBalance(c, feeAccount.getAccountNo(), feeBalance, now, true);
            transactions.insert(c, feeAccount.getAccountNo(), TxType.FEE, FOREIGN_FEE, feeBalance, null, "海外送金手数料",
                    idempotencyKey, now);

            AmlService.Screening s = aml.screen(c, name, country);
            String status = s.hit ? "HELD" : "SENT";
            long id = insertReturningId(c, "INSERT INTO foreign_remittance (account_no, currency, amount_minor, debit_amount, rate, "
                    + "fee_jpy, fee_account_no, beneficiary_name, beneficiary_account, swift_bic, country, purpose, status, "
                    + "screening_result, idempotency_key, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "remit_id", source.getAccountNo(), o.currency, minor, debit, appliedRate, FOREIGN_FEE, feeAccount.getAccountNo(),
                    name, acct, bic, country, purpose, status, s.detail, idempotencyKey, by, now);
            if (s.hit) {
                aml.alert(c, s.rule, source.getCustomerId(), source.getAccountNo(), "FREMIT:" + id, s.score, s.detail);
            } else {
                execute(c, "UPDATE foreign_remittance SET message = ? WHERE remit_id = ?",
                        mt103(id, source, o.currency, minor, name, acct, bic, purpose, now.toLocalDate()), id);
            }
            if (yenEquivalent.compareTo(AmlService.FOREIGN_REPORT) >= 0) {
                aml.alert(c, "FOREIGN_LARGE", source.getCustomerId(), source.getAccountNo(), "FREMIT-RPT:" + id, 30,
                        String.format("海外送金 %,d 円相当（国外送金等調書の対象）", yenEquivalent.longValue()));
            }
            return foreignView(c, id);
        });
    }

    /** 保留した海外送金の判断（役席）: 送金するか、謝絶して返金（送金額・手数料）する。 */
    public Map<String, Object> decideForeign(long remitId, boolean release, String note, String by) {
        return Transactions.inTransaction(dataSource.get(), c -> {
            LocalDateTime now = LocalDateTime.now(clock);
            List<Map<String, Object>> rows = query(c, "SELECT * FROM foreign_remittance WHERE remit_id = ? FOR UPDATE", remitId);
            if (rows.isEmpty()) throw new BankException(ErrorCode.REMITTANCE_NOT_FOUND, String.valueOf(remitId));
            Map<String, Object> r = rows.get(0);
            if (!"HELD".equals(r.get("status"))) throw new BankException(ErrorCode.REMITTANCE_STATE_INVALID, String.valueOf(r.get("status")));
            if (note == null || note.trim().isEmpty()) throw new BankException(ErrorCode.VALIDATION_ERROR);
            String screening = clip(r.get("screeningResult") + " / 判断: " + note, 400);
            if (release) {
                Account source = BankService.lockExisting(c, accounts, (String) r.get("accountNo"));
                execute(c, "UPDATE foreign_remittance SET status = 'SENT', screening_result = ?, decided_by = ?, decided_at = ?, "
                        + "message = ? WHERE remit_id = ?", screening, by, now,
                        mt103(remitId, source, (String) r.get("currency"), LoanService.num(r.get("amountMinor")),
                                (String) r.get("beneficiaryName"), (String) r.get("beneficiaryAccount"),
                                (String) r.get("swiftBic"), (String) r.get("purpose"), now.toLocalDate()), remitId);
            } else {
                refundTo(c, (String) r.get("accountNo"), LoanService.num(r.get("debitAmount")), "海外送金 謝絶返金 No." + remitId, now);
                refundTo(c, (String) r.get("feeAccountNo"), LoanService.num(r.get("feeJpy")), "海外送金手数料 返金 No." + remitId, now);
                execute(c, "UPDATE foreign_remittance SET status = 'REJECTED', screening_result = ?, decided_by = ?, "
                        + "decided_at = ? WHERE remit_id = ?", screening, by, now, remitId);
            }
            return foreignView(c, remitId);
        });
    }

    private void refundTo(Connection c, String accountNo, BigDecimal amount, String memo, LocalDateTime now) throws SQLException {
        Account account = BankService.lockExisting(c, accounts, accountNo);
        BigDecimal balance = account.getBalance().add(amount);
        accounts.updateBalance(c, accountNo, balance, now, false);
        transactions.insert(c, accountNo, TxType.REMIT_IN, amount, balance, null, clip(memo, 50), null, now);
    }

    /** SWIFT MT103（顧客送金）形式の電文（簡略）。 */
    static String mt103(long id, Account source, String currency, BigDecimal minor, String name, String account, String bic,
                        String purpose, LocalDate valueDate) {
        String amount = DepositMath.fromMinor(minor, currency).toPlainString().replace('.', ',');
        if (!amount.contains(",")) amount = amount + ",";
        String bic11 = bic.length() == 8 ? bic + "XXX" : bic;
        return "{1:F01EIGOJPJTAXXX0000000000}{2:I103" + bic11 + "N}{4:\r\n"
                + ":20:FR" + Kana.padNumber(id, 14) + "\r\n"
                + ":23B:CRED\r\n"
                + ":32A:" + valueDate.format(DateTimeFormatter.ofPattern("yyMMdd")) + currency + amount + "\r\n"
                + ":50K:/" + source.getAccountNo() + "\r\nCUSTOMER " + source.getCustomerId() + "\r\n"
                + ":57A:" + bic + "\r\n"
                + ":59:/" + account + "\r\n" + name + "\r\n"
                + ":70:" + purpose + "\r\n"
                + ":71A:SHA\r\n-}";
    }

    public List<Map<String, Object>> listForeign(String customerScope, String status) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (customerScope != null) {
            where.append(" AND a.customer_id = ?");
            params.add(customerScope);
        }
        if (status != null) {
            where.append(" AND f.status = ?");
            params.add(status);
        }
        return Transactions.readOnly(dataSource.get(), c -> query(c, "SELECT f.remit_id, f.account_no, a.customer_id, f.currency, "
                + "f.amount_minor, f.debit_amount, f.rate, f.fee_jpy, f.beneficiary_name, f.swift_bic, f.country, f.purpose, "
                + "f.status, f.screening_result, f.created_at FROM foreign_remittance f JOIN account a ON a.account_no = f.account_no"
                + where + " ORDER BY f.remit_id DESC FETCH FIRST 200 ROWS ONLY", params.toArray()));
    }

    public Map<String, Object> getForeign(long remitId) {
        return Transactions.readOnly(dataSource.get(), c -> foreignView(c, remitId));
    }

    private static Map<String, Object> foreignView(Connection c, long id) throws SQLException {
        List<Map<String, Object>> rows = query(c, "SELECT f.*, a.customer_id FROM foreign_remittance f "
                + "JOIN account a ON a.account_no = f.account_no WHERE f.remit_id = ?", id);
        if (rows.isEmpty()) throw new BankException(ErrorCode.REMITTANCE_NOT_FOUND, String.valueOf(id));
        return rows.get(0);
    }

    // ===================== helpers =====================

    private static Account withBalance(Account a, BigDecimal balance) {
        return new Account(a.getAccountNo(), a.getCustomerId(), a.getCustomerName(), a.getAccountType(), balance,
                a.getStatus(), a.getUpdatedAt(), a.getOpenedAt(), a.getClosedAt(), a.getLastActivityAt(),
                a.getDormancyNotifiedAt(), a.getDormantAt(), a.getCurrency(), a.getOverdraftLimit());
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
