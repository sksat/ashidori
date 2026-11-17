// 端末: the app's status rows of the user's devices, the selected device's
// details, and 「このブラウザ」 (docs/web-ui.md, "端末").

import type { DeviceStatus } from "../data/model";
import { RECORDING_MODES } from "../data/records";
import type { KeyStorage } from "../data/session";
import type { Ctx } from "./context";
import { confirmDialog, emoji, h, icon, replace, srOnly, toast } from "./dom";
import { absTime, relTime, shortTime, STALE_MS, timeEl } from "./format";
import { MapPanel } from "./mappanel";

let panel: MapPanel | null = null;
let refreshing = false;

function recText(s: DeviceStatus | null): HTMLElement {
  if (!s) return h("span", { class: "muted" }, "状態なし");
  if (!s.recording.on) return h("span", { class: "muted" }, "■ 記録停止中");
  const m = RECORDING_MODES[s.recording.mode] ?? { label: s.recording.mode || "記録中", token: "--rm-bal" };
  return h("span", { class: "rec", css: { "--c": `var(${m.token})` } }, `● ${m.label}`);
}

function batText(s: DeviceStatus | null): HTMLElement | null {
  if (!s || s.battery.pct < 0) return null;
  const low = !s.battery.charging && s.battery.pct <= 15;
  return h("span", { class: low ? "low" : "" }, emoji(s.battery.charging ? "⚡" : "🔋"), ` ${s.battery.pct}%`, s.battery.charging ? srOnly("（充電中）") : null);
}

function age(t: number | null): HTMLElement {
  if (t === null) return h("span", { class: "age" }, "未受信");
  const stale = Date.now() - t > STALE_MS;
  return h("span", { class: `age${stale ? " stale" : ""}` }, stale ? icon("alert") : null, relTime(t), stale ? srOnly("（更新停止）") : null);
}

export function renderDevices(ctx: Ctx, main: HTMLElement): void {
  const store = ctx.store;
  const st = ctx.state;
  if (st.load.kind === "loading" || !store) {
    replace(
      main,
      h("h1", { class: "sr-only", id: "h1", tabindex: -1 }, "端末"),
      h("div", { class: "devgrid" }, h("div", { class: "card", "aria-busy": "true" }, h("div", { class: "skel sk-line h" }), h("div", { class: "skel sk-line row" }), h("div", { class: "skel sk-line row" }), srOnly("読み込み中"))),
    );
    return;
  }
  const ids = store.deviceIds().filter((id) => !store.devices.find((d) => d.id === id)?.revoked && id !== ctx.session?.deviceId);
  if (!st.devSel || !ids.includes(st.devSel)) st.devSel = ids[0] ?? null;

  const rows = ids.map((id) => {
    const e = store.status.get(id);
    const s = e?.status ?? null;
    return h(
      "li",
      null,
      h(
        "button",
        {
          class: "devrow",
          type: "button",
          "aria-pressed": String(id === st.devSel),
          onclick: () => {
            st.devSel = id;
            ctx.render();
          },
        },
        h("span", { class: "l1" }, h("b", null, store.deviceName(id)), age(s ? s.t : null)),
        h("span", { class: "l2" }, recText(s), batText(s)),
        h(
          "span",
          { class: "l3" },
          s?.lastFix ? [emoji("📍"), ` 最終測位 ${relTime(s.lastFix.t)}`] : null,
          e?.error ? h("span", { class: "low" }, ` · 最新の状態を読めません`) : null,
        ),
        h("span", { class: "chev", "aria-hidden": "true" }, icon("chevR")),
      ),
    );
  });

  const refresh = h(
    "button",
    {
      class: "btn tonal sm",
      type: "button",
      onclick: async () => {
        if (refreshing) return;
        refreshing = true;
        refresh.setAttribute("aria-busy", "true");
        refresh.lastChild!.textContent = "更新中";
        try {
          await store.refreshStatus();
          toast("更新しました");
        } catch {
          toast("更新できませんでした。時間をおいて再試行してください。");
        } finally {
          refreshing = false;
          ctx.render();
        }
      },
    },
    icon("refresh"),
    "更新",
  );

  const listCard = h(
    "section",
    { class: "card", "aria-labelledby": "dl-h" },
    h("div", { class: "cardhead" }, h("h2", { id: "dl-h", class: "grow" }, "端末"), refresh),
    ids.length ? h("ul", { class: "devlist" }, rows) : h("div", { class: "empty" }, icon("phone"), h("b", null, "端末の状態はまだありません"), h("span", null, "アプリで同期すると、ここに表示されます。")),
  );

  const sess = ctx.session;
  const storageRadio = (v: KeyStorage, label: string) =>
    h(
      "label",
      { class: "radio" },
      h("input", {
        type: "radio",
        name: "ks",
        value: v,
        checked: sess?.storage === v,
        onchange: async () => {
          if (!sess) return;
          sess.storage = v;
          await ctx.sessions.save(sess);
          toast(v === "browser" ? "このブラウザに保存しました" : "このタブを閉じると鍵を消去します");
        },
      }),
      label,
    );
  const browserCard = h(
    "section",
    { class: "card", "aria-labelledby": "tb-h" },
    h("div", { class: "cardhead" }, h("h2", { id: "tb-h", tabindex: -1, class: "grow" }, icon("laptop"), " このブラウザ"), h("span", { class: "pill op" }, "閲覧専用")),
    h(
      "div",
      { class: "kv" },
      h(
        "dl",
        null,
        h("dt", null, "名前"),
        h("dd", null, sess?.name ?? ""),
        h("dt", null, "配信元"),
        h("dd", { class: "anywhere" }, location.host),
        h("dt", null, "登録"),
        h("dd", null, timeEl(sess?.registeredAt ?? null)),
        sess?.expiresAt ? [h("dt", null, "期限"), h("dd", null, `${absTime(sess.expiresAt)} まで`)] : null,
        sess?.source === "pairing" ? [h("dt", null, "鍵の保存"), h("dd", null, "このタブを閉じるまで")] : null,
      ),
    ),
    // Only the dev stub can keep its key in IndexedDB; a paired browser keeps it in memory only.
    sess?.source === "dev"
      ? h("div", { class: "zone" }, h("fieldset", null, h("legend", null, "鍵の保存"), storageRadio("tab", "このタブを閉じるまで"), storageRadio("browser", "このブラウザに保存"), h("p", { class: "hint" }, "共用の PC では保存しないでください。")))
      : null,
    h(
      "div",
      { class: "zone danger" },
      h("p", null, "このブラウザから鍵を消去し、登録を取り消します。"),
      h(
        "button",
        {
          class: "btn danger",
          type: "button",
          onclick: async () => {
            const ok = await confirmDialog({
              title: "このブラウザの登録を解除しますか",
              items: ["このブラウザから鍵と表示中の記録を消去します。", "再度閲覧するには、アプリで再登録が必要です。"],
              action: "登録を解除",
            });
            if (ok) await ctx.unregister();
          },
        },
        "登録を解除",
      ),
    ),
  );

  const detail = st.devSel ? deviceDetail(ctx, st.devSel) : null;
  replace(main, h("h1", { class: "sr-only", id: "h1", tabindex: -1 }, "端末"), h("div", { class: "devgrid" }, h("div", { class: "col" }, listCard, browserCard), detail));
}

