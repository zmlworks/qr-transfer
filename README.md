# QR分割転送 (電波を使わないデータ受け渡し) — QR Split Transfer

テキストや小さなファイルを、**電波を一切使わず**に画面とカメラだけで受け渡すアプリです。
送信側がデータを分割して QR コードのアニメーションとして表示し、受信側がカメラで読み取って復元・検証します。
スマホ ⇄ スマホ のほか、PC のブラウザ (付属の `web/sender.html`) → スマホ も可能です。

> このアプリは AI アシスタント **Grok Bot** が設計・実装しました (zmlworks の依頼による)。

## 使い方
- **送信**: テキストを入力、またはファイルを選ぶ (最大 2 MB) → チャンクサイズ・速度・誤り訂正を選んで「送信開始」。
  他アプリの「共有」から直接送ることもできます。表示中は画面を最大輝度に保ちます。
- **受信**: 送信側の QR にカメラを向けるだけ。進捗 (n / 総数)、欠けているチャンク (例 `欠落: 3, 7-9`) とマス目表示。
  揃うと CRC-32 を検証し、テキストはコピー、ファイルは保存 (SAF) / 共有できます。
- **PC → スマホ**: `web/sender.html` をブラウザで開く (オフラインの単一ファイル、外部読み込みなし、CSP で通信禁止)。
  アプリのトップ画面の「PC用Web送信機を共有」から、この HTML をスマホから PC へ渡すこともできます。

## 確認音 (acknowledgment tone)
受信側は **新しいチャンクを読むたびに 1900 Hz** の短いビープ、**完了時に 2850 Hz** を鳴らします。
送信側で「受信側の確認音で進める (マイク)」をオンにすると:
1. 表示中のコードに対する確認音が聞こえたら、待たずに次のコードへ進む (最大 3 周期で自動的に次へ)。
2. 確認済みのチャンクは次の周回で飛ばす (欠けた分だけ再送)。全部確認済みなのに完了音がなければ全体をもう一周。
3. 完了音を聞いたら送信を止める。

検出は Goertzel 法 (1 つの周波数だけを見る軽い DFT) にハン窓をかけ、「その周波数のエネルギーが全体の 45% 以上」を
2 ブロック (32 ms) 連続で満たしたら 1 回と数えます。話し声や雑音では反応しにくくしてあります。
オフのとき (既定) は全チャンクを連続ループするだけなので、マイク権限は不要です。Web 送信機も同じ方式に対応しています。

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
確認音は「受信側→送信側」の唯一の逆向き通信路で、これも電波を使いません。QR 自体は黒地に白ではなく白地に黒にしています
(反転 QR は読めない読み取り器が多いため)。暗いテーマはその周りだけです。

## Why this design (English, short)
Base45 maps 2 bytes to 3 characters of exactly the QR *alphanumeric* set (5.5 bits/char), ~97 % efficient vs. 75 % for
base64 in byte mode — about 30 % more data per code. Every frame carries ID/total/CRC so reception can start anywhere;
text is DEFLATE-compressed when it helps. Camera2 is used directly (CameraX would pull in AndroidX and add megabytes).
The acknowledgment tone is a radio-free back channel: Goertzel detection of 1900 Hz (ACK) / 2850 Hz (DONE) lets the
sender advance early, skip acknowledged chunks and stop when done; without a mic it simply loops all chunks.

## ビルド / テスト
```sh
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
node tools/test-web.js   # web/sender.html のプロトコル部と QR 生成を node で実行し、JVM テスト用の fixture を書き出す
```
JVM テスト: Base45 (RFC 9285 のテストベクタ)、分割/順不同・重複受信/再構成/CRC 検証、改ざん検出、ZXing でのエンコード→デコード往復
(英数字モードになることも確認)、**Web 送信機が作ったフレームと QR 画像を Java 側でデコード**、確認音の検出 (雑音・倍音・短い音で誤検出しないこと)。

## 既知の制限 / Known limitations
- 速度の目安: 400 B × 4 枚/秒 ≈ 1.6 KB/s (理想時)。数百 KB 以上は時間がかかります (上限 2 MB)。
- 読み取りはカメラの性能・画面の反射・距離に左右されます。モアレが出るときは少し離すか角度を変えてください。
- 確認音モードは静かな場所向け。スピーカー/マイクの特性や OS の音声処理によっては検出できないことがあります (その場合も単純ループとして動きます)。
- 確認音は「どのチャンクか」を伝えないため、受信の遅れが大きいと確認を前のコードと取り違えることがあります (取りこぼしは次の周回で補完)。
- 受信画面は縦向き固定。実機・エミュレータでの動作確認は未実施です (ビルド・単体テスト・lint のみ。初回は実機で確認してください)。

## ライセンス
MIT © 2026 zmlworks — [LICENSE](LICENSE)。ZXing (Apache-2.0)・qrcode-generator (MIT): [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
