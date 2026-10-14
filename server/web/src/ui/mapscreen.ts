// マップ: a large map of a day, week or month, with its totals
// (docs/web-ui.md, "マップ").

import { modeInfo } from "../data/model";
import { addDays } from "../data/timeline";
import { contentOf } from "../map/content";
import type { Ctx, Unit } from "./context";
import { emoji, h, icon, replace } from "./dom";
import { fmtKm, nf, todayKey, weekStart } from "./format";
import { MapPanel } from "./mappanel";
import { daysOf, ensureDays, lastFixOf, periodKey, periodLabel, statsOf } from "./timeline";

let panel: MapPanel | null = null;

function keyOf(unit: Unit, k: string): string {
  return unit === "day" ? k : unit === "week" ? `w:${k}` : `m:${k}`;
}

function step(unit: Unit, k: string, d: number): string {
  if (unit === "day") return addDays(k, d);
  if (unit === "week") return addDays(k, 7 * d);
  const [y, m] = k.split("-").map(Number);
  const t = new Date(y!, m! - 1 + d, 1);
  return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}`;
}

function norm(unit: Unit, date: string): string {
  return unit === "day" ? date : unit === "week" ? weekStart(date) : date.slice(0, 7);
}

export function renderMapScreen(ctx: Ctx, main: HTMLElement): void {
  const st = ctx.state;
  const store = ctx.store;
  const devices = store?.recordingDevices() ?? [];
  if (!st.device || !devices.includes(st.device)) st.device = devices[0] ?? null;
  const loaded = store && st.device ? store.days(st.device) : [];
  if (!st.map.key) st.map.key = loaded[0] ?? todayKey();
  const u = st.map.unit;
  const k = norm(u, st.map.key);
  const key = keyOf(u, k);
  const days = daysOf(ctx, key);
  const a = statsOf(days);
  const l = periodLabel(key);
  const today = todayKey();
  const isToday = u === "day" && k === today;
  const fixes = isToday ? lastFixOf(ctx, st.device) : [];
  const unitName = { day: "日", week: "週", month: "月" }[u];
  const last = norm(u, today);
  const oldest = loaded[loaded.length - 1];
  const more = !!(store && st.device && store.hasMore(st.device));
  const prev = step(u, k, -1);
  const next = step(u, k, 1);
  const canPrev = more || (oldest !== undefined && norm(u, oldest) <= prev);

  const go = async (d: number) => {
    st.map.key = step(u, k, d);
    if (d < 0 && store && st.device && more) {
      // Older days than loaded: open more batches first.
      const want = st.map.key;
      for (let i = 0; i < 50; i++) {
        const ds = store.days(st.device);
        if ((ds.length && norm(u, ds[ds.length - 1]!) <= want) || !store.hasMore(st.device)) break;
        ctx.render();
        await ensureDays(ctx, 7);
      }
    }
    ctx.render();
    document.querySelector<HTMLElement>(`[data-act="mnav"][data-d="${d}"]`)?.focus();
  };

  if (!panel) panel = new MapPanel("期間の地図");
  panel.setHead(h("b", null, `${l.rel ? `${l.rel}　` : ""}${l.text}`));
  panel.setContent(contentOf(days, null, fixes), true);
  const loading = st.load.kind === "loading" || !!store?.progress;
  panel.setOverlay(...(loading ? [h("span", { class: "spin" }), "復号中"] : days.length || fixes.length ? [] : [u === "day" ? "この日の記録はありません" : "この期間の記録はありません"]));

  const stats: [string, string][] =
    u === "day"
      ? [
          [nf(a.steps), "歩数"],
          [fmtKm(a.distanceM), "移動"],
          [String(a.stays), "滞在"],
        ]
      : [
          [fmtKm(a.distanceM), "移動"],
          [nf(a.steps), "歩数"],
          [String(a.places), "場所"],
        ];
  const max = a.modes[0]?.[1] ?? 1;
  const unitBtn = (v: Unit, t: string) =>
    h(
      "button",
      {
        type: "button",
        "aria-pressed": String(u === v),
        onclick: () => {
          st.map.unit = v;
          ctx.render();
        },
      },
      t,
    );
  const side = h(
    "div",
    { class: "mpanel" },
    h("div", { class: "seg", role: "group", "aria-label": "期間" }, unitBtn("day", "日"), unitBtn("week", "週"), unitBtn("month", "月")),
    h(
      "div",
      { class: "dnav" },
      h("button", { class: "iconbtn", type: "button", "data-act": "mnav", "data-d": "-1", "aria-label": `前の${unitName}`, disabled: !canPrev, onclick: () => void go(-1) }, icon("chevL")),
      h("h2", { "aria-live": "polite" }, `${l.rel ? `${l.rel}　` : ""}${l.text}`),
      h("button", { class: "iconbtn", type: "button", "data-act": "mnav", "data-d": "1", "aria-label": `次の${unitName}`, disabled: next > last, onclick: () => void go(1) }, icon("chevR")),
    ),
    h("div", { class: "stats" }, stats.map(([v, t]) => h("div", { class: "stat" }, h("b", { class: "num" }, v), h("span", null, t)))),
    a.modes.length
      ? h(
          "div",
          null,
          h("h3", { class: "h3" }, "移動手段"),
          h(
            "ul",
            { class: "mtable" },
            a.modes.map(([m, d]) => {
              const info = modeInfo(m as never);
              return h(
                "li",
                null,
                h("span", null, emoji(info.emoji), ` ${info.label}`),
                h("i", { css: { "--c": info.color, "--w": `${Math.max((d / max) * 100, 3).toFixed(1)}%` } }),
                h("span", { class: "km num" }, fmtKm(d)),
              );
            }),
          ),
        )
      : null,
    devices.length > 1
      ? h(
          "label",
          null,
          "端末 ",
          h(
            "select",
            {
              class: "field",
              onchange: (e: Event) => {
                st.device = (e.target as HTMLSelectElement).value;
                ctx.render();
                void ensureDays(ctx, 7);
              },
            },
            devices.map((id) => h("option", { value: id, selected: id === st.device }, store?.deviceName(id) ?? id)),
          ),
        )
      : null,
    h(
      "button",
      {
        class: "btn ghost",
        type: "button",
        onclick: () => {
          st.unit = u;
          st.sel = { key: u === "day" ? k : periodKey(u, u === "month" ? `${k}-01` : k), index: null };
          ctx.go("timeline");
          requestAnimationFrame(() => {
            const id = `card-${st.sel!.key.replace(":", "-")}`;
            document.getElementById(id)?.scrollIntoView({ block: "start" });
          });
        },
      },
      `この${unitName}のタイムライン`,
    ),
  );
  replace(main, h("h1", { class: "sr-only", id: "h1", tabindex: -1 }, "マップ"), h("div", { class: "mapscreen" }, panel.el, side));
}
