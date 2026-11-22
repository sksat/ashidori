# 同期プロトコルの形式検証

`docs/sync-protocol.md` の同期プロトコルのうち、壊れると利用者のデータや鍵を失う部分を形式モデルで検査する。
形式検証そのものは目的ではない。
各モデルは、実装で起きうる具体的な障害（下の表の「防ぐ障害」）を一つずつ防ぐためにある。

- 悪意あるサーバ管理者が位置ログを読めないこと（Tamarin による暗号モデル）
- 並行処理、障害、再起動のもとで、登録、鍵、送信の状態が食い違わないこと（TLA+ と TLC による状態モデル）
- 生データの保存（[設計案](../docs/design/detail-log-format.md)）で、確定と削除の途中の障害がデータを失わせず、重複させないこと（TLA+）

暗号モデルはサーバを攻撃者とみなす。
状態モデルはサーバが仕様どおりに動くと仮定し（status 取得のモデルだけは偽の一覧を出すサーバを含む）、通信路とクライアントの障害を網羅する。
二つは別の前提に立つため、片方の結果をもう片方の前提のもとで読まない。

## 実行方法

```sh
formal/run.sh            # すべて
formal/run.sh fast       # 時間のかかる TLA+ の設定（run.sh の SLOW_TLA）を除く。CI のプッシュ時とデプロイ前はこれを使い、すべては毎日と手動実行で行う
formal/run.sh tamarin    # 暗号モデルのみ
formal/run.sh tla Outbox BatchCommit_fixed   # TLA+ の指定した設定のみ（未知の名前はエラー）
formal/run.sh fetch      # ツールの取得のみ
```

Java 17 以上、`curl`、`unzip`、`sha256sum` が必要である。
Tamarin と Maude は Linux x86_64 向けの配布バイナリを使う。
ツールは `$ASHIDORI_FORMAL_CACHE`（既定は `~/.cache/ashidori-formal`）に取得し、SHA-256 を照合してから使う。
ログは同じ場所の `logs/` に残る。
TLC は設定を一つずつ、ヒープ上限 `-Xmx3g`、ワーカー 4 で実行する（`TLC_JAVA_OPTS`、`TLC_WORKERS` で変えられる）。
後述の時間は、この既定値で 16 コアの機械で測った値である。

| ツール | 版 | 取得元 |
|---|---|---|
| TLA+ tools (TLC) | 1.7.4 | tlaplus/tlaplus の GitHub release `tla2tools.jar` |
| Tamarin prover | 1.12.0 | tamarin-prover/tamarin-prover の GitHub release（linux64-ubuntu） |
| Maude | 3.5.1 | maude-lang/Maude の GitHub release（linux-x86_64） |

`run.sh` は検査ごとに期待する結果を持ち、期待と違えば終了コード 1 で終わる。
一件も検査しなかった場合も失敗にする。
TLA+ の期待は次のいずれかである。

- `pass`：エラーなしで終わる。
- `inv:名前`、`act:名前`：その名前の不変条件、行動性質が破れる（変異、修正前の設計、実装の問題の再現）。
- `live:名前`：時相性質が破れる。TLC は破れた時相性質の名前を出さないため、設定の PROPERTY はその一つだけでなければならない（run.sh が確かめる）。
- `cover:動作,...`：`pass` の設定で、列挙した動作が一度以上起きる（TLC の `-coverage`）。分岐が到達不能なまま成立する空虚な検査を防ぐ。
- `reach:名前,...`：`Unreached_*`（「この場面は起きない」）を一つだけ検査する設定を作って走らせ、破れることを確かめる。場面に到達できることの証拠である。

CI では `.github/workflows/formal.yml` が、`formal/`、`docs/sync-protocol.md`、`server/src`、`server/migrations`、アプリの `sync/`、`Db.scala`、`ArchiveStore.scala`、`ui/SyncSettingsScreen.scala`、`core` の `sync/` と `SendGate.scala` の変更時に実行する。

## モデルの一覧

| モデル | 防ぐ障害 | 主な性質 |
|---|---|---|
| `crypto/batch.spthy`, `batch_privacy.spthy` | サーバ管理者による位置ログの閲覧、別の端末やスロットへの暗号化したファイルの付け替え、閲覧端末から漏れたバッチ鍵による偽の blob | `payload_secrecy`、`slot_binding`、識別不能性 |
| `crypto/browser_pairing.spthy` | ブラウザのペアリング（予定、[設計](../docs/design/browser-pairing-v1.md)）でサーバが鍵を差し替え、バッチ鍵を得る、偽の鍵をブラウザに渡す | `batch_key_secrecy`、`grant_agreement` |
| `Registration` | 応答を失った端末が再送で別の結果を受け、トークンも端末も失う。期限切れのコードの消費 | `RetriesAgree`、`RecoverUngated`、`CodeIffDevice` |
| `RegistrationClient` | 保存に失敗した鍵や登録で同期する。取消の失敗で再試行の手段を失う。解除の途中で落ちた後、別の登録が古い鍵を使う | `ReadReturnsPersisted`、`TokenRecoverable`、`NoForeignKey`、`CancelledNoEffect`、`Converges` |
| `Accounts` | 自己登録の再送が最後の枠や登録の締切で拒否され、作られたアカウントを失う | `RetriesAgree`、`RecoverUngated`、`UsersUnderMax` |
| `Invites` | 招待が利用者なしで使用済みになる。一つの招待で二人が登録する。ユーザ名が重複する。再送が別の結果を受ける | `ConsumedHasUser`、`OneUserPerInvite`、`UsernameUnique`、`RetriesAgree` |
| `Outbox` | 同じスロットに別の暗号文が送られて 409 で止まる。進捗だけ進んでデータが欠ける。アーカイブの別の世代の部分を混ぜる | `SameSlotSameCiphertext`、`ProgressBacked`、`ArchiveComplete` |
| `BatchCommit` | 掃除が確定済みの実体を消し、一覧にあるのに取得できないバッチが残る。使用量の計上の狂い | `CommittedHasObject`、`SweepSafe`、`CountersMatch`、`UnderCaps` |
| `StatusDownload` | 偽の一覧行、範囲外のカーソル、壊れた blob で、正しい status が永久に読まれない | `StatusLive`、`NoSilentSkip`、`AuthOnlyOnSuccess`、`CursorValid` |
| `StatusRotation` | 保持数を超える偽の行で正しい status が押し出され続ける（F6） | `StatusLive`、`EveryListedTried`、`ReadWithinPasses` |
| `SharedCheckin` | 古い取消の再送が再共有を消す。遅れた PUT が公開を復活させる | `LatestWins`、`StaleMeansSuperseded` |
| `FeedResync` | フォロー解除後も表示が残る。新しくフォローした相手の過去分が欠ける | `NoMixedVersions`、`Converges` |
| `DetailLog` | 生データのジャーナル、断片、日の世代で、入力を消した後に出力が残っていない。やり直しや遅れたデータで二重に公開する。系列ごとの書き込みや捨てたサンプルの損失が報告されない。読む側が消えたファイルや食い違う一覧を読む | `Durability`、`NoDup`、`LossReported`、`LossOnlyUnsaved`、`ReaderOK`、`JournalsResolved` |
| `DetailMigrate` | 生データの移行で、rowid の再利用や時刻の範囲で別の行を消す。途中まで取り込まれた旧い形式のテキストを二重に移す | `Durability`、`NoDup` |

## 暗号モデル（`crypto/`）

### 攻撃者と前提

攻撃者は Dolev-Yao モデルに従い、通信路、Worker、D1、R2 をすべて制御する。

- 端末 ID を決める（同じ ID を二台に与えることもできる）。
- 端末トークン、ペアリングコード、すべての暗号文を知る。
- どの端末にどのスロットとしてどの暗号文を渡すか（あるいは渡さないか）を決める。
- 暗号化する stream と seq も攻撃者が選ぶ。アプリは一つのスロットに一度しか暗号化しないが、これを仮定しない。

信頼するのは、正規のアプリ、乱数生成、復旧コードによる端末間での `K` の受け渡しである。
AES-256-GCM は理想的な AEAD とし、鍵、nonce、AAD が揃ったときだけ復号できるとする。
バッチ鍵は `K` から一方向関数で導出する（HKDF のモデル）。
`batch.spthy` の blob は v1.1 の `ASHM` であり、GCM の部分に、`K` から導く `Ka` による MAC（理想的な MAC）を続ける。
閲覧端末（ブラウザ）が受け取ったバッチ鍵を漏らす規則 `Leak_BatchKey` を置く。閲覧端末は `K` も `Ka` も持たない。

