// ペアリング (docs/web-ui.md, docs/design/browser-pairing-v1.md): a 3-step
// stepper. 1 QR コードの読み取り: this browser shows a QR code that the app's
// 「ブラウザでも見る」 scans, and the code for a phone that cannot scan (typed
// there). 2 確認コードの照合: both show the same four words; the user approves
// in the app and presses 「一致する」 here. 3 完了.
// Mismatch, expiry and failure discard the temporary key and offer one
// 「最初からやり直す」. Dev builds add a panel that registers with a pasted
// device token and batch key; production builds do not contain it.

import type { EndReason, PairingEvent } from "../data/pairing";
import { DEV_PANEL, devSession, pairingV1, type KeyStorage, type Pairing, type PairingControl, type ViewerSession } from "../data/session";
import { viewerQrUri } from "../data/viewerqr";
import type { Ctx } from "./context";
import { $, h, icon, replace, toast } from "./dom";
import { absTime } from "./format";
import { qrSvg } from "./qr";

const CODE_RE = /^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/;

/** Hyphens, spaces and case do not matter; I, L and O read as 1, 1 and 0 (Crockford). */
export function normalizeCode(s: string): string {
  return s.toUpperCase().replace(/[\s-]/g, "").replace(/[IL]/g, "1").replace(/O/g, "0");
}

/** "ABCD2345" → "ABCD-2345". */
export function displayCode(code: string): string {
  return CODE_RE.test(code) ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}

