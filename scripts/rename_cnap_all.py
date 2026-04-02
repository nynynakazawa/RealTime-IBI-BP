#!/usr/bin/env python3
"""
CNAPから始まるbeats、summary、waveformファイルを、対応するsummaryファイルの17行目のNameを使ってリネームするスクリプト
"""

from pathlib import Path
import re

def extract_name_from_summary(summary_path):
    """
    summaryファイルからNameを取得（Entry Nameが"Name"で、Entry Valueが空でない行を探す）
    """
    try:
        with open(summary_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        for line in lines[1:]:  # ヘッダー行をスキップ
            parts = line.strip().split(';')
            if len(parts) >= 3:
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

def rename_file(file_path, name, base_name, file_type):
    """
    ファイルをリネーム
    file_type: 'beats', 'summary', 'waveform'
    """
    new_filename = f"{name}_{base_name}_{file_type}.csv"
    new_path = file_path.parent / new_filename
    
    if new_path.exists() and new_path != file_path:
        print(f"  スキップ: {file_path.name} → {new_filename} (既に存在します)")
        return False
    
    try:
        file_path.rename(new_path)
        print(f"  リネーム: {file_path.name} → {new_filename}")
        return True
    except Exception as e:
        print(f"  エラー: {file_path.name} のリネームに失敗: {e}")
        return False

def main():
    beats_dir = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/pdp/beats")
    
    # 対象ファイルのリスト
    target_files = [
        "CNAP_2025-11-20_17-45-40_001_summary.csv",
        "CNAP_2025-11-20_17-45-40_001_waveform.csv",
        "CNAP_2025-11-20_17-51-36_001_summary.csv",
        "CNAP_2025-11-20_17-51-36_001_waveform.csv",
        "CNAP_2025-11-20_17-56-53_001_beats.csv",
        "CNAP_2025-11-20_17-56-53_001_summary.csv",
        "CNAP_2025-11-20_17-56-53_001_waveform.csv",
        "CNAP_2025-11-20_18-00-54_001_beats.csv",
        "CNAP_2025-11-20_18-00-54_001_summary.csv",
        "CNAP_2025-11-20_18-00-54_001_waveform.csv",
        "CNAP_2025-11-20_18-03-55_001_summary.csv",
        "CNAP_2025-11-20_18-03-55_001_waveform.csv",
        "CNAP_2025-11-20_18-07-29_001_beats.csv",
        "CNAP_2025-11-20_18-07-29_001_summary.csv",
        "CNAP_2025-11-20_18-07-29_001_waveform.csv",
    ]
    
    print("=" * 60)
    print("CNAPファイルのリネーム処理")
    print("=" * 60)
    
    # ベース名ごとにグループ化
    base_to_files = {}
    for filename in target_files:
        base_name = get_base_name(filename)
        if base_name:
            if base_name not in base_to_files:
                base_to_files[base_name] = []
            base_to_files[base_name].append(filename)
    
    renamed_count = 0
    skipped_count = 0
    
    for base_name, files in sorted(base_to_files.items()):
        print(f"\n{base_name}:")
        
        # summaryファイルからNameを取得
        summary_file = beats_dir / f"{base_name}_summary.csv"
        if not summary_file.exists():
            # 既にリネームされている可能性がある
            existing_summary = list(beats_dir.glob(f"*_{base_name}_summary.csv"))
            if existing_summary:
                summary_file = existing_summary[0]
                # 既存のsummaryファイルからNameを抽出
                name_match = re.match(r"([A-Z0-9\s]+)_" + re.escape(base_name) + r"_summary\.csv", existing_summary[0].name)
                if name_match:
                    name = name_match.group(1).strip()
                    print(f"  既存のsummaryファイルからNameを取得: {name}")
                else:
                    name = extract_name_from_summary(summary_file)
            else:
                print(f"  エラー: summaryファイルが見つかりません")
                skipped_count += len(files)
                continue
        else:
            name = extract_name_from_summary(summary_file)
        
        if not name:
            print(f"  エラー: Nameを取得できませんでした")
            skipped_count += len(files)
            continue
        
        # 各ファイルをリネーム
        for filename in sorted(files):
            file_path = beats_dir / filename
            
            if not file_path.exists():
                print(f"  スキップ: {filename} (ファイルが存在しません)")
                skipped_count += 1
                continue
            
            # ファイルタイプを判定
            if "_beats.csv" in filename:
                file_type = "beats"
            elif "_summary.csv" in filename:
                file_type = "summary"
            elif "_waveform.csv" in filename:
                file_type = "waveform"
            else:
                print(f"  スキップ: {filename} (不明なファイルタイプ)")
                skipped_count += 1
                continue
            
            if rename_file(file_path, name, base_name, file_type):
                renamed_count += 1
            else:
                skipped_count += 1
    
    print("\n" + "=" * 60)
    print(f"処理完了: {renamed_count}個をリネーム, {skipped_count}個をスキップ")

if __name__ == "__main__":
    main()
