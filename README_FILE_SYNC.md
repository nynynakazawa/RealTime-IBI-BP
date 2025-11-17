# AndroidデバイスからPCへのファイル自動転送

## 概要

Androidアプリから直接PCのファイルシステムに書き込むことはできません。そのため、USB接続時にADBコマンドを使用してファイルを転送するスクリプトを用意しました。

## 使用方法

### 1. 前提条件

- Android SDK Platform Tools（ADB）がインストールされていること
- AndroidデバイスがUSB接続されていること
- USBデバッグが有効になっていること

### 2. スクリプトの実行

#### Bash版（推奨）

```bash
./sync_files_from_android.sh
```

#### Python版

```bash
python3 sync_files_from_android.py
```

### 3. 自動実行（オプション）

macOSで自動実行するには、`launchd`を使用して定期実行を設定できます。

#### 設定ファイルの作成

`~/Library/LaunchAgents/com.nakazawa.sync_android_files.plist` を作成：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.nakazawa.sync_android_files</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/sync_files_from_android.sh</string>
    </array>
    <key>StartInterval</key>
    <integer>30</integer>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>
```

#### ロード

```bash
launchctl load ~/Library/LaunchAgents/com.nakazawa.sync_android_files.plist
```

これで30秒ごとに自動的にファイルが転送されます。

## ファイルの保存先

- **Android側**: `/sdcard/Download/PC_Sync/Analysis/Data/Smartphone/{baseName}/`
- **PC側**: `/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/Smartphone/{baseName}/`

## トラブルシューティング

### ADBが見つからない

```bash
# Homebrewでインストール
brew install android-platform-tools
```

### デバイスが認識されない

1. USBデバッグが有効になっているか確認
2. USB接続モードが「ファイル転送」になっているか確認
3. `adb devices` でデバイスが表示されるか確認

### ファイルが転送されない

1. Androidアプリでファイルが保存されているか確認（`/sdcard/Download/PC_Sync/` フォルダを確認）
2. ファイルのパーミッションを確認
3. スクリプトのパスが正しいか確認

