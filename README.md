# 銀行システム サンプルパッケージ

口座開設・本人確認（KYC）、入金・出金・振込／振替、残高照会・取引履歴、利息・税金計算、口座解約・休眠預金管理を行うオンラインバンキングのサンプルです（架空データ・認証なし・**本番利用不可**）。

| 層 | 技術 |
|---|---|
| フロントエンド | Next.js 15 (App Router) / React 19 / TypeScript 5 |
| アプリケーションサーバー | **IBM WebSphere Application Server Liberty**（Java 8, Servlet 4.0 / javax） |
| API | Apache Struts 2.6 系（6.13.0）+ struts2-json-plugin、JDBC |
| ビルド | **Gradle 8.14.3**（Wrapper 同梱、Java 8 向けにコンパイル） |
| データベース | **Oracle Database 23ai Free**（gvenzl/oracle-free、PDB `FREEPDB1`、スキーマ `BANK`） |
| 実行環境 | Docker Compose（oracle / liberty / frontend の 3 コンテナ） |

```text
ブラウザ ──▶ frontend (Next.js :3000)
               │  /api/bank/*  を同一オリジンでプロキシ（CORS 不要）
               ▼
            liberty (WebSphere Liberty :9080, /bank/api/*)
               │  Struts 2 Action → BankService → JDBC
               │  接続プールは Liberty 管理の JNDI DataSource  jdbc/bankDS
               ▼
            oracle (Oracle 23ai Free :1521 / FREEPDB1)
```

## 起動

前提: Docker Desktop。初回はイメージ取得と Oracle の DB 作成に数分かかります。

```bash
cp .env.example .env    # パスワードを変更（.env はコミットしない）
```

```bash
docker compose up -d --build
```

```bash
docker compose ps
```

すべて `healthy` になったら http://localhost:3000 を開きます。

Windows の Docker Desktop で `use docker --context=desktop-linux buildx ...` エラーになる場合は、`COMPOSE_BAKE=false DOCKER_CONTEXT=desktop-linux docker compose up -d --build` を使ってください。

停止 / データ初期化:

```bash
docker compose down
```

```bash
docker compose down -v
```

（`-v` は Oracle のデータボリュームを削除し、次回起動時に `db/init/*.sql` で再作成します）

`03_lifecycle.sql` を追加する前に作成したデータベースには、一度だけ次を実行してください（データは残ります）:

```bash
docker compose exec oracle bash -c "sqlplus -s / as sysdba @/container-entrypoint-initdb.d/03_lifecycle.sql"
```

## 画面

| URL | 内容 |
|---|---|
| `/` | 口座一覧（残高・状態・合計） |
| `/accounts/{口座番号}` | 口座詳細、入金・出金、利息・税の見込み、取引履歴（期間・種別検索、ページ送り、期首／期末残高、CSV 出力）、解約、休眠解除 |
| `/transfer` | 振込（任意の口座へ）／振替（本人名義の口座間）：入力 → 確認 → 完了 |
| `/customers` | 顧客一覧（本人確認の状態） |
| `/customers/new` | 口座開設の申込（顧客情報・本人確認書類） |
| `/customers/{顧客番号}` | 顧客詳細、本人確認（確認済／謝絶）、口座開設、保有口座 |
| `/operations` | 業務処理：利息決算（一括）、休眠預金管理（通知対象・休眠対象・休眠口座） |

サンプル口座: `1000001`〜`1000004`（利用可）、`1000005`（凍結：取引不可の確認用）、`1000006`（最終異動 2015 年・残高 1 万円未満：休眠化の対象）、`1000007`（最終異動 2017 年・残高 25 万円：休眠前通知の対象）。

## API（WebSphere Liberty 上の Struts 2）

