// タイムライン: days newest first (日), or week and month cards (週・月),
// with a large map that follows the selection on wide screens and a small
// map per card on narrow ones (docs/web-ui.md, "タイムライン").

import { mainMode, modeInfo, placeName, PLACE_KIND_LABEL, type Segment, type Trip, type Unrecorded, type Visit } from "../data/model";
import type { DayData } from "../data/store";
import { addDays, checkInsOf, dayStart, modeTotals } from "../data/timeline";
import { drawStatic } from "../map/canvas";
import { contentOf, EMPTY, type MapFix, type MapContent } from "../map/content";
import type { Ctx, Unit } from "./context";
import { announce, emoji, h, icon, replace, srOnly, toast } from "./dom";
import { fmtDist, fmtDur, fmtKm, hm, md, mdLabel, nf, shortTime, todayKey, weekStart, ymLabel } from "./format";
import { MapPanel } from "./mappanel";

const PAGE = 7;
const narrow = () => matchMedia("(max-width: 959px)").matches;

let bigMap: MapPanel | null = null;
let moreObserver: IntersectionObserver | null = null;
let loadingMore = false;
let skeletonTimer = 0;

// ------------------------------------------------------------- periods

export function periodKey(unit: Unit, date: string): string {
  return unit === "day" ? date : unit === "week" ? `w:${weekStart(date)}` : `m:${date.slice(0, 7)}`;
}

export function periodLabel(key: string, today = todayKey()): { rel: string; text: string } {
  if (key.startsWith("w:")) {
    const s = key.slice(2);
    const e = addDays(s, 6);
    return { rel: s === weekStart(today) ? "今週" : "", text: `${md(s)}〜${md(e)}` };
  }
  if (key.startsWith("m:")) return { rel: key.slice(2) === today.slice(0, 7) ? "今月" : "", text: ymLabel(key.slice(2)) };
  return { rel: key === today ? "今日" : key === addDays(today, -1) ? "昨日" : "", text: mdLabel(key) };
}

export function daysOf(ctx: Ctx, key: string): DayData[] {
  const st = ctx.store;
  const dev = ctx.state.device;
  if (!st || !dev) return [];
  const dates = st.days(dev).filter((d) => (key.startsWith("w:") || key.startsWith("m:") ? periodKey(key.startsWith("w:") ? "week" : "month", d) === key : d === key));
  return dates.map((d) => st.day(dev, d)).filter((d): d is DayData => d !== null);
}

export interface PeriodStats {
  distanceM: number;
  steps: number;
  places: number;
  stays: number;
  days: number;
  modes: [string, number][];
}

export function statsOf(days: DayData[]): PeriodStats {
  const places = new Set<string>();
  let stays = 0;
  for (const d of days)
    for (const s of d.segments)
      if (s.type === "visit") {
        stays++;
        places.add(s.placeKey ?? `${s.lat.toFixed(3)},${s.lon.toFixed(3)}`);
      }
  const modes = modeTotals(days.map((d) => d.summary));
  return {
    distanceM: modes.reduce((a, [, v]) => a + v, 0),
    steps: days.reduce((a, d) => a + d.summary.steps, 0),
    places: places.size,
    stays,
    days: days.filter((d) => d.summary.visits > 0 || d.summary.totalDistanceM > 0).length,
    modes,
  };
}

/** The last fix of the selected device, for today's map. */
export function lastFixOf(ctx: Ctx, device: string | null): MapFix[] {
  const s = device ? ctx.store?.status.get(device)?.status : null;
  if (!s?.lastFix) return [];
  return [{ lon: s.lastFix.lon, lat: s.lastFix.lat, acc: s.lastFix.acc, color: null }];
}

// ---------------------------------------------------------------- rows

