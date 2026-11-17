// The viewer: タイムライン, マップ and 端末, as in the app's bottom tabs
// (docs/web-ui.md). Without a registration it shows the pairing steps.

import "./ui/styles.css";
import { Api, ApiError, ListingError } from "./data/api";
import { DEV_PANEL, Sessions, signOut, type ViewerSession } from "./data/session";
import { ViewerStore } from "./data/store";
import type { Ctx, Section, State } from "./ui/context";
import { renderDevices } from "./ui/devices";
import { $, h, icon, installTooltips, replace } from "./ui/dom";
import { absTime } from "./ui/format";
import { renderMapScreen } from "./ui/mapscreen";
import { renderPairing, renderUnregistered } from "./ui/pairing";
import { disposeTimeline, ensureDays, refreshTimeline, renderTimeline } from "./ui/timeline";

const SECTIONS: [Section, string, string][] = [
  ["timeline", "タイムライン", "history"],
  ["map", "マップ", "map"],
  ["devices", "端末", "phone"],
];
const TITLES: Record<Section, string> = { timeline: "タイムライン", map: "マップ", devices: "端末" };

const state: State = {
  section: sectionFromHash(),
  unit: "day",
  device: null,
  sel: null,
  map: { unit: "day", key: "" },
  devSel: null,
  load: { kind: "loading" },
  online: navigator.onLine,
};
/** Why the registration ended (the token got 401), until the user pairs again. */
let expired: false | "revoked" | "expired" = false;

