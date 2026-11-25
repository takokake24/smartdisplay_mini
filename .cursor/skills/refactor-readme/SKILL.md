---
name: refactor-readme
description: >-
  機能を変えずに smartdisplay の Kotlin ソース容量を削減し、同じ作業で README.md を機能概要に合わせる。
  Use when the user asks to refactor for size reduction, リファクタリング, 容量削減,
  deduplicate code without changing behavior, update README, refresh feature docs,
  or sync README with app capabilities. Always do both in the same pass.
---

# 容量削減と README 整備（smartdisplay）

## 目的

1回の作業で次を両方行う。

- 挙動・見た目・公開文言は変えず、`app/src/main/java` 配下の `.kt` 行数・バイト数を減らす
- `README.md` を「何ができるか」の要約に保つ。実装手順や内部 API の詳細は書かない

リファクタでユーザーから見える振る舞いは変えない。README は、コードと文書のずれがあるときだけ直す。

## 禁止

- 機能追加・仕様変更・アプリ内の文言変更をしない
- 推測で機能を削らない。推測で README に機能を足さない
- ビルドコマンドの提案は不要（ユーザーが手動実行）
- 削減が 0 以下で、README も差分がなければコミットしない

## 容量の測り方

作業前後で同じコマンドを使い、Kotlin ソース全体を測る。README は含めない。

```bash
python3 -c "
import os
root='app/src/main/java'
lines=0; bytes_=0; files=0
for dp,_,fs in os.walk(root):
  for f in fs:
    if f.endswith('.kt'):
      p=os.path.join(dp,f); files+=1
      data=open(p,'rb').read(); bytes_+=len(data); lines+=data.count(b'\n')
print(f'files={files} lines={lines} bytes={bytes_}')
"
```

- **容量** = `lines`（行数）を主、`bytes` も併記
- 削減量 = 作業前 − 作業後（正の値が削減）

## 手順

1. 作業前の `lines` / `bytes` を記録する
2. 現在の `README.md` を読む
3. 重複・共通化できる箇所だけ直す
4. エディタ診断でエラーがあれば直す
5. 作業後の `lines` / `bytes` を測る
6. 次を確認し、README と食い違う機能だけ直す
   - `MenuScreen.kt` のホームタイル（`menuItems`）と `MenuPage`
   - `ClockScreen.kt` で常時動いているコントローラ／リポジトリ
   - パッケージ直下の責務（`jihou` / `weather` / `warning` / `earthquake` / `calendar` / `transit` / `bus` / `notification`）
   - 方面・路線など公開ラベルは enum の表示名を正とする（例: `BusDirection.tabLabel`、`TrainLine.displayName`）
7. README の該当セクションだけを更新する。無関係な文言はいじらない。すでに一致していれば README は触らない
8. 必要ならこのスキルの「構成」に合わせて見出しを足す／整理する
9. 下の「コミット」に従ってコミットする（プッシュはしない）

## README の構成

次の骨子を維持する。順序を大きく変えない。

```markdown
# smartdisplay

（1〜2文のプロダクト説明）

Android Studio でこのフォルダを開く。

## 機能概要

### 時計画面
### 時報
### 通知
### 天気予報（対象地域）
### 気象警報（対象地域）
### 地震
### カレンダー
### 運行情報
### バス時刻表
### その他（設定）
```

新しい大機能が増えたら `## 機能概要` 配下に同レベルの `###` を足す。ホームメニューに出ない裏機能は「その他（設定）」か、明示的な節にまとめる。

## 書き方

- 日本語
- 箇条書き中心。1項目は短く「ユーザーから見える振る舞い」を書く
- 会社名・方面名・路線名はコード上の表示名に合わせる
- 取得元 URL、クラス名、ファイルパス、ビルドコマンドは書かない
- 「今後の予定」や未実装の約束は書かない
- 空行はインデントしない

## 良い例 / 悪い例

良い:

```markdown
- 便のタップで乗車通知（発車 15・10・5・3 分前）
```

悪い:

```markdown
- `BusReminderScheduler` が `NotificationCenter` に post する
- NAVITIME の HTML をパースして…
```

## 方針の目安

- 同一ロジックのコピペをヘルパ／object に寄せる
- UI の同型レイアウトを小さな Composable にまとめる
- enum 定数の冗長な改行は、引数の意味が保たれる範囲でコンパクト化してよい
- ファイル分割だけの移動は総行数が減らないので、容量削減目的では優先しない

## コミット

Kotlin の変更と README の変更は、あるとき 1 コミットにまとめる。

1行目: 理由を一文。Kotlin を減らしたときは `refactor:`。README だけのときは `change:`。両方のときは `refactor:` の文に README も直した旨を含める

2行目: Kotlin を減らしたときだけ、次の形式を**そのまま**（数値は実測）

```text
容量: 前 <beforeLines>行/<beforeBytes>B → 後 <afterLines>行/<afterBytes>B（-<reducedLines>行/-<reducedBytes>B）
```

- 削減が正で README も変わった → 両方を 1 コミット。2行目に容量
- 削減が正で README は無変更 → 容量付きでコミット
- 削減が 0 以下で README だけ変わった → README のみコミット。容量行は付けない
- どちらも無変更 → コミットしない

例（両方）:

```bash
git commit -m "$(cat <<'EOF'
refactor: バス取得の重複を共通化し README を機能概要に合わせる

容量: 前 14816行/547814B → 後 14200行/520000B（-616行/-27814B）
EOF
)"
```

例（README のみ）:

```bash
git commit -m "$(cat <<'EOF'
change: README を現状の機能概要に合わせる

メニューと時計画面の差分を反映する
EOF
)"
```