function visitRow(ctx: Ctx, d: DayData, v: Visit, i: number): HTMLElement {
  const place = v.placeKey ? ctx.store?.places.find((p) => p.key === v.placeKey) : undefined;
  const sel = ctx.state.sel?.key === d.date && ctx.state.sel.index === i;
  const cap = `${hm(v.start)} – ${hm(v.end)}・${fmtDur(v.end - v.start)}` + (v.radius > 80 ? `・範囲 約${Math.round(v.radius / 10) * 10}m` : "");
  const chips: HTMLElement[] = [];
  if (place && place.kind !== "UNKNOWN" && placeName(place) !== PLACE_KIND_LABEL[place.kind]) chips.push(h("span", { class: "cichip" }, PLACE_KIND_LABEL[place.kind]));
  for (const c of checkInsOf(v, d.checkIns)) chips.push(h("span", { class: "cichip" }, `✓ ${c.venue}`));
  return h(
    "li",
    { class: "row v" },
    h("span", { class: "rail", "aria-hidden": "true" }, h("i")),
    h(
      "button",
      { class: "rowbtn", type: "button", "aria-pressed": String(sel), onclick: () => select(ctx, d.date, i) },
      h("span", { class: "pn" }, placeName(place)),
      h("span", { class: "cap num" }, cap),
      chips.length ? h("span", { class: "chips" }, chips) : null,
    ),
  );
}

function tripRow(ctx: Ctx, d: DayData, t: Trip, i: number): HTMLElement {
  const sel = ctx.state.sel?.key === d.date && ctx.state.sel.index === i;
  const legs = t.legs.filter((l) => l.mode !== "STILL").reverse();
  const color = modeInfo(mainMode(t)).color;
  return h(
    "li",
    { class: "row t", css: { "--c": color } },
    h("span", { class: "rail", "aria-hidden": "true" }),
    h(
      "button",
      { class: "rowbtn", type: "button", "aria-pressed": String(sel), onclick: () => select(ctx, d.date, i) },
      h("span", { class: "cap num" }, `${hm(t.start)} – ${hm(t.end)}・${fmtDur(t.end - t.start)}`),
      legs.map((l) => {
        const m = modeInfo(l.mode);
        return h(
          "span",
          { class: "leg" },
          h(
            "span",
            { class: "lm" },
            emoji(m.emoji),
            ` ${m.label}${l.confidence < 0.5 ? "（推定）" : ""}`,
            l.corrected ? h("span", { class: "edited", "aria-label": "編集済み" }, "✎") : null,
          ),
          h("span", { class: "ld num" }, `${l.uncertain >= 0.3 ? "約" : ""}${fmtDist(l.distanceM)}・${fmtDur(l.end - l.start)}${l.steps > 0 ? `・${nf(l.steps)}歩` : ""}`),
        );
      }),
    ),
  );
}

/** 記録なし: how the user got from one place to the next is unknown. */
function unrecordedRow(ctx: Ctx, d: DayData, u: Unrecorded, i: number): HTMLElement {
  const sel = ctx.state.sel?.key === d.date && ctx.state.sel.index === i;
  return h(
    "li",
    { class: "row u" },
    h("span", { class: "rail", "aria-hidden": "true" }),
    h(
      "button",
      { class: "rowbtn", type: "button", "aria-pressed": String(sel), onclick: () => select(ctx, d.date, i) },
      h("span", { class: "pn" }, "記録なし"),
      h("span", { class: "cap num" }, `${hm(u.start)} – ${hm(u.end)}・${fmtDur(u.end - u.start)}`),
    ),
  );
}

/** The segments shown as rows, newest first, with their index in the day. */
function shown(d: DayData): [Segment, number][] {
  return d.segments
    .map((s, i): [Segment, number] => [s, i])
    .reverse()
    .filter(([s]) => s.type !== "trip" || s.legs.some((l) => l.mode !== "STILL"));
}

