"use client";

// 画面右下に浮かぶ「利用手順」パネル。アプリの構成・役割・試し方・トラブル対処・機能ごとの手順を表示する。
// 本文の内容はこのファイル内の定数で管理し、開閉状態はブラウザ（localStorage）に保存する。

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "./UsageGuidePanel.module.css";

/*
 * 利用手順パネル（StockAI の UsageGuidePanel と同じデザイン）。
 * 見出しをドラッグで移動、▼▲ で本文の開閉、× または Esc で閉じる。画面を移動しても開いたまま。
 */

/** 使用技術のタグ一覧（パネル上部に表示） */
const techStack = [
  "Next.js 15 · React 19",
  "Struts 2 · Liberty",
  "Oracle 23ai",
  "nginx ×2 冗長",
  "冪等キー",
  "監査ハッシュチェーン",
] as const;

/** サービス構成図（等幅フォントの <pre> でそのまま表示するテキスト図） */
const archDiagram = `ブラウザ（この画面）
    │ :3000
    ▼
lb（nginx）── frontend ×2（Next.js）
    │ /api/bank/* → :9080
    ▼
lb（nginx）── liberty ×2（Struts 2 / Java 8）
    │ JDBC（1 取引 = 1 トランザクション）
    ▼
Oracle 23ai（スキーマ BANK）
    ├ 口座・明細（取引後残高）
    ├ 冪等キー（二重送信の防止）
    ├ 監査ログ（ハッシュチェーン）
    └ 夜間バッチの実行記録（1 日 1 回）`;

/** 「5 分で一通り試す」の手順 */
const quickFlow = [
  "supervisor1 でログイン（パスワード BankDemo#2026）",
  "「口座一覧」で 1000001 を開き、入金 10,000 円 → 取引履歴に反映されることを確認",
  "「振込・振替」で 1000001 → 1000003 へ 3,000 円を振込（入力 → 確認 → 完了）",
  "「顧客・口座開設」で申込 → 本人確認（受付者とは別の役席）→ 普通預金を開設",
  "「業務処理」で夜間バッチの結果を確認し、「監査ログ」で改ざん検知を実行",
] as const;

/** デモ用の利用者 ID・役割・できることの表 */
const roles = [
  ["yamada / sato / suzuki / takahashi", "お客さま", "本人名義の口座の照会・履歴・利息見込み、振込・振替"],
  ["teller1", "窓口担当", "全口座の照会、入金・出金、口座開設の申込受付・開設、顧客ログインの発行、顧客分析"],
  ["supervisor1 / supervisor2", "役席", "窓口の操作 + 本人確認の判定、解約、休眠解除、利息決算、リスク・AML・業務処理・監査ログ、経営ダッシュボード・帳票"],
] as const;

/** 運用のルール：やってよいこと／やらないこと */
const doList = [
  "画面の「確認」まで進んでから確定する（確定ボタンの二度押しは冪等キーで 1 回だけ処理）",
  "本人確認・融資の承認は、申込を受け付けた人とは別の役席で行う",
  "夜間バッチが失敗したら、業務処理画面で原因を確認してから手動実行する",
] as const;

const dontList = [
  "実在の氏名・口座番号・本人確認書類番号を入力しない（架空データ専用）",
  "デモ用パスワード（BankDemo#2026）を他の環境で使わない",
  "DB の残高を SQL で直接書き換えない（取引元帳の整合性検証でジョブが失敗する）",
] as const;

/** うまくいかないときの確認事項 */
const troubles = [
  "ログイン画面に戻される：15 分無操作でタイムアウト。もう一度ログイン",
  "「この画面を利用する権限がありません」：役割が足りない（ADMIN メニューは役席のみ）",
  "5 回続けてログインに失敗：15 分ロック。時間をおいて再試行",
  "画面が表示されない：docker compose ps で oracle・liberty・frontend・lb が healthy か確認",
  "残高不足・上限超過：1 回 1,000 万円・1 日 2,000 万円まで。凍結・休眠・解約口座は取引不可",
] as const;

