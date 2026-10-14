// Loads a user's batches and turns them into days, newest first.
//
// The server only sees stream names, so which day (or table) an `archive`
// batch holds is known after decrypting it. Batches are therefore opened
// newest first (highest seq), small ones before large ones: fixes and
// motion of a day are well below 1 MiB, while the raw GNSS and IMU tables
// come in parts of 3 MiB. Once a part names its file, the seqs of that
// file's other parts are known: they are fetched next if the file is
// needed, and skipped if it is a table the viewer does not draw.

import type { Api, BatchMeta, DeviceInfo } from "./api";
import { ApiError } from "./api";
import {
  ArchiveAssembler,
  dedupeFixes,
  dedupeMotion,
  fileInfo,
  fixesOf,
  legacyFix,
  legacyMotion,
  motionOf,
  parseArchiveRecord,
  readParquet,
} from "./archive";
import { BlobError, STATUS_CAPS, openBlob } from "./crypto";
import type { CheckIn, DeviceStatus, Fix, ModeOverride, MotionWindow, Place, Segment } from "./model";
import { checkInOf, latestByKey, latestOverrides, overrideOf, placeOf, statusOf } from "./records";
import type { DaySummary } from "./timeline";
import { addDays, applyOverrides, buildTimeline, dayKey, dayStart, linkPlaces, splitByDay, summarizeDay } from "./timeline";

const NEEDED = new Set(["fixes", "motion"]);
const SMALL = 1024 * 1024;
const MARGIN = 12 * 3_600_000;
const CONCURRENCY = 4;

/** Seqs count per stream: legacy `fixes`/`motion` batches have their own. */
const bkey = (b: { stream: string; seq: number }) => `${b.stream}/${b.seq}`;

export interface DayData {
  date: string;
  segments: Segment[];
  summary: DaySummary;
  checkIns: CheckIn[];
  /** A file of this day arrived only in part. */
  partial: boolean;
}

export interface StatusEntry {
  status: DeviceStatus | null;
  seq: number;
  /** The newest status could not be read (shown on the device row). */
  error: string | null;
}

interface DeviceArchive {
  queue: BatchMeta[];
  done: Set<string>;
  inFlight: Set<string>;
  skip: Set<string>;
  /** Keys of parts of a needed file, fetched before anything else. */
  priority: string[];
  assembler: ArchiveAssembler;
  fixes: Map<string, Fix[]>;
  motion: Map<string, MotionWindow[]>;
  /** Days with fixes, newest first. */
  days: string[];
  partialDays: Set<string>;
  failures: number;
  version: Map<string, number>;
  cache: Map<string, { v: string; data: DayData }>;
}

export type StoreEvent = "change" | "progress" | "unauthorized";

export class ViewerStore extends EventTarget {
  devices: DeviceInfo[] = [];
  status = new Map<string, StatusEntry>();
  places: Place[] = [];
  checkIns: CheckIn[] = [];
  overrides: ModeOverride[] = [];
  /** When the listing was last read. */
  fetchedAt = 0;
  progress: { device: string; ready: number; target: number } | null = null;

  private archives = new Map<string, DeviceArchive>();
  private batches: BatchMeta[] = [];
  private readonly controller = new AbortController();

  constructor(
    private readonly api: Api,
    private readonly key: CryptoKey,
    private readonly now: () => number = Date.now,
  ) {
    super();
  }

  /** Stops every request (登録の解除, sign-out). */
  close(): void {
    this.controller.abort();
  }

  private emit(type: StoreEvent): void {
    this.dispatchEvent(new Event(type));
  }

  private guard<T>(p: Promise<T>): Promise<T> {
    return p.catch((e: unknown) => {
      if (e instanceof ApiError && e.unauthorized) this.emit("unauthorized");
      throw e;
    });
  }

  /** Devices, the listing, the small streams and every device's status. */
  async init(): Promise<void> {
    const signal = this.controller.signal;
    const [devices] = await Promise.all([this.guard(this.api.devices(signal))]);
    this.devices = devices;
    const all: BatchMeta[] = [];
    for await (const page of this.api.allBatches(0, signal)) all.push(...page);
    this.batches = all;
    this.fetchedAt = this.now();
    for (const d of this.deviceIds()) this.archives.set(d, this.newArchive(d));
    await Promise.all([this.loadEditable(), this.loadStatus()]);
    this.emit("change");
  }

