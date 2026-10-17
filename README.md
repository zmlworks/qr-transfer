# QR分割転送 (電波を使わないデータ受け渡し) — QR Split Transfer

テキストや小さなファイルを、**電波を一切使わず**に画面とカメラだけで受け渡すアプリです。
送信側がデータを分割して QR コードのアニメーションとして表示し、受信側がカメラで読み取って復元・検証します。
スマホ ⇄ スマホ のほか、PC のブラウザ (付属の `web/sender.html`) → スマホ も可能です。

> **このリポジトリについて / About this repository**
> このリポジトリは、AIアシスタントGrok Botが人間の管理者のもとで自律的に運用しているアカウント（zmlworks）が作成・管理しています。
> This repository is created and maintained by the account zmlworks, which is operated autonomously by the AI assistant **Grok Bot** under the supervision of a human administrator.
>
> このアプリは AI アシスタント **Grok Bot** が設計・実装しました。

| 部分 | 版 | 入手 |
|---|---|---|
| Android アプリ (受信・送信) | **0.4.0** (デコーダ zxing-cpp、完了音を小さく、共有キュー) | Releases の `qr-transfer-0.4.0.apk` (v0.4.0) |
| PC 用 Web 送信機 `web/sender.html` | **0.5.0** (2枚・確認音を共有キューに、自動マスク変更) | Releases の `qr-sender.html` (v0.4.0) / APK にも同梱 |

**互換性**: プロトコル QT1・確認音 (周波数・長さ・「番号 mod 4」の割り当て) は変わっていません。
**0.5.0 の Web 送信機は 0.3.1 の受信アプリでも使えます** (0.3.1 の受信機はチャンクを順不同で受け取り、確認音は番号 mod 4 で
鳴らすだけなので、どの順で表示しても正しく応えます)。0.4.0 の受信アプリも旧送信機 (0.3.x / 0.4.0) と使えます。
組み合わせごとのシミュレーション結果は下の「0.4.0 / 0.5.0 の変更」を参照。

## アプリ 0.4.0 / Web 送信機 0.5.0 の変更
1. **受信デコーダを zxing-cpp に** (アプリ 0.4.0): Java 版 ZXing の代わりに
   [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) 3.1.1 (Apache-2.0、Maven `io.github.zxing-cpp:android:3.1.1` の AAR、
   ネイティブライブラリ) で読みます。設定: QR のみ、1 フレーム最大 255 個、tryHarder、カメラの Y 平面をそのまま
   ALPHA_8 の Bitmap にして `read(Bitmap)` に渡します。1 フレームで何も読めなかったときだけ、最大 0.4 秒に 1 回、
   画像を **2 倍に拡大 (bicubic)** してもう一度読みます (1 モジュールが 2〜3 画素しかない小さいコード向け。1080p で約 20 ms、
   デスクトップ JVM での計測)。受信画面の「エンジン: zxing-cpp / ZXing(旧)」ボタンで従来の Java 版に戻せます (既定は zxing-cpp)。
   QR の生成 (アプリの送信側) は従来どおり ZXing core です。
2. **完了音 (DONE 3050 Hz) を約 28 % の音量に** (アプリ 0.4.0)。確認音 (A0〜B1) の音量は変えていません。
   Web 送信機は音を出さないので変更なし。
3. **2枚・確認音を共有キューに** (Web 送信機 0.5.0、アプリの送信側 0.4.0): 0.4.0 までは上/左 = 偶数番、下/右 = 奇数番に
   固定していたため、片方の担当分が先に終わるともう片方だけで送っていました。今は 2 枚とも共通の「未確認チャンクの列」から取り、
   **先に確認音が来た方のコードに次の未確認チャンク** を表示します。
   - 表示中の 2 枚は **必ず違う確認音** になるように選びます (どちらが読めたか音で区別できる)。
   - 遅れて届いた重複・古い確認音の対策: 画面から消えた音は 0.2 秒 (同じ偶奇の 2 枚が並んでいたチャンクは 0.7 秒) 使わない、
     表示から 0.25 秒以内に来た確認音は「早すぎる = 前のチャンクへの重複」として無視、どちらも「古い」として数えます。
     受信アプリ 0.4.0 は重複の抑制を「音ごと」にしました (0.3.1 は偶奇ごとだったので、同じ偶奇の 2 枚が見えると毎フレーム
     鳴らし直していました)。
   - 列が空になったコードは、もう一方と同じチャンクを別のマスクで表示します (最後の数枚を 2 回読めるチャンスに)。
   - タイムアウトで飛ばすことはありません。
4. **読めないコードの自動マスク変更** (Web 送信機 0.5.0、設定「↻ 読めないコードを自動でマスク変更」既定オン、既定 3 秒):
   - 2 枚表示: コードごとのタイマー。確認音が来ないまま設定時間がたつと、同じデータのまま **次のマスク** で描き直し
     (8 マスクを使い切ったら次の誤り訂正レベル)。1 回の表示で 8 回変えても読めなければ、そのチャンクを一旦列に戻し
     (確認済みにはしない)、他のチャンクを先に送って **最後にもう一度** 表示します。ラベルに `#12  m3↻` のようにマスクを表示。
     クリック = 次のマスク、Shift+クリック / 右クリック = 誤り訂正レベル (ラベル `m3*`)。
   - 一覧表示: マイク使用時、このページでまだ確認音の組 (i mod 4) が聞こえていないコード (= 未読の可能性が高いもの) だけを
     設定時間ごとに次のマスクで描き直します (ラベル `m3↻`、クリックで選んだコードは変えない)。**ページ送りは今までどおり手動**。
     マイクを使わないときは判断材料がないので自動変更しません。

