// The browser's registration as a read-only device (a viewer): its token
// and the batch key, nothing else. Pairing v1 (data/pairing.ts,
// docs/design/browser-pairing-v1.md) hands them over: the browser shows a
// code, the app seals the batch key to the browser's temporary key after
// the user compared the four words on both. Dev builds also have a panel
// that takes a device token and the batch key pasted by the developer.
//
// The key is kept as a non-extractable CryptoKey (docs/web-ui.md,
// "セキュリティに関わる UX"): scripts of the same origin (the admin page)
// can use it while the page is open, but cannot read its value. A paired
// session lives in memory only; a reload or a closed tab pairs again.

import { fromBase64, fromHex } from "./bytes";
import { importBatchKey } from "./crypto";
import { PairingAttempt, browserDeps, type PairingDeps, type PairingEvent } from "./pairing";

export type KeyStorage = "tab" | "browser";

export interface ViewerSession {
  /** Device token of this browser (`Authorization: Bearer`). */
  token: string;
  batchKey: CryptoKey;
  /** This browser's device id, when the pairing assigned one (needed to unregister). */
  deviceId: string | null;
  /** The name the browser registered under. */
  name: string;
  registeredAt: number;
  /** When the server stops serving this browser (ms), null if unknown. */
  expiresAt: number | null;
  storage: KeyStorage;
  source: "dev" | "pairing";
}

/** Where a session is kept. */
export interface SessionStore {
  load(): Promise<ViewerSession | null>;
  save(s: ViewerSession): Promise<void>;
  clear(): Promise<void>;
}

/** One running attempt, as the UI controls it. */
export interface PairingControl {
  /** 「一致する」. */
  confirm(): void;
  /** 「一致しない」: aborts and drops the temporary key. */
  mismatch(): Promise<void>;
  /** Leaves the attempt without a result (the page starts another). */
  cancel(): Promise<void>;
  /** Milliseconds left of the attempt. */
  remainingMs(): number;
}

/** The pairing protocol. The UI (3 steps: code, words, done) only depends on this. */
export interface Pairing {
  /** Starts a new attempt; its progress and outcome arrive as events. */
  start(onEvent: (e: PairingEvent) => void): PairingControl;
}

/** Pairing v1 against the server that serves this page. */
export function pairingV1(deps: PairingDeps = browserDeps()): Pairing {
  return {
    start(onEvent) {
      const a = new PairingAttempt(deps, onEvent);
      void a.run();
      return a;
    },
  };
}

/** The dev panel exists only in `vite` dev builds (import.meta.env.DEV is false in production bundles). */
export const DEV_PANEL: boolean = import.meta.env.DEV;

/** Device tokens: 32 bytes base64url without padding. */
export const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;

/** A 32-byte key as 64 hex digits or base64 (standard or URL-safe). */
export function parseKeyText(text: string): Uint8Array {
  const t = text.trim();
  const raw = /^[0-9a-fA-F]{64}$/.test(t) ? fromHex(t) : fromBase64(t);
  if (raw.byteLength !== 32) throw new Error("鍵は 32 バイトです。");
  return raw;
}

/** Dev builds only: a session from a pasted token and batch key. */
export async function devSession(token: string, keyText: string, storage: KeyStorage = "tab"): Promise<ViewerSession> {
  if (!DEV_PANEL) throw new Error("dev sessions are not available in this build");
  const tok = token.trim();
  if (!TOKEN_RE.test(tok)) throw new Error("端末トークンは 43 文字です。");
  const raw = parseKeyText(keyText);
  const batchKey = await importBatchKey(raw);
  raw.fill(0);
  return { token: tok, batchKey, deviceId: null, name: "開発用", registeredAt: Date.now(), expiresAt: null, storage, source: "dev" };
}

/** 「このタブを閉じるまで」: memory only; a reload asks again. */
export class MemoryStore implements SessionStore {
  private s: ViewerSession | null = null;
  async load() {
    return this.s;
  }
  async save(s: ViewerSession) {
    this.s = s;
  }
  async clear() {
    this.s = null;
  }
}

const DB = "ashidori-viewer";
const OS = "session";

function idb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(OS);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error ?? new Error("IndexedDB"));
  });
}

async function tx<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await idb();
  try {
    return await new Promise<T>((resolve, reject) => {
      const t = db.transaction(OS, mode);
      const r = f(t.objectStore(OS));
      t.oncomplete = () => resolve(r.result);
      t.onerror = () => reject(t.error ?? new Error("IndexedDB"));
      t.onabort = () => reject(t.error ?? new Error("IndexedDB"));
    });
  } finally {
    db.close();
  }
}

/** 「このブラウザに保存」: IndexedDB; the CryptoKey stays non-extractable there too. */
export class IdbStore implements SessionStore {
  async load(): Promise<ViewerSession | null> {
    try {
      const v = (await tx("readonly", (s) => s.get("current"))) as ViewerSession | undefined;
      return v && v.source === "dev" && v.batchKey instanceof CryptoKey && TOKEN_RE.test(v.token) ? { ...v, expiresAt: v.expiresAt ?? null } : null;
    } catch {
      return null;
    }
  }
  async save(s: ViewerSession): Promise<void> {
    await tx("readwrite", (os) => os.put(s, "current"));
  }
  async clear(): Promise<void> {
    try {
      await tx("readwrite", (os) => os.delete("current"));
    } catch {
      // Nothing stored, or storage blocked.
    }
  }
}

/** Both stores behind one: saving under one storage removes the other copy. */
export class Sessions {
  constructor(
    private readonly memory: SessionStore = new MemoryStore(),
    private readonly persistent: SessionStore = new IdbStore(),
  ) {}

  async load(): Promise<ViewerSession | null> {
    return (await this.memory.load()) ?? (await this.persistent.load());
  }

  /** A paired session is never persisted (memory only, browser-pairing-v1). */
  async save(s: ViewerSession): Promise<void> {
    await this.memory.save(s);
    if (s.storage === "browser" && s.source === "dev") await this.persistent.save(s);
    else await this.persistent.clear();
  }

  /** Forgets the key everywhere (登録の解除, a revoked token). */
  async clear(): Promise<void> {
    await this.memory.clear();
    await this.persistent.clear();
  }
}