  /** Re-reads devices and statuses (「更新」). */
  async refreshStatus(): Promise<void> {
    const signal = this.controller.signal;
    this.devices = await this.guard(this.api.devices(signal));
    const fresh: BatchMeta[] = [];
    const after = this.batches.reduce((a, b) => Math.max(a, b.cursor), 0);
    for await (const page of this.api.allBatches(after, signal)) fresh.push(...page);
    this.batches.push(...fresh);
    this.fetchedAt = this.now();
    await this.loadStatus();
    this.emit("change");
  }

  /**
   * Devices with any batch, plus the listed app devices; the listed order
   * first. Browsers (viewers) record nothing and are left out.
   */
  deviceIds(): string[] {
    const ids = new Set(this.devices.filter((d) => d.kind !== "viewer").map((d) => d.id));
    for (const b of this.batches) ids.add(b.deviceId);
    return [...ids];
  }

  /** Devices that recorded positions (have archive or legacy fixes batches). */
  recordingDevices(): string[] {
    const ids = new Set(this.batches.filter((b) => b.stream === "archive" || b.stream === "fixes").map((b) => b.deviceId));
    return this.deviceIds().filter((d) => ids.has(d));
  }

  deviceName(id: string): string {
    const d = this.devices.find((x) => x.id === id);
    return d?.name || this.status.get(id)?.status?.device.model || "端末";
  }

  private newArchive(deviceId: string): DeviceArchive {
    const queue = this.batches
      .filter((b) => b.deviceId === deviceId && (b.stream === "archive" || b.stream === "fixes" || b.stream === "motion"))
      .sort((a, b) => (a.stream === b.stream ? b.seq - a.seq : b.cursor - a.cursor));
    return {
      queue,
      done: new Set(),
      inFlight: new Set(),
      skip: new Set(),
      priority: [],
      assembler: new ArchiveAssembler(),
      fixes: new Map(),
      motion: new Map(),
      days: [],
      partialDays: new Set(),
      failures: 0,
      version: new Map(),
      cache: new Map(),
    };
  }

  // ------------------------------------------------------- small streams

  private async openAll(refs: BatchMeta[]): Promise<{ ref: BatchMeta; records: unknown[] }[]> {
    const out: { ref: BatchMeta; records: unknown[] }[] = [];
    let i = 0;
    const worker = async () => {
      while (i < refs.length) {
        const ref = refs[i++]!;
        try {
          const blob = await this.guard(this.api.blob(ref, this.controller.signal));
          out.push({ ref, records: await openBlob(this.key, ref, blob) });
        } catch (e) {
          if (e instanceof ApiError && e.unauthorized) throw e;
          // A batch that cannot be read is left out; the rest still shows.
        }
      }
    };
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    return out.sort((a, b) => a.ref.cursor - b.ref.cursor);
  }

  private async loadEditable(): Promise<void> {
    const refs = this.batches.filter((b) => b.stream === "places" || b.stream === "checkins" || b.stream === "overrides");
    const opened = await this.openAll(refs);
    const places: Place[] = [];
    const checkIns: CheckIn[] = [];
    const overrides: ModeOverride[] = [];
    for (const { ref, records } of opened) {
      for (const r of records) {
        if (ref.stream === "places") {
          const p = placeOf(ref.deviceId, r);
          if (p) places.push(p);
        } else if (ref.stream === "checkins") {
          const c = checkInOf(ref.deviceId, r);
          if (c) checkIns.push(c);
        } else {
          const o = overrideOf(r);
          if (o) overrides.push(o);
        }
      }
    }
    this.places = [...latestByKey(places).values()];
    this.checkIns = [...latestByKey(checkIns).values()].sort((a, b) => a.time - b.time);
    this.overrides = latestOverrides(overrides);
  }

