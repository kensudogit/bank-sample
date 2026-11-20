-- Sample data (fictional). Requires NLS_LANG=..AL32UTF8 for the SQL*Plus client (set in docker-compose.yml).
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = BANK;

INSERT INTO customer (customer_id, name, created_at) VALUES ('CU0001', '山田 太郎', TIMESTAMP '2026-01-05 09:00:00');
INSERT INTO customer (customer_id, name, created_at) VALUES ('CU0002', '佐藤 花子', TIMESTAMP '2026-01-05 09:10:00');
INSERT INTO customer (customer_id, name, created_at) VALUES ('CU0003', '鈴木 一郎', TIMESTAMP '2026-01-06 14:30:00');

INSERT INTO account (account_no, customer_id, account_type, balance, status, opened_at, updated_at) VALUES ('1000001', 'CU0001', 'ORDINARY', 1250000, 'ACTIVE', TIMESTAMP '2026-01-05 09:00:00', TIMESTAMP '2026-02-01 10:00:00');
INSERT INTO account (account_no, customer_id, account_type, balance, status, opened_at, updated_at) VALUES ('1000002', 'CU0001', 'SAVINGS', 3000000, 'ACTIVE', TIMESTAMP '2026-01-05 09:05:00', TIMESTAMP '2026-01-05 09:05:00');
INSERT INTO account (account_no, customer_id, account_type, balance, status, opened_at, updated_at) VALUES ('1000003', 'CU0002', 'ORDINARY', 480000, 'ACTIVE', TIMESTAMP '2026-01-05 09:10:00', TIMESTAMP '2026-02-01 10:00:00');
INSERT INTO account (account_no, customer_id, account_type, balance, status, opened_at, updated_at) VALUES ('1000004', 'CU0003', 'ORDINARY', 120000, 'ACTIVE', TIMESTAMP '2026-01-06 14:30:00', TIMESTAMP '2026-01-06 14:30:00');
INSERT INTO account (account_no, customer_id, account_type, balance, status, opened_at, updated_at) VALUES ('1000005', 'CU0003', 'SAVINGS', 52000, 'FROZEN', TIMESTAMP '2026-01-06 14:35:00', TIMESTAMP '2026-01-20 16:00:00');

INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000001', 'DEPOSIT', 1300000, 1300000, NULL, '口座開設時入金', TIMESTAMP '2026-01-05 09:01:00');
INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000002', 'DEPOSIT', 3000000, 3000000, NULL, '口座開設時入金', TIMESTAMP '2026-01-05 09:06:00');
INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000003', 'DEPOSIT', 430000, 430000, NULL, '口座開設時入金', TIMESTAMP '2026-01-05 09:11:00');
INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000001', 'TRANSFER_OUT', 50000, 1250000, '1000003', '立替金精算', TIMESTAMP '2026-02-01 10:00:00');
INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000003', 'TRANSFER_IN', 50000, 480000, '1000001', '立替金精算', TIMESTAMP '2026-02-01 10:00:00');
INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000004', 'DEPOSIT', 120000, 120000, NULL, '口座開設時入金', TIMESTAMP '2026-01-06 14:31:00');
INSERT INTO account_transaction (account_no, tx_type, amount, balance_after, counterparty_account_no, memo, created_at) VALUES ('1000005', 'DEPOSIT', 52000, 52000, NULL, '口座開設時入金', TIMESTAMP '2026-01-06 14:36:00');

COMMIT;
