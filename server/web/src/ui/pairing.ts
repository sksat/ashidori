// ペアリング (docs/web-ui.md): the 3-step stepper. The protocol behind it
// (data/session.ts `Pairing`) is not implemented yet, so step 1 reports
// that. Dev builds add a panel that registers with a pasted device token
// and batch key; production builds do not contain it.

import { DEV_PANEL, PAIRING_UNAVAILABLE, devSession, type KeyStorage, type Pairing } from "../data/session";
import type { Ctx } from "./context";
import { h, icon, replace } from "./dom";

const CODE_RE = /^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/;

/** Hyphens, spaces and case do not matter; I, L and O read as 1, 1 and 0 (Crockford). */
export function normalizeCode(s: string): string {
  return s.toUpperCase().replace(/[\s-]/g, "").replace(/[IL]/g, "1").replace(/O/g, "0");
}

let pairing: Pairing = PAIRING_UNAVAILABLE;

/** For the pairing implementation to plug in. */
export function setPairing(p: Pairing): void {
  pairing = p;
}

function optIn(): HTMLElement {
  const more = h(
    "ul",
    { hidden: true, id: "optin-more" },
    h("li", null, `配信元: ${location.host}`),
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
  const steps = ["コードの入力", "確認コードの照合", "完了"];
  return h(
    "ol",
    { class: "stepper", "aria-label": "手順" },
    steps.map((t, i) => h("li", { "aria-current": step === i + 1 ? "step" : null, class: step > i + 1 ? "done" : "" }, h("span", { class: "n" }, step > i + 1 ? icon("check") : String(i + 1)), t)),
  );
}

function codeStep(): HTMLElement {
  const err = h("p", { class: "ferr", id: "pair-err", hidden: true });
  const input = h("input", {
    id: "pair-code",
    class: "codein",
    autocomplete: "one-time-code",
    autocapitalize: "characters",
    spellcheck: "false",
    maxlength: 11,
    "aria-describedby": "pair-hint",
  });
  const showErr = (msg: string | null) => {
    err.hidden = !msg;
    replace(err, ...(msg ? [icon("xcirc"), msg] : []));
    if (msg) {
      input.setAttribute("aria-invalid", "true");
      input.setAttribute("aria-describedby", "pair-hint pair-err");
    } else {
      input.removeAttribute("aria-invalid");
      input.setAttribute("aria-describedby", "pair-hint");
    }
  };
  const submit = h("button", { class: "btn primary", type: "submit" }, "次へ");
  const form = h(
    "form",
    { novalidate: true },
    h("label", { for: "pair-code", class: "flabel" }, "コード"),
    input,
    h("p", { class: "hint", id: "pair-hint" }, "8 文字。ハイフンは省略できます。"),
    err,
    h("div", { class: "formact" }, submit),
  );
  input.addEventListener("blur", () => {
    const c = normalizeCode(input.value);
    if (c && !CODE_RE.test(c)) showErr("8 文字の英数字を入力してください。");
  });
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const c = normalizeCode(input.value);
    if (!CODE_RE.test(c)) {
      showErr("8 文字の英数字を入力してください。");
      input.focus();
      return;
    }
    showErr(null);
    submit.setAttribute("aria-busy", "true");
    submit.textContent = "確認中";
    try {
      await pairing.start(c);
    } catch {
      await pairing.cancel();
      showErr("このサーバは、ブラウザの登録にまだ対応していません。");
      input.focus();
    } finally {
      submit.removeAttribute("aria-busy");
      submit.textContent = "次へ";
    }
  });
  return h(
    "section",
    { class: "card ppanel" },
    h("h2", null, "コードの入力"),
    h("p", null, "アプリの「ブラウザでも見る」で発行したコードを入力してください。"),
    form,
  );
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
  replace(
    main,
    h(
      "div",
      { class: "pair" },
      h("h1", { id: "h1", tabindex: -1 }, "このブラウザを登録"),
      optIn(),
      stepper(1),
      codeStep(),
      devPanel(ctx),
    ),
  );
}

export function renderUnregistered(ctx: Ctx, main: HTMLElement): void {
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
          h("div", { class: "grow" }, h("h1", { id: "h1", tabindex: -1 }, "このブラウザの登録は解除されました"), h("p", null, "鍵はこのブラウザから消去しました。")),
          h("button", { class: "btn primary", type: "button", onclick: () => ctx.go("timeline") }, "もう一度登録する"),
        ),
      ),
    ),
  );
}