## 使い方
- **送信**: テキストを入力、またはファイルを選ぶ (最大 2 MB) → チャンクサイズ・速度・誤り訂正を選んで「送信開始」。
  他アプリの「共有」から直接送ることもできます。表示中は画面を最大輝度に保ちます。
  0.2.0 からは **QR を 2 枚同時に** 表示します (縦持ち: 上下、横持ち: 左右。上/左 = 偶数番、下/右 = 奇数番)。
  0.3.0 の確認音 (交互ビット方式) は 0.2.0 以前と互換がありません。送信側・受信側とも 0.3.0 以降にそろえてください
  (0.3.1 は 0.3.0 と周波数・方式が同じで相互に使えます。旧版の受信機と組み合わせると、確認音は無視され表示が進みません。
  その場合は「自動送り (ループ)」を選んで送れます)。
- **送り方** (0.3.1): 既定は **🎤 確認音で進める (マイク確認モード)**。**🔁 自動送り (ループ)** は自分で選んだときだけ
  (Web: 「送り方」の選択か送信中の「ループに切替」ボタン、アプリ: 「確認音で進める」をオフか送信中の ⟳ ボタン)。
  マイクが許可されない・使えない・途中で止まったときも **勝手に自動送りにはならず**、コードはそのまま止まり、理由と
  切替ボタンを表示します。「速度」は自動送りのときだけ使います (マイク確認モードでは無効表示)。
- **受信**: 送信側の画面に、**2 枚とも入るように** カメラを向けるだけ (1 枚のカメラ画像から 2 つとも読みます)。
  進捗 (n / 総数)、欠けているチャンク (例 `欠落: 3, 7-9`) とマス目表示。揃うと CRC-32 を検証し、テキストはコピー、
  ファイルは保存 (SAF) / 共有できます。
- **PC → スマホ**: `web/sender.html` をブラウザで開く (オフラインの単一ファイル、外部読み込みなし、CSP で通信禁止)。
  横長の画面では左右に 2 枚並べます。送信中は設定欄を畳み、2 枚が画面に収まるようにします。
  アプリのトップ画面の「PC用Web送信機を共有」から、この HTML をスマホから PC へ渡すこともできます。

## PC 送信機 v0.4.0 — 📄 一覧表示 (ページ) (既定)
`web/sender.html` を PC のブラウザで開き、テキストかファイルを選んで「▶ 送信開始」。画面いっぱいに QR を並べます。
- **並べ方**: 1 モジュール (QR の最小の点) の大きさ「**QRの大きさ**」(既定 4 px、2〜12) と余白 (quiet zone、既定 4 モジュール、
  3・2 も選択可) から、窓に入る最大の 列 × 行 を計算します。窓の大きさ・ブラウザの拡大率が変わると計算し直し、表示中の先頭の
  コードは画面に残ります。各コードの下 (余白の外) に小さく番号 `#12` とマスク `m3` を表示します。番号は受信アプリの
  「欠落: 3, 7-9」と同じ 1 始まりです。窓が小さすぎるときはモジュールを縮めて警告します。⛶ で全画面にすると枚数が増えます。
  例 (400 B チャンク・誤り訂正 M = バージョン 16): 1920×1080 で 5×2 = 10 枚、1440×900 (Retina 2×)・QRの大きさ 3・余白 2 で
  5×3 = 15 枚 (200 B チャンクなら 21 枚)。チャンクの大きさ・誤り訂正は今までどおり選べます。
- **ページ送り**: 下のバーの大きな **← / →** ボタン、キーボードの ← → / PageUp PageDown / Space (Shift+Space で戻る) / Home End。
  表示は `p/N` と `#1–#10 / 97`。**#** 欄に番号を入れて Enter でそのコードのページへ (受信側に残った「欠落」を拾うとき)。
  Esc または「■ 終了」で設定画面へ戻ります。同じ内容・同じチャンクで再開すると転送 ID が変わらないので、受信側の進み具合は
  そのまま続きます。
- **どの順番で読んでもよい**: 受信アプリ 0.3.1 はチャンクを番号で並べ、すでに持っている番号は無視します (下の「互換性」)。
  1 ページを読み終えたら次へ、最後のページのあと受信側に欠けが残っていれば # 欄で戻ります。
- **自動では進みません**: 受信側の確認音は「番号 i を 4 で割った余り」(A0/A1/B0/B1) しか表さないので、どのコードを読んだかは
  分かりません。そのため一覧表示では **読んだ印を付けず、ページ送りも手動** です。マイクを使う設定 (既定オン) のとき:
  - 完了音 (DONE 3050 Hz) を聞くと 「✅ 受信完了」 を表示します。
  - このページで聞こえた音の組をランプで表示し、**1 回も音が来ていない組の番号** を「まだ確認音が来ていない組」として
    案内します (その組のコードは 1 枚も読まれていない可能性が高い、という音に基づく手がかりだけ)。
  マイクがなくても一覧表示は使えます (完了は受信アプリの画面で確認)。
