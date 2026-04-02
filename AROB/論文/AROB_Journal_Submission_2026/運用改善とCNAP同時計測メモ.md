# 運用改善とCNAP同時計測メモ

このメモは、今の `RealTime-IBI-BP` の実装を見たうえで、

- 今いちばん効く改善は何か
- もっとデータを取る前に何を直すべきか
- `CNAP Monitor 500 HD` と同時に、できるだけ自動で計測するにはどう考えるべきか

を、修士学生にもわかるように整理したものです。

---

## 1. まず結論

### 1.1 いま一番効く改善

今いちばん効く改善は、**新しい数式を考えることではなく、データの記録方法を強くすること**です。

今のアプリは、

- `RTBP`
- `sin(M)`
- `sin(D) = RTBP features + E`

という計算ロジック自体は、今の論文の方針とだいたい合っています。

でも、データの保存にはまだ弱いところがあります。

たとえば、

- `subject_id` が本物ではなく placeholder になっている
- `ref_SBP`, `ref_DBP` はあとで埋める前提になっている
- `timestamp` が明示的に保存されていない
- camera の条件は取得しているのに、CSV に残していない

という状態です。

これは、すごく簡単に言うと、

- **実験はしている**
- **特徴量も出している**
- **でも、あとでちゃんと再現できる形でラベル付けして保存できていない**

ということです。

研究では、ここが弱いまま大量にデータを取ると、後で

- どのファイルがどの被験者か
- どの区間がどのセッションか
- いつ CNAP を校正したか
- Android と CNAP が本当に同じ時間を見ているか

がわからなくなります。

つまり、**今の最優先は「推定式の改善」より「記録の型を整えること」**です。

### 1.2 おすすめの大きな方針

今の段階では、次の方針が一番安全です。

- アプリは「その場で BP を出すもの」でもあるが、もっと大事なのは「特徴量をきれいに保存する記録装置」として使う
- `trend` の計算や `20 s window` の集計はアプリで決め打ちしない
- それらは後で Python 側で行う
- `CNAP` とは「同時に記録する」ことをまず確実にし、その上で「自動同期」を強くする

### 1.3 いま一番おすすめの実装の形

今の時点でいちばんおすすめなのは、**PC を中央ハブにする方式**です。

考え方は単純です。

- Android は beat ごとの特徴量を PC に送る
- CNAP は `AUX analog out` または `USB記録` を使う
- PC が両方を受けて、その場で時刻をそろえて保存する

この形にすると、

- 実験中に「ちゃんと両方来ているか」を確認できる
- session の取り違えが減る
- 後で自動マージしやすい
- Android に CNAP を直接つなぐより安全

です。

---

## 2. 修士向けにもっと簡単に言うと

今の状況をすごく平たく言うと、

- 数式の中身は、今の論文にかなり近い
- でも、実験ノートの書き方がまだ弱い

ということです。

たとえば、研究室で 100 本の試験管を作っても、

- ラベルが弱い
- 時刻が書いていない
- どの条件で取ったか書いていない

と、後で解析するときに困ります。

今のアプリもそれに近いです。

だから、今やるべきことは

- 「もっと賢い式」を考えること

よりも、

- 「あとで絶対に困らない形で保存すること」

です。

---

## 3. 今すぐ効く改善

### 3.1 絶対に保存すべきもの

もっとデータを取る前に、最低でも次の情報を毎回残すべきです。

- `session_id`
- `subject_id`
- `session_number`
- `timestamp_ms`
- `elapsed_time_s`
- `beat_index`
- `mode`
- `app_version`
- `coefficient_version`

これがあると、

- どの拍がどのセッションのものか
- どの係数で計算した結果か
- Android と CNAP をどう合わせるか

がかなり明確になります。

### 3.2 camera 条件も保存すべき

今のアプリは camera 情報を取得して UI に表示しています。

でも、研究として大事なのは「見えた」ことではなく「残った」ことです。

残すべきものは、

- `ISO`
- `exposure_time`
- `white_balance_mode`
- `focus_distance`
- `fps`

です。

理由は簡単で、30 fps visible-light rPPG の研究では、

- 画質
- 露光
- センサ条件

が変わると、波形の見え方も変わるからです。

あとで

- 「この session だけ精度が悪い」
- 「この人だけ E が異常に大きい」

となったときに、camera 条件が残っていないと原因を追えません。

### 3.3 品質フラグも保存すべき

今後の自動化のためには、各 beat に