function rows(ctx: Ctx, d: DayData): HTMLElement[] {
  return shown(d).map(([s, i]) =>
    s.type === "visit" ? visitRow(ctx, d, s, i) : s.type === "unrecorded" ? unrecordedRow(ctx, d, s, i) : tripRow(ctx, d, s, i),
  );
}

function mini(content: MapContent, key: string): HTMLElement {
  const canvas = h("canvas", { "aria-hidden": "true" });
  const box = h("div", { class: "mini", "data-mini": key }, canvas);
  requestAnimationFrame(() => drawStatic(canvas, content));
  return box;
}

function dayCard(ctx: Ctx, d: DayData): HTMLElement {
  const l = periodLabel(d.date);
  const sel = ctx.state.sel?.key === d.date;
  const hl = sel && ctx.state.sel?.index != null ? { date: d.date, index: ctx.state.sel.index } : null;
  return h(
    "article",
    { class: "day", "data-key": d.date, id: `card-${d.date}` },
    h(
      "h2",
      { class: "dh" },
      h(
        "button",
        { class: "dayhead", type: "button", "aria-pressed": String(sel && ctx.state.sel?.index == null), onclick: () => select(ctx, d.date, null) },
        h("span", { class: "dt" }, l.rel ? h("span", { class: "rel" }, l.rel) : null, l.text),
        h("span", { class: "ds num" }, `${nf(d.summary.steps)}歩・${fmtKm(d.summary.totalDistanceM)}`),
      ),
    ),
    d.partial ? h("p", { class: "partial" }, icon("alert"), "一部の記録が未受信") : null,
    narrow() ? mini(contentOf([d], hl), d.date) : null,
    h("ol", { class: "rows", "aria-label": `${mdLabel(d.date)}の滞在と移動` }, rows(ctx, d)),
  );
}

/** Today: only the device's status (the day's route arrives once the day is over). */
function todayCard(ctx: Ctx): HTMLElement | null {
  const dev = ctx.state.device;
  const s = dev ? ctx.store?.status.get(dev)?.status : null;
  if (!s) return null;
  const today = todayKey();
  const sel = ctx.state.sel?.key === today;
  const fix = s.lastFix;
  const when = fix ? (fix.t >= dayStart(today) ? hm(fix.t) : shortTime(fix.t)) : null;
  return h(
    "article",
    { class: "day", "data-key": today, id: `card-${today}` },
    h(
      "h2",
      { class: "dh" },
      h(
        "button",
        { class: "dayhead", type: "button", "aria-pressed": String(sel), onclick: () => select(ctx, today, null) },
        h("span", { class: "dt" }, h("span", { class: "rel" }, "今日"), mdLabel(today)),
      ),
    ),
    h(
      "p",
      { class: "todaymark", tabindex: 0, "data-tip": "今日の経路は翌日以降に表示" },
      h("i"),
      when ? `最終測位 ${when}` : "測位なし",
    ),
    narrow() && fix ? mini({ ...EMPTY, fixes: lastFixOf(ctx, dev) }, today) : null,
  );
}

function periodCard(ctx: Ctx, key: string): HTMLElement {
  const days = daysOf(ctx, key);
  const a = statsOf(days);
  const l = periodLabel(key);
  const sel = ctx.state.sel?.key === key;
  return h(
    "article",
    { class: `period${sel ? " sel" : ""}`, "data-key": key, id: `card-${key.replace(":", "-")}` },
    h(
      "h2",
      { class: "dh" },
      h(
        "button",
        { class: "dayhead", type: "button", "aria-pressed": String(sel), onclick: () => select(ctx, key, null) },
        h("span", { class: "dt" }, l.rel ? h("span", { class: "rel" }, l.rel) : null, l.text),
      ),
    ),
    narrow() ? mini(contentOf(days), key) : null,
    h("p", { class: "sum num" }, `${fmtKm(a.distanceM)}・${nf(a.steps)}歩・${a.places}か所・記録日数 ${a.days}日`),
    modeBar(a.modes),
  );
}

