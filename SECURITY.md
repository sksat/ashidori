# セキュリティ

ashidori は位置の記録を扱い、同期サーバとの間をエンドツーエンドで暗号化する。設計と脅威モデルは [`docs/privacy.md`](docs/privacy.md)、[`docs/design/`](docs/design/) と [`formal/`](formal/) にある。

## 脆弱性の報告

公開の Issue には書かず、GitHub の [Private vulnerability reporting](https://github.com/sksat/ashidori/security/advisories/new) で報告する。次の内容があると調べやすい。

- 影響を受ける部分（アプリ、同期サーバ、Web の閲覧、プロトコル）と版
- 再現の手順、または攻撃の筋書き
- 想定する攻撃者（悪意あるサーバ、ネットワーク上の攻撃者、端末を持つ者など）

特に、サーバ側（侵入、データベースやストレージの流出、悪意ある運営者）から位置や履歴が読める経路、偽の記録を受理させる経路、鍵が想定より広く使える経路を重視する。

## 対象の版

最新のリリースと `main` のみを対象とする。