  /** The newest readable status of every device; up to 3 older ones are tried. */
  private async loadStatus(): Promise<void> {
    await Promise.all(
      this.deviceIds().map(async (id) => {
        const rows = this.batches.filter((b) => b.deviceId === id && b.stream === "status").sort((a, b) => b.seq - a.seq);
        const prev = this.status.get(id);
        let error: string | null = null;
        for (const ref of rows.slice(0, 4)) {
          if (prev && prev.status && ref.seq <= prev.seq) {
            this.status.set(id, { ...prev, error });
            return;
          }
          try {
            const blob = await this.guard(this.api.blob(ref, this.controller.signal));
            const recs = await openBlob(this.key, ref, blob, STATUS_CAPS);
            const s = statusOf(recs[recs.length - 1]);
            if (s) {
              this.status.set(id, { status: s, seq: ref.seq, error });
              return;
            }
            error ??= "parse";
          } catch (e) {
            if (e instanceof ApiError && e.unauthorized) throw e;
            error ??= e instanceof BlobError ? e.kind : e instanceof ApiError ? `HTTP ${e.status}` : "network";
          }
        }
        this.status.set(id, { status: prev?.status ?? null, seq: prev?.seq ?? -1, error: rows.length ? error : null });
      }),
    );
  }

  // ------------------------------------------------------------ archive

  /** Days with positions of a device, newest first. */
  days(deviceId: string): string[] {
    return this.archives.get(deviceId)?.days ?? [];
  }

  /** Whether older batches remain to be opened. */
  hasMore(deviceId: string): boolean {
    const a = this.archives.get(deviceId);
    return !!a && a.queue.some((b) => !a.done.has(bkey(b)) && !a.skip.has(bkey(b)));
  }

  failures(deviceId: string): number {
    return this.archives.get(deviceId)?.failures ?? 0;
  }

  /**
   * Opens batches until `want` more days are known, or nothing is left.
   * Reports progress as "復号中 ready / want 日".
   */
  async loadMore(deviceId: string, want: number): Promise<void> {
    const a = this.archives.get(deviceId);
    if (!a) return;
    const start = a.days.length;
    this.progress = { device: deviceId, ready: 0, target: want };
    this.emit("progress");
    const worker = async () => {
      for (;;) {
        if (a.days.length - start >= want) return;
        const ref = this.nextBatch(a);
        if (!ref) return;
        a.inFlight.add(bkey(ref));
        try {
          await this.openArchiveBatch(a, ref);
        } catch (e) {
          if (e instanceof ApiError && e.unauthorized) throw e;
          a.failures++;
        } finally {
          a.inFlight.delete(bkey(ref));
          a.done.add(bkey(ref));
        }
        this.progress = { device: deviceId, ready: Math.min(a.days.length - start, want), target: want };
        this.emit("progress");
      }
    };
    try {
      await Promise.all(Array.from({ length: CONCURRENCY }, worker));
    } finally {
      this.progress = null;
      this.emit("change");
    }
  }

  private nextBatch(a: DeviceArchive): BatchMeta | null {
    const open = (b: BatchMeta) => !a.done.has(bkey(b)) && !a.inFlight.has(bkey(b)) && !a.skip.has(bkey(b));
    while (a.priority.length) {
      const k = a.priority.shift()!;
      const b = a.queue.find((x) => bkey(x) === k);
      if (b && open(b)) return b;
    }
    return a.queue.find((b) => open(b) && b.size <= SMALL) ?? a.queue.find(open) ?? null;
  }