/** Remaining time as the page shows it: "9:58". */
export function fmtLeft(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

let pairing: Pairing = pairingV1();

/** For tests and alternative implementations. */
export function setPairing(p: Pairing): void {
  pairing = p;
}

/** The stepper's steps. */
export const STEPS = ["QR コードの読み取り", "確認コードの照合", "完了"] as const;

/** The step (1 to 3) a view is at; ended views show no stepper. */
export function stepOf(k: View["k"]): number {
  return k === "words" ? 2 : k === "done" ? 3 : 1;
}

/**
 * The QR code's text for this page's origin, or null if the origin cannot
 * be put in it (the code still works).
 */
export function qrText(origin: string, e: { id: string; code: string; commit: Uint8Array }): string | null {
  try {
    return viewerQrUri(origin, e.id, e.code, e.commit);
  } catch {
    return null;
  }
}

export type View =
  | { k: "intro" }
  | { k: "code"; code: string | null; qr: string | null; claimed: boolean }
  | { k: "words"; words: string[]; scope: string; ttl: number; waiting: boolean; limit: boolean }
  | { k: "done"; session: ViewerSession }
  | { k: "ended"; reason: EndReason };

let view: View = { k: "intro" };
let control: PairingControl | null = null;
let ticker: ReturnType<typeof setInterval> | null = null;
let announced = new Set<number>();
let lastCtx: Ctx | null = null;

function stopTicker(): void {
  if (ticker !== null) clearInterval(ticker);
  ticker = null;
}

/** Updates the countdown each second; announces only at 1 minute and 30 seconds. */
function startTicker(): void {
  stopTicker();
  announced = new Set();
  ticker = setInterval(() => {
    if (!control) return stopTicker();
    const left = control.remainingMs();
    const el = $("#pair-left");
    if (el) el.textContent = fmtLeft(left);
    const sr = $("#pair-left-sr");
    for (const mark of [60_000, 30_000]) {
      if (left <= mark && left > mark - 1000 && !announced.has(mark) && sr) {
        announced.add(mark);
        sr.textContent = mark === 60_000 ? "残り 1 分です。" : "残り 30 秒です。";
      }
    }
  }, 1000);
}

function show(next: View, focus = true): void {
  view = next;
  if (next.k === "ended" || next.k === "done" || next.k === "intro") {
    stopTicker();
    control = null;
  }
  lastCtx?.render();
  if (focus) $<HTMLElement>("#pair-h")?.focus();
}

function onEvent(attempt: PairingControl, e: PairingEvent): void {
  // Events of an attempt that the page has left are discarded.
  if (attempt !== control) return;
  switch (e.kind) {
    case "code":
      show({ k: "code", code: e.code, qr: qrText(location.origin, e), claimed: false });
      break;
    case "claimed":
      if (view.k === "code") show({ ...view, claimed: true }, false);
      break;
    case "words":
      show({ k: "words", words: e.words, scope: e.scope, ttl: e.ttl, waiting: false, limit: false });
      break;
    case "waiting":
      if (view.k === "words") show({ ...view, waiting: true, limit: false }, false);
      $<HTMLElement>("#pair-wait")?.focus();
      break;
    case "limit":
      if (view.k === "words") show({ ...view, waiting: true, limit: true }, false);
      $<HTMLElement>("#pair-wait")?.focus();
      break;
    case "done":
      show({ k: "done", session: e.session });
      break;
    case "ended":
      show({ k: "ended", reason: e.reason });
      break;
  }
}

/** Starts a new attempt, leaving any earlier one. */
function begin(): void {
  const prev = control;
  if (prev) void prev.cancel();
  view = { k: "code", code: null, qr: null, claimed: false };
  let self: PairingControl | null = null;
  const queued: PairingEvent[] = [];
  self = pairing.start((e) => (self ? onEvent(self, e) : queued.push(e)));
  control = self;
  for (const e of queued) onEvent(self, e);
  startTicker();
  if (view.k === "code" && view.code === null) show(view);
}

function optIn(): HTMLElement {
  const more = h(
    "ul",
    { hidden: true, id: "optin-more" },
    h("li", null, `配信元: ${location.host}`),
    h("li", null, "このブラウザは、これまでの記録と、これから送る記録を読めます。"),
    h("li", null, "期限はサーバが記録を渡すのをやめるだけです。鍵が持ち出されると、期限の後も読めます。"),
    h("li", null, "サーバの管理者が画面を書き換えると、記録を見られる可能性があります。"),
    h("li", null, "登録はいつでも解除できます。"),
  );
  const btn = h("button", { class: "linkbtn", type: "button", "aria-expanded": "false", "aria-controls": "optin-more" }, "詳しく");
  btn.addEventListener("click", () => {
    more.hidden = !more.hidden;
    btn.setAttribute("aria-expanded", String(!more.hidden));
  });
  return h("div", { class: "optin" }, h("b", null, "ブラウザでも見る"), h("p", null, "アプリだけで見る場合より、保護は弱くなります。", btn), more);
}

function stepper(step: number): HTMLElement {
  return h(
    "ol",
    { class: "stepper", "aria-label": "手順" },
    STEPS.map((t, i) => h("li", { "aria-current": step === i + 1 ? "step" : null, class: step > i + 1 ? "done" : "" }, h("span", { class: "n" }, step > i + 1 ? icon("check") : String(i + 1)), t)),
  );
}

function heading(text: string): HTMLElement {
  return h("h2", { id: "pair-h", tabindex: -1 }, text);
}

function countdown(): HTMLElement {
  return h(
    "p",
    { class: "pleft" },
    "残り ",
    h("span", { id: "pair-left", class: "num", "aria-hidden": "true" }, fmtLeft(control?.remainingMs() ?? 0)),
    h("span", { id: "pair-left-sr", class: "sr-only", role: "status" }),
  );
}

const ttlLabel = (ttl: number) => (ttl === 86400 ? "24時間" : ttl === 3600 ? "1時間" : `${Math.round(ttl / 60)}分`);

function introStep(): HTMLElement {
  return h(
    "section",
    { class: "card ppanel" },
    heading(STEPS[0]),
    h("p", null, "このブラウザで記録を見るには、表示する QR コードをアプリで読み取り、アプリで承認します。"),
    h("div", { class: "formact" }, h("button", { class: "btn primary", type: "button", onclick: () => begin() }, "QR コードを表示")),
  );
}

function codeStep(v: Extract<View, { k: "code" }>): HTMLElement {
  const code = v.code;
  const copy = h(
    "button",
    {
      class: "btn tonal sm",
      type: "button",
      disabled: !code,
      onclick: async () => {
        if (!code) return;
        try {
          await navigator.clipboard.writeText(code);
          toast("コードをコピーしました");
        } catch {
          toast("コピーできませんでした。コードを入力してください。");
        }
      },
    },
    "コピー",
  );
  return h(
    "section",
    { class: "card ppanel", "aria-busy": code ? null : "true" },
    heading(STEPS[0]),
    h("p", null, "アプリの「ブラウザでも見る」で QR コードを読み取ってください。"),
    h("div", { class: "qrshow" }, v.qr ? qrSvg(v.qr, "アプリで読み取る QR コード") : h("span", { class: "qrwait" }, code ? "QR コードを表示できません。下のコードを入力してください。" : "QR コードを準備しています")),
    h("p", { class: "hint" }, "読み取れない場合は、アプリで「コードを入力」を選び、このコードを入力してください。"),
    h("div", { class: "codeshow" }, h("span", { class: "codebig", "aria-label": code ? `コード ${code.split("").join(" ")}` : "コードを準備しています" }, code ? displayCode(code) : "····-····"), copy),
    v.claimed ? h("p", { class: "pstate", role: "status" }, icon("okcirc"), "アプリで受け付けました。確認コードを準備しています。") : null,
    code ? countdown() : null,
    h("div", { class: "formact" }, h("button", { class: "btn ghost", type: "button", onclick: () => void abandon() }, "中止する")),
  );
}

function wordsStep(v: Extract<View, { k: "words" }>): HTMLElement {
  const acts = v.limit
    ? [
        h(
          "p",
          { class: "pstate", id: "pair-wait", tabindex: -1, role: "alert" },
          icon("alert"),
          "記録を読めるブラウザが上限（5 台）に達しています。アプリでほかのブラウザを終了してから、再試行してください。",
        ),
        h(
          "div",
          { class: "formact" },
          h("button", { class: "btn primary", type: "button", onclick: () => control?.retry() }, "再試行"),
          h("button", { class: "btn danger", type: "button", onclick: () => void control?.mismatch() }, "中止する"),
        ),
      ]
    : v.waiting
    ? [
        h("p", { class: "pstate", id: "pair-wait", tabindex: -1, role: "status" }, icon("info"), "アプリでの承認を待っています。"),
        h("div", { class: "formact" }, h("button", { class: "btn danger", type: "button", onclick: () => void control?.mismatch() }, "中止する")),
      ]
    : [
        h(
          "div",
          { class: "formact" },
          h("button", { class: "btn primary", type: "button", onclick: () => control?.confirm() }, "一致する"),
          h("button", { class: "btn danger", type: "button", onclick: () => void control?.mismatch() }, "一致しない"),
        ),
      ];
  return h(
    "section",
    { class: "card ppanel" },
    heading("確認コードの照合"),
    h("p", null, "アプリに表示された 4 語と同じであることを確かめてください。"),
    h("ol", { class: "sas", lang: "ja" }, v.words.map((w) => h("li", null, w))),
    h(
      "div",
      { class: "kv" },
      h("dl", null, h("dt", null, "範囲"), h("dd", null, v.scope === "batch-v1" ? "すべての記録の閲覧" : v.scope), h("dt", null, "期限"), h("dd", null, `${ttlLabel(v.ttl)}（アプリで選択）`)),
    ),
    countdown(),
    ...acts,
  );
}

function doneStep(ctx: Ctx, v: Extract<View, { k: "done" }>): HTMLElement {
  return h(
    "section",
    { class: "card ppanel" },
    heading("完了"),
    h("p", { class: "pstate" }, icon("okcirc"), "このブラウザを登録しました。"),
    h(
      "div",
      { class: "kv" },
      h(
        "dl",
        null,
        h("dt", null, "鍵の保存"),
        h("dd", null, "このタブを閉じるまで"),
        h("dt", null, "期限"),
        h("dd", null, v.session.expiresAt ? `${absTime(v.session.expiresAt)} まで` : "—"),
      ),
    ),
    h(
      "div",
      { class: "formact" },
      h(
        "button",
        {
          class: "btn primary",
          type: "button",
          onclick: async () => {
            const s = v.session;
            view = { k: "intro" };
            await ctx.signIn(s);
            ctx.go("timeline");
          },
        },
        "タイムラインを開く",
      ),
    ),
  );
}

const ENDED: Record<EndReason, [string, string]> = {
  mismatch: ["中止しました", "確認コードが一致しなかったため、中止しました。一時的な鍵は破棄しました。"],
  aborted: ["中止されました", "アプリまたはサーバで中止されました。一時的な鍵は破棄しました。"],
  expired: ["時間切れです", "10 分以内に完了しなかったため、中止しました。一時的な鍵は破棄しました。"],
  failed: ["登録できませんでした", "受け取った内容を確認できなかったため、中止しました。一時的な鍵は破棄しました。"],
  cancelled: ["中止しました", "一時的な鍵は破棄しました。"],
};

function endedStep(v: Extract<View, { k: "ended" }>): HTMLElement {
  const [title, text] = ENDED[v.reason];
  return h(
    "section",
    { class: "card ppanel" },
    h("div", { class: "errpanel" }, icon(v.reason === "expired" ? "alert" : "xcirc"), h("div", { class: "grow" }, heading(title), h("p", null, text))),
    h("div", { class: "formact" }, h("button", { class: "btn primary", type: "button", onclick: () => begin() }, "最初からやり直す")),
  );
}

async function abandon(): Promise<void> {
  const c = control;
  control = null;
  stopTicker();
  if (c) await c.cancel();
  show({ k: "intro" });
}

/** Dev builds only (import.meta.env.DEV): register with a pasted token and batch key. */
function devPanel(ctx: Ctx): HTMLElement | null {
  if (!DEV_PANEL) return null;
  const token = h("input", { id: "dev-token", class: "field", autocomplete: "off", spellcheck: "false" });
  const key = h("textarea", { id: "dev-key", class: "field", rows: 2, autocomplete: "off", spellcheck: "false" });
  const err = h("p", { class: "ferr", hidden: true, role: "alert" });
  let storage: KeyStorage = "tab";
  const radio = (v: KeyStorage, label: string) =>
    h("label", { class: "radio" }, h("input", { type: "radio", name: "dev-ks", value: v, checked: v === storage, onchange: () => (storage = v) }), label);
  const form = h(
    "form",
    { novalidate: true },
    h("label", { for: "dev-token", class: "flabel" }, "端末トークン"),
    token,
    h("p", { class: "hint" }, "43 文字の base64url。登録済みの端末のものを使います。"),
    h("label", { for: "dev-key", class: "flabel" }, "バッチ鍵"),
    key,
    h("p", { class: "hint" }, "32 バイト。16 進 64 文字または base64。"),
    h("fieldset", null, h("legend", null, "鍵の保存"), radio("tab", "このタブを閉じるまで"), radio("browser", "このブラウザに保存")),
    err,
    h("div", { class: "formact" }, h("button", { class: "btn primary", type: "submit" }, "開発用に登録")),
  );
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    try {
      const s = await devSession(token.value, key.value, storage);
      key.value = "";
      token.value = "";
      await abandon();
      await ctx.signIn(s);
    } catch (x) {
      err.hidden = false;
      replace(err, icon("xcirc"), x instanceof Error ? x.message : "登録できませんでした。");
    }
  });
  return h(
    "section",
    { class: "card ppanel devpanel", "aria-labelledby": "dev-h" },
    h("h2", { id: "dev-h" }, "開発用の登録"),
    h("p", { class: "hint" }, "開発用のビルドだけに表示します。本番のビルドには含まれません。"),
    form,
  );
}