- `is_valid_beat`
- `is_iso_valid`
- `artifact_flag`
- `E`

のような品質情報を付けておくと強いです。

これは後で

- 悪い beat を自動除外する
- E が大きいときは重みを下げる
- calibration 直後の不安定区間を外す

といった処理に使えます。

---

## 4. おすすめ運用

### 4.1 一番おすすめの考え方

いま一番おすすめなのは、

- **Android と CNAP を同時に記録する**
- **同期はしっかり取る**
- **本格的な結合は後処理で自動化する**

という方式です。

これは「リアルタイムで同時に測る」ことと、「その場で全部を Android に取り込む」ことを分けて考える、という意味です。

ここは大事です。

`リアルタイム同時計測` には 3 段階あります。

1. 同じ時間に両方で測る
2. 両方の時刻を合わせる
3. Android 側で CNAP の値までその場で直接読む

今の研究でまず必要なのは `1` と `2` です。

`3` まで最初からやろうとすると、機器連携と安全面が一気に重くなります。

### 4.2 いま一番現実的な運用

いちばん現実的で安全なのは、次の流れです。

1. 記録開始前に `subject_id` と `session_id` を決める
2. Android 側の recording を開始する
3. CNAP 側も同じ session で記録を開始する
4. calibration や刺激開始などのイベントを残す
5. 記録終了後、Android CSV と CNAP CSV を同じ `session_id` で自動結合する

この方式なら、

- 実験中の負担が少ない
- 後で自動解析しやすい
- Android 側のコードを極端に複雑にしなくてよい

です。

### 4.3 いまの app をどう使うべきか

今の app は、

- 推定値をその場で表示する
- でも本番では `per-beat feature logger` として使う

という使い方が良いです。

つまり、

- 画面の BP 値は参考として見る
- 本当に大事なのは保存された beat ごとの特徴量

という位置づけです。

### 4.4 もっと自動化したいなら PC ハブ方式がよい

ここから先は、今までより一歩進めたおすすめです。

もし「できるだけ自動化したい」「実験が終わった時点でほぼ同期済みデータが欲しい」と考えるなら、

- **PC が中央サーバ**
- **Android と CNAP はそこへ送るクライアント**

という形が一番筋が良いです。

この方式では、PC 側で次の 3 種類を同時に保存します。

1. Android から来た beat-level feature
2. CNAP から来た連続血圧データ
3. 実験イベント
   例: `start`, `calibration`, `recalibration`, `stimulus_on`, `stimulus_off`, `stop`

こうすると、PC 側で

- その場で軽い同期
- その場で欠損チェック
- 実験直後の自動マージ

までできます。

---

## 4.5 修士向けにもっと簡単に言うと

一番わかりやすいイメージは、

- Android は「特徴量を送る係」
- CNAP は「本物の血圧を送る係」
- PC は「全部まとめる係」

です。

今までは、

- Android で記録
- CNAP で記録
- 後で人が頑張って合わせる

に近いです。

これを、

- Android も CNAP も PC に送る
- PC が session ごとに整理して保存する

に変えると、かなり楽になります。

---

## 5. CNAP Monitor 500 HD について調べたこと

以下は、公式ページと公開マニュアルを見て整理した内容です。

### 5.1 何を測っている機械か

`CNAP Monitor 500 HD` は、

- 指のセンサで連続血圧波形を測る
- 上腕カフは brachial pressure への calibration に使う

という仕組みです。

つまり、

- 連続値の本体は finger sensor
- cuff は校正用

と理解するとわかりやすいです。

### 5.2 研究で大事なポイント

CNAP の公式情報では、

- continuous values come from the finger sensor
- the internal NBP cuff provides reference values for calibration
- after repositioning the patient, fingers, or hand, recalibration is needed

と説明されています。

これは研究上かなり大事です。

つまり、

- 指や手の位置が変わると、値がずれる可能性がある
- calibration / recalibration のタイミングを記録しておくべき
- 手の高さを心臓の高さに対してできるだけ安定させるべき

ということです。

### 5.3 出力まわり

公開マニュアルでは、CNAP には次の出力の考え方があります。

#### BP Wave Out

- patient monitor の IBP port 向け
- 標準化された blood pressure waveform 出力

#### AUX analog output

- 最大 4 チャンネルを同時出力可能
- 出せる候補には `BP waveform`, `MAP`, `sys BP`, `dia BP`, `Pulse`, `CO`, `SV`, `SVR`, `PPV` がある
- サンプリング周波数は `100 Hz`