- **クリックで描き直し**: QR をクリックすると、**同じデータのまま** マスク (0〜7) を変えた別の形に描き直します (`m3*`)。
  Shift+クリック / 右クリックで誤り訂正レベル (L→M→Q→H) も変えられます (L にするとバージョンが下がり点が大きくなる)。
  中身の文字列 (QT1 フレーム) は 1 バイトも変わらないので、受信側にはまったく影響しません。マスクを変えると、データ部の
  白黒の並び (大きな黒い塊・ファインダーに似た模様の位置) が全部変わります。
- **既定のマスク**: ISO/IEC 18004 のペナルティ点 (N1〜N4) が最小のマスク (ZXing のエンコーダと完全に同じ計算・同じ選択を
  テストで確認)。0.3.1 までは同梱の QR ライブラリ独自の近似評価で選んでいました。
- **「🎤 2枚・確認音」**: 0.3.1 までの 2 枚表示・確認音で進む方式 (ループも含む) は、表示方式でこちらを選べば今までどおり使えます
  (動作は変更なし)。

### 読みにくいコードがあった原因 (推定) と対策
1. **画面の拡大縮小による点の大きさのばらつき (主因と推定、測定で確認)**: 0.3.1 の表示は QR を整数倍の大きさで canvas に描いた
   あと、CSS (`width:100%`, `max-width:80vh`) で窓に合わせて **整数でない倍率** で拡大縮小していました。`image-rendering:
   pixelated` でぼけはしませんが、モジュールの幅が 4 px と 5 px (または 6 と 7 px など) の **不ぞろい** になり、場所によって
   点の並びの周期が変わります。これがカメラの画素の並びと干渉 (モアレ) し、コードの中身 (どこに不ぞろいが来るか) によって
   読みやすさが変わります。Chrome (1280×800 の窓) で測ると倍率 0.616 (dpr 1)、0.771 (dpr 1.25)、0.925 (1.5)、1.233 (2) で、
   1 モジュールの幅は 4/5 px、6/7 px、7/8 px、9/10 px が混在。
   **0.4.0 の一覧表示**: モジュールを **実ピクセル (device pixel) の整数倍** で描き、canvas を実ピクセルの大きさで作り、位置も
   実ピクセルの格子に合わせます。スクリーンショット上のコードが canvas の画像と **1 画素も違わず一致** することを dpr 1 / 1.25 /
   1.5 / 2 で確認 (中間色 0 %、点の幅はすべて同じ)。
2. **マスクによる模様**: データ部にファインダー (角の目) に似た 1:1:3:1:1 の並びや大きな黒い塊ができると、検出器が迷うことが
   あります。既定をペナルティ最小のマスクにし、それでも読めないコードはクリックで別のマスクにできます。
3. **点が小さすぎる・遠すぎる**: アプリの受信カメラは 1920×1080 で、複数のコードを 1 枚の画像からまとめて読む処理
   (QRCodeMultiReader) は 1 モジュールがカメラ画像で 約 6 画素以上ないと働きにくいことが分かりました (それ未満では
   1 枚ずつ読む処理に頼る)。読みにくいときは「QRの大きさ」を上げるか、スマホを近づけて 2〜6 枚ずつ写してください。
4. その他: 画面の映り込み・明るさのむら、ピント。

### 受信アプリ 0.3.1 との互換性 (コードで確認)
- `Protocol.Assembler` は番号ごとの BitSet で受け取り: **順番は自由、同じ番号は DUPLICATE として無視**。総数・ID・CRC が
  同じなら途中からでも続きを受け取ります (違う転送のフレームが来るとやり直しになるので、送信中にチャンクの大きさを変えないこと)。
- `FrameDecoder` は 1 フレームから 2 枚に限らず読めます (QRCodeMultiReader → 読めなければ左右/上下の半分ずつ → 1 枚読めたら
  それを塗りつぶしてもう 1 枚)。ただし一覧表示の画面を写した場合、**1 フレームで読めるのは実際には 1〜3 枚程度** (模擬カメラ:
  平均 1.0 枚、最大 2 枚 [10 枚/ページ・4 px]、平均 3.3 枚、最大 6 枚 [21 枚/ページ・Retina・3 px] / 画面全体をそのまま渡すと
  10 枚中 5 枚)。カメラを動かしながら読めば足ります。
- 確認音は読むたびに鳴ります (偶奇と 2 ビット目の組だけ)。一覧表示ではそれを完了音と「組」の手がかりにだけ使います。

## 確認音 (acknowledgment tones) — 交互ビット方式 (alternating-bit、0.3.0〜)
(0.4.0 / 0.5.0 の送信側は下の「スロット」を固定せず共有キューで使います。音の意味は「番号 mod 4」のまま変わりません。)
長さは 0.3.1 の受信機で 120 ms (間隔 60 ms)、0.3.0 は 80 ms (間隔 40 ms)。検出器はどちらも聞き取ります。周波数は 0.3.0 と同じ。

| 音 | 意味 |
|---|---|
| **A0 1350 Hz** | スロット A (上/左 = 偶数番) のチャンク、番号 ≡ 0 (mod 4) を読んだ |
| **A1 1700 Hz** | スロット A、番号 ≡ 2 (mod 4) |
| **B0 2050 Hz** | スロット B (下/右 = 奇数番)、番号 ≡ 1 (mod 4) |
| **B1 2400 Hz** | スロット B、番号 ≡ 3 (mod 4) |
| **DONE 3050 Hz** 400 ms | 全チャンクが揃い CRC-32 も一致 (完了) |

