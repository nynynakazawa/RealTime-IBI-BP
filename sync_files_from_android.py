#!/usr/bin/env python3
"""
AndroidデバイスからPCへのファイル自動転送スクリプト（Python版）
USB接続時に実行して、AndroidデバイスのDownloadフォルダからPCの指定パスにファイルを転送
"""

import subprocess
import os
import sys
import re
from pathlib import Path
from collections import defaultdict

# 設定
ANDROID_SOURCE = "/storage/emulated/0/Download"
PC_DEST = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/Smartphone")

# 対象となるファイルパターン
FILE_PATTERNS = [
    r"(.+)_元データ\.csv$",
    r"(.+)_RTBP\.csv$",
    r"(.+)_SinBP_M\.csv$",
    r"(.+)_SinBP_D\.csv$",
    r"(.+)_Wave_Data\.csv$",
    r"(.+)_Training_Data\.csv$"
]

def check_adb():
    """ADBが利用可能か確認"""
    try:
        subprocess.run(["adb", "version"], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("エラー: ADBがインストールされていません。")
        print("Android SDK Platform Toolsをインストールしてください。")
        return False

def check_device():
    """デバイスが接続されているか確認"""
    try:
        result = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True)
        devices = [line for line in result.stdout.split('\n') if 'device' in line and 'List' not in line]
        return len(devices) > 0
    except subprocess.CalledProcessError:
        return False

def extract_base_name(filename):
    """ファイル名から共通部分（baseName）を抽出"""
    for pattern in FILE_PATTERNS:
        match = re.match(pattern, filename)
        if match:
            return match.group(1)
    return None

def sync_files():
    """ファイルを転送"""
    if not check_adb():
        sys.exit(1)
    
    if not check_device():
        print("エラー: Androidデバイスが接続されていません。")
        print("USB接続を確認し、USBデバッグが有効になっているか確認してください。")
        sys.exit(1)
    
    print("Androidデバイスからファイルを転送中...")
    
    # Downloadフォルダから対象ファイルを検索
    try:
        # 対象となるCSVファイルを検索
        result = subprocess.run(
            ["adb", "shell", f"find {ANDROID_SOURCE} -maxdepth 1 -type f \\( -name '*_元データ.csv' -o -name '*_RTBP.csv' -o -name '*_SinBP_M.csv' -o -name '*_SinBP_D.csv' -o -name '*_Wave_Data.csv' -o -name '*_Training_Data.csv' \\)"],
            capture_output=True,
            text=True,
            check=True
        )
        
        files = [line.strip() for line in result.stdout.split('\n') if line.strip()]
        
        if not files:
            print("転送するファイルが見つかりませんでした。")
            return
        
        # ファイル名の共通部分（baseName）でグループ化
        file_groups = defaultdict(list)
        for file_path in files:
            filename = os.path.basename(file_path)
            base_name = extract_base_name(filename)
            if base_name:
                file_groups[base_name].append(file_path)
        
        # 各グループのファイルを転送
        for base_name, file_list in file_groups.items():
            # PC側の保存先ディレクトリを作成
            pc_target_dir = PC_DEST / base_name
            pc_target_dir.mkdir(parents=True, exist_ok=True)
            
            print(f"転送中: {base_name} ({len(file_list)}ファイル)")
            
            # 各ファイルを転送
            for file_path in file_list:
                filename = os.path.basename(file_path)
                pc_file_path = pc_target_dir / filename
                
                try:
                    subprocess.run(
                        ["adb", "pull", file_path, str(pc_file_path)],
                        check=True,
                        capture_output=True
                    )
                    print(f"  ✓ {filename}")
                except subprocess.CalledProcessError as e:
                    print(f"  ✗ {filename} の転送に失敗しました")
            
            print(f"✓ {base_name} のファイルを転送しました: {pc_target_dir}\n")
    
    except subprocess.CalledProcessError as e:
        print(f"エラー: {e}")
        return
    
    print("転送完了")

if __name__ == "__main__":
    sync_files()

