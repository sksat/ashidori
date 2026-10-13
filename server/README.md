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

GitHub Actions（[`.github/workflows/server.yml`](../.github/workflows/server.yml)）は `server/` の変更ごとにテストを実行し、`main` ではマイグレーションとデプロイを行う。D1・R2・カスタムドメイン・Access アプリケーションは事前に用意しておく。これらはダッシュボード、wrangler、IaC のいずれで作成してもよい。`wrangler.jsonc` にはルーティングを書かないため、デプロイによってルーティングが変わることはない。

### 1. リソースを用意する

| リソース | 名前・内容 |
| --- | --- |
| D1 データベース | `ashidori` |
| R2 バケット | `ashidori-blobs` |
| Worker | `ashidori-sync`（初回デプロイで作成される） |
| カスタムドメイン | Worker `ashidori-sync` に任意のホスト名（例: `sync.example.com`）を割り当てる |
| Access アプリケーション | 上記ホスト名の `/admin` を自分のみに許可する |

wrangler で作成する場合は次のとおり。カスタムドメインは初回デプロイ後、ダッシュボードの Worker → Settings → Domains & Routes で追加する。

```sh
pnpm exec wrangler d1 create ashidori
pnpm exec wrangler r2 bucket create ashidori-blobs
```

Access アプリケーションは Zero Trust → Access → Applications → Add an application → Self-hosted で作成する。

- Application domain: カスタムドメインのホスト名、path: `admin`（`/admin/*` を保護する）
- Policy: Allow、Include: 自分のメールアドレスのみ
- 作成後、**Application Audience (AUD) Tag** と、Zero Trust → Settings の **Team domain**（`<team>.cloudflareaccess.com`）を控える

Worker は `Cf-Access-Jwt-Assertion` を自身でも検証する。検証内容は、`https://<team>.cloudflareaccess.com/cdn-cgi/access/certs` の鍵による RS256 署名、`aud`、`iss`、`exp`、`nbf` である。Access の設定漏れで `/admin/*` が公開されることはなく、`CF_ACCESS_*` が空のときは admin API をすべて拒否する。

### 2. デプロイ用の API トークンを作る

CI 用には、ユーザーではなくアカウントが所有する API トークン（Account API token）を使う。作成者の権限やアカウント所属に依存せず、`cfat_` 接頭辞により漏洩の検出もしやすい。ダッシュボード → 対象アカウント → Manage account → Account API tokens → Create Token で作成する。

| 権限 | 範囲 | 用途 |
| --- | --- | --- |
| Workers: Editor | Specified Workers: `ashidori-sync` | Worker のデプロイ（既存の Worker の更新・バージョン・設定） |
| D1: Edit | アカウント | マイグレーションの適用 |

- Workers ロールは Worker 単位で付与できる。Editor は既存の Worker を更新・デプロイできるが、作成・削除はできない。Worker が未作成の場合は、初回のみ Workers 全体の Admin で作成するか、ダッシュボード・IaC で作成しておく。
- ルーティング（カスタムドメイン）は `wrangler.jsonc` で変更しないため、Zone の Workers Routes 権限は不要。
- D1 は Workers ロールの対象外で、アカウント単位の権限になる。
- 旧来の Workers Scripts: Edit（legacy）は Workers 全体の Editor に相当する。範囲を絞れないため使わない。
- 有効期限を設定し、期限前に作り直す。
- `CLOUDFLARE_ACCOUNT_ID` を渡すため、アカウントの検索（Memberships・Account Settings）の権限は不要。

### 3. GitHub に登録する

トークンは環境（environment）`production` の secret とし、この環境を使えるブランチを `main` に限定する。PR や他のブランチのワークフローからはトークンを参照できない。

| 種類 | 名前 | 登録先 | 値 |
| --- | --- | --- | --- |
| Secret | `CLOUDFLARE_API_TOKEN` | 環境 `production` | 2. のトークン |
| Secret | `CLOUDFLARE_ACCOUNT_ID` | 環境 `production` | Account ID |
| Secret | `D1_DATABASE_ID` | 環境 `production` | D1 データベースの ID |
| Variable | `CF_ACCESS_TEAM_DOMAIN` | リポジトリ | Access の Team domain |
| Variable | `CF_ACCESS_AUD` | リポジトリ | Access アプリケーションの AUD Tag |
| Variable | `DEPLOY_SERVER` | リポジトリ | `true`（`true` のときのみデプロイする） |

```sh
# 環境 production を作り、デプロイ元を main に限定する
gh api -X PUT 'repos/{owner}/{repo}/environments/production' \
  -F 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true'
gh api -X POST 'repos/{owner}/{repo}/environments/production/deployment-branch-policies' \
  -f name=main -f type=branch

gh secret set CLOUDFLARE_API_TOKEN --env production
gh secret set CLOUDFLARE_ACCOUNT_ID --env production
gh secret set D1_DATABASE_ID --env production
gh variable set CF_ACCESS_TEAM_DOMAIN --body '<team>.cloudflareaccess.com'
gh variable set CF_ACCESS_AUD --body '<AUD Tag>'
gh variable set DEPLOY_SERVER --body true
```

GitHub Actions の IP アドレスは Azure と共有の広い範囲（7,000 件以上、毎週更新）のため、トークンの IP 制限には使わない。

### 4. デプロイする

Actions の `server` ワークフローを Run workflow で実行する（`main` への push でも実行される）。本番で必要な Worker の secret はない。`ADMIN_DEV_TOKEN` は本番に設定せず、`ALLOW_DEV_ADMIN` は `"false"` のままにする。

将来の web viewer を別オリジンで動かす場合は、`CORS_ALLOWED_ORIGINS` にそのオリジンをカンマ区切りで書く（例: `"https://viewer.example.com"`）。空なら CORS ヘッダは返さない。CORS は `/v1/*` のみに適用し、`/admin/*` には適用しない。

### 5. 最初のデバイスをペアリングする

1. ブラウザで `https://<ホスト名>/admin/` を開く（Access のログインを経由する）。
2. 「発行する」を押すと 8 文字のペアリングコードが表示される。有効期限は 10 分で、1 回のみ使用できる。
3. アプリの同期設定にサーバ URL とコードを入力する。アプリは `POST /v1/devices/register` を呼び、デバイストークンを受け取る。

2 台目以降は、既存デバイスから `POST /v1/pairing` でコードを発行することもできる。admin ページではデバイス一覧の確認と失効もできる。

### 手元からデプロイする場合

`wrangler login` した上で、`wrangler.jsonc` の `database_id` を D1 の ID に置き換えて実行する（置き換えはコミットしない）。

```sh
pnpm exec wrangler d1 migrations apply ashidori --remote
pnpm exec wrangler deploy --var CF_ACCESS_TEAM_DOMAIN:<team>.cloudflareaccess.com --var CF_ACCESS_AUD:<AUD Tag>
```

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