function sectionFromHash(): Section {
  const s = location.hash.replace(/^#\/?/, "");
  return s === "map" || s === "devices" ? s : "timeline";
}

const ctx: Ctx = {
  state,
  store: null,
  session: null,
  sessions: new Sessions(),
  render,
  go(section, focus = true) {
    state.section = section;
    const hash = section === "timeline" ? "" : `#${section}`;
    if (location.hash !== hash) history.pushState(null, "", hash || location.pathname);
    render();
    if (focus) $<HTMLElement>("#h1")?.focus();
  },
  retry() {
    void start();
  },
  async unregister() {
    // The key and the token go first and the page shows it; a paired
    // browser then revokes its own device in the background (the dev stub
    // only forgets the key).
    await signOut(ctx.session, async () => {
      const cleared = forget();
      expired = false;
      render();
      await cleared;
    });
  },
  async signIn(s: ViewerSession) {
    await ctx.sessions.save(s);
    ctx.session = s;
    expired = false;
    await start();
  },
};

/** Erases the key and everything decrypted (登録の解除, a revoked token). */
async function forget(): Promise<void> {
  ctx.store?.close();
  ctx.store = null;
  ctx.session = null;
  state.device = null;
  state.sel = null;
  await ctx.sessions.clear();
}

function renderTop(): void {
  const registered = !!ctx.session;
  const link = (s: Section, label: string, ic: string, cls: string) =>
    h(
      "a",
      {
        class: cls,
        href: s === "timeline" ? "#" : `#${s}`,
        "aria-current": state.section === s ? "page" : null,
        onclick: (e: Event) => {
          e.preventDefault();
          ctx.go(s);
        },
      },
      cls === "tab" ? [icon(ic), label] : [h("span", { class: "pi" }, icon(ic)), label],
    );
  const themeBtn = h("button", { class: "iconbtn", type: "button", "aria-label": "配色", "data-tip": themeLabel() }, icon("theme"));
  themeBtn.addEventListener("click", () => {
    cycleTheme();
    themeBtn.setAttribute("data-tip", themeLabel());
  });
  replace(
    $("#top")!,
    h(
      "div",
      { class: "top-in" },
      h("div", { class: "host" }, icon("pin"), h("span", null, location.hostname)),
      registered ? h("nav", { class: "tabs", "aria-label": "主要" }, SECTIONS.map(([s, l, i]) => link(s, l, i, "tab"))) : null,
      h("div", { class: "top-right" }, registered ? h("span", { class: "chip" }, "閲覧専用") : null, themeBtn),
    ),
  );
  const bnav = $("#bnav")!;
  bnav.hidden = !registered;
  replace(bnav, registered ? SECTIONS.map(([s, l, i]) => link(s, l, i, "")) : null);
  document.documentElement.style.setProperty("--toph", `${$("#top")!.offsetHeight}px`);
}

function renderBanner(): void {
  const b = $("#banner")!;
  if (!state.online && ctx.store?.fetchedAt)
    replace(b, h("div", { class: "bar info", role: "status" }, icon("wifioff"), `オフライン: ${absTime(ctx.store.fetchedAt)} 時点の内容`));
  else if (DEV_PANEL && ctx.session?.source === "dev") replace(b, h("div", { class: "bar dev" }, icon("key"), "開発用の登録で表示しています。"));
  else replace(b);
}

function render(): void {
  renderTop();
  renderBanner();
  const main = $("#main")!;
  if (state.section !== "timeline") disposeTimeline();
  if (!ctx.session) {
    document.title = expired === "expired" ? "登録の期限切れ" : expired ? "登録の解除" : "このブラウザを登録";
    if (expired) renderUnregistered({ ...ctx, go: () => ((expired = false), render()) }, main, expired);
    else renderPairing(ctx, main);
    return;
  }
  document.title = TITLES[state.section];
  if (state.section === "map") renderMapScreen(ctx, main);
  else if (state.section === "devices") renderDevices(ctx, main);
  else renderTimeline(ctx, main);
}

function onStoreChange(): void {
  if (state.section === "timeline") refreshTimeline(ctx);
  else render();
}

async function start(): Promise<void> {
  const s = ctx.session;
  if (!s) return render();
  ctx.store?.close();
  const store = new ViewerStore(new Api({ token: s.token }), s.batchKey);
  ctx.store = store;
  state.load = { kind: "loading" };
  render();
  store.addEventListener("progress", () => state.section === "timeline" && refreshTimeline(ctx));
  store.addEventListener("change", onStoreChange);
  store.addEventListener("unauthorized", () => {
    // The browser's registration was revoked or expired: erase the key, then guide to pairing.
    const reason = s.expiresAt !== null && Date.now() >= s.expiresAt ? "expired" : "revoked";
    void forget().then(() => {
      expired = reason;
      render();
    });
  });
  try {
    await store.init();
    state.load = { kind: "ready" };
    render();
    await ensureDays(ctx, 7);
  } catch (e) {
    if (ctx.store !== store || !ctx.session) return;
    state.load =
      e instanceof ApiError
        ? { kind: "error", message: "記録を読み込めませんでした。", status: e.status }
        : e instanceof ListingError
          ? { kind: "error", message: "サーバの一覧に不正な行があり、読み込みを中止しました。", status: null }
          : { kind: "error", message: "記録を読み込めませんでした。接続を確認してください。", status: null };
    render();
  }
}

// ---- theme: automatic, light or dark (per viewer, in localStorage) ----

const THEMES = ["auto", "light", "dark"] as const;
type Theme = (typeof THEMES)[number];

function currentTheme(): Theme {
  try {
    const t = localStorage.getItem("theme");
    return t === "light" || t === "dark" ? t : "auto";
  } catch {
    return "auto";
  }
}

function applyTheme(t: Theme): void {
  if (t === "auto") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", t);
}

function themeLabel(): string {
  return { auto: "配色: 自動", light: "配色: ライト", dark: "配色: ダーク" }[currentTheme()];
}

function cycleTheme(): void {
  const next = THEMES[(THEMES.indexOf(currentTheme()) + 1) % THEMES.length]!;
  try {
    if (next === "auto") localStorage.removeItem("theme");
    else localStorage.setItem("theme", next);
  } catch {
    // Storage blocked: the choice lasts for this page only.
  }
  applyTheme(next);
}

// ---- boot ----

function shell(): void {
  document.body.append(
    h("a", { class: "sr-only", href: "#main" }, "本文へ移動"),
    h("header", { class: "top", id: "top" }),
    h("div", { class: "banner", id: "banner" }),
    h("main", { class: "wrap", id: "main" }),
    h("nav", { class: "bnav", id: "bnav", "aria-label": "主要" }),
    h("div", { class: "toasts", id: "toasts", role: "status", "aria-live": "polite" }),
    h("div", { class: "sr-only", id: "live", "aria-live": "polite" }),
  );
  installTooltips();
  addEventListener("popstate", () => {
    state.section = sectionFromHash();
    render();
  });
  addEventListener("online", () => {
    state.online = true;
    renderBanner();
  });
  addEventListener("offline", () => {
    state.online = false;
    renderBanner();
  });
  addEventListener("resize", () => document.documentElement.style.setProperty("--toph", `${$("#top")!.offsetHeight}px`));
}

async function boot(): Promise<void> {
  applyTheme(currentTheme());
  shell();
  ctx.session = await ctx.sessions.load();
  await start();
}

void boot();
