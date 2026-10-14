// State shared by the views.

import type { Sessions, ViewerSession } from "../data/session";
import type { ViewerStore } from "../data/store";

export type Section = "timeline" | "map" | "devices";
export type Unit = "day" | "week" | "month";

export interface Selection {
  /** "YYYY-MM-DD", "w:YYYY-MM-DD" (Monday) or "m:YYYY-MM" */
  key: string;
  /** Index into the day's segments, for a selected row. */
  index: number | null;
}

export type LoadState = { kind: "loading" } | { kind: "ready" } | { kind: "error"; message: string; status: number | null };

export interface State {
  section: Section;
  unit: Unit;
  device: string | null;
  sel: Selection | null;
  map: { unit: Unit; key: string };
  devSel: string | null;
  load: LoadState;
  online: boolean;
}

export interface Ctx {
  state: State;
  store: ViewerStore | null;
  session: ViewerSession | null;
  sessions: Sessions;
  /** Re-renders the current section. */
  render(): void;
  go(section: Section, focus?: boolean): void;
  retry(): void;
  unregister(): Promise<void>;
  signIn(s: ViewerSession): Promise<void>;
}
