// Numbers, dates and times as the app writes them (docs/web-ui.md, "時刻",
// "数値と単位"). Times are in the browser's time zone.

import { h } from "./dom";

const WD = "日月火水木金土";
const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;
const pad = (n: number) => String(n).padStart(2, "0");

export const nf = (n: number) => Math.round(n).toLocaleString("en-US");

/** 1 km 未満は m の整数、以上は km の小数 1 桁 */
export function fmtDist(m: number): string {
  return m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1)} km`;
}

export const fmtKm = (m: number) => `${(m / 1000).toFixed(1)} km`;

/** 「34分」「1時間55分」 (Ui.fmtDuration of the app: whole minutes, seconds under one) */
export function fmtDur(ms: number): string {
  if (ms < MIN) return `${Math.max(0, Math.floor(ms / 1000))}秒`;
  const min = Math.floor(ms / MIN);
  return min < 60 ? `${min}分` : `${Math.floor(min / 60)}時間${min % 60}分`;
}

/** 「19:23」 */
export function hm(t: number): string {
  const d = new Date(t);
  return `${d.getHours()}:${pad(d.getMinutes())}`;
}

const keyDate = (key: string) => {
  const [y, m, d] = key.split("-").map(Number);
  return new Date(y!, m! - 1, d ?? 1);
};

/** 「10月13日 (火)」 */
export function mdLabel(key: string): string {
  const d = keyDate(key);
  return `${d.getMonth() + 1}月${d.getDate()}日 (${WD[d.getDay()]})`;
}

/** 「10/13」 */
export function md(key: string): string {
  const d = keyDate(key);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

/** 「2026年10月」 */
export function ymLabel(key: string): string {
  const [y, m] = key.split("-");
  return `${y}年${Number(m)}月`;
}

/** 「2026-10-14 14:05」 */
export function absTime(t: number): string {
  const d = new Date(t);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function absDate(t: number): string {
  const d = new Date(t);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 「10/14 14:05」 */
export function shortTime(t: number): string {
  const d = new Date(t);
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 「1分以内」「15分前」「3時間前」「2日前」; 7 日以上前は日付 */
export function relTime(t: number, now = Date.now()): string {
  const x = now - t;
  if (x < MIN) return "1分以内";
  if (x < HOUR) return `${Math.floor(x / MIN)}分前`;
  if (x < DAY) return `${Math.floor(x / HOUR)}時間前`;
  if (x < 7 * DAY) return `${Math.floor(x / DAY)}日前`;
  return absDate(t);
}

/** A relative time with the absolute one on hover and focus. */
export function timeEl(t: number | null, none = "なし"): HTMLElement {
  if (t === null || !Number.isFinite(t) || t <= 0) return h("span", { class: "muted" }, none);
  const rel = relTime(t);
  const iso = new Date(t).toISOString();
  if (rel === absDate(t)) return h("time", { datetime: iso }, rel);
  const abs = absTime(t);
  return h("time", { class: "tt", tabindex: 0, datetime: iso, "data-tip": abs }, rel, h("span", { class: "sr-only" }, `（${abs}）`));
}

export const STALE_MS = 3 * HOUR;

/** Monday of the week of a day (the mock's weeks start on Monday). */
export function weekStart(key: string): string {
  const d = keyDate(key);
  const back = (d.getDay() + 6) % 7;
  const m = new Date(d.getFullYear(), d.getMonth(), d.getDate() - back);
  return `${m.getFullYear()}-${pad(m.getMonth() + 1)}-${pad(m.getDate())}`;
}

export function todayKey(now = Date.now()): string {
  const d = new Date(now);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
