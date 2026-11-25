---
name: sync-smartdisplay-pair
description: >-
  smartdisplay と smartdisplay_mini のどちらに機能を入れるか決め、両方に入れるときは挙動だけを合わせて移植する。
  Use when the user mentions 両方, こちらだけ, 本体だけ, mini, smartdisplay_mini,
  porting a feature between the two smart display apps, or when a change might belong in both.
---

# smartdisplay と mini の使い分け

## リポジトリ

- 本体: `/Users/takokake/Documents/work/smartdisplay`
- mini: `/Users/takokake/Documents/work/smartdisplay_mini`

Kotlin のパッケージ根・`namespace` はどちらも `app.smartdisplay`。同じ相対パスのファイルが多い。中身は分岐している。インストール ID（`applicationId`）だけ異なり、本体は `app.smartdisplay`、mini は `app.smartdisplay.mini`。

この skill は両方の `.cursor/skills/sync-smartdisplay-pair/SKILL.md` にある。直したら両方を同じ内容にする。

## どちらに入れるか

ユーザーの指定に従う。

- 「こちらだけ」「このリポジトリだけ」「本体だけ」「mini だけ」→ 指定した方だけ。もう一方は変更しない
- 「両方」「mini にも」「本体にも」→ 下の手順で両方に入れる
- 指定が無いときは推測で移植しない。ユーザーから見える挙動や、両方にありそうな共通処理を変えるときは、入れる前にどちら向けか質問する。片方にしか無い機能の内部修正では質問しない

## 両方に入れる

1. 先に開いている側で変更を終えてから、もう一方へ移す
2. 相手の対応箇所を探す。同じ相対パスを先に見る。無ければシンボル名で探す
3. 挙動を合わせる。相手に無い差分は上書きしない。相手にその機能が無い（ファイルも呼び出しも無い）ときは移植せず、その旨を伝える
4. README は、そのアプリがすでに書いている文だけ直す。相手の README を本体の文で置き換えない
5. 各リポジトリの auto-commit に従い、そのリポジトリで変えたファイルだけをコミットする。メッセージは既存の `feat:` / `change:` に合わせ、日本語で理由を書く。プッシュは依頼があるまでしない
6. ビルドは提案しない。エディタ診断で出たエラーは直す

本体にしか無いことが多い機能は、mini のディレクトリにファイルが無い。天気、バス、鉄道、歩数、賞味期限、記念日、外出アラート、タイマー、地震、ごみ、Spotify、ナイトモード。移植前に相手の `app/src/main/java/app/smartdisplay` を見て判断する。
