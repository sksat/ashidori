# ashidori sync server

ashidori の同期サーバです。
プロトコルは [`docs/sync-protocol.md`](../docs/sync-protocol.md) に従います。

- Cloudflare Workers (Hono) で動きます。
- D1 にデバイス、ペアリングコード、バッチのメタデータ、rate limit のカウンタを保存します。
- R2 に暗号化済みのバッチ blob を保存します。サーバは平文を一切扱いません。
- 1 デプロイ = 1 人用です。その人の複数デバイスが同じデータを共有します。

## 開発

Node 24 と pnpm を使います。

```sh
pnpm install
pnpm test               # vitest + @cloudflare/vitest-plugin (Workers runtime 上で実行)
pnpm exec tsc --noEmit  # 型チェック
```

ローカルで動かす場合は `.dev.vars.example` を `.dev.vars` にコピーし、`ADMIN_DEV_TOKEN` を長いランダム文字列にします。

```sh
cp .dev.vars.example .dev.vars
pnpm exec wrangler d1 migrations apply ashidori --local
pnpm dev
# 最初のペアリングコード (ローカルのみ。ALLOW_DEV_ADMIN=true のときだけ有効)
curl -X POST -H "Authorization: Bearer $ADMIN_DEV_TOKEN" http://localhost:8787/admin/pairing
```

`ALLOW_DEV_ADMIN` は本番では必ず `"false"` (既定値) のままにします。
`"false"` のとき `ADMIN_DEV_TOKEN` は設定されていても無視されます。

## デプロイ手順

`wrangler login` 済みであることを前提にします。

### 1. D1 と R2 を作る

```sh
pnpm exec wrangler d1 create ashidori
pnpm exec wrangler r2 bucket create ashidori-blobs
```

`d1 create` が表示した `database_id` を `wrangler.jsonc` の `d1_databases[0].database_id` に書きます。

### 2. マイグレーションを適用する

```sh
pnpm exec wrangler d1 migrations apply ashidori --remote
```

### 3. カスタムドメインを決める

`wrangler.jsonc` は `workers_dev: false` にしてあります。
Access で `/admin/*` を守るために、Worker を自分のゾーンのカスタムドメイン (例: `ashidori.example.com`) に割り当てます。
`wrangler.jsonc` に次を追加します。

```jsonc
"routes": [{ "pattern": "ashidori.example.com", "custom_domain": true }]
```

### 4. Cloudflare Access application を作る (`/admin/*`)

Zero Trust ダッシュボード → Access → Applications → Add an application → Self-hosted で作ります。

- Application domain: `ashidori.example.com`、path: `admin` (= `/admin/*` を保護します)
- Policy: Allow、Include: 自分のメールアドレスだけ
- 作成後、application の **Application Audience (AUD) Tag** を控えます。
- チームドメイン (`<team>.cloudflareaccess.com`) は Zero Trust → Settings の Team domain で確認できます。

`wrangler.jsonc` の `vars` に設定します。

```jsonc
"CF_ACCESS_TEAM_DOMAIN": "<team>.cloudflareaccess.com",
"CF_ACCESS_AUD": "<AUD tag>"
```

Worker は `Cf-Access-Jwt-Assertion` を自分でも検証します。
検証内容は、`https://<team>.cloudflareaccess.com/cdn-cgi/access/certs` の鍵による RS256 署名、`aud`、`iss`、`exp`、`nbf` です。
このため、Access の設定漏れで `/admin/*` がそのまま公開されることはありません。
`CF_ACCESS_*` が空のとき、admin API はすべて拒否されます。

### 5. secrets / vars

本番で必要な secret はありません。
`ADMIN_DEV_TOKEN` は本番に設定しないでください。

将来の web viewer を別オリジンで動かす場合は、`CORS_ALLOWED_ORIGINS` にそのオリジンをカンマ区切りで書きます (例: `"https://viewer.example.com"`)。
空なら CORS ヘッダは一切返しません。
CORS は `/v1/*` だけに適用し、`/admin/*` には適用しません。

