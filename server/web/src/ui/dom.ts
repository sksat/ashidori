// DOM building without HTML strings: every text goes through text nodes
// (user input such as place names and comments is never parsed as HTML),
// and no style attribute is written (CSP style-src has no 'unsafe-inline';
// custom properties are set through the CSSOM, which CSP allows).

type Child = Node | string | number | null | undefined | false | Child[];
type Attrs = {
  [k: string]: string | number | boolean | null | undefined | EventListener | Record<string, string>;
  /** CSS custom properties or properties, set with style.setProperty. */
  css?: Record<string, string>;
};

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs | null = null, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "css") {
        for (const [p, x] of Object.entries(v as Record<string, string>)) el.style.setProperty(p, x);
      } else if (k.startsWith("on") && typeof v === "function") {
        el.addEventListener(k.slice(2), v as EventListener);
      } else if (v === true) el.setAttribute(k, "");
      else if (v !== false && v !== null && v !== undefined) el.setAttribute(k, String(v));
    }
  }
  append(el, children);
  return el;
}

export function append(el: Node, children: Child[]): void {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
  }
}

export function replace(el: Element, ...children: Child[]): void {
  el.replaceChildren();
  append(el, children);
}

export const $ = <T extends Element = HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector<T>(sel);

const SVG = "http://www.w3.org/2000/svg";

const ICONS: Record<string, string> = {
  history: "M3 12a9 9 0 1 0 2.6-6.4M3 4v4.5h4.5M12 7.5V12l3 2",
  map: "M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2zM9 4v14M15 6v14",
  phone: "M7 2.5h10v19H7zM11 18.5h2",
  lock: "M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3",
  users: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 20c1-4 3.6-6 6.5-6s5.5 2 6.5 6M16 4.5a3.5 3.5 0 0 1 0 7M18 14c2 .6 3.2 2.6 3.5 6",
  plus: "M12 5v14M5 12h14",
  minus: "M5 12h14",
  chevL: "M15 6l-6 6 6 6",
  chevR: "M9 6l6 6-6 6",
  check: "M5 12.5l4.5 4.5L19 7",
  alert: "M12 3 2 20h20L12 3zM12 10v4M12 17h.01",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01",
  xcirc: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9 9l6 6M15 9l-6 6",
  okcirc: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8 12.5l3 3 5-6",
  fit: "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5",
  wifioff: "M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 12.5a10 10 0 0 1 4.5-2.4M19 12.5a10 10 0 0 0-2-1.5M2 9a15 15 0 0 1 4.2-2.7M22 9a15 15 0 0 0-10.5-3.9M12 20h.01",
  laptop: "M4 5h16v10H4zM2 19h20",
  cal: "M4 6h16v14H4zM4 10h16M8 3v4M16 3v4",
  refresh: "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7",
  pin: "M12 22s7-7.5 7-13a7 7 0 0 0-14 0c0 5.5 7 13 7 13zM12 11.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z",
  layers: "M12 3 2 8l10 5 10-5-10-5zM2 13l10 5 10-5M2 17.5l10 5 10-5",
  theme: "M12 21a9 9 0 1 0 0-18v18z M12 3a9 9 0 0 1 0 18",
  key: "M14 10a4 4 0 1 0-3.5 4L12 15.5h2v2h2v2h3v-3l-5.5-5.5",
};

export function icon(name: keyof typeof ICONS | string, cls = ""): SVGSVGElement {
  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("class", `ic ${cls}`.trim());
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("focusable", "false");
  const p = document.createElementNS(SVG, "path");
  p.setAttribute("d", ICONS[name] ?? "");
  svg.appendChild(p);
  return svg;
}

/** An emoji the app uses, hidden from screen readers (a word is always next to it). */
export const emoji = (e: string) => h("span", { "aria-hidden": "true" }, e);

export const srOnly = (t: string) => h("span", { class: "sr-only" }, t);

// ---- tooltip (hover and focus; Esc closes; WCAG 1.4.13) ----

let tipEl: HTMLElement | null = null;

export function installTooltips(): void {
  tipEl = h("div", { class: "tip", role: "tooltip", hidden: true });
  document.body.appendChild(tipEl);
  const show = (e: Event) => {
    const t = (e.target as Element | null)?.closest?.("[data-tip]");
    if (!t || !tipEl) return;
    tipEl.textContent = t.getAttribute("data-tip");
    tipEl.hidden = false;
    const r = t.getBoundingClientRect();
    const w = tipEl.offsetWidth;
    tipEl.style.left = `${Math.max(12, Math.min(window.innerWidth - w - 12, r.left))}px`;
    tipEl.style.top = `${r.bottom + 6 + tipEl.offsetHeight > window.innerHeight ? r.top - tipEl.offsetHeight - 6 : r.bottom + 6}px`;
  };
  const hide = () => tipEl && (tipEl.hidden = true);
  document.addEventListener("mouseover", show);
  document.addEventListener("focusin", show);
  document.addEventListener("mouseout", hide);
  document.addEventListener("focusout", hide);
  document.addEventListener("scroll", hide, true);
  document.addEventListener("keydown", (e) => e.key === "Escape" && hide());
}

// ---- toasts and live region ----

export function toast(text: string): void {
  const box = $("#toasts");
  if (!box) return;
  const t = h("div", { class: "toast" }, icon("info"), h("span", null, text));
  box.appendChild(t);
  let left = 6000;
  let since = Date.now();
  let paused = false;
  const tick = () => {
    if (!t.isConnected) return;
    if (!paused) {
      left -= Date.now() - since;
      since = Date.now();
      if (left <= 0) return t.remove();
    }
    setTimeout(tick, 250);
  };
  t.addEventListener("mouseenter", () => (paused = true));
  t.addEventListener("mouseleave", () => {
    paused = false;
    since = Date.now();
  });
  setTimeout(tick, 250);
}

export function announce(text: string): void {
  const live = $("#live");
  if (live) live.textContent = text;
}

// ---- confirmation dialog (no alert/confirm; docs/web-ui.md "操作の規則") ----

export function confirmDialog(opts: { title: string; items: string[]; action: string }): Promise<boolean> {
  return new Promise((resolve) => {
    const prev = document.activeElement as HTMLElement | null;
    const cancel = h("button", { class: "btn ghost", type: "button" }, "キャンセル");
    const ok = h("button", { class: "btn danger-fill", type: "button" }, opts.action);
    const dlg = h(
      "dialog",
      { "aria-labelledby": "dlg-t" },
      h("div", { class: "dlg" }, h("h2", { id: "dlg-t" }, opts.title), h("ul", null, opts.items.map((i) => h("li", null, i))), h("div", { class: "acts" }, cancel, ok)),
    );
    const done = (v: boolean) => {
      dlg.close();
      dlg.remove();
      prev?.focus();
      resolve(v);
    };
    cancel.addEventListener("click", () => done(false));
    ok.addEventListener("click", () => done(true));
    dlg.addEventListener("cancel", (e) => {
      e.preventDefault();
      done(false);
    });
    document.body.appendChild(dlg);
    dlg.showModal();
    cancel.focus();
  });
}