#### Ethernet

- `CNAP Monitor 500` の operator manual では **service purposes only**

#### USB

- USB は主に `data recording on a USB flash drive`
- 公開マニュアル上、**USB には flash drive をつなぐ前提**
- CSV 記録は optional feature

ここからわかることは、

- Android に直接 USB でつなぐ発想は、少なくとも安全かつ正規の使い方としては弱い
- `Ethernet` は `CNAP Monitor 500` では **service 用**
- 研究用途で現実的なのは `USB記録` と `AUX analog out`

です。

### 5.3.1 AUX ケーブルがないときはどうするか

正しい manual を見ると、ここはかなりはっきりしています。

- `Ethernet port is restricted for service purposes only`
- `USB` は flash drive への data recording 用

つまり、`AUX` がないからといって、**有線LANで研究データを同期する** という設計は、`CNAP Monitor 500` では基本的に取りにくいです。

#### 有線 LAN

`CNAP Monitor 500` のこの manual では、Ethernet は **service 用** です。

したがって、

- PC と LAN でつないで研究データを取る
- UDP や SSH で同期する

といった方針は、この機種の manual ベースでは採用しにくいです。

#### 無線 LAN

この manual では、`CNAP Monitor 500` 本体が **Wi-Fi で直接 PC にデータを送る** ことも確認できません。

なので、

- `CNAP 本体 -> 無線LAN -> PC`

を前提にした設計も、少なくとも公開 manual ベースではおすすめできません。

### 5.3.2 修士向けにもっと簡単に言うと

すごく簡単に言うと、

- `AUX` があれば波形を取りやすい
- `AUX` がなければ `USB記録` が次の現実的候補
- `有線LAN` や `無線LAN` は、この manual では研究用主経路にしにくい

です。

つまり、AUX がない場合のおすすめ順は、

1. `USBでCSV記録`
2. `BP Wave Out / 互換機器経由`
3. `AUX analog out を準備する`

です。

### 5.3.3 AUX がない場合のおすすめ判断

現時点でのおすすめは次です。

- まず実機 menu で `CSV File` または `Advanced` record の有無を確認する
- `AUX` が使える個体かどうか確認する
- `AUX` がないなら、`USBでCNAP記録 + AndroidからPC送信` を第一候補にする
- `Ethernet` と `無線LAN` は、この manual では主経路にしない

### 5.3.4 有線LANでこのPCに同期するときの現実的な手順

この manual ベースでは、**有線LANでこのPCに研究同期する手順は作りにくい**です。

理由は単純で、

- `Ethernet port is restricted for service purposes only`

と明記されているからです。

つまり、前に想定していたような

- `CNAP Ethernet -> LAN to USB -> Mac`
- `PC上で同期を取る`

という流れは、`CNAP Monitor 500` では正式手順として置きにくいです。

この PC 側には `en8`, `en18`, `en19` の Ethernet アダプタ候補がありますが、**CNAP 側 manual が service 限定としている以上、PC 側設定だけで研究同期の経路にするのはおすすめできません**。

### 5.3.5 この方法の限界

ここで重要なのは、

- `CNAP Monitor 500` の manual
- `より新しい別機種 / 別プラットフォーム` の資料

を混ぜないことです。

`Monitor 500` の manual では Ethernet は service 用です。

したがって、この機種については

- LAN 同期
- LAN 経由 live export
- 無線 LAN 連携

を主ルートとして設計しない方が安全です。

### 5.4 CSV 記録について

公開マニュアルでは、

- `CSV File` は optional feature
- CSV には `CNAP waveform and beat-to-beat values`, `NBP`, `pulse rate`, `time`, `interventions`, `measurement parameters`

が含まれるとされています。

つまり、**もし手元の装置で CSV 記録ライセンスが有効なら、かなり使いやすい**です。

逆に言うと、

- そのライセンスが有効か
- いまの施設の装置で CSV 記録メニューが出るか

は最初に確認した方がよいです。

### 5.5 注意点

公開マニュアルでは、calibration 中や change finger 中に、

- BP Wave Out / AUX Analog Out に矩形の calibration signal が出る
- これは生理信号ではない

と書かれています。

これはかなり重要です。

つまり、

- calibration 中の出力をそのまま学習に入れてはいけない
- `recalibration_event` を記録して、その前後を除外できるようにするべき

です。

---

## 6. 「同時にリアルタイムで計測したい」をどう考えるか

### 6.1 できることを分けて考える

