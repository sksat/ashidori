# 配布データの生成と公開

[README に戻る](../README.md)

アプリが使う地図・地名・施設のデータの生成と公開の手順を記す。
出典は [`tools/ATTRIBUTION`](../tools/ATTRIBUTION)、ライセンスの全文は [`tools/licenses/`](../tools/licenses/) にある。

| データ | 生成のツール | 配布 | アプリの固定値 |
| --- | --- | --- | --- |
| 地名データベース | `tools/geocoder/` | APK に同梱（`app/src/main/assets/geocoder/`） | — |
| オフライン地図（全域） | `tools/basemap/` | GitHub Releases `basemap-<版>`、同期サーバ | `basemap-pin.json` |
| 施設データ | `tools/venues/` | GitHub Releases `venues-<版>` | `venues-pin.json` |
| 地域データ（区画ごとの詳しい地図） | `tools/regionpacks/` | GitHub Releases `regions-<版>` | `regions-pin.json` |

GitHub Releases や同期サーバから取得するデータは、アプリに固定した大きさと sha256 で照合してから使う。取得元を信頼する必要はない。
公開済みのリリースのファイルは差し替えない。データを変えたときは新しい版を作る。

## 地名データベース

大字・町丁目レベル位置参照情報（国土交通省）と GeoNames から作る。
位置参照情報のダウンロードには、位置参照情報ダウンロードサービス利用約款と位置参照情報利用約款への同意が必要である。

```sh
tools/geocoder/fetch.sh <作業ディレクトリ>   # app/src/main/assets/geocoder/ に index.bin と manifest.json を出力する
```

## オフライン地図（全域）

Protomaps Basemap の日次ビルドから、日本（ズーム 6〜10）と全世界（ズーム 0〜5）を切り出す（約 64 MB）。
Protomaps の日次ビルドは 1 週間ほどで消え、直接の参照も推奨されないため、切り出したデータを GitHub Releases と同期サーバで配布する。

```sh
tools/basemap/build.sh <作業ディレクトリ>   # tools/basemap/out/ に生成する（BUILD=YYYYMMDD で日次ビルドを指定）
tools/basemap/package.sh                    # tools/basemap/dist/ にアーカイブと記述子を作り、アプリの固定値を更新する
tools/basemap/release.sh                    # 公開のコマンドを表示する（実行はしない）
```

- `package.sh` の版は、既定では日次ビルドの日付である。データを変えたときは `VERSION=<版>` で新しい版にする。
- `release.sh` は `gh release create basemap-<版> …` と、同期サーバに置く場合の `wrangler r2 object put … --remote` を表示する（[`server/README.md`](../server/README.md#オフライン地図の配信)）。
- 固定値（`basemap-pin.json`）の変更をコミットし、そのアプリを配布する前にアーカイブを公開する。

アプリは初回の起動時に Wi-Fi 接続でアーカイブを取得し、`filesDir/basemap/<版>/` に展開する。
同期を設定した端末は、サーバの `GET /v1/info` が固定の版を挙げていればサーバから、それ以外は GitHub Releases から取得し、一方が失敗すると他方を試す。
取得は WorkManager で行い、中断しても HTTP Range で続きから取得する。

## 施設データ

Geofabrik の日本の抽出（OpenStreetMap）から、名前と種別を持つ施設を抜き出す（約 18 MB）。

```sh
tools/venues/build.sh <作業ディレクトリ>   # tools/venues/out/ に生成する（約 3 GB の作業領域、5 分ほど）
tools/venues/package.sh                    # tools/venues/dist/ にアーカイブと記述子を作り、アプリの固定値を更新する
tools/venues/release.sh                    # 公開のコマンドを表示する（実行はしない）
```

## 地域データ

Protomaps Basemap の日次ビルドから、ズーム 8 の区画ごとにズーム 8〜14 を切り出す（日本の 136 区画、約 1.2 GB）。
手順と保証は [`docs/design/outbound-and-offline.md`](design/outbound-and-offline.md) の 11 章にある。

```sh
tools/regionpacks/build.sh <作業ディレクトリ> <出力>   # 区画、スタイルの束、カタログを作る
tools/regionpacks/package.sh <版> <出力>              # カタログをアプリに固定する（regions-pin.json）
tools/regionpacks/release.sh <版> <出力>              # 照合し、公開のコマンドを表示する（実行はしない）
```

## 公開する前にデバッグ版で試す

作ったデータを GitHub Releases に公開する前に、デバッグ版のアプリで試せる。
アプリはどの経路でも、固定した大きさと sha256 で照合する。先に `package.sh` で固定値を更新し、デバッグ版をビルドして入れる。

```sh
./gradlew :app:installDebug
```

**ファイルを端末に置いて取り込む。** データの中身と表示を試すときに使う。

```sh
v=$(python3 -c 'import json; print(json.load(open("app/src/main/assets/basemap-pin.json"))["version"])')
adb push tools/basemap/dist/ashidori-basemap-$v.zip /sdcard/Android/data/net.sksat.ashidori.debug/files/basemap/
adb shell am start -n net.sksat.ashidori.debug/net.sksat.ashidori.MainActivity --ez ashidori.basemapImport true
```

**手元の HTTP サーバから取得させる。** ダウンロードの処理（進み具合、中断と再開）も試すときに使う。`""` を渡すと既定の取得元に戻る。

```sh
(cd tools/basemap/dist && python3 -m http.server 8000) &
adb shell am start -n net.sksat.ashidori.debug/net.sksat.ashidori.MainActivity --es ashidori.basemapUrl http://10.0.2.2:8000
```

施設データは、`--ez ashidori.venuesImport true`（`files/venues/` に置いたファイル）と `--es ashidori.venuesUrl <URL>` で同じように試せる。