export function renderPairing(ctx: Ctx, main: HTMLElement): void {
  lastCtx = ctx;
  const v = view;
  const step = stepOf(v.k);
  const body =
    v.k === "intro" ? introStep() : v.k === "code" ? codeStep(v) : v.k === "words" ? wordsStep(v) : v.k === "done" ? doneStep(ctx, v) : endedStep(v);
  replace(
    main,
    h(
      "div",
      { class: "pair" },
      h("h1", { id: "h1", tabindex: -1 }, "このブラウザを登録"),
      v.k === "intro" || v.k === "code" ? optIn() : null,
      v.k === "ended" ? null : stepper(step),
      body,
      v.k === "intro" ? devPanel(ctx) : null,
    ),
  );
}

export type UnregisteredReason = "revoked" | "expired";

export function renderUnregistered(ctx: Ctx, main: HTMLElement, reason: UnregisteredReason = "revoked"): void {
  replace(
    main,
    h(
      "div",
      { class: "pair" },
      h(
        "section",
        { class: "card ppanel" },
        h(
          "div",
          { class: "errpanel" },
          icon("lock"),
          h(
            "div",
            { class: "grow" },
            h("h1", { id: "h1", tabindex: -1 }, reason === "expired" ? "このブラウザの登録の期限が切れました" : "このブラウザの登録は解除されました"),
            h("p", null, "鍵はこのブラウザから消去しました。"),
          ),
          h("button", { class: "btn primary", type: "button", onclick: () => ctx.go("timeline") }, "もう一度登録する"),
        ),
      ),
    ),
  );
}