ベース URL: `http://localhost:9080/bank/api`（フロントからは `/api/bank`）

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/health` | 稼働・DB 接続確認 |
| GET | `/accounts` | 口座一覧 |
| GET | `/account?accountNo=1000001` | 口座詳細 + 明細 |
| POST | `/deposit` | 入金 `{"accountNo","amount","memo","idempotencyKey"}` |
| POST | `/withdraw` | 出金（同上） |
| POST | `/transfer` | 振込 `{"fromAccountNo","toAccountNo","amount","memo","idempotencyKey"}`。`"ownAccountsOnly": true` で振替（同じ顧客の口座間のみ） |
| GET | `/transactions?accountNo=&from=&to=&types=&page=&size=` | 取引履歴の検索（日付は `YYYY-MM-DD`、種別はカンマ区切り、size 最大 200）。期首／期末残高・入出金合計つき |
| GET | `/interest-preview?accountNo=&asOf=` | 利息・税の見込み（基準日の前日まで） |
| POST | `/interest-settle` | 利息決算 `{"asOf"?, "accountNo"?}`（accountNo 省略で全口座、決算日は本日以前） |
| GET | `/customers` | 顧客一覧 |
| GET | `/customer?customerId=CU0001` | 顧客詳細 + 保有口座 |
| POST | `/customer-register` | 口座開設の申込 `{"name","nameKana","birthDate","postalCode","address","phone","idDocType","idDocNumber","idempotencyKey"}` |
| POST | `/customer-kyc` | 本人確認の結果 `{"customerId","decision":"VERIFIED"\|"REJECTED","note"}`（謝絶時は note 必須） |
| POST | `/account-open` | 口座開設 `{"customerId","accountType":"ORDINARY"\|"SAVINGS","initialDeposit","idempotencyKey"}` |
| POST | `/account-close` | 口座解約 `{"accountNo","payoutAccountNo"?,"idempotencyKey"}`（払戻し先省略で現金） |
| POST | `/account-reactivate` | 休眠解除 `{"accountNo","idempotencyKey"}` |
| GET | `/dormancy?asOf=` | 休眠管理の対象一覧（未来日も可） |
| POST | `/dormancy-run` | 休眠前通知の記録・休眠化 `{"asOf"?}`（本日以前） |

- 成功: `200 {"data": ...}`／失敗: `{"error": {"code","message","fieldErrors"?}}`
  - 400 入力エラー、403 ヘッダー不正、404 口座なし、405 メソッド不可、409 二重送信、422 残高不足・凍結・上限超過・同一口座、500 システムエラー
- POST は `Content-Type: application/json` と `X-Requested-With: XMLHttpRequest` が必須（CSRF 対策）。
- 金額は円単位の整数を**文字列**で送ります（浮動小数点誤差を避けるため）。1 回あたり上限 10,000,000 円。

```bash
curl -s http://127.0.0.1:9080/bank/api/accounts
```

```bash
curl -s -X POST http://127.0.0.1:9080/bank/api/transfer -H "Content-Type: application/json" -H "X-Requested-With: XMLHttpRequest" -d "{\"fromAccountNo\":\"1000001\",\"toAccountNo\":\"1000003\",\"amount\":\"5000\",\"memo\":\"test\",\"idempotencyKey\":\"0f8fad5b-d9cb-469f-a165-70867728950e\"}"
```

Windows でスクリプトや curl から呼ぶ場合は `localhost` ではなく `127.0.0.1` を使ってください。ポートは IPv4 ループバックにのみ公開しているため、`localhost` だと先に `::1` への接続を試みて毎回約 200 ms 待たされます（ブラウザーは影響を受けません）。

## 性能上の工夫

| 層 | 内容 |
|---|---|
| JDBC | 明細取得は `setFetchSize(50)`（Oracle 既定のプリフェッチ 10 行では 50 件に 5 往復以上かかる）。口座一覧も同様にまとめて取得 |
| JDBC | 口座詳細（口座 + 明細）は 1 接続で取得し、プールからの取得・返却を 1 回に削減 |
| JDBC | 振込の残高更新 2 件・明細登録 2 件を JDBC バッチで送信（行ロック保持中の DB 往復 4 → 2）。ロック順序は従来どおり口座番号順 |
| Liberty | `statementCacheSize=30`（全 SQL を解析済みで再利用）、`queryTimeout=30s`（ロック待ち等で接続を抱え込まない）、`minPoolSize=5`、DB 再起動時は `purgePolicy=EntirePool` で古い接続を一括破棄、`oracle.net.CONNECT_TIMEOUT=5000` |
| フロント | `lib/query.ts` の stale-while-revalidate キャッシュ（メモリのみ）。画面遷移時は直前のデータを即表示して裏で再取得、同一リクエストは 1 本に集約、口座リンクのホバー／フォーカスで詳細を先読み、タブ復帰時に再取得 |
| フロント | 入金・出金・振込の後は（成功・失敗にかかわらず）関係口座のキャッシュを無効化し、表示中の画面は自動で最新化 |

## 業務ルールと整合性

### 口座開設・本人確認（KYC）

- 申込 → 本人確認 → 口座開設の順。本人確認が「確認済」の顧客だけが口座を開設できます（謝絶された申込者は再申込可）。
- 本人確認書類は運転免許証・マイナンバーカード・パスポート・在留カード。**番号は保存せず下 4 桁のみ**記録します。
- 18 歳以上のみ受付（未成年は親権者同意の手続きが必要なため対象外）。カナ氏名・生年月日が同じ顧客がいれば重複申込として受け付けません（名寄せ）。
- 同じ種別（普通・貯蓄）の口座は 1 顧客 1 口座まで。開設は顧客行をロックして判定するため、同時に申し込んでも重複しません。

### 利息・税金

- 利息 = Σ（日々の最終残高 × その日の年利%）÷ 100 ÷ 365、1 円未満切捨て。うるう年も 365 日で計算します。金利は `interest_rate` 表（種別・適用開始日ごと、初期値 普通 0.20%／貯蓄 0.25%）。
- 計算期間は「前回決算期間の翌日（初回は開設日）〜決算日の前日」。決算は `interest_settlement` に記録し、同じ期間を二重に決算しません。
- 税（個人・源泉分離課税）: 国税 15.315%（所得税 15% + 復興特別所得税 0.315%）と地方税 5% を**それぞれ** 1 円未満切捨て。明細には利息（税引前）と源泉税を分けて記帳します。
- 法人・非課税制度（マル優等）は対象外です。

### 口座解約・休眠預金

- 解約: 1 トランザクションで「解約日前日までの利息決算 → 全残高の払戻し（本人名義の口座へ入金、または現金）→ CLOSED」。凍結・休眠口座は解約前に解除が必要です。
- 最終異動日: お客さまによる入金・出金・振込の最終日時。**利息の入金は異動に含めません**。
- 休眠預金等活用法を簡略化したルール: 最終異動から 9 年・残高 1 万円以上で休眠前の通知を記録し、10 年経過かつ「残高 1 万円未満」または「通知から 1 か月経過」で休眠（DORMANT）にします。休眠口座は取引できず、本人確認済みの顧客の申出で解除します。通知後に取引があれば通知はリセットされます。
- 休眠化・通知の UPDATE は条件（状態・最終異動日）を再確認するため、処理中に取引のあった口座は対象から外れます。
- 実運用では、休眠預金の預金保険機構への移管、通知文書の送付、公告などの手続きが別途必要です。

### 共通

- 更新系は 1 トランザクション: **冪等キー登録 → 口座行ロック（`SELECT ... FOR UPDATE`）→ 残高検証 → 残高更新 → 明細登録 → コミット**。
- 振込は 2 口座を**口座番号順にロック**し、逆方向の同時振込でもデッドロックしません。
- 冪等キー（`OPERATION_REQUEST` 主キー）で二重送信・リトライによる二重計上を防止。業務エラー時はキーもロールバックされ、同じキーで再試行できます。
- 残高は `NUMBER(15,0)` + `CHECK (balance >= 0)`、明細は取引ごとに取引後残高を保持。
- 凍結・解約口座は取引不可。

## ディレクトリ

```text
bank-sample/
├─ docker-compose.yml        oracle / liberty / frontend
├─ .env.example              パスワード等（.env にコピー）
├─ db/init/                  01_schema.sql（DDL）, 02_seed.sql（サンプルデータ）, 03_lifecycle.sql（KYC・利息・休眠の追加 DDL とデータ）
├─ backend/                  Gradle プロジェクト（bank-api.war）
│  ├─ build.gradle, gradlew  Struts 2 / json-plugin / ojdbc8（Liberty 共有ライブラリへ配置）
│  ├─ Dockerfile             Gradle ビルド → WebSphere Liberty (kernel-java8-openj9-ubi-minimal)
│  └─ src/main/
│     ├─ liberty/config/server.xml   Liberty 設定（features, jdbc/bankDS, contextRoot=/bank）
│     ├─ resources/struts.xml        Action・インターセプター・結果（HTTP ステータス）定義
│     └─ java/com/example/bank/      action / interceptor / service / repository / domain / support
└─ frontend/                 Next.js（App Router, standalone 出力）
   ├─ app/                   画面（一覧・詳細・振込）
   └─ lib/                   API クライアント・型・表示整形
