"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "./UsageGuidePanel.module.css";

/*
 * 利用手順パネル（StockAI の UsageGuidePanel と同じデザイン）。
 * 見出しをドラッグで移動、▼▲ で本文の開閉、× または Esc で閉じる。画面を移動しても開いたまま。
 */

const techStack = [
  "Next.js 15 · React 19",
  "Struts 2 · Liberty",
  "Oracle 23ai",
  "nginx ×2 冗長",
  "冪等キー",
  "監査ハッシュチェーン",
] as const;

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

const quickFlow = [
  "supervisor1 でログイン（パスワード BankDemo#2026）",
  "「口座一覧」で 1000001 を開き、入金 10,000 円 → 取引履歴に反映されることを確認",
  "「振込・振替」で 1000001 → 1000003 へ 3,000 円を振込（入力 → 確認 → 完了）",
  "「顧客・口座開設」で申込 → 本人確認（受付者とは別の役席）→ 普通預金を開設",
  "「業務処理」で夜間バッチの結果を確認し、「監査ログ」で改ざん検知を実行",
] as const;

const roles = [
  ["yamada / sato / suzuki / takahashi", "お客さま", "本人名義の口座の照会・履歴・利息見込み、振込・振替"],
  ["teller1", "窓口担当", "全口座の照会、入金・出金、口座開設の申込受付・開設、顧客ログインの発行、顧客分析"],
  ["supervisor1 / supervisor2", "役席", "窓口の操作 + 本人確認の判定、解約、休眠解除、利息決算、リスク・AML・業務処理・監査ログ、経営ダッシュボード・帳票"],
] as const;

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

const troubles = [
  "ログイン画面に戻される：15 分無操作でタイムアウト。もう一度ログイン",
  "「この画面を利用する権限がありません」：役割が足りない（ADMIN メニューは役席のみ）",
  "5 回続けてログインに失敗：15 分ロック。時間をおいて再試行",
  "画面が表示されない：docker compose ps で oracle・liberty・frontend・lb が healthy か確認",
  "残高不足・上限超過：1 回 1,000 万円・1 日 2,000 万円まで。凍結・休眠・解約口座は取引不可",
] as const;

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
    title: "12. 停止・初期化・環境メモ",
    body: "データを最初の状態に戻すときはボリュームごと削除します。",
    items: [
      "停止：docker compose down",
      "初期化：docker compose down -v（次回起動時に db/init の SQL で再作成）",
      "既存の DB に情報系の表を追加：docker compose exec oracle bash -c \"sqlplus -s / as sysdba @/container-entrypoint-initdb.d/08_information.sql\"",
      "Windows でビルドに失敗するとき：COMPOSE_BAKE=false DOCKER_CONTEXT=desktop-linux を付ける",
      "API の稼働確認：http://localhost:9080/bank/api/health（ログイン不要）",
    ],
  },
] as const;

const STORAGE_KEY = "bank-sample.guide.open";

type Pos = { x: number; y: number };

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

type Props = {
  open: boolean;
  onClose: () => void;
};

export function UsageGuidePanel({ open, onClose }: Props) {
  const panelRef = useRef<HTMLDivElement>(null);
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

  const onPointerDown = (e: React.PointerEvent<HTMLElement>) => {
    if ((e.target as HTMLElement).closest("[data-ug-control]") || e.button !== 0 || !panelRef.current) return;
    const rect = panelRef.current.getBoundingClientRect();
    setPos({ x: rect.left, y: rect.top });
    dragRef.current = { pointerId: e.pointerId, dx: e.clientX - rect.left, dy: e.clientY - rect.top };
    setDragging(true);
    e.currentTarget.setPointerCapture(e.pointerId);
  };

  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== e.pointerId) return;
    setPos(clamp(e.clientX - drag.dx, e.clientY - drag.dy));
  };

  const onPointerUp = (e: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== e.pointerId) return;
    dragRef.current = null;
    setDragging(false);
    e.currentTarget.releasePointerCapture(e.pointerId);
  };

  if (!open) return null;

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