### 6. デプロイ

```sh
pnpm exec wrangler deploy
```

### 7. 最初のデバイスをペアリングする

1. ブラウザで `https://ashidori.example.com/admin/` を開きます (Access のログインを経由します)。
2. 「発行する」を押すと 8 文字のペアリングコードが出ます。有効期限は 10 分で、1 回だけ使えます。
3. アプリの同期設定にサーバ URL とコードを入力します。アプリは `POST /v1/devices/register` を呼び、デバイストークンを受け取ります。

2 台目以降は、既存デバイスから `POST /v1/pairing` でコードを発行することもできます。
admin ページではデバイス一覧の確認と revoke もできます。

## 実装メモ

- **時刻**: API のタイムスタンプ (`createdAt`, `lastSeenAt`, `expiresAt`) はすべて Unix epoch ミリ秒の数値です。
- **カーソル**: `cursor` は D1 の `INTEGER PRIMARY KEY AUTOINCREMENT` です。単調増加で、再利用されません。`after` の既定値は `0` です。`limit` の既定値は 100、上限は 1000 です。
- **トークン**: 32 byte 乱数の base64url (43 文字) です。D1 には SHA-256 hex だけを保存し、照合は constant-time 比較で行います。
- **ペアリングコード**: Crockford base32 で 8 文字 (40 bit) です。D1 には SHA-256 hex だけを保存します。入力は大文字小文字を区別せず、`-` と空白を無視し、`I`/`L` を `1`、`O` を `0` として扱います。
  - 消費は `UPDATE ... WHERE used_at IS NULL AND expires_at > now RETURNING` の 1 文で行うので、同じコードで 2 台が登録されることはありません。
- **lastSeenAt**: 認証時に保存値が 60 秒以上古いときだけ、`waitUntil` で更新します。
- **Rate limit**: Workers Rate Limiting binding ではなく、D1 の固定ウィンドウカウンタ (`rate_limits` テーブル) を使います。
  - 理由: personal server のリクエスト量では D1 で十分です。結果が強整合で、`wrangler dev` とテストでも同じように動きます。
  - IP ごと (`CF-Connecting-IP`、IPv6 は /64 単位) に、次の上限をかけます。
    - `register`: 10 回 / 10 分。失敗した試行も数えます。
    - `pairing`: 20 回 / 10 分。`/v1/pairing` と `/admin/pairing` で共通です。
  - 上限を超えると `429` を返します。
- **PUT**: `Content-Type: application/octet-stream` と `X-Content-SHA256` が必須です。
  - 本文は 8 MiB を超えた時点で読み込みを打ち切り、`413` を返します。
  - R2 への put が成功してから D1 にメタデータを insert します。そのため、一覧に出るバッチは必ず blob が存在します。
  - R2 には `sha256` を渡して、R2 側でも整合性を検証させています。
- **GET blob**: D1 にメタデータがあるものだけを返します。
- **admin**: `Origin` ヘッダが別オリジンのリクエストは `403` で拒否します (CSRF 対策)。
- **セキュリティヘッダ**: 全レスポンスに付けます。対象は `nosniff`、`Referrer-Policy: no-referrer`、`X-Frame-Options: DENY`、HSTS、CSP (`default-src 'none'`、admin ページは nonce 付き)、`Cache-Control: no-store` です。

### 既知の制限

- 同じデバイスが同じ `(stream, seq)` に**異なる内容を同時に** PUT した場合 (クライアントのバグでしか起きません)、R2 の blob が後勝ちで上書きされる可能性があります。D1 のメタデータは先勝ちです。
  - その場合、GET で得た blob の SHA-256 は一覧の `sha256` と一致しません。クライアントはダウンロード後にハッシュを検証してください (AES-GCM の認証でも検出されます)。