「同時にリアルタイムで計測したい」は、研究では次の 3 段階に分けて考えると整理しやすいです。

#### レベル1

- Android と CNAP を同じ時間に動かす

これはすぐ可能です。

#### レベル2

- Android と CNAP の時刻をちゃんと合わせる

これもかなり現実的です。
ここまでは、今の研究で強くおすすめです。

#### レベル3

- CNAP の値をその場で Android 側に直接流し込む

これは一気に難しくなります。

理由は、

- CNAP の USB は flash drive 用の記載が中心
- Ethernet は `Monitor 500` では service 用
- medical device なので安全面と絶縁を無視できない

からです。

### 6.2 いちばんおすすめの実装レベル

いちばんおすすめなのは、**PC ハブ方式でレベル2.5を作ること**です。

ここでいうレベル2.5とは、

- Android と CNAP の両方を PC にリアルタイム送信する
- PC 側で軽い同期をその場で行う
- ただし raw データも必ず別保存する

という形です。

これは、完全な live integration ほど重くなく、
でも単なる後処理よりかなり自動化が進んでいる中間形です。

この方式の良いところは、

- 実験中にデータ欠損に気づける
- session 間違いが減る
- その場で仮同期済みデータを作れる
- でも最終的には raw に戻って再同期できる

ところです。

研究用途では、この「**live でも便利、final は raw 基準**」という考え方がかなり大事です。

### 6.3 具体的にどういう構成にするか

一番おすすめの構成は次です。

#### Android 側

- beat が確定したら 1 行ずつ構造化データを PC に送る
- 送るのは `CSV` より `JSON Lines` がよい

たとえば送る内容は、

- `session_id`
- `subject_id`
- `beat_index`
- `android_elapsed_ms`
- `android_unix_ms`
- `M1_*`
- `M2_*`
- `M3_*`
- `ISO`
- `exposure_time`
- `fps`
- `valid_flag`
- `event_type`

です。

#### CNAP 側

候補は 2 つあります。

1. `AUX analog out -> 絶縁付きDAQ -> PC`
2. `USB flash drive に CNAP を記録 -> PC に取り込み`

研究用途で扱いやすいのは `1` です。

理由は、

- 波形を連続信号として取りやすい
- 100 Hz で扱える
- sys/dia だけでなく waveform も取れる

からです。

#### PC 側

PC では、

- Android 受信プロセス
- CNAP 受信プロセス
- event recorder
- live synchronizer

を動かします。

そして session ごとに最低でも次の 4 つを保存します。

- `android_raw.jsonl`
- `cnap_raw.csv` または `cnap_raw.bin`
- `events.csv`
- `sync_manifest.json`

さらに便利なら、

- `merged_live.parquet`

のような「その場で同期した見やすいファイル」も作ります。

### 6.4 この方法はありか

結論として、**かなりあり**です。

しかも、今まで話していた

- Android 単独記録
- CNAP 単独記録
- 後で人が頑張って合わせる

より、かなり良いです。

理由は次の通りです。

- 人手のミスが減る
- session の取り違えが減る
- 実験直後に同期済みデータを見られる
- 欠損や異常がその場でわかる
- 長期的にデータが増えても回しやすい

### 6.5 もっといい方法はあるか

あります。  
ただし、「理想的だが重いもの」と「現実的で強いもの」を分けて考えるべきです。

#### いま一番おすすめ

**PC ハブ方式 + raw 別保存 + live 仮同期**

これは、

- 実装の重さ
- 研究としての堅さ
- 自動化のしやすさ

のバランスが一番良いです。

#### あまりおすすめしない方法

**Android に CNAP を直接入れる**

研究で本当に必要なのは、

- Android 画面に CNAP 値を出すこと

ではなく、

- Android 特徴量と CNAP 参照値を正しく対応づけること

です。

だから、直結は見た目は派手でも、研究の本質にはあまり効きません。

### 6.6 Android から PC へ送る方法は何がよいか

ここも重要です。

`logcat` でも試作はできますが、正式運用としては弱いです。

もっと良いのは、

- `TCP socket`
- `WebSocket`
- `UDP`
- `adb reverse` を使ったローカル socket

のどれかです。

今の研究用途なら、一番おすすめは

- **Android -> PC に `JSON Lines` を `TCP socket` で送る**

です。

理由は、

- 1 beat ごとに 1 レコードで扱いやすい
- パースが簡単
- system log と混ざらない
- session 単位で保存しやすい
- ack や再送の設計も入れやすい