  private async openArchiveBatch(a: DeviceArchive, ref: BatchMeta): Promise<void> {
    const blob = await this.guard(this.api.blob(ref, this.controller.signal));
    const records = await openBlob(this.key, ref, blob);
    if (ref.stream === "fixes") {
      this.addFixes(a, records.map(legacyFix).filter((f): f is Fix => f !== null));
      return;
    }
    if (ref.stream === "motion") {
      this.addMotion(a, records.map(legacyMotion).filter((m): m is MotionWindow => m !== null));
      return;
    }
    for (const r of records) {
      const part = parseArchiveRecord(r);
      if (!part) continue;
      const info = fileInfo(part.file);
      if (!info) continue;
      // The other parts of this file sit at the neighbouring seqs.
      const siblings = Array.from({ length: part.parts }, (_, i) => ref.seq - part.part + i).filter((s) => s !== ref.seq && s >= 0);
      if (!NEEDED.has(info.table)) {
        for (const s of siblings) if (a.queue.find((b) => b.seq === s && b.stream === "archive" && b.size > 64 * 1024)) a.skip.add(`archive/${s}`);
        continue;
      }
      a.priority.push(...siblings.map((s) => `archive/${s}`));
      const bytes = a.assembler.add(part, ref.seq);
      if (!bytes) {
        // An abandoned generation is not a gap when a complete one exists.
        if (!info.month && !a.assembler.hasComplete(part.file)) a.partialDays.add(info.period);
        continue;
      }
      a.partialDays.delete(info.period);
      const table = await readParquet(bytes);
      if (info.table === "fixes") this.addFixes(a, fixesOf(table));
      else this.addMotion(a, motionOf(table));
    }
  }

  private touch(a: DeviceArchive, day: string): void {
    a.version.set(day, (a.version.get(day) ?? 0) + 1);
  }

  private addFixes(a: DeviceArchive, fixes: Fix[]): void {
    const byDay = new Map<string, Fix[]>();
    for (const f of fixes) {
      const k = dayKey(f.time);
      (byDay.get(k) ?? byDay.set(k, []).get(k)!).push(f);
    }
    for (const [k, list] of byDay) {
      a.fixes.set(k, dedupeFixes([...(a.fixes.get(k) ?? []), ...list]));
      this.touch(a, k);
      if (!a.days.includes(k)) a.days.push(k);
    }
    a.days.sort((x, y) => (x < y ? 1 : x > y ? -1 : 0));
  }

  private addMotion(a: DeviceArchive, ws: MotionWindow[]): void {
    const byDay = new Map<string, MotionWindow[]>();
    for (const w of ws) {
      const k = dayKey(w.start);
      (byDay.get(k) ?? byDay.set(k, []).get(k)!).push(w);
    }
    for (const [k, list] of byDay) {
      a.motion.set(k, dedupeMotion([...(a.motion.get(k) ?? []), ...list]));
      this.touch(a, k);
    }
  }

  /** The timeline of one day, built as the app does (with 12 h of context on both sides). */
  day(deviceId: string, date: string): DayData | null {
    const a = this.archives.get(deviceId);
    if (!a || !a.fixes.has(date)) return null;
    const near = [addDays(date, -1), date, addDays(date, 1)];
    const v = near.map((d) => a.version.get(d) ?? 0).join(".") + `|${this.places.length}|${this.overrides.length}`;
    const hit = a.cache.get(date);
    if (hit && hit.v === v) return hit.data;

    const ds = dayStart(date);
    const de = dayStart(addDays(date, 1));
    const fixes = near.flatMap((d) => a.fixes.get(d) ?? []).filter((f) => f.time >= ds - MARGIN && f.time < de + MARGIN);
    const motion = near.flatMap((d) => a.motion.get(d) ?? []).filter((m) => m.start >= ds - MARGIN && m.start < de + MARGIN);
    const built = linkPlaces(buildTimeline(fixes, motion), this.placesFor(deviceId));
    const corrected = applyOverrides(built, this.overrides.filter((o) => o.end > ds - MARGIN && o.start < de + MARGIN));
    const segments = splitByDay(corrected).get(date) ?? [];
    const steps = (a.motion.get(date) ?? []).filter((m) => m.start >= ds && m.start < de).reduce((s, m) => s + m.steps, 0);
    const data: DayData = {
      date,
      segments,
      summary: summarizeDay(date, segments, steps),
      checkIns: this.checkIns.filter((c) => c.time >= ds && c.time < de),
      partial: a.partialDays.has(date),
    };
    a.cache.set(date, { v, data });
    return data;
  }

  /** The device's own places first, then those of the user's other devices. */
  private placesFor(deviceId: string): Place[] {
    const own = this.places.filter((p) => p.key.startsWith(`${deviceId}/`));
    return [...own, ...this.places.filter((p) => !p.key.startsWith(`${deviceId}/`))];
  }
}