export function modeBar(modes: [string, number][]): HTMLElement[] {
  return [
    h(
      "div",
      { class: "modebar", "aria-hidden": "true" },
      modes.map(([m, km]) => h("i", { css: { "flex-grow": String(km), "--c": modeInfo(m as never).color } })),
    ),
    h(
      "ul",
      { class: "modelist" },
      modes.map(([m, d]) => {
        const info = modeInfo(m as never);
        return h("li", null, emoji(info.emoji), srOnly(info.label), " ", h("span", { class: "num" }, fmtKm(d)));
      }),
    ),
  ];
}

function skeleton(n: number): HTMLElement[] {
  return Array.from({ length: n }, () =>
    h(
      "div",
      { class: "day", "aria-hidden": "true" },
      h("div", { class: "skel sk-line h" }),
      h("div", { class: "skel sk-line w40" }),
      h("div", { class: "skel sk-line row" }),
      h("div", { class: "skel sk-line row" }),
    ),
  );
}

// --------------------------------------------------------------- view

export function renderTimeline(ctx: Ctx, main: HTMLElement): void {
  const st = ctx.state;
  const devices = ctx.store?.recordingDevices() ?? [];
  if (!st.device || !devices.includes(st.device)) st.device = devices[0] ?? null;

  const unitBtn = (u: Unit, label: string) =>
    h(
      "button",
      {
        type: "button",
        "aria-pressed": String(st.unit === u),
        onclick: () => {
          st.unit = u;
          st.sel = null;
          ctx.render();
        },
      },
      label,
    );
  const devSel = h(
    "select",
    {
      class: "field",
      onchange: (e: Event) => {
        st.device = (e.target as HTMLSelectElement).value;
        st.sel = null;
        ctx.render();
        void ensureDays(ctx, PAGE);
      },
    },
    devices.map((id) => h("option", { value: id, selected: id === st.device }, ctx.store?.deviceName(id) ?? id)),
  );
  const dateIn = h("input", { class: "field", type: "date", max: todayKey(), onchange: (e: Event) => void jumpTo(ctx, (e.target as HTMLInputElement).value) });
  const tool = h(
    "div",
    { class: "tltool" },
    h("div", { class: "seg", role: "group", "aria-label": "単位" }, unitBtn("day", "日"), unitBtn("week", "週"), unitBtn("month", "月")),
    devices.length > 1 ? h("label", null, srOnly("端末"), devSel) : null,
    h("label", { class: "datejump", "data-tip": "日付へ移動" }, icon("cal"), srOnly("日付へ移動"), dateIn),
    h("button", { class: "btn ghost sm", type: "button", onclick: () => toToday(ctx) }, "今日へ"),
  );

  const list = h("div", { id: "tl-list" });
  fillList(ctx, list);

  if (!bigMap) bigMap = new MapPanel("選択した日の地図");
  const mapPane = h("div", { class: "tlmap" }, bigMap.el);
  replace(
    main,
    h("h1", { class: "sr-only", id: "h1", tabindex: -1 }, "タイムライン"),
    h("div", { class: "tlgrid" }, h("div", { class: "tlcol" }, tool, list), mapPane),
  );
  updateBigMap(ctx);
  watchMore(ctx);
}

