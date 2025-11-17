#!/bin/bash

# AndroidデバイスからPCへのファイル自動転送スクリプト
# USB接続時に実行して、AndroidデバイスのDownloadフォルダからPCの指定パスにファイルを転送

# 設定
ANDROID_SOURCE="/storage/emulated/0/Download"
PC_DEST="/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/Smartphone"

# ADBが利用可能か確認
if ! command -v adb &> /dev/null; then
    echo "エラー: ADBがインストールされていません。"
    echo "Android SDK Platform Toolsをインストールしてください。"
    exit 1
fi

# デバイスが接続されているか確認
if ! adb devices | grep -q "device$"; then
    echo "エラー: Androidデバイスが接続されていません。"
    echo "USB接続を確認し、USBデバッグが有効になっているか確認してください。"
    exit 1
fi

echo "Androidデバイスからファイルを転送中..."

# Downloadフォルダから対象ファイルを検索
adb shell "find $ANDROID_SOURCE -maxdepth 1 -type f \( -name '*_元データ.csv' -o -name '*_RTBP.csv' -o -name '*_SinBP_M.csv' -o -name '*_SinBP_D.csv' -o -name '*_Wave_Data.csv' -o -name '*_Training_Data.csv' \)" | while read -r file_path; do
    if [ -z "$file_path" ]; then
        continue
    fi
    
    # ファイル名を取得
    filename=$(basename "$file_path")
    
    # ファイル名から共通部分（baseName）を抽出
    # 例: "中澤1-1_17_39_05_元データ.csv" -> "中澤1-1_17_39_05"
    base_name=$(echo "$filename" | sed -E 's/_(元データ|RTBP|SinBP_M|SinBP_D|Wave_Data|Training_Data)\.csv$//')
    
    if [ -z "$base_name" ]; then
        continue
    fi
    
    # PC側の保存先ディレクトリを作成
    pc_target_dir="$PC_DEST/$base_name"
    mkdir -p "$pc_target_dir"
    
    # ファイルを転送
    echo "転送中: $filename -> $base_name/"
    adb pull "$file_path" "$pc_target_dir/$filename" 2>/dev/null
    
    if [ $? -eq 0 ]; then
        echo "  ✓ $filename"
    else
        echo "  ✗ $filename の転送に失敗しました"
    fi
done

echo "転送完了"