からです。

### 6.7 live 同期で大事なこと

リアルタイム同期をやるときに一番大事なのは、

- **live で作った同期済みデータを最終真実にしない**

ことです。

必ず、

- Android raw
- CNAP raw
- events

を別々に保存し、それを最終的な source of truth にします。

live で作る同期済みデータは、

- 実験中の確認用
- 仮解析用

として使うのが安全です。

これは、後で

- 時刻ズレ補正
- calibration 除外
- 欠損補完

をやり直せるようにするためです。

---

## 7. やってはいけないこと

### 7.1 このまま何も直さず大量収集する

これはおすすめしません。

理由は、

- 後で整理できなくなる危険が高い
- `subject_id` や `timestamp` が弱いと再解析が苦しい

からです。

### 7.2 CNAP の calibration 区間を普通の生理データとして扱う

これは危険です。

公開マニュアルでは calibration 中の出力は矩形信号だと書かれています。

なので、

- calibration
- finger change
- recalibration

の前後は除外できる設計にすべきです。

### 7.3 CNAP に適当な USB 機器をつなぐ

公開マニュアルでは、

- USB は flash drive 用
- 高消費電力の機器や外部電源機器は不可

とされています。

なので、

- Android を直接つなぐ
- よくわからない USB デバイスをつなぐ

のような運用は避けた方がよいです。

### 7.4 医療機器の絶縁を軽く考える

Ethernet や analog out を使う場合は、

- 絶縁
- patient safety

を必ず考える必要があります。

研究用途でも、ここを雑にすると危ないです。

---

## 8. 今後のおすすめロードマップ

### Step 1

まずは app の保存形式を強くする。

やること:

- `session_id`, `subject_id`, `timestamp_ms`, `beat_index` を保存
- camera 情報を保存
- quality flag を保存

### Step 2

CNAP 側の運用を固定する。

やること:

- CSV export が有効か確認
- calibration / recalibration の運用ルールを決める
- `session_id` の付け方を Android と合わせる
- `500at` かどうか、AUX が使えるか確認する

### Step 3

Android と CNAP の自動結合を作る。

やること:

- PC 側に受信サーバを置く
- Android raw と CNAP raw を同じ `session_id` で保存
- `timestamp` と `event` で同期
- calibration 区間を除外

### Step 4

real-time sync を強くする。

候補:

- Android `JSONL over TCP`
- AUX analog out + DAQ
- もしくは USB 記録とイベント同期の強化

### Step 5

PC 側で live 仮同期ファイルを作る。

やること:

- `merged_live` を session ごとに作る
- 実験中に欠損やズレを表示する
- ただし final 解析では raw から再構成する

### Step 6

それでも必要なら live 表示まで考える。

ただしこれは、研究としては最後でよいです。

---

## 9. いまのおすすめ結論

いまの時点でのおすすめは次です。

- 推定式は大きく変えない
- `RTBP / sin(M) / sin(D)` は今のまま使う
- もっとデータを取る前に、保存形式と同期設計を直す
- `CNAP` とはまず「AUX があるなら PC ハブ方式、なければ USB 記録 + イベント同期」を目標にする
- Android に CNAP を直接食わせるのは後回しにする

研究として一番筋が良いのは、

- **Android は beat-level feature sender / logger**
- **CNAP は AUX があれば sender / logger、なければ独立ロガー**
- **PC が中央で Android raw を受け、CNAP raw と最終的に Python で再構成する**

という形です。

これが、いちばん安全で、再現性があって、自動化しやすいです。

---

## 10. 参考リンク

- CNSystems official product page  
  https://www.cnsystems.com/products/cnap-monitor-hd/
- CNSystems official technology page  
  https://www.cnsystems.com/technology/cnap-technology/
- Correct manual for CNAP Monitor 500  
  https://apccardiovascular.co.uk/wp-content/uploads/2026/02/CNAP_Monitor500_OPERATOR-MANUAL_v4-1_ebook.pdf

---

## 11. 補足

このメモの `CNAP` に関する技術的な説明は、主に公開されている製品ページと公開マニュアルの記載に基づいています。

一方で、

- いま手元の装置にどのライセンスが有効か
- CSV export が有効か
- `500at` かどうか
- AUX が本当に使えるか

は、**実機の menu と契約オプションを確認しないと最終確定できません**。

つまり、

- 原理的にできること
- あなたの研究室の個体で本当に使えること

は分けて考えるべきです。
