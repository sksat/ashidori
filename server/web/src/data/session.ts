// The browser's registration as a read-only device: its device token and
// the batch key. The pairing protocol that hands these over (the browser
// makes a temporary key pair, the app seals the key to it after the user
// compared the confirmation words; docs/web-viewer.md) is designed
// separately (docs/design/browser-pairing-v1.md). Until it exists, the only
// way in is the dev panel, compiled into dev builds only, which takes a
// device token and the batch key pasted by the developer.
//
// The key is kept as a non-extractable CryptoKey (docs/web-ui.md,
// "セキュリティに関わる UX"): scripts of the same origin (the admin page)
// can use it while the page is open, but cannot read its value.

import { fromBase64, fromHex } from "./bytes";
import { importBatchKey } from "./crypto";

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
  storage: KeyStorage;
  source: "dev" | "pairing";
}

/** Where a session is kept. */
export interface SessionStore {
  load(): Promise<ViewerSession | null>;
  save(s: ViewerSession): Promise<void>;
  clear(): Promise<void>;
}

/**
 * The pairing protocol, to be implemented from browser-pairing-v1. The UI
 * (3 steps: code, confirmation words, done) only depends on this.
 */
export interface Pairing {
  /** Step 1: a code issued by the app. Resolves with the words to compare. */
  start(code: string, signal?: AbortSignal): Promise<{ words: string[]; expiresAt: number }>;
  /** Step 2: waits until the app allowed it, then returns the session. */
  wait(signal?: AbortSignal): Promise<ViewerSession>;
  /** 「一致しない」, expiry or failure: drops the temporary key. */
  cancel(): Promise<void>;
}

/** No protocol yet: every attempt says so. */
export const PAIRING_UNAVAILABLE: Pairing = {
  async start() {
    throw new Error("pairing not implemented");
  },
  async wait() {
    throw new Error("pairing not implemented");
  },
  async cancel() {},
};

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
  return { token: tok, batchKey, deviceId: null, name: "開発用", registeredAt: Date.now(), storage, source: "dev" };
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
      return v && v.batchKey instanceof CryptoKey && TOKEN_RE.test(v.token) ? v : null;
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

  async save(s: ViewerSession): Promise<void> {
    await this.memory.save(s);
    if (s.storage === "browser") await this.persistent.save(s);
    else await this.persistent.clear();
  }

  /** Forgets the key everywhere (登録の解除, a revoked token). */
  async clear(): Promise<void> {
    await this.memory.clear();
    await this.persistent.clear();
  }
}