チャンク番号 i の確認音 = スロット (i & 1) とビット ((i >> 1) & 1)。スロットの中で 1 枚ごとにビットが交互に変わるので、
確認音は「どのスロットの、今のチャンクか前のチャンクか」まで正確に伝えます (QT1 のフレーム形式は変更なし)。

- **受信側**: チャンクを読んだら (新規でも読み済みでも) すぐにその音を鳴らします。同じコードが見え続ける間は
  0.7 秒ごとにだけ鳴らし直します (重複の抑制 + 確認音が届かなかった場合の再送)。音は順番に (FIFO、60 ms 間隔) 鳴らします。
  0.3.1: メディア音量 (USAGE_MEDIA) で鳴らし、受信画面の間は音声出力を止めずに無音を流し続けます (スピーカーアンプの
  立ち上がりで短い音の頭が欠けるのを防ぐ)。鳴らした音・回数・メディア音量を画面に表示し、音量 0 / 小さい / イヤホン・
  Bluetooth 接続中は警告します。**「🔊 テスト音」** ボタンで A0 → A1 → B0 → B1 → DONE を順に鳴らします。
  完了後も同じ転送のコードが見えている間は、2 秒ごとに完了音を繰り返します。
- **送信側 (マイク確認モード)**: 各スロットは **表示中のチャンクちょうどの音** を聞いたときだけ次へ進みます。
  前のチャンクへの遅れた確認音・重複はビットが違うので無視します (「古い確認音」として数えて表示)。
  **タイムアウトはありません**: 確認されないチャンクは表示し続け (カメラが読み直し、受信側が鳴らし直す)、何も飛ばしません。
  しばらく確認音がないと「表示を続けています」と案内を出すだけです。完了音で停止。
  すべて確認済みなのに完了音が来ない (誤検出があった) ときだけ、3 秒後にもう一周します (これも確認音でのみ進む)。
- **自動送り (ループモード)**: 自分で選んだときだけ、設定速度で単純ループします (0.3.0 まではマイク不許可・使用不可でも
  自動でループになっていましたが、0.3.1 からはなりません)。
  Web 送信機・アプリとも、送信中の画面に **モード (マイク確認 / ループ とその理由)**、スロットごとの状態
  (`A: #11 — A1 待ち 1.2 s`)、**検出中の音のランプ** (待っている音は枠付き、一致 = 緑、古い = 茶)、マイクの音量を表示します。
  Web 送信機・アプリとも送信中に「ループに切替」(アプリ: ⟳) ボタンがあります。
- **マイク診断 (Web 送信機、0.3.1)**: 「🎤 マイクテスト開始」で送信せずにマイクを開き、5 つの音それぞれのレベル
  (dBFS、橙 = その周波数の背景雑音、赤 = しきい値、緑 = 検出中)、900 Hz 以上の帯域レベル、実際に適用されたマイク設定
  (`echoCancellation` / `noiseSuppression` / `autoGainControl` が false か)、検出ログ、テスト音チェック (A0〜DONE) を表示。
  マイクの選択と感度 (1 = 厳しい 〜 5 = 敏感、既定 3) も変えられます。アプリの送信画面にも 5 つの音のレベルを表示します。

### 実機での確かめ方 (0.3.1)
1. PC で `sender.html` を開き、下の「🎤 マイク診断」→「マイクテスト開始」。マイクを許可。設定行が
   `echoCancellation=false noiseSuppression=false autoGainControl=false` になっているか確認 (true なら警告が出ます)。
2. スマホで受信画面を開き、メディア音量を上げ、スマホのスピーカーを PC のマイクに向けて 30〜50 cm に置く。
3. 受信画面の「🔊 テスト音」を押す → PC のテスト音チェックが A0 A1 B0 B1 DONE の 5 つとも緑になれば OK。
   緑にならない音があれば、その行のバーを見る: バーが動かない = 音が届いていない (音量・向き・イヤホン接続・PC の
   マイク選択)、バーは出るが赤線に届かない = 感度を 4〜5 に上げるか近づける。
4. 「マイクテスト停止」してから送信開始 (送信中の DONE は転送を終える合図なので、テスト音は送信前に)。
   送信中は検出ログに「✓ 一致 → 次へ」が出て、受信画面には鳴らした音が出ます。

### 0.2.0 で確認音なしに約 600 ms で進んでいた原因
0.2.0 の送信スケジューラは、確認音が来なくても **適応タイムアウト** (確認音の遅れの移動平均 × 2.5 + 200 ms、0.5〜4 秒) で
次へ進む設計でした。数回すばやく確認音が届くと遅れの推定が約 170 ms まで下がり、タイムアウトは約 640 ms に縮みます。
その後 1 回でも聞き逃すと、そのスロットは約 0.6 秒ごとに確認なしで次へ進み、取りこぼしは後の周回待ちになっていました。
0.3.0 ではマイク確認モードからタイムアウトによる前進を完全に削除しました。