```

## ローカル開発（コンテナを使わない部分）

バックエンドのテスト（H2 の Oracle 互換モードで `db/init` の DDL/データを使用。同時振込テストを含む）:

```bash
cd backend && ./gradlew test
```

WAR のビルド（`build/libs/bank-api.war`、`build/liberty/jdbc/ojdbc8-*.jar`）:

```bash
cd backend && ./gradlew war copyJdbcDriver
```

フロントエンドを開発モードで起動（Liberty は compose で起動済みであること）:

```bash
cd frontend && npm install && npm run dev
```

## セキュリティ設定（Struts 2）

`struts.xml`: devMode=false、Dynamic Method Invocation 無効、`action:`/`method:` プレフィックス無効、静的フィールドアクセス無効、`struts.parameters.requireAnnotations=true`（`@StrutsParameter` を付けた setter のみ値を受け付け）。Liberty は `X-Powered-By` を無効化、エラー時は詳細を返しません。

## 本番化に向けて未実装・要対応

- 認証・認可（顧客ログイン、口座の所有者チェック、行員権限）、監査ログ、セッション管理
  - 特に本人確認・利息決算・休眠化・休眠解除・解約は行員権限と承認（四つの目）が必須。現状は API を誰でも呼べます
- 本人確認: 犯罪収益移転防止法に沿った確認方法（eKYC・書類画像の保管・取引時確認の記録）、反社チェック、名寄せの高度化
- 利息決算・休眠処理の夜間ジョブ化（現在は画面／API から手動実行）、休眠預金の預金保険機構への移管
- HTTPS（Liberty `ssl-1.0` / リバースプロキシ）、秘密情報の外部管理（Vault 等）
- 取引上限の日次集計、営業日・締め処理、外部勘定系・全銀連携、ジャーナルの改ざん防止
- Struts 6.x / Java 8 / WebSphere Liberty のサポート期限確認と最新パッチ適用
- 負荷試験（接続プール上限・`queryTimeout` を実測で調整）、口座一覧のページング（口座数増加時）、`OPERATION_REQUEST` の古いキーの定期削除、バックアップ
"# bank-sample" 
