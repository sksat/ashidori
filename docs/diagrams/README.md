# 図

このディレクトリには、ドキュメントの構成図の D2 のソース（`*.d2`）と、それから生成した SVG を置く。

- 図を変えるときは `.d2` を編集し、`tools/docs/render-diagrams.sh` で SVG を生成して両方をコミットする。
  D2 とフォントは版と SHA-256 を固定しており、初回に `~/.cache/ashidori-docs` へ取得する。
- CI（`.github/workflows/docs.yml`）は `render-diagrams.sh --check` で、コミットした SVG が生成結果と一致するかを確かめる。
- SVG は明暗の二つのテーマを含み、閲覧者の設定に合わせて切り替わる。
  日本語は Noto Sans JP（SIL Open Font License 1.1）のうち使う字だけを SVG に埋め込む。
- 構成要素の境界や包含を示す構成図には D2 を使い、Markdown から `![短い説明](diagrams/名前.svg)` で参照する。
  シーケンス図と単純な状態遷移図は、GitHub がそのまま表示する Mermaid で Markdown に直接書く。