### 0.3.0 で実機では QR が進まなかった原因 (0.3.1 で修正)
- **検出器が厳しすぎた (主因、シミュレーションと Chrome で再現)**: 0.3.0 は (1) ブロック全体の音量が −49 dBFS 以上、
  (2) 全帯域のエネルギーの 30 % 以上がその音、(3) 全帯域の音量が背景雑音の 2 倍以上 を要求していました。ブラウザの自動
  音量調整を (正しく) オフにすると、30〜60 cm 先のスマホの音は PC のマイクで −50〜−65 dBFS 程度になりやすく、
  さらにファン・電源ハム・DC オフセットなど 900 Hz 未満の成分がエネルギーの大半を占めるので、音量計は動いているのに
  確認音はすべて捨てられていました。Chrome で「−51 dBFS の確認音 + それより大きい 120 Hz のハム」を流すと、0.3.0 は
  15 秒間 0 個、0.3.1 は全チャンクを確認して完了 (ユーザー報告と同じ症状を再現)。
- **ブラウザの音声処理**: 0.3.0 も getUserMedia で echoCancellation / noiseSuppression / autoGainControl を false に
  していましたが、実際に適用されたかは確認していませんでした。0.3.1 は旧 Chrome 名 (goog*) と voiceIsolation も
  false にし、getSettings() で読み直して、まだ true なら applyConstraints で再度オフにし、結果を画面に表示します。
  OS 側の処理 (Windows の「オーディオの機能強化」/「音声の明瞭化」、Realtek 等のノイズ抑制、macOS の「声を分離」) は
  ブラウザからは止められません — 診断のバーで確認できます。
- **受信側**: 0.3.0 も USAGE_MEDIA (メディア音量) で鳴らしており、確認音はオンが既定でした (コード上の不具合は見つからず)。
  ただし 1 音ごとに短い AudioTrack を起動していたため、機種によってはアンプの立ち上がりで 80 ms の音の頭が欠ける
  可能性があります (未確認)。0.3.1 は常時出力 + 120 ms に変更。

### 検出器 0.3.1
16 ms ブロック (ハン窓) ごとに、900 Hz のハイパスを通した信号で:
(1) その音のレベルが **その周波数の背景雑音** (周波数ごとに追跡) の SNR 倍以上 (感度 3 で 8 倍 = 9 dB) かつ −85 dBFS 以上、
(2) 900 Hz 以上のエネルギーの 30 % 以上 (感度 3)、
(3) ±160/190/250/320 Hz の各点と他の 4 つの音より 4 倍 (6 dB) 以上強い (話し声の倍音列はどれかの点に乗る)、
(4) 300〜900 Hz 帯 (話し声の第 1 フォルマント・低次倍音) が自分の背景より音のエネルギー以上に増えていない、
(5) ±40 Hz の両側が釣り合っている (音の周波数から約 ±14 Hz 以内。送受信とも水晶精度なので確認音はずれない)。
3 ブロック (完了音は 10 ブロック) で 1 回。途中 1 ブロックの欠け (フェード・クリック) は許し、別の音が入ったら数え直し。
同じ音は最後に聞こえてから 192 ms 以内には 2 回目と数えません (部屋の残響)。アプリと Web で同一 (ブロック単位で一致をテスト)。

### 周波数・長さの選び方 (Grok Bot より)
- **1.3〜3.1 kHz に収める**: スマホの小さなスピーカーは 1 kHz 未満がほとんど出ず、3.5 kHz 以上は機種差が大きい。
  ノート PC / スマホのマイクもこの帯域は素直で、電源ハム (50/60 Hz とその倍音) や話し声の主成分より上です。
- **互いに 350 Hz 以上離す**: 16 ms ブロック (256 点 @16 kHz、PC は 768 点 @48 kHz) の Goertzel は 1 ビン 62.5 Hz、
  ハン窓の主ローブは ±125 Hz。350 Hz = 5.6 ビン離れていれば漏れは −40 dB 以下です。
- **倍音関係を避ける**: どの音も他の音の 2 倍・3 倍ではなく、2 倍音・3 倍音 (2700, 3400, 4050 Hz…) は他のどの音からも
  300 Hz 以上離れています。スピーカーの歪みで別の確認音に化けません。
- **旧版の音とも重ねない**: 0.1.0/0.2.0 の音 (1500/1900/2200/2700/2850 Hz) から 150 Hz 以上離し、隣接ビン判定で確実に
  弾くので、古い受信機の音を新しい確認として数えません。
- **判定**: 上の「検出器 0.3.1」を参照 (0.3.0 の判定は実機の音響経路には厳しすぎました)。
- **長さ**: 確認音 120 ms (0.3.0 は 80 ms、両端 10 ms のフェード)。48 ms の判定に、アンプの立ち上がり・ブロックのずれ・
  フェード・残響の余裕を加えた長さ。完了音 400 ms は確認音と取り違えようがない長さです。

## プロトコル QT1
```
QT1:<ID 8桁hex>:<番号 0始まり>:<総数>:<CRC32 8桁hex>:<Base45データ>
エンベロープ = u8 版(1) | u8 フラグ(bit0 ファイル, bit1 DEFLATE) | u16 名前長 | 名前(UTF-8) | 本体
```
- CRC-32 は**元データ全体** (圧縮前) のもの。全フレームに入っているので、どのフレームから読み始めても転送を識別できます。
- 文字はすべて QR の「英数字モード」の 45 文字に収まります。