function deviceDetail(ctx: Ctx, id: string): HTMLElement {
  const store = ctx.store!;
  const s = store.status.get(id)?.status ?? null;
  const stale = s ? Date.now() - s.t > STALE_MS : false;
  if (!panel) panel = new MapPanel("端末の最終測位");
  const color = s?.recording.on ? `var(${(RECORDING_MODES[s.recording.mode] ?? { token: "--rm-bal" }).token})` : "var(--line-strong)";
  const resolved = s?.lastFix ? getComputedStyle(document.documentElement).getPropertyValue(color.slice(4, -1)).trim() || null : null;
  panel.setContent({ routes: [], stays: [], fixes: s?.lastFix ? [{ lon: s.lastFix.lon, lat: s.lastFix.lat, acc: s.lastFix.acc, color: resolved }] : [] });
  panel.setHead(h("b", null, s?.lastFix ? `最終測位 ${shortTime(s.lastFix.t)}` : "最終測位なし"));
  panel.setOverlay(...(s?.lastFix ? [] : ["位置は未受信"]));
  const model = s?.device.model ?? "";
  const app = s?.app.versionName ? `${s.app.versionName}（${s.app.versionCode}）` : "";
  return h(
    "section",
    { class: "devdetail", "aria-labelledby": "dd-h" },
    h("h2", { id: "dd-h" }, store.deviceName(id)),
    panel.el,
    h(
      "section",
      { class: "card kv" },
      h(
        "dl",
        null,
        h("dt", null, "更新"),
        h("dd", { class: stale ? "stale-t" : "" }, s ? `${shortTime(s.t)}（${relTime(s.t)}）` : "未受信", stale ? srOnly("更新停止") : null),
        h("dt", null, "記録"),
        h("dd", null, recText(s), s?.recording.on && s.recording.since > 0 ? `・${shortTime(s.recording.since)} から` : ""),
        h("dt", null, "電池"),
        h("dd", null, batText(s) ?? "—", s?.battery.charging ? " 充電中" : ""),
      ),
    ),
    h(
      "section",
      { class: "card kv" },
      h("h3", null, "位置"),
      h(
        "dl",
        null,
        h("dt", null, "座標"),
        h("dd", { class: "num" }, s?.lastFix ? `${s.lastFix.lat.toFixed(5)}, ${s.lastFix.lon.toFixed(5)}${Number.isFinite(s.lastFix.acc) ? `・±${Math.round(s.lastFix.acc)} m` : ""}` : "—"),
        h("dt", null, "測位"),
        h("dd", null, s?.lastFix ? `${shortTime(s.lastFix.t)}（${relTime(s.lastFix.t)}）` : "—"),
      ),
    ),
    h(
      "section",
      { class: "card kv" },
      h("h3", null, "端末"),
      h("dl", null, h("dt", null, "機種"), h("dd", null, model || "—"), h("dt", null, "アプリ"), h("dd", null, app || "—"), h("dt", null, "受信"), h("dd", null, store.fetchedAt ? shortTime(store.fetchedAt) : "—")),
    ),
  );
}
