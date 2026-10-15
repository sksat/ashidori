# ashidori sync server

ashidori の同期サーバです。
プロトコルは [`docs/sync-protocol.md`](../docs/sync-protocol.md) に従います。

- Cloudflare Workers (Hono) で動きます。
- D1 に利用者、デバイス、ペアリングコード、バッチのメタデータ、使用量のカウンタ、フォロー、共有チェックイン、rate limit のカウンタを保存します。
- R2 に暗号化済みのバッチ blob を保存します。サーバは記録の平文を扱いません。例外は、利用者がフォロワーに共有したチェックインだけです。
- 1 デプロイで複数の利用者を扱えます。データは利用者ごとに分かれ、同じ利用者のデバイスどうしだけが共有します。
- 運用者（管理者）に見える情報は [`docs/sync-protocol.md`](../docs/sync-protocol.md#what-the-server-operator-can-see) にまとめています。

## 管理者と利用者

管理者（運用者）と利用者を区別する。

- **管理者**は admin ページ（`/admin/`）を使う。条件は、Cloudflare Access のログインを経て、その JWT の `email` が `ADMIN_EMAILS`（カンマ区切り、大文字小文字を区別しない）に含まれることである。Worker が JWT を検証して照合するため、Access のポリシーが広くても（例: SSO でログインできる全員）一覧にない人は `403` になる。`ADMIN_EMAILS` が空なら admin API はすべて拒否する。
- **利用者**は web にログインしない。利用者は最初の端末で作る。管理者は admin ページで招待（1 回限り、期限付きの登録リンク）を作って渡し、本人がアプリでそれを開いてユーザ名を決めると、利用者と最初の端末ができる（`POST /v1/accounts`）。2 台目以降は、アプリから `POST /v1/pairing` でコードを発行して追加する。
- `REGISTRATION` が `open` のときは、招待なしでも同じ手順で登録できる（自己登録）。詳細は「[新規登録と上限](#新規登録と上限)」を参照。
- デバイストークンはアプリが生成し、登録の要求に含める。サーバはそのハッシュだけを保存し、トークンを生成も返却もしない。応答が失われても、アプリは同じトークンと同じ要求で再試行すれば同じデバイスを受け取れる（`200`）。
- デバイスが発行したペアリングコードは、そのデバイスが失効していない間だけ使える。デバイスを失効させると、そのデバイスが発行した未使用のコードも同じトランザクションで削除する。
- デバイスはペアリングコードの利用者に属する。デバイスから見えるのは、同じ利用者のデバイスとバッチだけである。他の利用者のバッチは一覧に出ず、取得しようとしても `404` になる。
- 利用者を停止すると、その利用者のデバイストークンと未使用のペアリングコードは使えなくなる。データは消さない。再開すれば元どおり使える。
- 利用者の導入前に登録されたデバイス（`user_id` が NULL）は、旧形式のデバイスとしてそのまま動く。旧形式のデバイスどうしはデータを共有し、利用者のデバイスとは分離される。管理者が旧形式のコードを発行することはできない。旧形式のデバイスは共有チェックインとフォローを使えない（`403`）。

## 新規登録と上限

### 変数

Worker の変数（`wrangler.jsonc` の `vars`）は次のとおり。CI では GitHub のリポジトリ変数を `--var` で渡す（「[3. GitHub に登録する](#3-github-に登録する)」）。

| 変数 | 既定値 | 内容 |
| --- | --- | --- |
| `CF_ACCESS_TEAM_DOMAIN` | 空 | Access の Team domain。空なら admin API を拒否する |
| `CF_ACCESS_AUD` | 空 | `/admin/*` を保護する Access アプリケーションの AUD Tag。空なら admin API を拒否する |
| `ADMIN_EMAILS` | 空 | 管理者のメールアドレス（カンマ区切り）。空なら admin API を拒否する |
| `REGISTRATION` | `admin` | 招待のない登録を許すか。`admin` は招待による登録のみ、`open` は招待がなくても誰でもアプリから登録できる。値が厳密に `open` のときだけ公開し、それ以外（空、`Open`、前後に空白のある値を含む）は `admin` として扱う |
| `USER_QUOTA_BYTES` | 空（2 GiB） | 利用者ごとの blob の合計バイト数の上限 |
| `USER_MAX_BATCHES` | 空（200000） | 利用者ごとのバッチ数の上限 |
| `USER_MAX_DEVICES` | 空（50） | 利用者ごとに登録できるデバイス数の上限。失効したデバイスも数える |
| `USER_MAX_SHARED_CHECKINS` | 空（5000） | 利用者ごとの共有チェックイン数の上限。共有を解除したもの（tombstone）も数える |
| `MAX_USERS` | 空（100） | サーバ全体の利用者数の上限。招待のない登録（自己登録）はこの数で止まる。招待による登録は止めない |
| `MAX_TOTAL_BYTES` | 空（50 GiB） | サーバ全体の blob の合計バイト数の上限 |
| `ANDROID_CERT_SHA256` | 空 | アプリの署名証明書の SHA-256 フィンガープリント（カンマ区切り）。`/.well-known/assetlinks.json` に載せ、登録リンクでアプリが開くようにする。空なら `[]` を返す（「[登録リンクと App Links](#登録リンクと-app-links)」） |
| `CORS_ALLOWED_ORIGINS` | 空 | `/v1/*` で CORS を許可するオリジン（カンマ区切り）。空なら CORS ヘッダを返さない |
| `ALLOW_DEV_ADMIN` | `false` | ローカル開発でのみ `true` にする。本番では必ず `false` |

上限の変数（`USER_*`、`MAX_*`）は、空なら既定値を使い、それ以外は正の整数でなければならない。`0`、負の数、`10MiB`、前後に空白のある値などは不正である。不正な値があると、サーバは利用者（招待による登録を含む）・デバイス・バッチ・共有チェックインの新規作成を `503 server misconfigured` で拒否し、admin ページに不正な変数名を表示する。既存のデータの読み出し、保存済みのバッチの再送、既存の共有チェックインの解除は続けて動く。CI はデプロイの前に `scripts/check-config.ts` で `REGISTRATION`、上限の変数、`ANDROID_CERT_SHA256` を検証し、不正ならデプロイしない。

### 招待と自己登録

利用者は必ず最初の端末で作る（`POST /v1/accounts`）。管理者は利用者を作らず、招待を作る。
招待は 1 回限りの登録リンク `https://<ホスト名>/pair#i=<招待>` で、有効期限（1〜30 日、既定 7 日）がある。
本人がアプリでリンクを開き、「新しく登録」でユーザ名を決めると、招待の消費、利用者の作成、最初の端末の作成を一つの D1 トランザクションで行う。
`REGISTRATION` を `open` にすると、招待がなくても同じ手順で登録できる（自己登録）。サーバはエンドツーエンド暗号化されたバックアップと同期の保存先として働き、管理者の操作は要らない。

- 要求は `{token, username, invite?, deviceName?}` で、応答は `201 {deviceId, username}` である。同じトークンでの再試行には同じ `deviceId` を `200` で返す。最初の要求の応答前に届いた再試行も同じで、最初の要求で招待やユーザ名や `MAX_USERS` の最後の枠を使った場合も、最初の要求がアカウントを作った後に `admin` へ切り替えた場合も `200` になる（`401`・`403`・`409` を返す前に、そのトークンのアカウントを照会し直す）。
- ユーザ名は英小文字・数字・`_` の 3〜20 文字で、一意である（大文字小文字を区別しない）。使用済みなら `409 username taken` を返し、招待は消費しない。
- 招待が未知、使用済み（別のトークンによるものを含む）、期限切れ、取り消し済みなら、どれも `401 invalid or expired invite` を返す。期限は D1 の時計で、消費する文の中で判定する。
- 招待による登録は `REGISTRATION` によらず使え、`MAX_USERS` で止めない。admin ページでは「招待」と表示する。
- 招待のない登録では、同じトークンの別の要求がアカウントを作る前に締切や上限の判定に来た要求は `403` を受ける（締切は Worker が要求ごとに読み、作成のトランザクションの中では判定しないため）。アプリは、一度も送っていないトークンへの `403` だけを確定した拒否とし、以前に送った可能性のあるトークンは `403` の後も 2 分間、間隔を広げながら送り直す。その間に別の要求がアカウントを作れば、送り直しが `200` を受ける。招待による登録では締切を読まないので、この `403` は起きない。
- 招待のない登録で作った利用者は、admin ページで「自由な登録」と表示する。
- 2 台目以降は、最初の端末で発行したペアリングコードで追加する。暗号化の鍵は端末で作成し、サーバには渡らない。
- 招待のない登録は IP ごとに 1 時間 5 回まで、招待による登録は IP ごとに 10 分 10 回まで（誤った招待も数える）に制限する。招待のない登録では、D1 の手前で数える Rate Limiting binding `RL_ACCOUNT` が必須で、ない場合は自己登録を `503 server misconfigured` で拒否し、admin ページにその旨を表示する。
- 招待のない登録は、利用者数が `MAX_USERS` に達すると `403 registration full` を返す。
- 作成から 7 日たっても何も保存していない（バッチも共有チェックインもない）招待のない登録の利用者は、定期処理がデバイス・ペアリングコード・フォローとともに削除する。招待で作った利用者と、何かを保存した利用者は自動では削除しない。
- `admin` のとき、招待のない `POST /v1/accounts` は `403 registration closed` を返す。
- 未使用の招待は、期限切れか取り消しから 30 日後に定期処理が削除する。使用済みの招待は残す。

`open` が向くのは、自分や身内だけが使うサーバで管理者の手間を省きたい場合と、暗号化済みデータの保存先として公開する場合である。公開サーバで `open` にすると、誰でも上限まで暗号化済みデータを保存できる。サーバ全体の費用は `MAX_USERS` と `MAX_TOTAL_BYTES` で抑える。管理者はデータの中身を読めないが、admin ページで利用者ごとの使用量とサーバ全体の合計を確認し、利用者を停止できる。利用者を限定したい場合は `admin` のままにする。

### 上限の判定

方式によらず、利用者ごとの上限とサーバ全体の上限を適用する。

- 利用者の全デバイス（失効したものを含む）を合わせて数える。失効してもデータは消えないためである。旧形式のデバイス（利用者なし）はデータを共有しているため、全体で 1 人分の上限を共有する。
- 使用量は D1 のカウンタ（`users` の `storage_bytes`・`batch_count`・`checkin_count` と、`server_usage`）に保持する。カウンタはトリガーで、データの追加と同じ文の中で更新する。判定はこの行を読むだけで、バッチのサイズを毎回合計しない。
- 新しいバッチは、スロットが空いていること、利用者のバイト数とバッチ数、サーバ全体のバイト数をすべて 1 つの文で確かめて確定する。同時にアップロードしても上限を超えない。
- 利用者の上限を超える PUT には `507 quota exceeded`、サーバ全体の上限を超える PUT には `507 server storage full` を返し、保存しない。保存済みのバッチの再送は上限に関係なく `200` を返す。
- デバイス数が上限に達していると、ペアリングコードでの登録に `409 too many devices` を返す。コードは消費しない。
- 共有チェックインが上限に達していると、新しいエントリに `507 too many shared check-ins` を返す。既存のエントリの更新と共有の解除はできる。
- admin ページの利用者一覧に使用量（`storageBytes`、`batchCount`、`sharedCheckinCount`）と容量に対する割合を表示する。
- アプリは `quota exceeded` と `server storage full` を受けると同期の状態にエラーとして表示し、バックオフによる再試行をせず、次回の定期同期で再び試す。

## 共有チェックインとフォロー

利用者は、選んだチェックインをフォロワーに共有できる。共有チェックインはサーバに平文で保存され、管理者も読める。それ以外の記録は暗号文のままである。

- 利用者はユーザ名の完全一致で相手を検索し（`GET /v1/users/lookup?username=`、rate limit あり）、フォローを申請する。一覧や前方一致の検索はなく、rate limit で大量の探索を制限する。ただし、存在するユーザ名を試せば、その利用者の存在は分かる。ユーザ名のない旧形式の利用者は、管理者が設定するまで検索できない。
- 申請された側が承認するとフォローが成立し、フォロワーは相手の共有チェックインを見られる。拒否すると申請は削除され、再申請できる。フォローは一方向で、フォロー解除とフォロワーの削除はどちらの側からもできる。
- 停止中の利用者は検索・フォローの対象にならず、その共有チェックインは削除済みとして配信される。再開すると元に戻る。
- 共有チェックインには利用者の変更の版 `rev`（例: 変更時刻のミリ秒）を付ける。サーバは保存済みの `rev` 以下の PUT を `409 stale revision` で拒否するため、遅れて届いた古い PUT が編集や共有の解除を取り消すことはない。
- 共有の解除は、共有したデバイスから local id で行うほか、同じ利用者の任意のデバイスからサーバの id で行える（`DELETE /v1/checkins/shared/by-id/:id`）。紛失して失効させたデバイスの共有も解除できる。
- 管理者は admin ページで共有チェックインを確認し、削除できる（モデレーション）。管理者による削除は取り消せず、利用者は同じ local id で共有し直せない（`409 removed by an admin`）。
- 詳細は [`docs/sync-protocol.md`](../docs/sync-protocol.md#shared-check-ins-and-follows) を参照。

### admin API

いずれも上記の管理者認証が必要である。POST・PATCH は同一オリジンからのみ受け付け、本文がある場合は `Content-Type: application/json` とする。

| Method | Path | 内容 |
| --- | --- | --- |
| GET | `/admin/` | admin ページ |
| GET | `/admin/invites` | 招待の一覧（新しい順に 200 件。状態 `pending`・`used`・`expired`・`revoked`、作成者、作成日時、有効期限、使用した利用者） |
| POST | `/admin/invites` | `{ttlDays?}`（1〜30、既定 7）→ `201 {id, code, expiresAt, link}`。コードを返すのはこのときだけである |
| POST | `/admin/invites/:id/revoke` | 未使用の招待を取り消す。使用済みなら `409` |
| GET | `/admin/users` | 利用者の一覧（ユーザ名 `username`、旧形式でユーザ名のない利用者の旧名 `legacyName`、メールアドレス（旧形式のみ）、状態、作成日時、作成の経路 `createdBy`（`self`、`invite:<招待の id>`、旧形式では管理者のメールアドレス）、失効していないデバイス数、デバイスの最終アクセス、使用量 `storageBytes`・`batchCount`・`sharedCheckinCount`） |
| PATCH | `/admin/users/:id` | `{username}` でユーザ名を設定・変更する。使用済みなら `409 username taken` |
| POST | `/admin/users/:id/pairing` | その利用者の端末を追加するペアリングコード → `{code, expiresAt}`。停止中の利用者には `409` |
| POST | `/admin/users/:id/disable` | 利用者を停止する |
| POST | `/admin/users/:id/enable` | 利用者を再開する |
| GET | `/admin/devices` | 全デバイスの一覧（`userId`・`username` 付き。旧形式は `null`） |
| POST | `/admin/devices/:id/revoke` | 任意のデバイスを失効させ、そのデバイスが発行した未使用のペアリングコードを削除する |
| GET | `/admin/checkins` | 共有チェックインの一覧（変更の新しい順に 200 件。`user`・`userStatus` 付き。削除済みは含まない） |
| POST | `/admin/checkins/:id/delete` | 共有チェックインを削除する（内容を消し、削除済みの記録だけを残す）。利用者は同じ local id で共有し直せない |

## 簡易地図の配信

アプリの簡易地図（端末内の地図）のデータを、GitHub Releases の代わりにこのサーバから配信できる。配信は任意である。同期を設定した端末は、`GET /v1/info` の `basemap` に自分の固定する版があればこのサーバから、なければ GitHub Releases から取得し、一方が失敗すると他方を試す。どちらから取得しても、アプリは自身に固定したサイズと sha256 で照合する。このため、サーバの運用者を信頼する必要はない。

- `GET /basemap/:version/:file` が R2 の `basemap/<version>/<file>` を返す。認証は不要で、Cookie も設定しない。データは全利用者で同じで、国全体を含むため、要求から利用者の位置は分からない。
- 返すのは `ashidori-basemap-<version>.zip`（アーカイブ）と `ashidori-basemap-<version>.json`（記述子）だけである。それ以外の名前と、存在しないオブジェクトには `404` を返す。`b/...` のバッチなど他のキーは読めない。
- 1 つの `Range` に `206`（範囲外は `416`）で応じ、`ETag`・`If-None-Match`・`If-Range`・`Content-Length` を扱う。版ごとの内容は変わらないため、`Cache-Control` は `public, max-age=604800, immutable` とする。
- `GET /v1/info` は、R2 にアーカイブがある版を `basemap: ["<version>", ...]` として返す。R2 の一覧は isolate ごとに 10 分間キャッシュする。古いサーバにはこのフィールドがなく、アプリは GitHub Releases から取得する。マイグレーションは不要である。
- 定期処理の R2 の掃除は `b/` だけを走査するため、`basemap/` は削除しない。

運用者は、リポジトリで `tools/basemap/package.sh` が作ったアーカイブと記述子を R2 に置く。`tools/basemap/release.sh` は、次のコマンドを版とパスを埋めて表示する。`server/` で実行し、`--remote` を付けて本番の R2 に書き込む（付けないとローカルの R2 に書き込まれる）。

```sh
v=20261013
pnpm exec wrangler r2 object put "ashidori-blobs/basemap/$v/ashidori-basemap-$v.zip" \
  --file "../tools/basemap/dist/ashidori-basemap-$v.zip" --content-type application/zip --remote
pnpm exec wrangler r2 object put "ashidori-blobs/basemap/$v/ashidori-basemap-$v.json" \
  --file "../tools/basemap/dist/ashidori-basemap-$v.json" --content-type application/json --remote
curl -sI "https://<サーバ>/basemap/$v/ashidori-basemap-$v.zip"   # 200 と Content-Length を確かめる
curl -s "https://<サーバ>/v1/info"                                 # 10 分以内に basemap に版が載る
```

置く版は、配布するアプリが固定する版（`app/src/main/assets/basemap-pin.json` の `version`）とする。それ以外の版は使われない。古い版は `pnpm exec wrangler r2 object delete "ashidori-blobs/basemap/<版>/<ファイル名>" --remote` で削除する。

## 開発

Node 24 と pnpm を使います。

```sh
pnpm install
pnpm --dir web build    # Web の閲覧画面 (web/) を web/dist にビルド。サーバのテストより先に行う
pnpm --dir web test     # 閲覧画面のテスト (復号、Parquet、タイムライン)
pnpm test               # vitest + @cloudflare/vitest-plugin (Workers runtime 上で実行)
pnpm exec tsc --noEmit  # 型チェック
```

閲覧画面は Workers Static Assets として `/` に配信します（`src/viewer.ts`、`wrangler.jsonc` の `assets`）。
画面の開発は `pnpm dev` と並べて `pnpm --dir web dev` を使います。開発用のビルドにだけ、端末トークンとバッチ鍵を貼り付けて登録する欄があります。
テストのデータ（`web/test/fixtures`）はアプリのコードで作ります（`./gradlew :core:webFixtures`）。

ローカルで動かす場合は `.dev.vars.example` を `.dev.vars` にコピーし、`ADMIN_DEV_TOKEN` を長いランダム文字列にします。

```sh
cp .dev.vars.example .dev.vars
pnpm exec wrangler d1 migrations apply ashidori --local
pnpm dev
# 招待を作成する (ローカルのみ。ALLOW_DEV_ADMIN=true のときだけ有効)。応答の link をアプリで開く
curl -X POST -H "Authorization: Bearer $ADMIN_DEV_TOKEN" -H "Content-Type: application/json" \
  -d '{}' http://localhost:8787/admin/invites
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
| Secret | `CF_ACCESS_TEAM_DOMAIN` | 環境 `production` | Access の Team domain |
| Secret | `CF_ACCESS_AUD` | 環境 `production` | Access アプリケーションの AUD Tag |
| Secret | `ADMIN_EMAILS` | 環境 `production` | 管理者のメールアドレス（カンマ区切り） |
| Variable | `REGISTRATION` | リポジトリ | `admin` または `open`（任意。未設定なら `admin`） |
| Variable | `USER_QUOTA_BYTES`、`USER_MAX_BATCHES`、`USER_MAX_DEVICES`、`USER_MAX_SHARED_CHECKINS`、`MAX_USERS`、`MAX_TOTAL_BYTES` | リポジトリ | 上限（任意。未設定なら既定値。「[変数](#変数)」を参照） |
| Variable | `ANDROID_CERT_SHA256` | リポジトリ | アプリの署名証明書の SHA-256 フィンガープリント（任意。「[登録リンクと App Links](#登録リンクと-app-links)」） |
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
gh secret set CF_ACCESS_TEAM_DOMAIN --env production --body '<team>.cloudflareaccess.com'
gh secret set CF_ACCESS_AUD --env production --body '<AUD Tag>'
gh secret set ADMIN_EMAILS --env production --body '<管理者のメールアドレス>'
gh variable set DEPLOY_SERVER --body true
```

Access の値と管理者のメールアドレスは、公開リポジトリでも実行ログに出ないよう Secret にする（Variable はログで伏せられない）。ワークフローは変数を `env:` で渡し、シェルでは `"$REGISTRATION"` のように参照する。変数の値をスクリプトに直接埋め込まないため、値にシェルのコードを含めても実行されない。デプロイの前に `node scripts/check-config.ts` で値を検証し、不正ならデプロイしない。

GitHub Actions の IP アドレスは Azure と共有の広い範囲（7,000 件以上、毎週更新）のため、トークンの IP 制限には使わない。

### 4. デプロイする

Actions の `server` ワークフローを Run workflow で実行する（`main` への push でも実行される）。本番で必要な Worker の secret はない。`ADMIN_DEV_TOKEN` は本番に設定せず、`ALLOW_DEV_ADMIN` は `"false"` のままにする。

将来の web viewer を別オリジンで動かす場合は、`CORS_ALLOWED_ORIGINS` にそのオリジンをカンマ区切りで書く（例: `"https://viewer.example.com"`）。空なら CORS ヘッダは返さない。CORS は `/v1/*` のみに適用し、`/admin/*` には適用しない。

### 5. 招待して、最初の端末を登録してもらう

1. ブラウザで `https://<ホスト名>/admin/` を開く（Access のログインを経由する）。
2. 有効期限を選んで「招待を作成」を押すと、登録リンク（`https://<ホスト名>/pair#i=<招待>`）とその QR コードが表示される。1 回のみ使用できる。リンクを本人に渡すか、QR コードを読み取らせる。この時点では利用者は作られない。
3. 本人はアプリの「QR コードを読み取る」で QR コードを読み取るか、アプリのある端末でリンクを開く。アプリの同期設定が「新しく登録」になり、登録先のサーバを表示するので、確かめてからユーザ名を入力して「登録」を押す。アプリはデバイストークンを生成して保存し、`POST /v1/accounts` で利用者と端末を作る。続けて暗号化の鍵を作り、復旧コードを表示する。

2 台目以降は、既存の端末の「別の端末を追加」でコード、登録リンク、その QR コードを発行し（`POST /v1/pairing`）、新しい端末で QR コードを読み取るか、リンクを開く（アプリは「この端末を追加」を表示する）。登録の後、新しい端末に復旧コードを入力する。復旧コードはリンクにも QR コードにも含まれない。admin ページでは招待の一覧と取り消し、利用者の停止・再開、ユーザ名のない旧形式の利用者へのユーザ名の設定、端末の一覧確認・失効もできる。

`REGISTRATION` が `open` なら 1.〜2. は不要で、本人はアプリでサーバの URL を入力して「新しく登録」を選ぶ。

### 登録リンクと App Links

登録リンク `https://<ホスト名>/pair#c=<コード>`（端末の追加）と `https://<ホスト名>/pair#i=<招待>`（新しく登録）は、コードをフラグメントに置くため、ブラウザで開いてもコードはサーバに送られない（形式は [`docs/sync-protocol.md` の Registration links](../docs/sync-protocol.md#registration-links)）。

- アプリが入っていない、または App Links が検証されていない場合は、`GET /pair` のページが開き、ペアリングコードまたは「招待」、「アプリで開く」（`ashidori://pair?...`）、リンクの QR コード、アプリの入手先を表示する。PC で開いた場合は、この QR コードをアプリで読み取る。
- アプリは、ビルド時に Gradle のプロパティ `ashidori.defaultServer` で指定したホストについてだけ App Links を宣言する。公開のビルドは指定せず、App Links を宣言しない。その場合は `/pair` ページの「アプリで開く」か QR コードを使う。
- QR コードはブラウザ内で生成する。生成器（qrcode-generator 2.0.4、MIT License）は `src/vendor/qrcode-generator.ts` に原文のまま置き、`/pair` と admin ページに nonce 付きのインラインスクリプトとして埋め込む。CSP は外部のスクリプトを許可しない。
- App Links の検証には、`/.well-known/assetlinks.json` にアプリ（`net.sksat.ashidori`）の署名証明書の SHA-256 フィンガープリントが必要である。`ANDROID_CERT_SHA256` にカンマ区切りで設定する。空なら `[]` を返し、リンクはブラウザで `/pair` を開く。

フィンガープリントは、リリースの APK に署名した鍵から求める。`keytool` と `apksigner` のどちらの形式でもよい。

```sh
# 鍵ストアから（現在のリリースはデバッグ鍵で署名している。場所は環境により
# ~/.android/debug.keystore または ~/.config/.android/debug.keystore）
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
  -storepass android -keypass android | grep SHA256
# 署名済みの APK から
$ANDROID_HOME/build-tools/<版>/apksigner verify --print-certs app-release.apk | grep SHA-256

gh variable set ANDROID_CERT_SHA256 --body 'AB:CD:...'
```

署名鍵を替えるときは、移行の間だけ新旧の値をカンマ区切りで並べる。設定後は `https://<ホスト名>/.well-known/assetlinks.json` を開いて確かめ、端末では `adb shell pm verify-app-links --re-verify net.sksat.ashidori` と `adb shell pm get-app-links net.sksat.ashidori` で検証の結果を確かめられる。

### 手元からデプロイする場合

`wrangler login` した上で、`wrangler.jsonc` の `database_id` を D1 の ID に置き換えて実行する（置き換えはコミットしない）。

```sh
pnpm exec wrangler d1 migrations apply ashidori --remote
pnpm exec wrangler deploy --var CF_ACCESS_TEAM_DOMAIN:<team>.cloudflareaccess.com --var CF_ACCESS_AUD:<AUD Tag> --var ADMIN_EMAILS:<管理者のメールアドレス>
```

`REGISTRATION`、上限の変数、`ANDROID_CERT_SHA256` を変える場合は、同じく `--var REGISTRATION:open` のように渡す。事前に `REGISTRATION=open MAX_USERS=20 node scripts/check-config.ts` のように検証できる。

Rate Limiting binding の `namespace_id`（`wrangler.jsonc` の `ratelimits`）はアカウント内で一意でなければならない。同じアカウントの別の Worker が同じ値を使っている場合は変更する。

### マイグレーションと旧 Worker の同居

ワークフローはマイグレーションを適用してからデプロイする。その間と、デプロイの失敗やロールバックの後は、旧 Worker が新しいスキーマで動く。このため、マイグレーションは旧 Worker の書き込みを受け付けるように作る。

- 新しい NOT NULL 列には既定値を付ける。旧 Worker が書かない値は、トリガーで補う。
- 0005 では、旧 Worker が書く rate limit のカウンタに `expires_at` をトリガーで設定する。値はウィンドウの開始から 1 時間後（全規則の最長のウィンドウ）で、ウィンドウが終わる前に定期処理が消すことはない。
- 0005 では、旧 Worker が発行元なしで保存したペアリングコードに、`created_by` のデバイスをトリガーで `issuer_device_id` として設定する。サーバも消費時に `created_by` から発行元を確かめる（「[実装メモ](#実装メモ)」）。
- 0006 は `invites` テーブルを足すだけで、旧 Worker は使わない。ユーザ名は既存の `users.handle` 列（一意、NULL を許す）に入れるので、列の改名はしない。同居の間に旧 Worker が作った利用者はユーザ名を持たず、admin ページで設定する。

## 実装メモ

- **時刻**: API のタイムスタンプ（`createdAt`、`lastSeenAt`、`expiresAt`）はすべて Unix epoch ミリ秒の数値である。
- **カーソル**: `cursor` は D1 の `INTEGER PRIMARY KEY AUTOINCREMENT` である。単調増加で、再利用しない。`after` の既定値は `0`、`limit` の既定値は 100、上限は 1000 である。
  - カーソルは全利用者で共通なので、ある利用者の一覧では値が飛ぶ。クライアントは値が増加することだけに依存する。
- **利用者による絞り込み**: `/v1/*` は、呼び出したデバイスと `user_id` が同じデバイスだけを対象にする。比較は `IS` で行い、旧形式（NULL）は旧形式どうしで一致する。デバイスの認証時に利用者の状態も確かめ、停止中なら `401` を返す。
- **トークン**: 32 byte 乱数の base64url（43 文字）で、アプリが生成する。D1 には SHA-256 hex だけを保存し、照合は constant-time 比較で行う。
- **ペアリングコード**: Crockford base32 で 8 文字（40 bit）。D1 には SHA-256 hex と、発行したデバイスの id（`issuer_device_id`、管理者の発行なら NULL）を保存する。入力は大文字小文字を区別せず、`-` と空白を無視し、`I`/`L` を `1`、`O` を `0` として扱う。
  - コードの消費とデバイスの作成は 1 つの D1 batch（トランザクション）で行う。消費は `UPDATE ... WHERE used_at IS NULL AND expires_at > now ... RETURNING` で行い、デバイスの INSERT は今回の要求がそのコードを消費した場合だけ行う。どちらかが失敗すると両方とも取り消す。同じコードで 2 台が登録されることはない。
  - 消費時に、利用者が有効であること、発行したデバイスが失効していないこと、利用者のデバイス数が上限未満であることを確かめる。
  - `issuer_device_id` が NULL のコードを、それだけで管理者の発行とはみなさない。`created_by` が `admin` または `admin:<メールアドレス>` のものだけを管理者の発行とし、それ以外は `created_by` のデバイスを発行元として失効を確かめる。マイグレーション 0005 より前の Worker が保存したコードがこれにあたる。
  - 期限切れから 1 日たったコードは定期処理で削除する。それまでは、同じトークンとコードでの登録の再試行に同じデバイスを返す。
  - 同じトークンとコードの 2 つの要求が同時に届くと、両方が「未登録」を確認した後で一方だけがコードを消費する。消費できなかった側は、`401` や `409` を返す前にトークンの登録を照合し直し、同じコードで登録済みなら同じデバイスを `200` で返す。自己登録の利用者作成も同じである。
- **時刻の粒度**: 表示にしか使わない時刻は、利用者の活動時間を残さないよう 1 時間単位に切り捨てて D1 に保存する。対象はデバイスの `lastSeenAt`、バッチの `createdAt`、フォローと共有チェックインの更新時刻である。並び順はカーソルで決めるため、切り捨てても順序は変わらない。マイグレーション 0003 で既存の値も切り捨てた。R2 はオブジェクトごとにアップロードの正確な時刻を記録するため、切り捨ては D1 のメタデータにしか効かない。
- **lastSeenAt**: 認証時に保存値が現在の時（1 時間単位）より古いときだけ、`waitUntil` で更新する。書き込みはデバイスごとに 1 時間に 1 回までである。
- **共有チェックインのカーソル**: 変更のたびに `MAX(cursor) + 1` を割り当てる。D1 は文を 1 つずつ実行するため、変更の順にカーソルが増える。行は削除せず（削除は内容を消した tombstone にする）、`MAX(cursor)` が減ることはない。利用者の停止・再開時は、その利用者の全エントリに新しいカーソルを割り当てる。各エントリは最新の状態だけを持つため、差分取得は最新の状態に収束するが、途中の状態は見えない。
- **共有チェックインの版**: 行は最後に反映した PUT または DELETE の `rev` を持つ（tombstone を含む）。PUT と DELETE は、`rev` が保存値より大きいときだけ反映する。判定と更新は 1 つの文で行う。DELETE の `rev` は `?rev=` の値で、なければサーバの現在時刻のミリ秒を使う。
  - 反映しなかった要求は `409 stale revision` を返し、何も変えない。ただし完全な再試行は `200` を返す。PUT では同じ `rev` と内容、DELETE では現在の tombstone と同じ `rev` のものである。
  - 未知の local id への DELETE は、その `rev` の tombstone を作る。後から届いた `rev` の小さい最初の PUT はこれで拒否される。tombstone は共有チェックインの上限に数え、上限では `507` を返す。
  - 管理者による削除は `rev` によらず tombstone にして `removed_by_admin` を立てる。以後その行への PUT を拒否し、DELETE は何も変えずに `200` を返す。マイグレーション 0005 より前の tombstone には、削除した時の終わり（`deleted_at` + 1 時間 - 1 ms）を `rev` として設定した。
- **followsVersion**: `users.follows_version` を `follows` のトリガーで更新する。フィードの応答に含め、値が変わったらクライアントは `after=0` から取り直す。
- **Rate limit**: 2 段で判定する。
  - 前段は Workers Rate Limiting binding（`wrangler.jsonc` の `ratelimits`、`RL_REGISTER`・`RL_ACCOUNT`・`RL_PAIRING`・`RL_LOOKUP`）である。Cloudflare の拠点ごとに 60 秒の窓で数え、結果整合で、D1 にアクセスしない。大量の要求を D1 の手前で安く断るためのもので、D1 の上限より緩い。
    - 登録、招待による登録、自己登録は、クライアントの IP で数える。
    - ペアリングとユーザ名の検索は、デバイスの認証（D1 を読む）の前にクライアントの IP で数え、認証の後に利用者でも数える。
    - binding がない場合: `REGISTRATION=open` の自己登録は `503` で拒否する（fail closed）。それ以外は通し、isolate ごとに 1 回だけ警告をログに出す。
    - binding が実行時に例外を投げた場合: 一時的な障害として通し、警告をログに出す。判定は後段の D1 のカウンタに任せる。
  - 後段は D1 の固定ウィンドウカウンタ（`rate_limits` テーブル）である。強整合で、文書に書いた上限はこちらで決まる。キーは IP（`CF-Connecting-IP`）である。IPv6 は 128 bit として解析して上位 64 bit を使い、省略表記、先頭の 0、IPv4 埋め込み表記を扱う。IPv4-mapped アドレスは IPv4 として数える。
    - `register`: 10 回 / 10 分。本文が正しい形式の試行を数え、誤ったコードも数える。
    - `pairing`: 20 回 / 10 分。`/v1/pairing` と `/admin/users/:id/pairing` で共通。
    - `invite`: 10 回 / 10 分。招待による `/v1/accounts`。誤った招待も数える。
    - `lookup`: 20 回 / 10 分。`/v1/users/lookup`（ユーザ名の大量探索の制限）。
    - `account`: 5 回 / 1 時間。招待のない `/v1/accounts`（自己登録）。成功するたびに利用者が増えるため厳しくする。
  - カウンタは 1 文の UPSERT で数え、上限に達した後の要求では行を変更しない。拒否した要求で D1 に書き込むことはない。上限を超えると `429` を返す。
  - カウンタのキーは IP を含むため、ウィンドウが終わったものを cron trigger（10 分ごと）で削除する。`expires_at` の索引を使い、1 回あたり最大 10000 件に制限する。
- **定期処理**（cron trigger、10 分ごと）: rate limit のカウンタ、古いペアリングコード、古い未使用の招待、何も保存していない自己登録の利用者（1 回 50 人まで）、参照されない R2 オブジェクト（1 回 500 キーまで走査）を削除する。各処理は件数を制限し、1 つが失敗しても他は続ける。
- **ログ**: IP アドレスやリクエスト本文はログに出さない。`console.error` はエラー自体だけを出す。Workers Logs の invocation log（リクエストごとのメタデータ）は `wrangler.jsonc` で無効にしている。
- **PUT**: `Content-Type: application/octet-stream` と `X-Content-SHA256` が必須である。
  - 本文は 8 MiB を超えた時点で読み込みを打ち切り、`413` を返す。32 byte（`ASH1`、nonce、GCM のタグ）未満には `400 blob too small` を返す。
  - R2 には作成専用の条件付き put がないため、blob は要求ごとに異なるキー `b/{deviceId}/{stream}/{seq 12 桁}/{sha256}.{乱数 16 桁}` に保存する。R2 には `sha256` を渡し、R2 側でも整合性を検証させる。
  - その後、1 つの INSERT 文でメタデータとそのキーを確定する。スロット `(device_id, stream, seq)` の UNIQUE 制約が勝者を決め、上限の判定も同じ文で行う。カウンタはトリガーが同じ文の中で更新する。カーソルはこの確定時に割り当てるため、一覧に出るバッチには必ず blob があり、後から現れるカーソルを読み飛ばすこともない。
  - 確定できなかった要求（スロットの競合、上限）は、自分のオブジェクトを `waitUntil` で削除する。キーが要求ごとに異なるため、勝者のオブジェクトを消すことはない。
  - 保存と確定の間で処理が止まった場合に残るオブジェクトは、定期処理が R2 を少しずつ走査し、1 日以上たっても参照されていなければ削除する。走査の位置は `server_usage.orphan_sweep_after` に保存する。
- **GET blob**: D1 のメタデータが参照するキーのオブジェクトだけを返す。マイグレーション 0005 より前に保存したバッチはメタデータにキーがなく（`r2_key` が NULL）、旧形式のキー `b/{deviceId}/{stream}/{seq 12 桁}` から返す。既存のオブジェクトは移動していない。
- **admin**: GET・HEAD・OPTIONS 以外で、`Origin` ヘッダが別オリジンのリクエストと `Sec-Fetch-Site: cross-site` のリクエストは `403` で拒否する（CSRF 対策）。GET を対象外にするのは、Access のログイン後に `/admin/` へ戻るリダイレクトが cross-site になるためである。GET は状態を変えず、`/admin/*` は CORS ヘッダを返さないため、別オリジンのページは応答を読めない。
- **セキュリティヘッダ**: 全レスポンスに付ける。対象は `nosniff`、`Referrer-Policy: no-referrer`、`X-Frame-Options: DENY`、HSTS、CSP（`default-src 'none'`、admin ページは nonce 付き）、`Cache-Control: no-store`（簡易地図の配信を除く）である。

### 既知の制限

- マイグレーション 0005 より前に、旧形式のキーへの同時 PUT で D1 と R2 が食い違っていた場合、その食い違いは残る。クライアントは取得した blob の SHA-256 を一覧の値と照合する。
- 登録の rate limit は IP 単位である。NAT の背後の利用者は上限を共有し、多数の IP を使う攻撃者は回避できる。サーバ全体の費用は `MAX_USERS` と `MAX_TOTAL_BYTES` で抑える。
- マイグレーション 0005 の適用からデプロイが終わるまでの間、旧 Worker は要求のたびに開始から 10 分を過ぎたカウンタを削除する。この間は、新しい Worker の自己登録のカウンタ（1 時間の窓）が早く消え、自己登録の制限が緩む。前段の `RL_ACCOUNT` は影響を受けない。
- バッチのカーソルは全利用者で共通のため、利用者は自分のカーソルの間隔から、他の利用者のアップロード量をおおよそ推測できる。
- 管理者による共有チェックインの削除は取り消せない。誤って削除した場合、利用者は別の local id で共有し直す。
