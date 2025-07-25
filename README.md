# RealTime HR & IBI Control

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="App Icon" width="120" height="120">
  
  [![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com/)
  [![API](https://img.shields.io/badge/API-34%2B-brightgreen.svg)](https://android-arsenal.com/api?level=34)
  [![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
</div>

## 📱 概要

**RealTime HR & IBI Control** は、スマートフォンのカメラを使用してリアルタイムで心拍数（HR）と心拍間隔（IBI）を測定し、音楽・触覚フィードバック・強化学習を通じて自律神経系をコントロールするAndroidアプリケーションです。

## ✨ 主要機能

### 🔍 生体信号測定
- **PPG（光電容積脈波）測定**: フロントカメラを使用した非接触心拍測定
- **リアルタイム心拍数検出**: 2つの異なるアルゴリズム（Logic1/Logic2）による高精度測定
- **IBI（心拍間隔）計算**: 心拍変動解析のための精密な間隔測定
- **血圧推定**: PPGの形態学的特徴を用いたリアルタイム血圧推定（SBP/DBP）

### 🎵 音楽・触覚フィードバック
- **MIDI対応音楽再生**: 6種類の音楽モード（bass, musica, musicb, musicc, musicd）
- **動的テンポ調整**: 心拍数に基づくリアルタイムテンポ制御（±10%調整）
- **ハプティックフィードバック**: 音楽と同期した振動パターン生成
- **多彩な振動効果**: Android 12+でのComposition APIサポート

### 🤖 強化学習システム
- **DQN（Deep Q-Network）エージェント**: 4つの独立したエージェントによる学習
- **自律神経制御**: 交感神経・副交感神経優位モードの自動調整
- **経験再生バッファ**: 効率的な学習のための経験蓄積
- **リアルタイム適応**: 個人の生体反応に基づく刺激パターン最適化

### 📊 データ分析・記録
- **リアルタイムグラフ表示**: 心拍信号の可視化
- **CSV出力**: 心拍間隔・血圧データの詳細記録
- **統計解析**: 心拍変動の標準偏差・平滑化処理
- **カメラ情報取得**: ISO感度・露出時間・F値などの詳細情報

## 🎯 動作モード

| モード | 説明 | 効果 |
|--------|------|------|
| **Mode 1** | 基本測定モード | 生体信号の測定のみ |
| **Mode 2** | ランダム刺激モード | ランダムな振動パターンによる刺激 |
| **Mode 3** | Bass +10% | ベース音楽で心拍数を10%上昇 |
| **Mode 4** | Bass -10% | ベース音楽で心拍数を10%低下 |
| **Mode 5** | Musica +10% | クラシック音楽で心拍数を10%上昇 |
| **Mode 6** | Musicb +10% | ポップ音楽で心拍数を10%上昇 |
| **Mode 7** | Musicc -10% | アンビエント音楽で心拍数を10%低下 |
| **Mode 8** | Musicd -10% | リラクゼーション音楽で心拍数を10%低下 |
| **Mode 9** | 強化学習（交感神経優位） | AIによる交感神経活性化 |
| **Mode 10** | 強化学習（副交感神経優位） | AIによる副交感神経活性化 |

## 🏗️ アーキテクチャ

### コアコンポーネント

```
├── BaseLogicProcessor          # 心拍検出アルゴリズムの基底クラス
├── Logic1/Logic2              # 2つの異なる心拍検出実装
├── GreenValueAnalyzer         # カメラ画像からのPPG信号抽出
├── RealtimeBP                 # リアルタイム血圧推定
├── MidiHaptic                 # 音楽・触覚フィードバック制御
├── DQNAgent                   # 深層強化学習エージェント
├── IBIControlEnv              # 強化学習環境
└── RandomStimuliGeneration    # ランダム刺激生成
```

### 技術スタック

- **言語**: Java
- **プラットフォーム**: Android (API 34+)
- **カメラ**: CameraX API
- **機械学習**: TensorFlow Lite, DeepLearning4j
- **音楽処理**: Android MIDI Library
- **グラフ表示**: MPAndroidChart
- **信号処理**: JTransforms (FFT)

## 🚀 セットアップ

### 必要要件

- Android Studio Arctic Fox以降
- Android SDK 34以降
- Java 8以降
- 最小Android API レベル: 34

### インストール手順

1. **リポジトリのクローン**
   ```bash
   git clone https://github.com/yourusername/RealTimeHRIBIControl.git
   cd RealTimeHRIBIControl
   ```

2. **Android Studioでプロジェクトを開く**
   ```bash
   # Android Studioを起動し、プロジェクトフォルダを選択
   ```

3. **依存関係の同期**
   ```bash
   ./gradlew build
   ```

4. **デバイスまたはエミュレータで実行**
   - USBデバッグを有効にしたAndroidデバイスを接続
   - または、API 34以降のエミュレータを起動
   - Android Studioで「Run」ボタンをクリック

### 権限設定

アプリは以下の権限を必要とします：

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

## 📱 使用方法

### 基本操作

1. **アプリ起動**: カメラ権限を許可
2. **測定開始**: 
   - フロントカメラに指を軽く当てる
   - 「Start Recording」ボタンをタップ
3. **モード選択**: 
   - 「Mode Select」ボタンで希望するモードを選択
4. **データ記録**: 
   - 5分間の自動記録
   - CSV形式でダウンロードフォルダに保存

### 測定のコツ

- 📱 **安定した環境**: 明るく、振動の少ない場所で測定
- 👆 **指の位置**: カメラとフラッシュを軽く覆うように配置
- ⏱️ **測定時間**: 最低2-3分間の連続測定を推奨
- 🔋 **バッテリー**: 長時間測定時は充電しながら使用

## 📊 データ出力

### CSV出力項目

**心拍間隔データ (IBI_data.csv)**
- IBI (ms): 心拍間隔
- bpmSD: 心拍数標準偏差
- Smoothed IBI: 平滑化心拍間隔
- Smoothed BPM: 平滑化心拍数
- SBP/DBP: 収縮期・拡張期血圧
- Timestamp: タイムスタンプ

**グリーン値データ (Green.csv)**
- Green: PPG信号強度
- Timestamp: タイムスタンプ

## 🔬 アルゴリズム詳細

### 心拍検出アルゴリズム

**Logic1**: 6点移動平均 + 3倍増幅
```java
smoothingWindowSize = 6
windowValue = correctedGreenValue * 3
```

**Logic2**: 4点移動平均 + 動的正規化
```java
smoothingWindowSize = 4
normalizedValue = ((value - localMin) / range) * 100
```

### 血圧推定アルゴリズム

PPGの形態学的特徴を使用：
- **S**: 最大振幅（収縮期ピーク）
- **D**: 拍末振幅（拡張期レベル）
- **TTP**: Time-to-Peak（ピーク到達時間）
- **AI**: Augmentation Index = (S-D)/S

```java
SBP = α₁×S + α₂×D + α₃×TTP + α₄×AI + α₅×HR + β₁
DBP = β₁×S + β₂×D + β₃×TTP + β₄×AI + β₅×HR + β₆
```

### 強化学習アルゴリズム

**DQN (Deep Q-Network)**
- 状態空間: [IBI変化, 刺激効果, 重複フラグ]
- 行動空間: 振動パターンの組み合わせ
- 報酬関数: 目標IBI達成度に基づく報酬設計

## 🤝 貢献

プロジェクトへの貢献を歓迎します！

1. **Fork** このリポジトリ
2. **Feature branch** を作成 (`git checkout -b feature/AmazingFeature`)
3. **Commit** 変更内容 (`git commit -m 'Add some AmazingFeature'`)
4. **Push** ブランチ (`git push origin feature/AmazingFeature`)
5. **Pull Request** を作成

### 開発ガイドライン

- コードスタイル: Google Java Style Guide
- コミットメッセージ: Conventional Commits
- テスト: JUnit 4を使用
- ドキュメント: JavaDocコメントを必須

## 📄 ライセンス

このプロジェクトはMITライセンスの下で公開されています。詳細は [LICENSE](LICENSE) ファイルをご覧ください。

## 🙏 謝辞

- **MPAndroidChart**: グラフ表示ライブラリ
- **CameraX**: カメラAPI
- **TensorFlow Lite**: 機械学習フレームワーク
- **DeepLearning4j**: Java深層学習ライブラリ
- **Android MIDI Library**: MIDI処理ライブラリ

## 📞 サポート

- **Issues**: [GitHub Issues](https://github.com/yourusername/RealTimeHRIBIControl/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/RealTimeHRIBIControl/discussions)
- **Email**: your.email@example.com

## 🔄 更新履歴

### v1.0.0 (2024-01-XX)
- 初回リリース
- 基本的な心拍測定機能
- 音楽・触覚フィードバック
- 強化学習システム
- CSV出力機能

---

<div align="center">
  <p>Made with ❤️ for biometric research and wellness applications</p>
</div>