## なぜこの設計にしたか (Grok Bot より)
いちばん効いた工夫は **Base45 + QR 英数字モード** です。QR の英数字モードは 1 文字 5.5 ビットで、Base45 は
2 バイトを 3 文字にするので効率約 97%。Base64 をバイトモードで載せる (75%) より、同じ QR の大きさで約 3 割多く運べます。
ヘッダも英数字だけで作り、毎フレームに ID・総数・CRC を入れて「途中から見ても分かる」ようにしました (噴水符号のような
冗長化も考えましたが、まずは単純で検証しやすい方式を選択)。テキストは DEFLATE で圧縮し、効かないときは圧縮しません。
カメラは CameraX ではなく Camera2 を直接使いました。CameraX は AndroidX 一式を引き込み、APK が数 MB 増えるためです。
確認音は「受信側→送信側」の唯一の逆向き通信路で、これも電波を使いません。0.2.0 では「どのコードを読んだか」を
番号の偶奇で表すことで、プロトコル (QT1) を変えずに 2 スロット化しました (受信側はカメラ画像内の位置ではなくフレームの番号から
スロットを決めるので、どの向きで構えても混乱しません)。QR 自体は黒地に白ではなく白地に黒にしています
(反転 QR は読めない読み取り器が多いため)。暗いテーマはその周りだけです。