/** 起動から運用までの機能別手順（見出し・説明・箇条書き） */
const steps = [
  {
    title: "1. 起動とログイン",
    body: "Docker Desktop を起動し、bank-sample のフォルダーで起動します。",
    items: [
      "docker compose up -d --build（初回は Oracle の DB 作成に数分）",
      "docker compose ps ですべて healthy になったら http://localhost:3000",
      "デモユーザーのパスワードはすべて BankDemo#2026（役割は下の表）",
      "窓口で発行した顧客ログインは初回ログイン時にパスワード変更が必要",
    ],
  },
  {
    title: "2. 口座一覧・口座詳細",
    body: "サイドバー「口座一覧」（お客さまは「ご利用口座」）。上部に全口座の残高合計を表示します。",
    items: [
      "検索欄：口座番号・顧客番号・氏名・カナで絞り込み → 「検索」",
      "口座番号をクリック → 口座詳細（残高・状態・利息と税の見込み）",
      "取引履歴：期間・種別で検索、ページ送り、期首／期末残高と入出金合計、CSV 出力",
      "状態：利用可 / 凍結 / 休眠 / 解約。凍結・休眠・解約の口座は取引できない",
    ],
  },
  {
    title: "3. 入金・出金（窓口担当・役席）",
    body: "口座詳細の入金・出金欄で金額（必要ならメモ）を入れて実行します。",
    items: [
      "成功すると残高と取引履歴が自動で最新化される",
      "出金は残高の範囲内（当座貸越契約のある当座預金は極度額まで）",
      "1 回 1,000 万円・1 口座 1 日 2,000 万円（出金・振込の合計）が上限",
      "定期・外貨口座では現金の入出金はできない（専用の手続き）",
    ],
  },
  {
    title: "4. 振込・振替",
    body: "「振込・振替」で 入力 → 確認 → 完了 の順に進みます。",
    items: [
      "振込：任意の当行口座へ。振替：同じお客さまの口座間だけ",
      "確認画面で金額と振込先の名義を確かめてから確定",
      "確定の二度押し・通信エラーの再試行でも二重に振り込まれない（冪等キー）",
      "お客さまは本人名義の口座からだけ振り込める",
    ],
  },
  {
    title: "5. 顧客・口座開設（窓口担当・役席）",
    body: "「顧客・口座開設」で 申込 → 本人確認 → 口座開設 の順に進めます。",
    items: [
      "申込：氏名・カナ・生年月日・住所・電話・本人確認書類（番号は下 4 桁だけ保存）",
      "18 歳以上のみ。カナ氏名と生年月日が同じ顧客がいれば重複申込として受け付けない",
      "本人確認の判定（確認済／謝絶）は役席のみ・受付者本人は不可。謝絶は理由が必要",
      "口座開設：普通・貯蓄・当座（法人）・通知・外貨。普通と貯蓄は 1 顧客 1 口座",
      "顧客詳細から顧客ログインを発行（初期パスワードは一度だけ表示）、顧客 360° と接触履歴",
    ],
  },
  {
    title: "6. 預金商品",
    body: "「預金商品」で定期預金の作成と外国為替相場を扱います。",
    items: [
      "定期：3 か月〜5 年・1 万円以上、作成時の金利で固定。満期は元利金を資金口座へか元利継続",
      "外貨（USD/EUR）：円口座との両替のみ。買いは TTS（切上げ）、売りは TTB（切捨て）",
      "通知預金：5 万円以上・据置 7 日。払出しは 2 日前までの通知が必要",
      "役席は公示相場を更新できる",
    ],
  },
  {
    title: "7. 為替・口座振替",
    body: "「為替・口座振替」で他行宛振込・口座振替・海外送金・全銀データを扱います。",
    items: [
      "他行宛振込：手数料 330 円（3 万円未満）／440 円。15:00 以降・休業日は翌営業日扱い",
      "送信前は取消、送信後は組戻し。全銀の総合振込データを作成",
      "被仕向：口座番号と受取人名（カナ）が一致すれば入金、不一致は保留して役席が判断",
      "口座振替：契約 → 請求データの取込（同じ請求の再取込は重複）→ 引落日に処理",
      "海外送金：手数料 4,000 円。制裁リスト・高リスク国は保留して役席が送金／謝絶",
    ],
  },
  {
    title: "8. 融資",
    body: "「融資」で 申込 → 審査 → 承認 → 担保・保証 → 契約 → 実行 → 返済 を進めます。",
    items: [
      "審査：スコア（返済比率・担保掛目・年収・取引年数・延滞）→ 格付 A〜E → 金利",
      "承認／謝絶は役席・受付者とは別の人",
      "返済予定表（元利均等／元金均等）、返済・繰上返済、カードローンの借入",
      "延滞日数から債務者区分を更新、督促。償却は役席のみ（引当を取り崩し、不足分は損失）",
      "返済シミュレーション（融資画面の右上）：元利均等と元金均等の返済額・利息総額を比較。お客さまも利用可",
      "貸倒引当金：区分ごとの引当率（正常先 0.5%〜実質破綻先 100%）で毎晩見直し。融資詳細と業務処理画面で確認",
      "担保の再評価・条件変更（役席）：融資詳細から。条件変更した債権は要管理先以下として扱う",
    ],
  },
  {
    title: "9. リスク管理・AML（役席）",
    body: "ADMIN メニューの「リスク管理」「AML」です。",
    items: [
      "信用リスク：EAD・期待損失・不良債権比率、格付・債務者区分・商品別、与信集中",
      "流動性（預貸率・30 日以内の定期満期）と為替ポジション",
      "AML：10 分ごとに取引を検査（大口現金・分割入金・短期間の入出金・海外送金）",
      "アラートは 未対応 → 調査中 → 問題なし／届出。届出は理由を記録",
    ],
  },
  {
    title: "10. 業務処理・監査ログ（役席）",
    body: "「業務処理」で夜間バッチと決算、「監査ログ」で操作の記録を確認します。",
    items: [
      "夜間バッチ（毎日 01:00 以降）：休眠管理・定期の満期・融資の約定返済・口座振替・元帳の整合性検証",
      "同じ日に 1 回だけ実行。未実行のジョブは画面から手動実行できる",
      "利息決算（一括）：決算日の前日までの利息と源泉税（国税 15.315%・地方税 5%）を記帳",
      "休眠預金管理：最終異動から 9 年で通知、10 年で休眠",
      "監査ログ：期間・利用者・操作・結果で検索。「検証」でハッシュチェーンの改ざんを検知",
    ],
  },
  {
    title: "11. 情報系（経営ダッシュボード・顧客分析・帳票）",
    body: "サイドバーの INFORMATION。勘定系とは別の日次スナップショット（DWH）から集計するので、業務処理と競合しません。",
    items: [
      "取込：夜間バッチ「情報系取込」が毎日前日分を取り込む（初回は直近 30 日分）。経営ダッシュボード下部から期間指定で再取込も可（役席・92 日まで）",
      "経営ダッシュボード（役席）：預金・貸出金残高と前日比、預貸率、不良債権比率、残高推移グラフ（30/90/180 日）、期間損益、残高構成、月次サマリー",
      "顧客分析（窓口・役席）：RFM（最近の取引・頻度・預かり資産）で 優良／成長／一般／休眠リスク／離反 に分類",
      "クロスセル候補（未保有の商品と理由）・離反リスク（取引の途絶・30 日で資産 30% 以上減）・顧客一覧を CSV 出力",
      "帳票（役席）：預金残高表・貸出金残高表・取引集計表・経営指標 日次推移表・顧客別 預かり資産。表示内容をそのまま CSV 出力（Excel 対応）",
    ],
  },
  {
    title: "12. 銀行バッチ（役席）",
    body: "ADMIN メニューの「銀行バッチ」。毎日 01:00 以降に前日分を処理し、照合で NG が出たジョブは失敗として残ります。",
    items: [
      "業務一覧：日次取引集計・残高照合・預金利息計算・融資返済・延滞判定・手数料計算・為替照合・会計仕訳集計・月末締め・帳票出力と、担当ジョブの直近の結果",
      "失敗したジョブは原因を直してから「再実行」（成功したジョブは再実行できない）。「未実行のジョブを今すぐ実行」で当日分を前倒し",
      "日次の結果：照合（OK / WARN / NG）、取引集計、総勘定元帳の日計（借方 = 貸方）、利息の日次計上と未払利息、手数料の照合",
      "月末締め：毎月 1 日に前月を確定（照合 NG が残っていれば例外）。帳票出力：取引明細表・日計表・月末残高一覧・月次集計表を CSV でダウンロード",
      "残高証明書：各口座の画面の「残高証明書」から、証明日を指定して印刷（お客さまも本人の口座で利用可）",
    ],
  },
  {
    title: "13. 外部連携（Kong・MuleSoft・役席）",
    body: "ADMIN メニューの「外部連携」。他行・収納企業・為替相場の提供元からのデータを、Kong（API ゲートウェイ）→ MuleSoft（変換）→ 勘定系の順に取り込みます。",
    items: [
      "画面の API は Kong 経由（ログイン 20 回/分・API 1,200 回/分の制限、Liberty 2 台のヘルスチェック付き負荷分散）",
      "連携先は http://localhost:8088/integration/v1/*（API キー＋連携先ごとの許可）。鍵は .env の KONG_KEY_*",
      "全銀データ（120 桁固定長・UTF-8 / Shift_JIS）：件数と合計を検証し、口座番号と受取人名が一致すれば入金、不一致は保留。同じファイルを再送しても二重に入金されない",
      "請求データ（CSV）と為替相場（JSON）も同じ画面から送信でき、結果と実行履歴（誰が・いつ・何件）を確認できる",
      "MuleSoft は Mule 4 アプリ一式（integration/mulesoft）。ローカルでは同じ API 契約の互換コンテナ（mule）で動く",
      "初回のみ：12_integration.sql を適用し、python integration/scripts/set_service_password.py で連携用アカウントを有効化",
    ],
  },
  {
    title: "14. 停止・初期化・環境メモ",
    body: "データを最初の状態に戻すときはボリュームごと削除します。",
    items: [
      "停止：docker compose down",
      "初期化：docker compose down -v（次回起動時に db/init の SQL で再作成）",
      "既存の DB に情報系の表を追加：docker compose exec oracle bash -c \"sqlplus -s / as sysdba @/container-entrypoint-initdb.d/08_information.sql\"",
      "既存の DB を最新に：同じ要領で 09_loan_management.sql（融資管理）・10_dw_performance.sql（情報系の索引）・11_batch_operations.sql（銀行バッチ）・12_integration.sql（外部連携）も 1 回ずつ実行",
      "Windows でビルドに失敗するとき：COMPOSE_BAKE=false DOCKER_CONTEXT=desktop-linux を付ける",
      "API の稼働確認：http://localhost:9080/bank/api/health（ログイン不要）",
    ],
  },
] as const;