### 性質と結果

`batch.spthy`（`Reveal(K)` が起きない限り、という条件付き。バッチ鍵の漏洩 `LeakBK` は秘匿の補題だけで条件に加える）：

| 補題 | 内容 | 結果 |
|---|---|---|
| `executable` | 正常な暗号化と受理が起こりうる | 成立（到達の確認） |
| `key_secrecy`, `batch_key_secrecy` | 攻撃者は `K` を得られない。バッチ鍵は閲覧端末が漏らさない限り得られない | 成立 |
| `auth_key_secrecy` | バッチ鍵が漏れても、攻撃者は `Ka` を得られない | 成立 |
| `payload_secrecy` | 閲覧端末がバッチ鍵を漏らさない限り、暗号化した位置データを得られない | 成立 |
| `slot_binding` | `Accept(K, device, stream, seq, m)` の前に、同じ `K` と AAD による `Seal` がある。バッチ鍵が漏れても成り立つ | 成立 |
| `no_slot_move` | あるスロット用の暗号文は、別のスロットでは受理されない | 成立 |
| `*_if_key_revealed` | 上の三つから条件を外したもの | 期待どおり反例あり |
| `-D=NOAAD` の `slot_binding`, `no_slot_move` | AAD にも MAC にもスロットを入れない変異 | 期待どおり反例あり |
| `-D=ASH1` の `slot_binding`, `no_slot_move` | アプリが MAC のない `ASH1` も受理する変異（切り替えの記録がない状態） | 期待どおり反例あり（漏れたバッチ鍵で任意のスロットの blob を作る） |
| `no_forward_secrecy` | 後から `K` が漏れると、過去の暗号文も読める | そのような実行が存在する |
| `same_device_id_for_two_devices` | 悪意あるサーバは同じ端末 ID を二台に与えられる | そのような実行が存在する |

`batch_privacy.spthy` は低エントロピーの位置データを対象にした識別不能性（diff-equivalence）である。
攻撃者が毎回二つの候補 `m0`、`m1` を選び、左の系は `m0`、右の系は `m1` を暗号化する。
受信端末が復号に成功したかどうかも攻撃者に見えるものとする。
`K` が漏れない限り二つの系は区別できない（成立）。
`-D=REVEAL` で `K` を漏らすと区別できる（期待どおり反例あり）。

位置ログの秘匿は `K` の秘匿だけに依存し、トークンやペアリングコードの漏洩には依存しない。
端末の失効は `K` の失効ではなく、`K` が一度漏れると過去と将来のすべてのバッチが読める。
AAD の端末 ID はサーバが割り当てた値なので、`slot_binding` が保証するのは「その ID を割り当てられた、利用者のいずれかの端末が暗号化した」ことまでである。