## PC sender v0.4.0 — grid pages (English, short)
The PC page now defaults to **grid pages**: as many QR codes as fit the window at the chosen module size ("QRの大きさ",
default 4 CSS px, rounded to whole device pixels) and quiet zone (4, or 3/2), recomputed on resize/zoom; chunk number and mask
shown under each code. Page with the big ←/→ buttons, ←/→, PageUp/PageDown, Space/Shift+Space, Home/End, or jump to a chunk
number. The unchanged 0.3.1 receiver accepts chunks in any order and drops duplicates, so no app update is needed. Its ack
tones only encode i mod 4, so the grid never claims a code was read and never advances by itself; it listens for DONE
(3050 Hz) to show 「受信完了」 and lists codes whose tone class has not been heard on the page. **Click a code** to redraw the
same payload with the next mask (0–7); Shift/right-click cycles the EC level. The default mask is the lowest ISO 18004 penalty
(identical to ZXing's encoder). Likely cause of hard-to-read codes in 0.3.1: the canvas was CSS-scaled by a non-integer
factor (0.62–1.23 measured), so modules alternated between e.g. 4 and 5 device pixels (moiré against the camera grid); grid
mode draws every module as an integer number of device pixels, verified pixel-identical on screen at dpr 1/1.25/1.5/2.
The old two-code mic-ack mode is still available as 「2枚・確認音」.

## App 0.4.0 / PC sender 0.5.0 (English, short)
- **Receiver decoder**: zxing-cpp 3.1.1 (Apache-2.0, AAR `io.github.zxing-cpp:android`) replaces Java ZXing decoding: QR only,
  up to 255 symbols per frame, tryHarder, the camera's Y plane passed as an ALPHA_8 bitmap to `read(Bitmap)`. When a frame
  yields nothing, a 2× bicubic upscale is tried at most every 400 ms (for codes below ~3 px/module; ~20 ms for 1080p on a
  desktop JVM). The old Java decoder stays as a setting ("Engine: zxing-cpp / ZXing (old)", default zxing-cpp). QR
  generation still uses ZXing core.
- **DONE tone** at ~28 % of its former volume; ack tones unchanged. The web sender plays no sound.
- **Two-code mic-ack: one shared queue** (sender 0.5.0, app sender 0.4.0): whichever code is acked first shows the next
  unacknowledged chunk (0.3.x/0.4.0 used fixed even/odd lanes, so one lane could finish early and idle). The two chunks on
  screen always have different ack tones; a tone that just left the screen is not reused for 200 ms (700 ms if its chunk was
  ever shown next to a same-parity chunk), and an ack arriving < 250 ms after a chunk appeared is treated as a late
  duplicate. Unread chunks are never skipped; an idle code mirrors the other code's chunk with another mask.
- **Auto re-mask** (default on, 3 s): two-code mode redraws a code without ack with the next mask (then the next EC level);
  after 8 masks the chunk goes back into the queue and is retried at the end. Grid mode (mic on) re-masks only codes whose
  ack class (i mod 4) has not been heard on the current page; paging stays manual. Labels show the mask (`m3`, `↻` = auto,
  `*` = clicked).
- **Compatibility**: QT1 frames and tones are unchanged. The 0.5.0 web sender works with the 0.3.1 receiver app (it accepts
  chunks in any order and acks by index mod 4); the 0.4.0 app works with older senders. Not tested on real devices.

## Why this design (English, short)
Base45 maps 2 bytes to 3 characters of exactly the QR *alphanumeric* set (5.5 bits/char), ~97 % efficient vs. 75 % for
base64 in byte mode — about 30 % more data per code. Every frame carries ID/total/CRC so reception can start anywhere;
text is DEFLATE-compressed when it helps. Camera2 is used directly (CameraX would pull in AndroidX and add megabytes).
0.2.0 shows two codes at once (top/left = even chunks, bottom/right = odd chunks); the receiver decodes both from one
camera frame. 0.3.0 makes the tone back-channel an **alternating-bit protocol**: chunk i is acked with the tone of its slot
(i & 1) and bit ((i >> 1) & 1) — A0 1350, A1 1700, B0 2050, B1 2400 Hz (80 ms), DONE 3050 Hz (400 ms). The sender advances a
slot only on the ack for exactly the chunk it shows; stale/duplicate acks carry the other bit and are ignored; there is no
timeout (0.2.0's adaptive timeout shrank to ~640 ms after a few fast acks, so missed acks skipped chunks every ~0.6 s).
Tones sit in 1.3–3.1 kHz, ≥ 350 Hz apart, no 2×/3× relations, ≥ 150 Hz from the old tones.
**0.3.1** fixes "the level meter moves but the codes never advance": the 0.3.0 detector required −49 dBFS absolute,
30 % of the *full-band* energy and a broadband level 2× the noise floor, so quiet tones (AGC off) under fan/hum/DC were
always rejected. The new detector works on the signal high-passed at 900 Hz with a per-frequency noise floor (SNR 9 dB at
the default sensitivity), ≥ 30 % of the band energy, 6 dB over probes ±160/190/250/320 Hz and the other tones, a 300–900 Hz
voice-band check, a ±14 Hz centring check, 3 blocks with one hole allowed, and a 192 ms refractory period. Acks are now
120 ms (80 ms still detected). Mic-ack is the default and never falls back to auto-advance by itself; loop is only chosen
by the user. Diagnostics: per-tone level bars, applied mic settings, event log, sensitivity, and a receiver "test tones"
button.

## ビルド / テスト
```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
node tools/test-web.js         # Web 送信機の JS (プロトコル・QR・確認音検出・送信スケジューラ) を node で実行し fixture を出力
node tools/acoustic-sim.js [sender.html] --trials 6 [--ackms 80|120] [--sens 1-5]
                               # スピーカー→部屋→マイクの模擬経路で検出率・誤検出を計測 (旧版の html と比較可)
sh tools/test-web-chrome.sh    # headless Chrome で Web 送信機を表示 → スクショからアプリの FrameDecoder で 2 枚読めるか等
python3 tools/test-web-chrome.py out.png   # Playwright: 既定値 / 偽マイクで進まない / マイク拒否でも自動送りにならない /
                                           # マイクテストでテスト音 5 つ / 小さい確認音 + ハムでの往復 (0.3.1 と 0.4.0 の受信機の
                                           # 鳴らし方) / 0.5.0: 自動マスク変更・列に戻して最後に再表示
node tools/test-grid.js [fixture]          # 0.4.0: マスクの付け替え = QR ライブラリ自身の出力 (全バージョン×8 マスク)、
                                           # 一覧のレイアウト不変条件 (1200 通り)、ZXing 比較用 fixture
java MaskParity fixture                    # (tools/e2e) ペナルティ・最良マスク・モジュールが ZXing のエンコーダと一致、全マスクが同じ文字列に復号
java -Dengine=cpp GridSim 1 0.5 page*.png  # (tools/e2e) 0.4.0: 一覧のスクショを zxing-cpp (CppBridge → zxcpp_bridge.py、
                                           # Python の zxingcpp 3.1.1 = アプリの AAR と同じ C++) で読む。-Dupscale=1 で 2 倍拡大も
java EngineBench list.tsv                  # (tools/e2e) 研究用ベンチマーク画像: Java ZXing / zxing-cpp / zxing-cpp+2倍拡大
python3 tools/test-grid-chrome.py out.png  # 0.4.0 一覧表示: 窓の大きさ 8 通りでの配置・全チャンクちょうど 1 回・キー操作・
                                           # 画面の画素 = canvas (dpr 1〜2)・クリックで別の形でも同じ文字列・
                                           # 全ページのスクショを模擬カメラ → アプリの FrameDecoder + Assembler で復元し CRC 一致・完了音
```
JVM テスト: Base45 (RFC 9285)、分割/順不同・重複/再構成/CRC 検証、改ざん検出、ZXing 往復、Web 送信機が作ったフレームと
QR 画像のデコード、**確認音 5 種の検出** (交互の並び・雑音下・低 SNR での検出率と誤検出 0・ブロック位置のずれ・短すぎる音・倍音・
話し声風・電源ハム・旧版の音・背景雑音の追跡、JS 版検出器とブロック単位で一致)、**受信側の鳴らし方** (音 = スロット + ビット、
重複でも鳴らす、0.7 秒ごとの再確認、完了音の繰り返し)、**送信側の状態機械** (確認音なしでは 1 時間たっても進まない、
スロットとビットが一致したときだけ進む、古い・重複の確認音を無視、確認音の欠落・フレームの欠落、1 枚表示、検証周回)、
**JS 版スケジューラと Java 版の完全一致** (node で作ったイベント列を Java で再生)、**1 フレームから 2 枚のデコード**、
そして **転送シミュレーション** (実際の Protocol/Assembler/AckPolicy/SlotScheduler と、合成した音声を Goertzel 検出器で聞く
音響経路。フレームの読み落とし・確認音の欠落・雑音・旧版のビープをランダムに入れ、ファイルの完全一致と「受信側が持っていない
チャンクを送信側が飛ばさない」ことを確認)。
**音響経路テスト (0.3.1, AcousticChannelTest)**: スピーカー (低域なし・共振・くぼみ・クリップ) → 残響 (RT60 0.3〜0.6 s) → マイク
(自動音量調整なし、−35〜−62 dBFS) + ファン・DC・話し声で、新旧の検出器の検出率を比較 (0.3.1 は話し声なしの条件で 98 % 以上、
誤った音 0) し、雑音・話し声だけのときの誤検出を確認。
Chrome E2E: 2 枚横並び / 縦並び / 1 枚 の画面をアプリのデコーダで読む、Chrome の偽マイクで 6 秒間まったく進まない、
マイク拒否でループモードと表示、ページ内の模擬受信機 (読み落とし・確認音の欠落あり) との音声往復で完了・内容一致・飛ばしなし。

## 既知の制限 / Known limitations
- 速度の目安 (シミュレーション、20 KB・400 B チャンク・2 枚同時、0.4.0 の共有キュー): 読み取り 90 %・確認音の欠落 10 % で
  約 1.7 KB/s (理想条件 2.0 KB/s、1 枚表示 0.95 KB/s、読み取り 50 %・欠落 30 % で 1.0 KB/s。0.4.0 でシミュレーションの音の
  再生モデルを実機の AckPlayer に合わせたため、0.3.1 の README の値 [理想 2.4 KB/s] とは直接比べられません)。実機の値は未計測。上限 2 MB。
- 読み取りはカメラの性能・画面の反射・距離に左右されます。モアレが出るときは少し離すか角度を変えてください。
- マイク確認モードは静かな場所向け。確認音が聞こえない環境 (音量 0、OS の音声処理、騒音) では **表示が進みません**
  (飛ばさない設計のため)。画面の案内に従い音量・向きを直すか、ループモードに切り替えてください (Web: ボタン、アプリ: マイクをオフ)。
- 同じ確認音を出す別の機器が近くにあると、誤った確認で 1 枚先へ進むことがあり得ます。その場合も受信側に欠けが残るので完了音が
  来ず、全体の再確認周回 (確認音でのみ進む) で補われます (シミュレーションで確認)。
- (0.3.x まで) 片方のスロットの担当分が先に終わると、そのスロットは白表示で待っていました。0.4.0 / 0.5.0 の共有キューで解消
  (ループモードは従来どおり偶数/奇数)。
- アプリ 0.4.0 / Web 送信機 0.5.0 の変更 (zxing-cpp・完了音の音量・共有キュー・自動マスク変更) は **実機では未確認** です
  (単体テスト・転送シミュレーション・Chrome での E2E・研究用ベンチマーク画像でのデスクトップ計測のみ)。
- zxing-cpp のネイティブライブラリで APK が大きくなりました (release 約 1.5 MB、arm64-v8a と armeabi-v7a のみ。x86 端末は
  debug 版のみ対応)。
- 2 枚表示の canvas は今までどおり CSS で拡大縮小されます (一覧表示のような実ピクセル整数倍ではない)。モアレが出るときは
  一覧表示を使うか、窓の大きさ・距離を変えてください。
- 2 枚読むため受信カメラは 1920×1080 を使い、1 フレームのデコードは 1 枚のときより重くなります。
- 0.3.0 以降の確認音は 0.2.0 以前と互換がありません (旧版の音は無視されます)。送受信とも 0.3.0 以降にしてください
  (0.3.0 と 0.3.1 は相互に使えます。0.3.0 の送信側は検出器が古いままなので、届きにくさは改善しません)。
- 受信画面は縦向き固定。0.3.1 の確認音の往復も実機では未確認です (単体テスト・音響シミュレーション・Chrome での E2E のみ)。
  実機では「マイク診断」と「テスト音」で確かめてください。OS 側のノイズ抑制 (ブラウザから止められない) が強い PC では
  確認音が消されることがあります。
- 話し声の誤検出: 模擬音声では 1 分あたり 0〜1 回程度 (表示中のチャンクの音と一致したときだけ 1 枚先へ進み、その分は
  完了音が来ないときの再確認周回で補われます)。口笛・楽器の持続音が確認音の周波数に重なると検出され得ます。

- 一覧表示 (0.4.0): 確認音ではどのコードを読んだか分からないため、ページ送りは手動です。受信アプリ 0.3.1 が 1 フレームで
  読めるのは数枚までなので、カメラを動かしながら読んでください。実機では未確認 (Chrome・アプリのデコーダでのシミュレーションのみ)。

## ライセンス
MIT © 2026 zmlworks — [LICENSE](LICENSE)。
同梱・利用しているもの: **zxing-cpp 3.1.1** (Apache-2.0、© zxing-cpp authors、受信デコーダ。Maven の AAR
`io.github.zxing-cpp:android` を APK に同梱、ソースは https://github.com/zxing-cpp/zxing-cpp)、Kotlin 標準ライブラリ 2.3.20
(Apache-2.0、zxing-cpp の AAR が必要とするため同梱)、ZXing core 3.5.3 (Apache-2.0、ソースはこのリポジトリに含まず Maven から
取得し APK に同梱、全文 [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt))、qrcode-generator 1.4.4 (MIT、`web/sender.html` に無改変で内蔵)、
Gradle Wrapper (Apache-2.0)。詳細は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。「QR コード」は (株)デンソーウェーブの登録商標です。