function fillList(ctx: Ctx, list: HTMLElement): void {
  const st = ctx.state;
  const store = ctx.store;
  clearTimeout(skeletonTimer);
  if (st.load.kind === "error") {
    replace(
      list,
      h(
        "div",
        { class: "errpanel", role: "alert" },
        icon("xcirc"),
        h("div", { class: "grow" }, h("b", null, st.load.message), " ", st.load.status ? h("small", null, `HTTP ${st.load.status}`) : null),
        h("button", { class: "btn ghost sm", type: "button", onclick: () => ctx.retry() }, "再試行"),
      ),
    );
    return;
  }
  const progress = store?.progress;
  const days = store && st.device ? store.days(st.device) : [];
  if (st.load.kind === "loading" || (progress && days.length === 0)) {
    const status = h("div", { role: "status", class: "bar info" }, h("span", { class: "spin" }), progress ? `復号中 ${progress.ready} / ${progress.target} 日` : "読み込み中");
    replace(list, status);
    // A skeleton only when it takes longer than 300 ms.
    skeletonTimer = window.setTimeout(() => list.isConnected && list.append(...skeleton(3)), 300);
    return;
  }
  if (!st.device || (days.length === 0 && !store?.hasMore(st.device))) {
    const today = todayCard(ctx);
    replace(
      list,
      today,
      h("div", { class: "empty" }, icon("history"), h("b", null, "まだ記録がありません")),
    );
    return;
  }
  const items: (HTMLElement | null)[] = [];
  if (st.unit === "day") {
    if (!days.includes(todayKey())) items.push(todayCard(ctx));
    for (const date of days) {
      const d = store!.day(st.device, date);
      if (d) items.push(dayCard(ctx, d));
    }
  } else {
    const keys = [...new Set(days.map((d) => periodKey(st.unit, d)))];
    for (const k of keys) items.push(periodCard(ctx, k));
  }
  const failures = store!.failures(st.device);
  const more = store!.hasMore(st.device);
  replace(
    list,
    failures ? h("p", { class: "partial" }, icon("alert"), `読めない記録が ${failures} 件あります`) : null,
    items,
    more
      ? h("div", { class: "more", id: "more-wrap" }, progress ? h("div", { role: "status", class: "bar info" }, h("span", { class: "spin" }), `復号中 ${progress.ready} / ${progress.target} 日`) : h("button", { class: "btn ghost", type: "button", id: "more", onclick: () => void loadMore(ctx, true) }, "さらに前を表示"))
      : h("p", { class: "end" }, "これより前の記録はありません"),
  );
}

/** Updates the list in place (store progress and changes) without rebuilding the map. */
export function refreshTimeline(ctx: Ctx): void {
  const list = document.getElementById("tl-list");
  if (!list) return;
  fillList(ctx, list);
  updateBigMap(ctx);
  watchMore(ctx);
}

function updateBigMap(ctx: Ctx): void {
  if (!bigMap) return;
  const st = ctx.state;
  const key = st.sel?.key ?? null;
  const today = todayKey();
  if (st.load.kind !== "ready") {
    bigMap.setHead(h("b", null, "地図"));
    bigMap.setContent(EMPTY);
    bigMap.setOverlay(...(st.load.kind === "loading" ? [h("span", { class: "spin" }), "復号中"] : []));
    return;
  }
  // Without a selection: the newest day or period.
  const newest = ctx.store && st.device ? ctx.store.days(st.device)[0] : undefined;
  const first = key ?? (newest ? periodKey(st.unit, newest) : undefined);
  if (!first) {
    bigMap.setHead(h("b", null, "地図"));
    bigMap.setContent(EMPTY);
    bigMap.setOverlay();
    return;
  }
  const l = periodLabel(first);
  const days = daysOf(ctx, first);
  const hl = st.sel && st.sel.index != null ? { date: st.sel.key, index: st.sel.index } : null;
  const fixes = first === today ? lastFixOf(ctx, st.device) : [];
  bigMap.setHead(h("b", null, `${l.rel ? `${l.rel}　` : ""}${l.text}`), " ", h("span", { class: "muted" }, `· ${ctx.store?.deviceName(st.device ?? "") ?? ""}`));
  bigMap.setContent(contentOf(days, hl, fixes), true);
  bigMap.setOverlay(...(days.length || fixes.length ? [] : [first.includes(":") ? "この期間の記録はありません" : "この日の記録はありません"]));
}