`ASH1` から `ASHM` への切り替え（[設計](../docs/design/browser-pairing-v1.md#blob-の認証v11)の切り替えの記録 `C`、`below` と `digest`、必須の状態に入る条件と保存、必須の前に `ASHM` を受理した位置より上の `ASH1` の拒否）はモデルにない。
モデルは `ASHM` だけを受理する場合（成立）と、`ASH1` を無条件に受理する場合（`-D=ASH1`、反例あり）の両端だけを表す。
切り替えの規則は core の試験 `BlobAuthSuite` で確かめる（`C` より上、`ASHM` を受理した位置より上、digest の不一致の `ASH1` の拒否、再起動と書き込みの失敗の後の状態）。

`browser_pairing.spthy` は、予定のブラウザのペアリング（[`docs/design/browser-pairing-v1.md`](../docs/design/browser-pairing-v1.md)）のモデルである。
攻撃者はサーバと通信路で、ページは信頼する。
利用者は、ブラウザとアプリの 4 語が等しいときだけ、アプリで承認し、ブラウザで確認する。
4 語は理想化したハッシュであり、後から自分の値を選ぶ者は `grindA`、`grindB` で一致させられるとして、44 bit の切り詰めを表す（確率そのものではない）。`grindA` は `nA` の原像 `sA` を返す。
4 語と HPKE の `info` は、試行 ID、コード、範囲、期限を含む同じ transcript から作る。
受け取った後のブラウザは、持つものをすべて漏らしうる（`B_Leak`）。
取消、期限、再起動はモデルにない。
`-D=QR` は、アプリがブラウザの QR コード（試行 ID、コード、commit）を読み取る経路を加える（コードの入力も同じ実行に残る）。
カメラは、攻撃者が読めるが書き換えられない経路（線形の事実 `UserSees`）とし、QR コードの経路ではアプリの承認に 4 語の見比べを求めない。
`run.sh` の期待の欄は、`QR+NOQRCHECK` のように `+` でつないだ複数の `-D` を表す。

| 補題 | 内容 | 結果 |
|---|---|---|
| `executable_*` | 承認と確認のどちらが先でも、また 2 つのブラウザで、正常に受け取れる | 成立（到達の確認） |
| `master_key_secrecy`, `auth_key_secrecy` | ブラウザが持つものをすべて漏らしても、攻撃者は `K` も blob の認証鍵も得られない | 成立 |
| `batch_key_secrecy` | その鍵を受け取ったブラウザか、アプリがその鍵を封じた宛先のブラウザが漏らさない限り、バッチ鍵は攻撃者に渡らない | 成立 |
| `grant_agreement` | ブラウザが受け取った鍵は、アプリが同じ transcript に承認したもの（単射的一致） | 成立 |
| `-D=NOCOMMIT` の `batch_key_secrecy` | commit なし。サーバはアプリの nonce を見てから自分の値を選ぶ | 期待どおり反例あり |
| `-D=NOGRANTAUTH` の `grant_agreement` | ブラウザが付与の中の `sA` を確かめない | 期待どおり反例あり |
| `-D=APPONLY` の `grant_agreement` | ブラウザでの確認を省く（秘匿は成立のまま） | 期待どおり反例あり |
| `-D=QR` の各補題、`executable_qr` | QR コードの経路（アプリは承認で見比べない） | 成立 |
| `-D=QR -D=NOQRCHECK` の `batch_key_secrecy` | アプリが commit を QR コードと照合しない（`grant_agreement` は成立のまま） | 期待どおり反例あり |
| `-D=QR -D=APPONLY` の `grant_agreement` | QR コードの経路でブラウザの確認も省く（秘匿は成立のまま） | 期待どおり反例あり |
| `-D=QR -D=EARLYNB` の `grant_agreement`、`batch_key_secrecy` | commit を残したまま `nB` を早く公開する。commit-then-reveal を残す根拠 | 期待どおり反例あり |

## 状態モデル（`tla/`）

暗号文は不透明な値として扱い、同一性だけを追う。
各モデルには、現行のコードを表す設定、性質を一つ壊す変異の設定、修正前の設計を表す設定がある。
変異の設定は、その性質が空虚でない（壊せば検出される）ことを示す。

### 設定の一覧

時間は `formal/run.sh all` での実測である。
「遅い」は `SLOW_TLA` に入っており、`fast` では実行しない。
`fast` は約 4 分（101 件）、`all` は約 17 分（114 件）かかる。
活性の検査は状態数のわりに時間がかかるため、安全性と活性を別の設定に分け、活性は小さい境界で検査する設定がある。

| 設定 | 内容 | 期待 | 時間 |
|---|---|---|---|
| `Registration` | 現行のサーバ。コード 2 個、攻撃者のトークンで両方のコード（UNIQUE の巻き戻し）。安全性 | 成立、分岐の到達 | 12 秒 |
| `Registration_full` | 別経路で作った端末があり、使えるコードが残ったまま上限に達する（409）。安全性 | 成立、分岐の到達 | 13 秒 |
| `Registration_live` | 各要求が応答を得る（攻撃者の要求 1 件） | 成立 | 5 秒 |
| `Registration_norecheck` | 0 行消費の後に再照合しない（N3） | `RetriesAgree` が破れる | 1 秒 |
| `Registration_gatefirst` | レート制限などを結果の回復より前に置く | `RecoverUngated` が破れる | 1 秒 |
| `Registration_expiry` | 期限を DB 実行前に Worker が読んだ時刻で判定する（レビュー 6 の 7） | `CodeIffDevice` が破れる | 1 秒 |
| `Registration_nounique` | UNIQUE 違反でコードの消費が巻き戻らない | `CodeIffDevice` が破れる | 1 秒 |
| `RegistrationClient` | 現行のクライアント。取消または解除、書込みの失敗、クラッシュか応答の消失、ゲートの拒否を各 1 回、期限切れ、鍵の設定、同期の実行 | 成立、分岐と場面（印の書き直し、不明な印のままの待機）の到達 | 16 秒 |
| `RegistrationClient_2faults` | 同じく、クラッシュか応答の消失 2 回 | 成立 | 遅い（25 秒） |
| `RegistrationClient_live` | 同じく活性（試行の終了と最終的な収束） | 成立 | 遅い（105 秒） |
| `RegistrationClient_legacy` | 旧版が登録に結び付けずに保存した鍵を起動時に結び付ける | 成立 | 1 秒 |
| `RegistrationClient_runread` | **所見（修正済み）**：修正前の `SyncRun.current`（登録を二度読む） | `RunRegMatches` が破れる | 2 秒 |
| `RegistrationClient_unknownmark` | **所見（修正済み）**：修正前の、読み戻せない印の読み方と書込みの起点 | `TokenRecoverable` が破れる | 4 秒 |
| `RegistrationClient_r2` | 照合と保存のあいだに取消が入れる修正前の方式（R2） | `CancelledNoEffect` が破れる | 1 秒 |
| `RegistrationClient_f2`, `_f2report` | 間に合わなかった取消も保留要求を消す修正前の方式（F2） | `TokenRecoverable`、`FailureReported` が破れる | 1 秒 |
| `RegistrationClient_r3` | `SharedPreferences.commit()` のまま（R3、F1） | `ReadyPersisted` が破れる | 1 秒 |
| `RegistrationClient_applypersist` | トークンを保存し終える前に送る（N2） | `TokenRecoverable` が破れる | 1 秒 |
| `RegistrationClient_unpair` | 鍵を登録に結び付けず、解除の印もない（レビュー 6 の 3） | `NoForeignKey` が破れる | 3 秒 |
| `RegistrationClient_noresume` | 印の付いた解除を起動時に終わらせない | `Converges` が破れる | 4 秒 |
| `Accounts` | 現行。トークン 3 個で上限 2 の枠を争う。締切と拒否。安全性 | 成立、分岐の到達 | 11 秒 |
| `Accounts_other` | 別種の端末のトークン（409）と最後の枠 | 成立、分岐の到達 | 遅い（9 秒） |
| `Accounts_live` | 各要求が応答を得る（トークン 2 個） | 成立 | 3 秒 |
| `Accounts_norecheck` | 0 行挿入の後に再照合しない（N3） | `RetriesAgree` が破れる | 1 秒 |
| `Accounts_gatefirst` | 締切やレート制限を回復より前に置く | `RecoverUngated` が破れる | 1 秒 |
| `Accounts_closedrace` | **所見（修正済み）**：締切で拒む前に回復を照会し直さない修正前の判定 | `RetriesAgree` が破れる | 1 秒 |
| `Invites` | 現行。二つのトークンが一つの招待を、二つの招待が一つのユーザ名を争う。再送、取り消し、期限切れ、活性 | 成立、分岐の到達 | 2 秒 |
| `Invites_open` | 同じく、招待のない登録が同じユーザ名を争う。安全性 | 成立、分岐の到達 | 1 秒 |
| `Invites_nonatomic` | 招待の消費と、利用者と端末の作成を別のトランザクションにする | `ConsumedHasUser` が破れる | 1 秒 |
| `Outbox` | 項目 3 個、登録 2 回、障害 1 回。暗号化と DB 確定の間のクラッシュを含む | 成立、到達 | 33 秒 |
| `Outbox_2faults` | 同じく障害 2 回、安全性のみ | 成立 | 遅い（90 秒） |
| `Outbox_nonatomic` | 行を書いてから seq と進捗を別トランザクションで進める旧方式 | `SameSlotSameCiphertext` が破れる | 1 秒 |
| `Outbox_archive` | アーカイブを部分ごとに送る。送信中と送信後に新しい世代に置き換わる | 成立、到達 | 26 秒 |
| `Outbox_r1` | 大きさをパスから読む修正前の方式（R1） | `ArchiveComplete` が破れる | 1 秒 |
| `Outbox_late` | **既知の制限**：送信済みのファイルが新しい世代に置き換わる | `ArchiveLatest` が破れる | 1 秒 |
| `BatchCommit_fixed` | 現行。R2 put → D1 commit と孤立掃除 | 成立 | 10 秒 |
| `BatchCommit_current` | commit の期限がない修正前の設計 | `CommittedHasObject` が破れる | 1 秒 |
| `BatchCommit_dg` | 期限を猶予より長くする（D > G） | `CommittedHasObject` が破れる | 1 秒 |
| `BatchCommit_nocount`, `_nocaps` | 件数を数えない、上限を確かめない | `CountersMatch`、`UnderCaps` が破れる | 1 秒 |
| `StatusDownload` | 現行、正直なサーバ | 成立 | 遅い（31 秒） |
| `StatusDownload_hostile` | 別の blob や 404 を 3 回まで返すサーバ | 成立 | 遅い（81 秒） |
| `StatusDownload_fixed`, `_fixed1` | 偽の行 3 件（大きな seq、同じ seq、範囲外のカーソル）を相手 2 台に、または 1 台に。正しい status は全体で 1 件 | 成立 | 遅い（20 秒、30 秒） |
| `StatusDownload_fakes` | 偽の行 5 件、不変条件のみ | 成立 | 遅い（80 秒） |
| `StatusDownload_manyfakes` | 保持数を超える偽の行（ローテーションあり） | 成立 | 遅い（28 秒） |
| `StatusDownload_manyfakes_pre` | 同じ、ローテーションなし（F6） | `StatusLive` が破れる | 遅い（9 秒） |
| `StatusDownload_fakeseq`, `_fakecursor` | 候補一つ、再走査なしの修正前の方式（#7） | `StatusLive` が破れる | 4 秒 |
| `StatusDownload_r4`, `_r5` | カーソルの範囲を確かめない（R4）、使い切った候補を再試行しない（R5） | `StatusLive` が破れる | 2 秒 |
| `StatusDownload_blob` | 一覧と違う blob を読めたものとして扱う | `AuthOnlyOnSuccess` が破れる | 1 秒 |
| `StatusRotation_f6` | F6 の例（正しい seq 0 と偽の 1〜8）、`MaxCand = 4`、再走査を 3 実行ごとまで頻繁にする | 成立 | 2 秒 |
| `StatusRotation_f6real` | 同じ例をアプリの間隔で。正しい status を 3 回失敗させるサーバ | 成立（再走査 4 回以内） | 8 秒 |
| `StatusRotation_f6pre` | 同じ例、ローテーションなし | `StatusLive` が破れる | 2 秒 |
| `StatusRotation_pages4`, `_pages5` | 1 ページ 2 行、1 実行 2 ページ（再走査に 1 ページ）、偽の行 4 件と 5 件 | 成立 | 3 秒 |
| `StatusRotation_2dev` | 相手 2 台に偽の行 5 件ずつ、再試行の予算 1 を共有 | 成立 | 遅い（55 秒） |
| `StatusRotation_r5` | 使い切った候補を再試行しない | `StatusLive` が破れる | 1 秒 |
| `SharedCheckin` | 1 端末、rev 1..4 の PUT と DELETE、重複と順序の入れ替え、管理者の削除 | 成立 | 2 秒 |
| `SharedCheckin_old` | rev を見ずに tombstone にする旧 DELETE（N5） | `LatestWins` が破れる | 1 秒 |
| `SharedCheckin_skew` | **既知の制限**：時計のずれた 2 端末 | `StaleMeansLaterOp` が破れる | 1 秒 |
| `FeedResync` | フィードの差分取得と再同期 | 成立 | 約 5 秒 |
| `FeedResync_firstpage` | 版を最初のページでだけ確かめるクライアント | `NoMixedVersions` が破れる | 1 秒 |
| `DetailLog` | 提案。サンプル 3（日 1 が 2、日 2 が 1、系列 2 つ）、ジャーナル 2、日 1 の世代 2、読む側 1、障害 1 回 | 成立、全動作の到達、5 つの場面に到達 | 3 秒 |
| `DetailLog_2faults` | 同じく障害 2 回 | 成立 | 7 秒 |
| `DetailLog_big` | サンプル 4、ジャーナル 3、世代 3、障害 1 回 | 成立 | 遅い（206 秒） |
| `DetailLog_live` | サンプル 2。障害が止んだ後、書き手が死んだか閉じたジャーナルは回復、確定され、消える（`JournalsResolved`） | 成立 | 4 秒 |
| `DetailLog_closefirst` | 回復が損失を記録する前にジャーナルを閉じる | 成立（確定のときの走査が同じ範囲を記録する） | 2 秒 |
| `DetailLog_nocreatesync`, `_nofsync`, `_nodirsync`, `_earlydelete`, `_normal`, `_childfirst`, `_noreadback`, `_alllive` | 作成時のディレクトリの fsync、名前の変更の前の fsync、ディレクトリの fsync、確定の記録の確定を待つ削除、確定の記録の `synchronous=FULL`、manifest の一括の確定（2 日のうち 1 日だけ確定）、読み戻し、固定した入力だけの削除をそれぞれ外す | `Durability` が破れる | 1〜2 秒 |
| `DetailLog_noscan`, `_tailonly` | 確定のときの走査による損失の記録、隙間の記録をそれぞれ外す | `LossReported` が破れる | 1 秒 |
| `DetailLog_nounique`, `_nonatomic` | 確定済みの入力のやり直し（同じ入力に 2 つ目の確定の記録の行）、世代の公開と消費の別トランザクション | `NoDup` が破れる | 1 秒 |
| `DetailLog_gcread`, `_mixedread` | 読む側との排他なしに古い世代を消す、世代と子を別の読み取りで得る | `ReaderOK` が破れる | 1〜2 秒 |
| `DetailMigrate` | 凍結した表の行 3（時刻の順でない）を 2 回に分けた入力、後から来た行 1、途中まで取り込まれた旧いテキスト、障害 2 回 | 成立、全動作の到達、4 つの場面に到達 | 1 秒 |
| `DetailMigrate_rowid`, `_bytime` | 同じ表での rowid の再利用、凍結した表の一部を時刻の範囲で消す | `Durability` が破れる | 1 秒 |
| `DetailMigrate_halfimport`, `_reimport`, `_nounique` | 途中まで取り込まれたテキストをソースとして移す、今のコードの取り込み、確定済みの入力のやり直し | `NoDup` が破れる | 1 秒 |

### Registration（サーバ）

`POST /v1/devices/register` の一要求を、D1 の原子的な段階に分ける。

- `SRecoverHit`, `SRecoverMiss`（`recoverRegistration`）：トークンの端末がこのコードを消費しており失効していなければ、すべてのゲートの前に 200 を返す。
- `SGateRefuse`, `SGatePass`：エッジとD1 のレート制限、制限変数。新規作成だけを 429/503 で拒みうる環境とする（回数に上限）。
- `SStartKnown`, `SStartNew`（`registerWithCode` の `earlier()`）、`SBatchCreate`, `SBatchUnique`, `SBatchZero`（消費と挿入の `db.batch`）、`SRecheckKnown`, `SRecheckNew`、`SFullCheckFull`, `SFullCheckInvalid`。
- コードの期限は `SBatch*` の時点の D1 の時計で判定する（`CODE_USABLE` の `D1_NOW_MS`）。

保証すること：トークンごとに端末は一つ（`OneDevicePerToken`）。コードは作った端末にだけ、使える間にだけ消費される（`CodeIffDevice`）。同じトークンとコードの最終応答は一致する（`RetriesAgree`）。回復できる要求はゲートで拒まれない（`RecoverUngated`）。端末 ID はトークンの持ち主にだけ返る（`TokenBound`）。各要求は応答を得る（`Responsive`）。

境界：利用者 1 人、コード 2 個、上限 3 台、正規の要求 2 件と攻撃者の要求 2 件（活性は 1 件）、ゲートの拒否 1 回。
二つの設定で、上限未満の UNIQUE の巻き戻し（攻撃者が同じトークンで両方のコードを送る）と、使えるコードが残ったままの満員（別経路の端末）に到達する（`cover:`）。
競合はトークン一つとコード二つの組合せで決まるため、台数を増やしても新しい分岐はない。
ただし利用者の無効化は発行端末の失効と同じ効果（全コードが使えない）として一つにまとめた。
使用済みコードの削除（期限の 1 日後）と新しい端末の失効は扱わない（どちらも設計上、再試行を失敗させる）。

### RegistrationClient（クライアント）

`core` の `RegistrationMachine`、`PairingStore`、`DurableMap` と、アプリでの使い方（`Sync.scala` の `Registration`、`SyncConfig`、`SyncRun`）を表す。
サーバは抽象化し、トークンに端末があればその端末を返す（冪等であることは `Registration` が確かめる）。

- 保存：`DurableMap` を二つ持つ（`C`：登録と解除の印、`S`：トークン、鍵、保留要求）。書込みは `WriteBegin` と `DiskNew`/`DiskOld`/`DiskUnreadable` に分け、その間に何でも読める。読み出しはスナップショットを返し、読み戻せない失敗で変わったキーは不明として読む（印は「解除中」、ほかは無い）。不明なキーがあるときの次の書込みは、ファイルを読み直してその内容から始める（`WriteReadsBack`。モデルでは読み直しは成功するものとし、読み直せないときに不明なキーを推測で書く書込みをコードが拒むことは扱わない）。クラッシュでは書込み中のファイルは旧か新のどちらかになる。
- 試行：`cur` は id、-id、0 のどれか。答えの取り込みも取消も一回の compare-and-set で、勝つのは片方だけである。-id のときの取消は何も変えない（F2）。
- 解除：印 → 試行の無効化（`local` 上の reset）→ 同期の鍵の取得 → 登録、トークン、鍵、残り（保留要求、同期状態）の消去 → 印の消去。どの段階の間でもクラッシュでき、起動時に印から再開する。印がある間（不明な間を含む）は `savePairing` を拒む。印の消去が読み戻せずに失敗したら書き直す（コードは 3 回まで。諦めた場合は印が不明のまま残り、「解除中」として読まれる）。
- 鍵は設定した登録に結び付け、その登録でだけ使う。旧版の結び付いていない鍵は起動時に現在の登録に結び付ける。
- 同期の実行（`SyncRun.current`）は同期の鍵の下で、登録、端末 ID、トークン、鍵を一度に読む（`PairingStore.session`）。

保証すること：

- `ReadReturnsPersisted`：読み出しは、ディスクにある値だけを返す（書込み中や失敗した値は返さない）。
- `ReadyPersisted`：同期可能と読めるなら、登録、トークン、鍵がディスクにある。
- `NoForeignKey`：同期の実行は、自分の登録（サーバと端末 ID）のために設定した鍵だけを使う。
- `CancelledNoEffect`：取消（または解除）が成功した試行は登録を保存しない。
- `FailureReported`：利用者が取り消していない試行の失敗は画面に届く。
- `TokenRecoverable`：クライアントのトークンでサーバに端末ができたら、ディスク上でその端末とペアリング済みか、保留要求にトークンがあるか、利用者がそれを手放した（取消、解除）。
- `AttemptEnds`（活性、要求ごと）：試行はいずれ終わる。
- `Converges`（活性、最終状態）：同期可能（鍵がその登録に結び付いている）、確定した拒否、すべてのトークンを手放した、のいずれかに落ち着いて変わらない。一度ペアリングしてから解除して止まる実行は満たさない。

公平性として、利用者はいずれ開始し、再試行し、鍵を入力し、アプリはいずれ再起動するものとする（取消と解除は強制しない）。

境界：トークン 2 個、試行 ID 5 個、取消または解除 1 回、書込みの失敗 1 回、クラッシュか応答の消失 1 回（`_2faults` は 2 回）、ゲートの拒否 1 回。
活性は試行 ID 7 個、クラッシュ 1 回、同期の実行なしで検査する。
それぞれの障害が一回あれば到達する競合（取消と保存、解除と同期、書込み失敗と読出し）を網羅する。
同じ種類の障害を重ねたときに新しい状態が生じるかは検査していない。

### Accounts

`POST /v1/accounts` の一要求を、回復（`recoverAccount`）、締切（`REGISTRATION`）、ゲート、`earlier()`、ユーザと端末の `db.batch`、再照合に分ける。
保証すること：利用者数は `MAX_USERS` を超えない。アカウントができた後の同じトークンの最終応答はそのアカウントである（`RetriesAgree`、締切後の 200 を含む）。回復できる要求は締切でもゲートでも拒まれない（`RecoverUngated`）。
`MaxUsers = 2` で、上限未満の UNIQUE の巻き戻しに到達する。
締切で 403 を返す前に回復を照会し直す（`RecheckClosed`）。
保証しないこと：締切の判定は Worker が要求ごとに環境変数を読むので、別の複製がアカウントを作る前に、ある複製が 403 を受けることがある。
アプリは、このプロセスで作って一度も送っていないトークンへの 403 だけを確定した拒否とし、以前に送った可能性のあるトークンは 2 分間、間隔を広げながら送り直す。その間にアカウントができれば、送り直しが回復で 200 を受ける。

### Invites

招待による `POST /v1/accounts` の一要求を、回復、招待の消費と利用者と端末の作成の `db.batch`、再照合に分ける。
招待のない登録（`Inv = 0`）も同じ batch で表す。締切と `MAX_USERS` は `Accounts` が扱う。
保証すること：招待は利用者と端末と同時にだけ使用済みになる（`ConsumedHasUser`）。一つの招待からできる利用者は一人（`OneUserPerInvite`）。ユーザ名は一意（`UsernameUnique`）。取り消した招待は使われない（`RevokedUnused`）。同じトークンの応答は一致し、ある複製がアカウントを得たとき別の複製が 401 や 409 で確定的に拒まれることはない（`RetriesAgree`）。
境界：トークン 3 個（`_open` は招待のない 1 個を加えて 4 個）、トークン 1 の要求の複製 2 件、招待 1 の取り消しと期限切れ。
非原子的な変異（`Invites_nonatomic`）では、ユーザ名の衝突で招待だけが使用済みのまま残る。同じ変異は `RetriesAgree` も破る（複製の一方が 401 を受けた後、もう一方がアカウントを作る）。

### Outbox

項目 1..3 を最大 2 件ずつバッチにし、登録 2 回、障害 1 回（`Outbox_2faults` は 2 回、安全性のみ）までを網羅する。
バッチは、暗号化（`Seal`、メモリ上、トランザクションの外）と確定（`Commit`、行、seq、進捗を一つのトランザクションで）の二段階である。
その間のクラッシュで暗号文は失われ、同じ seq が後で別の暗号文に暗号化される（`reach:Unreached_Resealed`）。
このため `SameSlotSameCiphertext` は「スロットごとに、送られうる暗号文（outbox、送信した要求、サーバ）は同一」と述べる。

- `ProgressBacked`：送信済みとした進捗の範囲は、outbox かサーバの確定済みの行にある。
- `OnlySealedSent`、`NoConflict`：送るのはそのスロット用に暗号化したものだけで、409 を受けない。
- `ArchiveComplete`：送信済み（`up`）にした世代は、その世代の全部分が outbox かサーバにある。
- `Done`（活性）：障害と再登録が止まれば、項目と、送信済みにした世代がサーバに届く。
- `ArchiveLatest`（活性）：ファイルの変更が止まれば、最新の世代がサーバにある。**成り立たない**（`Outbox_late`）。`up` はファイル名ごとの印で世代に結び付かないため、送信済みのファイルが遅れて届いた行で新しい世代に置き換わっても送り直さない。`docs/sync-protocol.md` の「What a backup can restore」に書いた既知の制限であり、アプリは変えていない。

`Archive = TRUE` の設定は、編集可能な項目の代わりにアーカイブファイルを一つ送る。
ファイルは任意の時点で、より大きい世代に置き換わる（アトミックな rename）。
世代 g は g 個の部分からなる。
`Snapshot = TRUE`（現行）はファイルを一度開き、大きさ、世代、すべての部分をその記述子から読む。
`Outbox_archive` は登録 1 回、障害 3 回、世代 3 まで、`Outbox_r1` は修正前の読み方である。
解除と再登録の組合せは `Outbox` に任せる（世代 3 と組み合わせると状態数が 100 倍を超える）。

### BatchCommit

端末 3 台（利用者 2 人）、試行 2 件、時刻 0..4 で検査する。
試行は R2 put と D1 commit のあいだで任意に遅れ、任意の段階で止まる。
孤立掃除は、一覧と D1 照合の段階と削除の段階に分ける。
D1 は「D1 の現在時刻 − 開始時刻 ≤ `D`」のときだけ commit する。
設計は `D ≤ G` を要する（`BatchCommit_dg` はこれを破って反例を出す）。

- `CommittedHasObject`、`SweepSafe`：確定した行の実体は R2 にあり、掃除は確定済みの実体を消さない。
- `CountersMatch`、`UnderCaps`、`OneRowPerSlot`：使用量は行の合計に一致し、上限を超えず、スロットごとに行は一つ。

カウンタはモデル自身が加算するので、SQL のトリガが正しいことはここでは示さない。
それは `server/test/conformance.test.ts` が実際の migration と SQL で、独立した SUM と COUNT と照合する。

### StatusDownload と StatusRotation

`StatusDownload` は一覧のカーソル、偽の一覧行、相手 1〜2 台を、1 ページ 1 行、`MaxCand = 2` で扱う。
ローテーションを入れてから状態数が増えたため、`_fixed` と `_fixed1` は正しい status を全体で 1 件に絞った（2 件では活性の検査が 5 分を超える）。
`StatusRotation` は一覧が固定された（偽の行も載り続ける）場合の候補の保持、ローテーション、実行ごとの予算を、コードと同じ `MaxCand = 4`、複数行のページで扱う。

コード（`StatusSyncState`）の要点：

- 端末ごとに、認証済み seq より新しい一覧の行を候補として、まだ試せるものと、上限回数失敗したもの（使い切った候補）を別々に最大 `MaxCand` 件ずつ持つ。
- ローテーション：今のパスで候補を使い切った seq の範囲（`sweep`）に入る行は、保持でも取得対象でも、範囲外の行の後に回る。範囲外に試せる行が残らないまま再走査が終わるとパスが終わる。範囲より上の成功で範囲は消える。
- 1 回の実行は、通常の一覧（最大 20 ページ、再走査中は 15）、再走査（残りのページ）、端末ごとに 1 件の取得、使い切った候補の再試行（端末ごとに 1 件、全体で 4 件、待ちの長い順）の順に進む。

性質：

- `AuthOnlyOnSuccess`、`ShownIsAuthenticated`、`AuthMonotone`：認証済み seq は一覧どおりの正しい blob を読めたときだけ増える。
- `NoSilentSkip`：一度でも一覧に出た、認証済み seq より新しい行があれば、その端末には取得対象があるか、問題として表示される。保持中の候補ではなく、一覧に出た行の独立した履歴（`seen`、`listed`）で量化するので、候補から落ちた行も対象になる。
- `CursorListed`、`CursorValid`、`CandsBounded`。
- `StatusLive`（活性）：障害が有限回で止まり、同期の実行が続くなら、各端末の最新の正しい status はいずれ読まれる。
- `EveryListedTried`（活性、`StatusRotation`）：一覧に出た、認証済み seq より新しい行は、いずれ一度は取得される。
- `ReadWithinPasses`（`StatusRotation_f6real`）：正しい status は再走査 4 回以内に読まれる（障害がなければ 2 回）。

時間の扱い：`StatusRotation` は 1 回の実行を時間の単位とし、再走査は「望まれて `Gap` 回後」か「`Week` 回後」に始まる。
`_f6real` はアプリの間隔（1 時間ごとの実行、望まれれば 1 日後、そうでなければ 7 日後）、`_f6` は 3 実行ごとまで頻繁にした敵対的な間隔である。
使い切った候補は、失敗した次の実行から再試行できるものとし、待ちの長い順を失敗の順で表す（コードは 1 時間から倍々に最長 1 日待つ。順序は概ね同じで、待ちはさらに長い）。
再試行の 1 日という上限は、次に試すまでの時間の上限ではない。
再試行の予算（1 回の実行で 4 件）は全端末で共有するので、使い切った候補が多ければ、待ちの終わった候補がさらに何回も待つ。
`StatusRotation` の活性は、`WF` ではなく「実行が繰り返し始まる」（`[]<><<RunStart>>_vars`）を前提とする含意で書く（TLC がこのモデルの ENABLED の評価に非常に時間がかかるため）。

境界：`StatusRotation` は同じ seq の偽の行（`StatusDownload` が扱う）を含まない。
偽の行は最初から一覧にあり、増え続けない。
サーバが正しい行より上に新しい偽の行を足し続ける限り、読むのを遅らせられる（保証の外）。
相手 2 台の再試行予算の競合は予算 1 で検査した。
49 台の相手に各 4 件の候補がある規模への一般化の議論はない。

### SharedCheckin

一つのエントリに対し、端末ごとに単調に増える rev の PUT と DELETE を、重複、順序の入れ替え、PUT より先の DELETE を含めて網羅する。
rev のない DELETE は 400 で拒まれるので、操作には含めない。
管理者の削除は rev を `MAX(rev, now)` にして tombstone にし、以後変わらない。

- `LatestWins`：削除されていないエントリの状態は、届いた操作のうち rev が最大のものの状態と一致する。
- `NoPhantom`：エントリは操作が届いた後にしか存在しない（`row ≠ None ⇒ delivered ≠ {}`。以前の式は逆向きだった）。
- `RemovedIsTombstone`、`AdminAbsorbing`。
- `StaleMeansSuperseded`：`stale` を返すのは、より大きい rev の操作が届いているときだけである。
- `StaleMeansLaterOp`：`stale` を返すのは、利用者が後から別の操作を出したときだけである。1 端末では成り立つ。時計のずれた 2 端末では、後の操作のほうが小さい rev を持てるので成り立たない（`SharedCheckin_skew`）。同じ rev で内容の違う操作も、後着が `stale` になる。容量超過（507）はモデルにない。

### FeedResync

自分と他の利用者 2 人のエントリ各一件、1 ページ 1 行、サーバ側の変更 5 回までを検査する。
クライアントの手順は仕様の記述に従う（アプリにはまだ実装がない）。

- `NoMixedVersions`：一つの表示に、異なる `followsVersion` のページが混ざらない。
- `ConvergedWhenStable`、`Converges`：サーバが変わらなければ、表示は見えるべき集合と一致する。

### DetailLog

生データの保存の提案（[設計](../docs/design/detail-log-format.md)、アプリは未実装）のうち、ジャーナル、断片、日の世代、回復、読む側を検査する。

- 記録器は、作成したジャーナルの先頭に最初の `seq` を書き、ディレクトリを fsync してからサンプルを入れる。サンプルは隙間のない `seq` で届き、系列（奇数と偶数）ごとのバッファに入り、系列ごとに古いものから一部ずつ書かれる（系列をまたいで順が入れ替わる）。バッファはサンプルを捨てることがある。
- 損失の範囲は、ファイルの走査だけで作る：最初の `seq` から最大の `seq` までの隙間と、最大の `seq` より後。確定は、全部の子と manifest とこの範囲を 1 回で確定の記録に書き、その後にだけジャーナルを消す。書き手が死んだジャーナルは、範囲を記録してから閉じる。
- 子は、一時ファイル、読み戻し（短い書き込みで行が欠けたファイルを拒む）、fsync、名前の変更、ディレクトリの fsync の順に作る。
- 日 1 の世代は、未消費の子を固定し、同じ手順で耐久化し、公開と消費を 1 回で確定し、固定した入力と前の世代だけを消す。
- 読む側は、公開中の世代と未消費の子を 1 回の読み取りで一覧にし、共有ロックを持ったまま全部を開く。消す側はロックを待つ。
- 障害：アプリのプロセスの死（バッファと読む側を失い、ジャーナルは回復待ちになる）、確定の側の死と I/O エラー（一部の子だけ fsync や名前の変更が済んだまま止まる）、電源断（fsync していないジャーナルの書き込みは一部だけ残る。名前が耐久でないジャーナルは消える。fsync していないファイルと、ディレクトリを fsync していない名前の変更を失う。`synchronous=FULL` でない確定の記録の commit を失う）、回復の途中の障害、サンプルの捨て。

性質：

- `Durability`：一度 fsync したサンプルは、耐久なジャーナルに残っているか、公開中の行にちょうど 1 回ある。
- `NoDup`：公開中の行を多重集合で数え、同じサンプルは 1 回以下である。モデルでは、同じ入力の確定の記録の 2 つ目の行を「同じ行のもう 1 つの写し」として数える（実装では別のファイルを指す行になる）。
- `LossReported`：ジャーナルが消えた後、そのサンプルは公開されているか、確定の記録の損失の範囲（隙間か末尾）に入る。ジャーナルが残る間は、その走査が後で報告する。
- `LossOnlyUnsaved`：損失の範囲は、fsync したサンプル、ジャーナルにあるサンプル、公開中のサンプルを含まない。履歴の変数で、ジャーナルを消した後も検査する。
- `ReaderOK`：読む側は一覧のファイルをすべて開け、一覧を読んだ時点の公開中の行をちょうど得る。
- `JournalsResolved`（`DetailLog_live`）：障害が止めば、書き手が死んだか閉じたジャーナルは、回復、確定され、消える。確定の側の各ジャーナルの手順に弱い公平性を仮定する。`LossReported` が回復待ちの間に空虚に成り立つことを、これで補う。

到達の確認（`reach:`、履歴変数による）：固定の後に確定した子が次の世代に入る（`late`）、途中で失敗した同じジャーナルを作り直して確定する（`failed` から `retried`）、2 日にまたがるジャーナルが両方の子の確定の後に消える、隙間（系列の入れ替えか捨てたサンプル）が報告される、読む側が新しい世代の公開をまたいで一覧どおりに読む。

`DetailLog_closefirst`（回復が範囲を記録する前に閉じる）は成立する。確定のときの走査が同じ範囲を記録するからであり、回復の記録は再起動の時刻を添えるだけである。

境界：ジャーナル 2 個で「閉じたものの確定」と「書き込み中」が同時に起き、系列 2 つで書き込みの入れ替えが起き、日の境界をまたぐサンプル 3 個で複数日のジャーナルが起き、世代 2 個で公開後の遅れたデータと古い世代の削除が起きる。`DetailLog_big` はサンプル 4、ジャーナル 3、世代 3（古い世代 2 つの削除）で同じ性質を確かめる。
省いたもの：表ごとのファイル（1 つの子にまとめた）、日 2 の世代、末尾以外の破損と隔離、ジャーナルの削除が電源断で戻る場面（入力が増えるだけ）。

### DetailMigrate

同じ提案の移行を検査する。
更新の時点で、`raw_lines` には時刻の順でない行 3 つと、旧い版が途中まで取り込んだテキストの行 1 つがあり、テキストのファイルにはその行と残りの行がある。
設計では、`raw_lines` を名前を変えて凍結し、以後に来た行は新しい表（別のソース）に入る。テキストは出自が分からないので隔離し、移さない。
凍結した表は rowid で 2 つの入力に分け、入力ごとに断片を書き、確定の記録に確定し、その後にその rowid だけを消す。再起動のたびに削除をやり直す。
断片の耐久化の手順は `DetailLog` が検査するので、1 つの動作にまとめた。

- `Durability`：元の表、ファイル、隔離にある行は、そこに残るか、公開中の行にちょうど 1 回ある。
- `NoDup`：公開中の行を多重集合で数え、同じ行は 1 回以下である。
- 到達の確認：確定と削除の間に来た行の移行、障害で失った同じ入力のやり直し、時刻の順でない 2 つの入力の両方の確定、隔離したテキストを残したままの移行の完了。
- 境界：rowid の再利用（空にした表の `max(rowid) + 1`）、凍結した表の中の時刻の範囲での巻き添え、途中まで取り込まれたテキストの二重の移行を起こすのに要る最小の大きさである。

## モデルと実装の対応
## モデルと実装の対応
## モデルと実装の対応

モデルはコードから自動で導いたものではなく、手で書いたものである。
対応を保つため、次のテストが実装側でモデルの性質と反例を確かめる。

- `server/test/conformance.test.ts`：実際の migration と SQL に乱数の操作列を流し、`CountersMatch`、`UnderCaps`、`UsersUnderMax`、`LatestWins`、`NoPhantom`、`AdminAbsorbing`、`StaleMeansSuperseded` を確かめ、各分岐（上限、競合、`stale`、`removed`）に到達したことも確かめる。
- `server/test/tlc-replay.test.ts` ほか：TLC の反例（`BatchCommit_current`、`FeedResync_firstpage`、`Registration_norecheck`、`Accounts_norecheck`、`Accounts_closedrace`、`SharedCheckin_old`）を実際の関数と SQL で再現し、現行のコードでは起きないことを確かめる。`server/test/registration-race.test.ts` は `Invites` の競合（同じ要求の二重送信、一つの招待を争う二つのトークン、一つのユーザ名を争う二つの招待）を実際の D1 で再現する。
- `server/test/protocol-constants.test.ts`：モデルが依る定数の関係（`D ≤ G` の余裕、カーソルと seq の範囲）を確かめる。
- `core` の `DurableMapSuite`、`PairingStoreSuite`、`RegistrationMachineSuite`、`DeviceStatusSuite`：書込み中の読出し（F1）、解除の途中のクラッシュ、F2、ローテーション（F6）、`RegistrationClient_runread` と `_unknownmark` の反例（後者は二つの経路）を実装の状態機械で確かめる。
- `DetailLog`、`DetailMigrate`（アプリは未実装）：下の表の境界ごとに、実装に kill と I/O エラーを差し込む点を置く（まだない。実装と同時に作る）。TLC の反例（ログに出る状態の列）を、その点の列（どの境界で kill し、電源断ではどの未耐久の書き込みを戻すか）に写し、実際のファイルと SQLite で再生して、`Durability` と `NoDup` に当たる検査（全サンプルの出現回数）を行う。試作の段階から、各変異の反例が現行のコードでは起きないことを確かめる試験として残す。

| モデルの動作 | 設計の手順 | 実装の境界（予定） |
| --- | --- | --- |
| `Create`, `CreateDirSync` | ジャーナルの作成、先頭の fsync、ディレクトリの fsync | 記録器のジャーナルの書き手 |
| `Deliver`, `Write`, `Drop`, `Sync`, `Rotate` | 系列ごとのバッファ、`write()`（系列ごと、短い書き込みを含む）、バッファの溢れ、30 秒ごとの fsync、CLOSE と名前の変更 | 同上 |
| `RecLoss`, `RecClose` | 回復：損失の範囲を確定の記録に確定、fsync して閉じる | 確定のプロセスの起動時の回復 |
| `Build`, `Verify`, `VerifyFails` | 子ごとの一時ファイル（気づかない短い書き込みを含む）、読み戻しの検証 | 断片の確定（Parquet の書き出し） |
| `Rename`, `DirSyncF` | fsync した一時ファイルの名前の変更、ディレクトリの fsync | 同上 |
| `CommitF` | 全部の子、manifest、走査で求めた損失の範囲の 1 トランザクション | 確定の記録（`detail-ledger.db`） |
| `DeleteJournal` | 確定済みの入力の削除 | 確定のプロセス |
| `GFreeze` .. `GPublish`, `GConsume` | 日の世代の固定、耐久化、公開と消費 | 日の世代 |
| `GC`, `RList`, `ROpen`, `RDone` | `gc.lock` の排他と共有、1 回の読み取りでの一覧、古いファイルの削除 | 世代の掃除、読む側（`ArchiveStore.read`、書き出し、送信） |
| `LedgerCheckpoint` | `synchronous=NORMAL` での WAL の耐久化（変異のみ） | 確定の記録の設定の確認 |
| `AppCrash`, `FinFail`, `PowerLoss` | プロセスの死、I/O エラー、電源断 | 障害注入の試験 |
| `DetailMigrate`：`Build`, `Commit`, `DeleteRows`, `InsertLate`, `DeleteFile`, `ImportIntoTable` | 凍結した表の入力、確定の記録、rowid の削除、後から来た行、テキストの削除と今のコードの取り込み（変異のみ） | 移行 |


| モデルの動作 | 実装 |
|---|---|
| `Registration`：`SRecover*` / `SGate*` / `SStart*`, `SBatch*`, `SRecheck*`, `SFullCheck*` | `v1.ts` の `/devices/register` / `edgeAllowed`, `hitRateLimit`, `limitsOr503` / `devices.ts` の `registerWithCode`（`earlier`、`db.batch`、`CODE_USABLE`） |
| `Accounts`：`SRecover*` / `SClosed` / `SBatch*` | `v1.ts` の `/accounts` / `registrationMode` と回復の再照会 / `accounts.ts` の `createAccount` |
| `Invites`：`SRecover*` / `SBatch*`, `SRecheck*` / `Revoke`, `Expire` | `accounts.ts` の `recoverAccount` / `createAccount`（招待の消費、`INVITE_USABLE`、UNIQUE の巻き戻し）/ `invites.ts` の `revokeInvite`、`expires_at` |
| `RegistrationClient`：`WriteBegin`, `Disk*`, `Rd` | `DurableMap.write`（`settle`、`DurableFile.replace`）, `DurableMap.get`, `view` |
| 同：`Start`, `Retry`, `Cancel`, `BeginCheck`…`FinishEnd`, `Drop`, `Reset` | `RegistrationMachine.start`, `retry`, `cancel`, `begin`, `finish`, `reset` |
| 同：`SaveTok`, `SaveReg`, `KeyWrite`, `Adopt` | `PairingStore.savePairing`, `confirmKey`, `adoptLegacyKey` |
| 同：`Unpair`, `UMark`…`UUnmarked`, `Resume` | `PairingStore.forget`, `resume`（`SyncConfig.forget`, `resumeUnpair`） |
| 同：`RunStart`, `RunReg` | `SyncRun.current`、`PairingStore.session`（`SmallSyncWorker` の `SyncLock` の下） |
| `Outbox`：`Seal`, `Commit` / `Send`, `Receive` / `ArchOpen`, `ArchPart` | `Uploader.enqueue`（`Crypto.seal`, `Db.enqueueSealed`）/ `Uploader.flush` / `ArchiveSnapshot.open`, `ArchiveUpload.nextPart`, `uploadArchive` |
| `BatchCommit`：`Start`, `Put`, `Commit`, `DelOwn` / `SweepScan`, `SweepDelete` | `batches.ts` の `putBatch` / `sweepOrphanBlobs` |
| `StatusDownload`, `StatusRotation`：`Take`, `Trim`, `Fail`, `Succeed`, `RescanPage`, `RunStart` / 一実行の段階 | `StatusSyncState.take`, `trim`, `failed`, `succeeded`, `rescanned`, `rescanIfDue` / `PeerStatus.pull` |
| `SharedCheckin`：`Deliver`, `AdminRemove` | `checkins.ts` の `upsertCheckin`, `unshareCheckin`, `unshareCheckinById` / `removeCheckin` |
| `FeedResync`：`Edit`, `Follow`, `Toggle` / `Fetch` | 共有の PUT と DELETE、`acceptFollow`, `deleteFollow`, `setUserStatus` / 仕様の手順（アプリは未実装） |

## 検査で見つかった問題

### このモデルで見つけて直した問題

次の三つは、修正前のコードを表す設定（`**所見（修正済み）**`）で反例が出る。
これらの設定は修正前の振る舞いの再現として残し、期待は反例のままにする。
モデルの主な設定は現行のコード（`RunReadsOnce`、`UnknownMarkSet`、`WriteReadsBack`、`RecheckClosed` がすべて `TRUE`）を表し、成り立つ。
修正のコミット：6aa4ecd

1. **同期の実行が、解除の印の付いた後の登録を読む**（`RegistrationClient_runread`、`RunRegMatches`）。
   修正前の `SyncRun.current` は端末 ID、トークン、鍵（`masterKey` はその時点の登録で照合）を読んだ後で、`SyncConfig.registration` を改めて読んでいた。
   解除の印は同期の鍵の外で書かれるので、その間に印が付くと、実行は A の鍵と A の端末 ID を持ったまま登録 `""` を得た。
   その実行は進捗や outbox を `""` の下で数え直し、A のスロットに seq 0 から送って 409 で止まる（解除がジョブを止めるまで）。
   修正：`PairingStore.session` が設定の一つのスナップショットから URL、端末 ID、印を読み、その登録でトークンと鍵を照合して返す。
   `savePairing`、`confirmKey`、`adoptLegacyKey` と同じロックの下で読むので、それらとは混ざらない。
   同期の鍵の外で書かれるのは印だけであり、スナップショットはそれを含むか含まないかのどちらかである。
   `SyncRun.current` はこの値だけを使う。
   テスト：`PairingStoreSuite` の「a sync session reads the registration once」が、鍵を読む瞬間に印を付けて、旧来の二度の読出しでは登録が `""` になり、`session` では A のまま揃うことを確かめる。
2. **読み戻せなかった印の消去で、新しい登録の保留要求を失う**（`RegistrationClient_unknownmark`、`TokenRecoverable`）。
   解除の最後の `config.commit(Unpairing -> null)` が失敗して読み戻しもできないと、印は不明となり、修正前は `getBoolean(Unpairing, false)` が false を返した。ディスクには印が残りうる。
   アプリは解除済みとして新しい登録を始め、トークンを保存して送る。
   サーバが端末を作った後、次の起動で `resume` が印を見て解除をやり直し、`Registration.reset()` と `clearRest` が保留要求を消す。
   利用者が手放していないトークンを失い、端末はサーバに残る。
   また、不明な印のまま別の書込み（`savePairing` の `config.commit`）が成功すると、スナップショットの古い値（印あり）がファイルに書き戻され、登録直後に「解除中」に戻った。
   修正は三つである。
   - 不明な印は「解除中」として読む（`PairingStore.unpairing`、`DurableMap.view`）。新しい登録は解除が終わるまで始まらない。
   - 印の消去が読み戻せずに失敗したら、印が分かるまで書き直す（3 回まで）。諦めた場合は印が残り、次の起動の `resume` が解除を終える。
   - `DurableMap` の書込みは、不明なキーがあるとき、まずファイルを読み直し、その内容から始める。読み直せなければ、不明なキーをすべて自ら設定する書込みだけを許し、ほかは拒む。他のキーの書込みが、失敗した書込みの置き換えたかもしれない値を書き戻すことはない。
   テスト：`PairingStoreSuite` の「a mark that cannot be read back counts as set」が二つの経路（新しい登録が始まらないこと、後の登録が「解除中」に戻らないこと）を、ディスクに新旧どちらが残った場合も、再起動の有無も含めて確かめる。「an unmark that fails ... is written again」が書き直しを、`DurableMapSuite` の「a later write of other keys never writes back ...」が書き戻しのないことを確かめる。
   モデルの主な設定では、印の書き直しと、不明な印のまま待つ場面に到達する（`reach:`）。
3. **自己登録の締切と再送の競合**（`Accounts_closedrace`、`RetriesAgree`）。
   同じトークンの複製 A、B が回復の照会を通り、A がアカウントを作り、運用者が登録を締め切った後に B が締切の判定に来ると、修正前の B は 403 を受けた。
   クライアントは 403 を確定した拒否として保留要求を消し、作られたアカウントを失う。
   修正：`POST /v1/accounts` は 403 `registration closed` を返す前に回復を照会し直し、このトークンのアカウントがあれば 200 でその端末 ID を返す。
   403 `registration full` は `createAccount` が 0 行の後に照会し直す（N3 の修正）。429 と 503 はクライアントが送り直す応答で、送り直しは回復を先に通る。
   テスト：`server/test/registration-race.test.ts` の「Accounts_closedrace: ...」が、B を回復の照会の直後で止め、A の作成の後で締切を読ませ、B が A と同じ端末 ID を 200 で受けることを確かめる。
   残る場合：A が確定する前に B が締切で拒まれると、B は 403 を受ける（モデルの `RetriesAgree` もこの場合を対象外とする）。
   アプリは、このプロセスで作って一度も送っていないトークンへの 403 だけを確定とする。以前に送った可能性のあるトークン（応答の消失、クラッシュ後、再試行）は、403 を受けても 2 分間、間隔を広げながら送り直し、その間に A が確定すれば回復で 200 を受ける。

### 以前に見つけて直した問題

- 孤立掃除が確定済みの実体を消す（`BatchCommit_current`）：D1 が期限切れの試行の commit を拒むようにした。期限 `D` は 1 時間、猶予 `G` は 24 時間で、時計のずれ `s` は `D + 2s ≤ G` の範囲で吸収する。
- 偽の一覧行で status の受信が止まる（`StatusDownload_fakeseq`、`_fakecursor`）：候補の複数保持、再走査、範囲外のカーソルの拒否（R4）、使い切った候補の再試行（R5）を入れた。
- 保持数を超える偽の行で正しい status が押し出され続ける（F6、`StatusRotation_f6pre`、`StatusDownload_manyfakes_pre`）：レビューの例（正しい seq 0 と偽の 1〜8）では、偽の 5〜8 を使い切り、再走査で 1〜4 が入って 0 は落ち、次の再走査で同じ状態に戻る。これは 1 時間ごとの取得と 1 日ごとの再走査という実際の間隔でも起こる。ローテーションを入れて直した。
- 送信の非原子性（`Outbox_nonatomic`）、apply による保存（N2）、0 行の後の再照合漏れ（N3）、rev を見ない DELETE（N5）、アーカイブの世代の混在（R1）、取消と保存の競合（R2）、保存失敗した値の読出し（R3、F1）、間に合わない取消での保留要求の削除（F2）、解除の途中のクラッシュによる古い鍵の使用（レビュー 6 の 3）。いずれも修正前の方式を表す設定が反例を出し、現行の設定では成り立つ。

## 証明していないこと

### モデルとコードの差

- 暗号モデルは AES-GCM、HKDF、SHA-256 を理想化している。nonce の衝突、実装の欠陥、サイドチャネルは対象外である。`K` の端末内での扱いと復旧コードの受け渡しは前提である。
- 状態モデルは SQL 文と D1 の `batch()` を原子的な遷移として扱う。時計は単一で、Worker、D1、R2 の時計のずれは扱わない（`BatchCommit` は `D + 2s ≤ G` で吸収する）。
- `RegistrationClient` のサーバは抽象化している。`Registration` は端末の失効、使用済みコードの削除、7 日後の未使用アカウントの削除を扱わない。
- `RegistrationClient` は画面の描き直しの順序、同じストアへの SharedPreferences 側の書込み（残っているのはネットワーク設定などで、登録と鍵ではない）を扱わない。
- アーカイブのモデルは、ファイルが rename でだけ置き換わり、開いた記述子が開いた版を読み続けることを前提にする。日ファイルから月ファイルへの統合と、復元したレコードの集合は扱わない。
- status 取得の時間は抽象化している（前述）。保存した状態の再起動後の復元、通知の順序は扱わない。
- フィードの取得側はアプリに未実装であり、仕様の手順をモデルにした。

### 有限の大きさ

TLC は各設定に書いた有限の大きさを網羅するだけである。
とくに status 取得は相手 1〜2 台、偽の行 8 件（1 台）または 5 件ずつ（2 台）で検査しており、一覧のカーソルとページと再試行の予算を全端末で共有するため、一般の台数と件数に拡張できる議論はない。
活性は、障害、再登録、サーバ側の変更が有限回で止まることと、各処理（同期のジョブ、利用者の再試行と鍵の入力、アプリの再起動）が繰り返し起こることを仮定する。

### メタデータ

暗号モデルが保証するのは内容の秘匿だけである。
端末 ID、stream 名、seq、blob の大きさ、送信時刻、一覧のカーソルは、サーバに見える値として扱う。
識別不能性も、長さと送信の頻度が等しい二つの履歴についてだけ成り立つ。
共有チェックインは定義上平文であり、対象外である。

## 残る課題

レビュー 6 の「未モデル化」の表のうち、次は扱っていない（優先順）。

| 優先 | 領域 | 扱っていない理由、次の一歩 |
|---|---|---|
| 2 | バックアップからの復元、日と月の統合、遅着データ | 遅着データの制限は `Outbox_late` で示した。世代付きの送信済みの印と、復元したレコード集合の検査は未着手 |
| 3 | フォロー承認、利用者の分離、停止と再開、端末の失効 | 認可はサーバのテスト（`server/test`）が扱う。効力の及ぶ時点のモデルは未着手 |
| 4 | 自己登録の 7 日削除、使用済みコードの削除と進行中の要求 | 再試行の有効期限との競合は未検査 |
| 5 | 複数端末からの共有の取消と再共有 | 時計のずれは `SharedCheckin_skew` で制限として示しただけ。同じ rev、容量超過（507）は未検査 |
| 6 | カウンタ、旧データ、移行、旧 Worker の同居、設定変更 | カウンタは `conformance.test.ts` が実 SQL で確かめる。移行と同居は未検査 |
| 7 | レート制限の窓の境界と遅延した要求 | 窓が後退しないこと、拒否で行が変わらないことはコードと SQL で直したが、モデルにはない |

このほか、次は将来の課題とする。

- 位置情報を外部に送らない設定（`SendGate`）のモデル：世代と票（ticket）、表示ごとの許可、スナップショットの待ち行列で、「古い票で自動の送信をしない」「別の表示の許可でオンラインにならない」を検査する。CI のパスには含めた。
- Tamarin：平文を公開する変異、`batch_privacy` での正常な受信の到達性（`executable` 相当）。
- 同期の実行全体の順序と、アップロードの失敗が status 取得を止めないこと（コードでは独立にした）。