// 開閉状態を保存する localStorage のキー
const STORAGE_KEY = "bank-sample.guide.open";

/** ドラッグ後のパネル位置（画面左上からの px） */
type Pos = { x: number; y: number };

/**
 * パネルの開閉状態を管理するフック。開閉を localStorage に保存し、ページを移動・再読み込みしても状態を保つ。
 * SSR とのずれを避けるため、保存値の読み込みはマウント後（useEffect）に行う。
 */
export function useGuideOpen(): [boolean, (open: boolean) => void] {
  const [open, setOpenState] = useState(false);
  useEffect(() => {
    let saved: string | null = null;
    try {
      saved = localStorage.getItem(STORAGE_KEY);
    } catch {
      // 保存できない環境では毎回閉じた状態から
    }
    // 初回（未保存）は開いて案内し、閉じたらその状態を覚える
    setOpenState(saved !== "0");
  }, []);
  const setOpen = useCallback((next: boolean) => {
    setOpenState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
    } catch {
      // 保存できなくても表示の切り替えはできる
    }
  }, []);
  return [open, setOpen];
}

/** UsageGuidePanel の引数：表示するか・閉じるときの処理 */
type Props = {
  open: boolean;
  onClose: () => void;
};

/** 利用手順パネル本体。初期位置は CSS で右下、ドラッグすると以降は left/top で位置を指定する。 */
export function UsageGuidePanel({ open, onClose }: Props) {
  const panelRef = useRef<HTMLDivElement>(null);
  // ドラッグ中のポインター ID と、パネル左上からのつかみ位置のずれ（再描画不要なので ref で持つ）
  const dragRef = useRef<{ pointerId: number; dx: number; dy: number } | null>(null);
  const [expanded, setExpanded] = useState(true);
  const [pos, setPos] = useState<Pos | null>(null);
  const [dragging, setDragging] = useState(false);

  // 画面の外に出ないようにする（ウィンドウを小さくしたときも）
  const clamp = useCallback((x: number, y: number): Pos => {
    const w = panelRef.current?.offsetWidth ?? 420;
    return {
      x: Math.min(Math.max(0, x), Math.max(0, window.innerWidth - w)),
      y: Math.min(Math.max(0, y), Math.max(0, window.innerHeight - 56)),
    };
  }, []);

  // 開いている間だけ Esc キーで閉じる処理と、ウィンドウサイズ変更時の位置補正を登録する
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    const onResize = () => setPos((p) => (p ? clamp(p.x, p.y) : p));
    window.addEventListener("keydown", onKey);
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", onResize);
    };
  }, [open, onClose, clamp]);

  // ドラッグ開始：ボタン（data-ug-control）上では開始しない。ポインターをキャプチャし、パネル外に出ても追従させる。
  const onPointerDown = (e: React.PointerEvent<HTMLElement>) => {
    if ((e.target as HTMLElement).closest("[data-ug-control]") || e.button !== 0 || !panelRef.current) return;
    const rect = panelRef.current.getBoundingClientRect();
    setPos({ x: rect.left, y: rect.top });
    dragRef.current = { pointerId: e.pointerId, dx: e.clientX - rect.left, dy: e.clientY - rect.top };
    setDragging(true);
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  // ドラッグ中：つかんだ位置のずれを保ったまま、画面内に収まる位置へ移動する
  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== e.pointerId) return;
    setPos(clamp(e.clientX - drag.dx, e.clientY - drag.dy));
  };

  // ドラッグ終了（キャンセル時も同じ処理）
  const onPointerUp = (e: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== e.pointerId) return;
    dragRef.current = null;
    setDragging(false);
    e.currentTarget.releasePointerCapture(e.pointerId);
  };

  if (!open) return null;

  // 一度ドラッグしたら、CSS の右下配置を打ち消して left/top の固定位置にする
  const style = pos
    ? ({ left: pos.x, top: pos.y, right: "auto", bottom: "auto", margin: 0 } as const)
    : undefined;

  return (
    <div
      ref={panelRef}
      className={`${styles.panel}${expanded ? "" : ` ${styles.collapsed}`}${dragging ? ` ${styles.dragging}` : ""}`}
      style={style}
      role="dialog"
      aria-label="利用手順"
      aria-modal="false"
    >
      {/* 見出し部分がドラッグのつかみ手。右側に開閉ボタンと閉じるボタン */}
      <header
        className={styles.header}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <div className={styles.headerText}>
          <span aria-hidden>☰</span>
          <div className={styles.headerTitles}>
            <strong>利用手順</strong>
            <span className={styles.headerSub}>Architecture &amp; Ops</span>
          </div>
          <span className={styles.dragHint}>ドラッグで移動</span>
        </div>
        <div className={styles.headerActions}>
          <button
            type="button"
            className={styles.toggle}
            data-ug-control
            aria-label={expanded ? "折りたたむ" : "開く"}
            aria-expanded={expanded}
            onClick={() => setExpanded((v) => !v)}
          >
            {expanded ? "▼" : "▲"}
          </button>
          <button type="button" className={styles.closeBtn} data-ug-control aria-label="閉じる" onClick={onClose}>
            ×
          </button>
        </div>
      </header>

      {expanded ? (
        <div className={styles.body}>
          <div className={styles.hero}>
            <p className={styles.heroKicker}>Portfolio-ready demo</p>
            <h2 className={styles.heroTitle}>永劫銀行 ― オンラインバンキング</h2>
            <p className={styles.heroLead}>
              口座照会・入出金・振込／振替から、口座開設と本人確認、預金商品、為替・口座振替、融資、リスク管理・AML、
              夜間バッチと監査ログまでを、お客さま・窓口担当・役席の 3 つの役割で再現するデモです。データはすべて架空で、
              本番利用はできません。
            </p>
            <div className={styles.stack} aria-label="Tech stack">
              {techStack.map((tag) => (
                <span key={tag} className={styles.stackPill}>
                  {tag}
                </span>
              ))}
            </div>
          </div>

          <section className={styles.featured} aria-label="アーキテクチャ">
            <div className={styles.featuredHead}>
              <span className={styles.featuredBadge}>Architecture</span>
              <strong>冗長構成と 1 取引 1 トランザクション</strong>
            </div>
            <p>
              ブラウザ → nginx → Next.js ×2 → nginx → Liberty ×2 → Oracle。更新は「冪等キー登録 → 口座行ロック →
              残高検証 → 残高更新 → 明細登録 → コミット」を 1 つのトランザクションで行い、どのインスタンスが止まっても
              サービスは続きます。
            </p>
          </section>

          <section className={styles.featured} aria-label="役割">
            <div className={styles.featuredHead}>
              <span className={styles.featuredBadge}>Roles</span>
              <strong>ログインと役割（パスワード BankDemo#2026）</strong>
            </div>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th style={{ width: "36%" }}>利用者ID</th>
                  <th style={{ width: "18%" }}>役割</th>
                  <th>できること</th>
                </tr>
              </thead>
              <tbody>
                {roles.map(([id, role, can]) => (
                  <tr key={id}>
                    <td>
                      <code>{id}</code>
                    </td>
                    <td>{role}</td>
                    <td>{can}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className={styles.featured} aria-label="推奨フロー">
            <div className={styles.featuredHead}>
              <span className={styles.featuredBadge}>Recommended</span>
              <strong>5 分で一通り試す</strong>
            </div>
            <ul className={styles.items}>
              {quickFlow.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>

          <section className={styles.featured} aria-label="運用のルール">
            <div className={styles.featuredHead}>
              <span className={styles.featuredBadge}>Readiness</span>
              <strong>やってよいこと・やらないこと</strong>
            </div>
            <p>
              <strong>やってよいこと</strong>
            </p>
            <ul className={styles.items}>
              {doList.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
            <p>
              <strong>やらないこと</strong>
            </p>
            <ul className={styles.items}>
              {dontList.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>

          <section className={styles.featured} aria-label="うまくいかないとき">
            <div className={styles.featuredHead}>
              <span className={styles.featuredBadge}>Troubleshoot</span>
              <strong>うまくいかないとき</strong>
            </div>
            <ul className={styles.items}>
              {troubles.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </section>

          <figure className={styles.diagram} aria-label="Service topology">
            <figcaption>Service topology</figcaption>
            <pre>{archDiagram}</pre>
          </figure>

          <p className={styles.scrollHint}>↓ 起動から運用までの手順</p>

          {/* 機能別の手順（steps 定数から生成） */}
          <ol className={styles.steps}>
            {steps.map((step) => (
              <li key={step.title}>
                <strong>{step.title}</strong>
                <p>{step.body}</p>
                <ul className={styles.items}>
                  {step.items.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>

          <p className={styles.footer}>
            ▼▲ で開閉 · ドラッグで移動 · × または Esc で閉じる · サイドバーの「利用手順」でいつでも再表示 ·
            架空データのデモ（本番利用不可）
          </p>
        </div>
      ) : null}
    </div>
  );
}
