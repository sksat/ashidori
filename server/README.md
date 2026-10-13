# ashidori sync server

ashidori の同期サーバです。
プロトコルは [`docs/sync-protocol.md`](../docs/sync-protocol.md) に従います。

- Cloudflare Workers (Hono) で動きます。
- D1 に利用者、デバイス、ペアリングコード、バッチのメタデータ、フォロー、共有チェックイン、rate limit のカウンタを保存します。
- R2 に暗号化済みのバッチ blob を保存します。サーバは記録の平文を扱いません。例外は、利用者がフォロワーに共有したチェックインだけです。
- 1 デプロイで複数の利用者を扱えます。データは利用者ごとに分かれ、同じ利用者のデバイスどうしだけが共有します。
- 運用者（管理者）に見える情報は [`docs/sync-protocol.md`](../docs/sync-protocol.md#what-the-server-operator-can-see) にまとめています。

## 管理者と利用者

管理者（運用者）と利用者を区別する。

- **管理者**は admin ページ（`/admin/`）を使う。条件は、Cloudflare Access のログインを経て、その JWT の `email` が `ADMIN_EMAILS`（カンマ区切り、大文字小文字を区別しない）に含まれることである。Worker が JWT を検証して照合するため、Access のポリシーが広くても（例: SSO でログインできる全員）一覧にない人は `403` になる。`ADMIN_EMAILS` が空なら admin API はすべて拒否する。
- **利用者**は web にログインしない。管理者が admin ページで利用者を作成し（名前は必須、メールアドレスは控えとして任意）、その利用者用のペアリングコードを発行して渡す。利用者はアプリにコードを入力して最初のデバイスを登録する。2 台目以降は、アプリから `POST /v1/pairing` でコードを発行して追加する。
- デバイスはペアリングコードの利用者に属する。デバイスから見えるのは、同じ利用者のデバイスとバッチだけである。他の利用者のバッチは一覧に出ず、取得しようとしても `404` になる。
- 利用者を停止すると、その利用者のデバイストークンと未使用のペアリングコードは使えなくなる。データは消さない。再開すれば元どおり使える。
- 利用者の導入前に登録されたデバイス（`user_id` が NULL）は、旧形式のデバイスとしてそのまま動く。旧形式のデバイスどうしはデータを共有し、利用者のデバイスとは分離される。管理者が旧形式のコードを発行することはできない。旧形式のデバイスは共有チェックインとフォローを使えない（`403`）。

## 共有チェックインとフォロー

利用者は、選んだチェックインをフォロワーに共有できる。共有チェックインはサーバに平文で保存され、管理者も読める。それ以外の記録は暗号文のままである。

- 管理者は利用者にハンドル（英小文字・数字・`_` の 3〜20 文字、大文字小文字を区別せず一意）を設定できる。ハンドルのない利用者は検索できない。
- 利用者はハンドルの完全一致で相手を検索し（`GET /v1/users/lookup`、IP ごとに rate limit）、フォローを申請する。一覧や前方一致の検索はないため、利用者を列挙できない。
- 申請された側が承認するとフォローが成立し、フォロワーは相手の共有チェックインを見られる。拒否すると申請は削除され、再申請できる。フォローは一方向で、フォロー解除とフォロワーの削除はどちらの側からもできる。
- 停止中の利用者は検索・フォローの対象にならず、その共有チェックインは削除済みとして配信される。再開すると元に戻る。
- 管理者は admin ページで共有チェックインを確認し、削除できる（モデレーション）。
- 詳細は [`docs/sync-protocol.md`](../docs/sync-protocol.md#shared-check-ins-and-follows) を参照。

### admin API

いずれも上記の管理者認証が必要である。POST・PATCH は同一オリジンからのみ受け付け、本文がある場合は `Content-Type: application/json` とする。

| Method | Path | 内容 |
| --- | --- | --- |
| GET | `/admin/` | admin ページ |
| GET | `/admin/users` | 利用者の一覧（名前、メールアドレス、状態、作成日時、失効していないデバイス数、デバイスの最終アクセス） |
| POST | `/admin/users` | `{name, handle?, email?}` → 作成した利用者。ハンドルが使用済みなら `409` |
| PATCH | `/admin/users/:id` | `{name?, handle?}` で名前・ハンドルを変更する。`handle: null` でハンドルを削除する |
| POST | `/admin/users/:id/pairing` | その利用者のペアリングコード → `{code, expiresAt}`。停止中の利用者には `409` |
| POST | `/admin/users/:id/disable` | 利用者を停止する |
| POST | `/admin/users/:id/enable` | 利用者を再開する |
| GET | `/admin/devices` | 全デバイスの一覧（`userId`・`userName` 付き。旧形式は `null`） |
| POST | `/admin/devices/:id/revoke` | 任意のデバイスを失効させる |
| GET | `/admin/checkins` | 共有チェックインの一覧（変更の新しい順に 200 件。`user`・`userStatus` 付き。削除済みは含まない） |
| POST | `/admin/checkins/:id/delete` | 共有チェックインを削除する（内容を消し、削除済みの記録だけを残す） |

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
# 利用者を作成し、その利用者のペアリングコードを発行する (ローカルのみ。ALLOW_DEV_ADMIN=true のときだけ有効)
curl -X POST -H "Authorization: Bearer $ADMIN_DEV_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"local"}' http://localhost:8787/admin/users
curl -X POST -H "Authorization: Bearer $ADMIN_DEV_TOKEN" http://localhost:8787/admin/users/<id>/pairing
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
| Worker | `ashidori-sync`（デプロイ用トークンは Worker を作成できないため、事前に作成する） |
| カスタムドメイン | Worker `ashidori-sync` に任意のホスト名（例: `sync.example.com`）を割り当てる |
| Access アプリケーション | 上記ホスト名の `/admin` を保護する |

wrangler で作成する場合は次のとおり。カスタムドメインは初回デプロイ後、ダッシュボードの Worker → Settings → Domains & Routes で追加する。

```sh
pnpm exec wrangler d1 create ashidori
pnpm exec wrangler r2 bucket create ashidori-blobs
```

Access アプリケーションは Zero Trust → Access → Applications → Add an application → Self-hosted で作成する。

- Application domain: カスタムドメインのホスト名、path: `admin`（`/admin/*` を保護する）
- Policy: Allow、Include: 管理者のメールアドレス（管理者の判定は Worker が `ADMIN_EMAILS` で行うため、SSO でログインできる全員などのより広い範囲でもよい）
- 作成後、**Application Audience (AUD) Tag** と、Zero Trust → Settings の **Team domain**（`<team>.cloudflareaccess.com`）を控える

Worker は `Cf-Access-Jwt-Assertion` を自身でも検証する。検証内容は、`https://<team>.cloudflareaccess.com/cdn-cgi/access/certs` の鍵による RS256 署名、`aud`、`iss`、`exp`、`nbf` である。Access の設定漏れで `/admin/*` が公開されることはなく、`CF_ACCESS_*` または `ADMIN_EMAILS` が空のときは admin API をすべて拒否する。

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
| Variable | `ADMIN_EMAILS` | リポジトリ | 管理者のメールアドレス（カンマ区切り） |
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
gh variable set ADMIN_EMAILS --body '<管理者のメールアドレス>'
gh variable set DEPLOY_SERVER --body true
```

GitHub Actions の IP アドレスは Azure と共有の広い範囲（7,000 件以上、毎週更新）のため、トークンの IP 制限には使わない。

### 4. デプロイする

Actions の `server` ワークフローを Run workflow で実行する（`main` への push でも実行される）。本番で必要な Worker の secret はない。`ADMIN_DEV_TOKEN` は本番に設定せず、`ALLOW_DEV_ADMIN` は `"false"` のままにする。

将来の web viewer を別オリジンで動かす場合は、`CORS_ALLOWED_ORIGINS` にそのオリジンをカンマ区切りで書く（例: `"https://viewer.example.com"`）。空なら CORS ヘッダは返さない。CORS は `/v1/*` のみに適用し、`/admin/*` には適用しない。

### 5. 利用者を作成し、最初のデバイスをペアリングする

1. ブラウザで `https://<ホスト名>/admin/` を開く（Access のログインを経由する）。
2. 利用者の名前（任意でハンドルとメールアドレス）を入力して「作成」を押す。ハンドルは他の利用者がフォローの申請に使う。
3. その利用者の「ペアリングコード発行」を押すと 8 文字のコードが表示される。有効期限は 10 分で、1 回のみ使用できる。コードを利用者に渡す。
4. 利用者はアプリの同期設定にサーバ URL とコードを入力する。アプリは `POST /v1/devices/register` を呼び、デバイストークンを受け取る。

2 台目以降は、利用者が既存デバイスから `POST /v1/pairing` でコードを発行して追加する。admin ページでは利用者の停止・再開と、デバイスの一覧確認・失効もできる。

### 手元からデプロイする場合

`wrangler login` した上で、`wrangler.jsonc` の `database_id` を D1 の ID に置き換えて実行する（置き換えはコミットしない）。

```sh
pnpm exec wrangler d1 migrations apply ashidori --remote
pnpm exec wrangler deploy --var CF_ACCESS_TEAM_DOMAIN:<team>.cloudflareaccess.com --var CF_ACCESS_AUD:<AUD Tag> --var ADMIN_EMAILS:<管理者のメールアドレス>
```

## 実装メモ

- **時刻**: API のタイムスタンプ (`createdAt`, `lastSeenAt`, `expiresAt`) はすべて Unix epoch ミリ秒の数値です。
- **カーソル**: `cursor` は D1 の `INTEGER PRIMARY KEY AUTOINCREMENT` です。単調増加で、再利用されません。`after` の既定値は `0` です。`limit` の既定値は 100、上限は 1000 です。
  - カーソルは全利用者で共通なので、ある利用者の一覧では値が飛ぶ。クライアントは値が増加することだけに依存する。
- **利用者による絞り込み**: `/v1/*` は、呼び出したデバイスと `user_id` が同じデバイスだけを対象にする。比較は `IS` で行い、旧形式（NULL）は旧形式どうしで一致する。デバイスの認証時に利用者の状態も確かめ、停止中なら `401` を返す。
- **トークン**: 32 byte 乱数の base64url (43 文字) です。D1 には SHA-256 hex だけを保存し、照合は constant-time 比較で行います。
- **ペアリングコード**: Crockford base32 で 8 文字 (40 bit) です。D1 には SHA-256 hex だけを保存します。入力は大文字小文字を区別せず、`-` と空白を無視し、`I`/`L` を `1`、`O` を `0` として扱います。
  - 消費は `UPDATE ... WHERE used_at IS NULL AND expires_at > now RETURNING` の 1 文で行うので、同じコードで 2 台が登録されることはありません。
- **時刻の粒度**: 表示にしか使わない時刻は、利用者の活動時間を残さないよう 1 時間単位に切り捨てて保存する。対象はデバイスの `lastSeenAt`、バッチの `createdAt`、フォローと共有チェックインの更新時刻である。並び順はカーソルで決めるため、切り捨てても順序は変わらない。マイグレーション 0003 で既存の値も切り捨てる。
- **lastSeenAt**: 認証時に保存値が現在の時（1 時間単位）より古いときだけ、`waitUntil` で更新する。書き込みはデバイスごとに 1 時間に 1 回までである。
- **共有チェックインのカーソル**: 変更のたびに `MAX(cursor) + 1` を割り当てる。D1 は文を 1 つずつ実行するため、変更の順にカーソルが増える。行は削除せず（削除は内容を消した tombstone にする）、`MAX(cursor)` が減ることはない。利用者の停止・再開時は、その利用者の全エントリに新しいカーソルを割り当てる。
- **followsVersion**: `users.follows_version` を `follows` のトリガーで更新する。フィードの応答に含め、値が変わったらクライアントは `after=0` から取り直す。
- **Rate limit**: Workers Rate Limiting binding ではなく、D1 の固定ウィンドウカウンタ (`rate_limits` テーブル) を使います。
  - 理由: personal server のリクエスト量では D1 で十分です。結果が強整合で、`wrangler dev` とテストでも同じように動きます。
  - IP ごと (`CF-Connecting-IP`、IPv6 は /64 単位) に、次の上限をかけます。
    - `register`: 10 回 / 10 分。失敗した試行も数えます。
    - `pairing`: 20 回 / 10 分。`/v1/pairing` と `/admin/users/:id/pairing` で共通です。
    - `lookup`: 20 回 / 10 分。`/v1/users/lookup`（ハンドルの列挙対策）。
  - 上限を超えると `429` を返します。
  - カウンタのキーは IP を含むため、ウィンドウが終わったものはすぐ削除する。各チェック時に削除し、さらに cron trigger（10 分ごと、`wrangler.jsonc` の `triggers`）でも削除する。後者はリクエストが来ない場合のためである。
- **ログ**: IP アドレスやリクエスト本文はログに出さない。`console.error` はエラー自体だけを出す。Workers Logs の invocation log（リクエストごとのメタデータ）は `wrangler.jsonc` で無効にしている。
- **PUT**: `Content-Type: application/octet-stream` と `X-Content-SHA256` が必須です。
  - 本文は 8 MiB を超えた時点で読み込みを打ち切り、`413` を返します。
  - R2 への put が成功してから D1 にメタデータを insert します。そのため、一覧に出るバッチは必ず blob が存在します。
  - R2 には `sha256` を渡して、R2 側でも整合性を検証させています。
- **GET blob**: D1 にメタデータがあるものだけを返します。
- **admin**: GET・HEAD・OPTIONS 以外で、`Origin` ヘッダが別オリジンのリクエストと `Sec-Fetch-Site: cross-site` のリクエストは `403` で拒否する（CSRF 対策）。GET を対象外にするのは、Access のログイン後に `/admin/` へ戻るリダイレクトが cross-site になるためである。GET は状態を変えず、`/admin/*` は CORS ヘッダを返さないため、別オリジンのページは応答を読めない。
- **セキュリティヘッダ**: 全レスポンスに付けます。対象は `nosniff`、`Referrer-Policy: no-referrer`、`X-Frame-Options: DENY`、HSTS、CSP (`default-src 'none'`、admin ページは nonce 付き)、`Cache-Control: no-store` です。

### 既知の制限

- 同じデバイスが同じ `(stream, seq)` に**異なる内容を同時に** PUT した場合 (クライアントのバグでしか起きません)、R2 の blob が後勝ちで上書きされる可能性があります。D1 のメタデータは先勝ちです。
  - その場合、GET で得た blob の SHA-256 は一覧の `sha256` と一致しません。クライアントはダウンロード後にハッシュを検証してください (AES-GCM の認証でも検出されます)。