function select(ctx: Ctx, key: string, index: number | null): void {
  ctx.state.sel = { key, index };
  const list = document.getElementById("tl-list");
  if (!list) return;
  // Update pressed states and the maps without re-rendering the list.
  list.querySelectorAll<HTMLElement>("[data-key]").forEach((card) => {
    const k = card.dataset.key!;
    card.classList.toggle("sel", card.classList.contains("period") && k === key);
    card.querySelectorAll<HTMLButtonElement>(".dayhead").forEach((b) => b.setAttribute("aria-pressed", String(k === key && index === null)));
    const rowBtns = card.querySelectorAll<HTMLButtonElement>(".rowbtn");
    const d = k.includes(":") ? null : ctx.store?.day(ctx.state.device ?? "", k);
    const order = d ? shown(d).map(([, i]) => i) : [];
    rowBtns.forEach((b, j) => b.setAttribute("aria-pressed", String(k === key && order[j] === index)));
    const m = card.querySelector<HTMLCanvasElement>(".mini canvas");
    if (m && d && (k === key || card.dataset.hl)) {
      drawStatic(m, contentOf([d], k === key && index !== null ? { date: k, index } : null));
      if (k === key) card.dataset.hl = "1";
      else delete card.dataset.hl;
    }
  });
  updateBigMap(ctx);
}

// ------------------------------------------------- loading and jumping

export async function ensureDays(ctx: Ctx, n: number): Promise<void> {
  const st = ctx.state;
  if (!ctx.store || !st.device || loadingMore) return;
  loadingMore = true;
  try {
    await ctx.store.loadMore(st.device, n);
  } catch {
    // An unauthorized token is handled by the store's event; other failures are counted.
  } finally {
    loadingMore = false;
  }
}

async function loadMore(ctx: Ctx, byButton: boolean): Promise<void> {
  const before = ctx.store && ctx.state.device ? ctx.store.days(ctx.state.device).length : 0;
  await ensureDays(ctx, PAGE);
  if (byButton && ctx.store && ctx.state.device) {
    const next = ctx.store.days(ctx.state.device)[before];
    if (next) document.querySelector<HTMLElement>(`#card-${next} .dayhead`)?.focus();
  }
}

function watchMore(ctx: Ctx): void {
  moreObserver?.disconnect();
  const m = document.getElementById("more");
  if (!m || !("IntersectionObserver" in window)) return;
  moreObserver = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && void loadMore(ctx, false), { rootMargin: "200px" });
  moreObserver.observe(m);
}

function toToday(ctx: Ctx): void {
  ctx.state.unit = "day";
  ctx.state.sel = { key: todayKey(), index: null };
  ctx.render();
  window.scrollTo({ top: 0 });
  document.querySelector<HTMLElement>("#tl-list .dayhead")?.focus({ preventScroll: true });
}

async function jumpTo(ctx: Ctx, date: string): Promise<void> {
  if (!date || !ctx.store || !ctx.state.device) return;
  const dev = ctx.state.device;
  ctx.state.unit = "day";
  // Open older batches until the date is reached or nothing is left.
  for (let i = 0; i < 100; i++) {
    const days = ctx.store.days(dev);
    if ((days.length && days[days.length - 1]! <= date) || !ctx.store.hasMore(dev)) break;
    await ensureDays(ctx, PAGE);
  }
  const days = ctx.store.days(dev);
  const target = days.find((d) => d <= date) ?? days[days.length - 1];
  if (!target) return;
  ctx.state.sel = { key: target, index: null };
  ctx.render();
  const card = document.getElementById(`card-${target}`);
  card?.scrollIntoView({ block: "start" });
  card?.querySelector<HTMLElement>(".dayhead")?.focus({ preventScroll: true });
  if (target !== date) toast(`${mdLabel(date)}の記録はありません。${mdLabel(target)}を表示します。`);
  else announce(`${mdLabel(target)}を表示しました`);
}

export function disposeTimeline(): void {
  moreObserver?.disconnect();
  moreObserver = null;
}
