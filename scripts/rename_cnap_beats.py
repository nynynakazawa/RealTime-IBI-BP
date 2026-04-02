#!/usr/bin/env python3
"""
CNAPから始まるbeatsファイルを、対応するsummaryファイルの17行目のNameを使ってリネームするスクリプト
"""

import pandas as pd
from pathlib import Path
import re

def extract_name_from_summary(summary_path):
    """
    summaryファイルからNameを取得（Entry Nameが"Name"で、Entry Valueが空でない行を探す）
    """
    try:
        # CSVを手動で読み込む（パースエラーを回避）
        with open(summary_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        # 各行をパースして、Entry Nameが"Name"でEntry Valueが有効な行を探す
        for line in lines[1:]:  # ヘッダー行をスキップ
            parts = line.strip().split(';')
            if len(parts) >= 3:
                # クォートを除去
                entry_name = parts[1].strip().strip('"')
                entry_value = parts[2].strip().strip('"')
                
                if entry_name == "Name" and entry_value and entry_value != "NA" and entry_value != "":
                    return entry_value
        
        return None
    except Exception as e:
        print(f"エラー: {summary_path.name} の読み込みに失敗: {e}")
        return None

def get_base_name(filename):
    """
    ファイル名からベース名（CNAP_2025-11-20_17-24-57_001）を抽出
    """
    match = re.match(r"(CNAP_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_\d{3})", filename)
    if match:
        return match.group(1)
    return None

def main():
    beats_dir = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/pdp/beats")
    
    # CNAPから始まるbeatsファイルを探す
    cnap_beats_files = sorted(beats_dir.glob("CNAP_*_beats.csv"))
    
    if not cnap_beats_files:
        print("CNAPから始まるbeatsファイルが見つかりませんでした。")
        return
    
    print(f"{len(cnap_beats_files)}個のCNAP beatsファイルが見つかりました。")
    print("=" * 60)
    
    renamed_count = 0
    skipped_count = 0
    
    for beats_file in cnap_beats_files:
        base_name = get_base_name(beats_file.name)
        if not base_name:
            print(f"スキップ: {beats_file.name} (ベース名を抽出できませんでした)")
            skipped_count += 1
            continue
        
        # 対応するsummaryファイルを探す
        summary_file = beats_dir / f"{base_name}_summary.csv"
        
        if not summary_file.exists():
            print(f"スキップ: {beats_file.name} (対応するsummaryファイルが見つかりません: {summary_file.name})")
            skipped_count += 1
            continue
        
        # summaryファイルからNameを取得
        name = extract_name_from_summary(summary_file)
        
        if not name:
            print(f"スキップ: {beats_file.name} (summaryファイルからNameを取得できませんでした)")
            skipped_count += 1
            continue
        
        # 新しいファイル名を生成（スペースを含む可能性があるので、そのまま使用）
        new_filename = f"{name}_{base_name}_beats.csv"
        new_path = beats_dir / new_filename
        
        # 既に同じ名前のファイルが存在するかチェック
        if new_path.exists() and new_path != beats_file:
            print(f"スキップ: {beats_file.name} → {new_filename} (既に存在します)")
            skipped_count += 1
            continue
        
        # リネーム
        try:
            beats_file.rename(new_path)
            print(f"リネーム: {beats_file.name} → {new_filename}")
            renamed_count += 1
        except Exception as e:
            print(f"エラー: {beats_file.name} のリネームに失敗: {e}")
            skipped_count += 1
    
    print("=" * 60)
    print(f"処理完了: {renamed_count}個をリネーム, {skipped_count}個をスキップ")

if __name__ == "__main__":
    main()
