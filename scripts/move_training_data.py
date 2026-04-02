#!/usr/bin/env python3
"""
各生データディレクトリにあるTraining_Data.csvファイルのうち、
Training_Dataフォルダに含まれていないものをTraining_Dataフォルダに移動するスクリプト
"""

import os
import shutil
from pathlib import Path

def main():
    base_dir = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/Smartphone")
    training_data_dir = base_dir / "Training_Data"
    
    # Training_Dataフォルダに既にあるファイル名のセットを作成
    existing_files = set()
    if training_data_dir.exists():
        for file in training_data_dir.iterdir():
            if file.is_file():
                existing_files.add(file.name)
    
    print(f"Training_Dataフォルダに既に存在するファイル数: {len(existing_files)}")
    
    # 各ディレクトリを走査してTraining_Data.csvファイルを探す
    moved_count = 0
    skipped_count = 0
    
    print("\n処理を開始します...")
    print("-" * 60)
    
    for dir_path in base_dir.iterdir():
        # Training_Dataフォルダ自体はスキップ
        if not dir_path.is_dir() or dir_path.name == "Training_Data" or dir_path.name.startswith('.'):
            continue
        
        # ディレクトリ内のTraining_Data.csvファイルを探す
        training_data_files = list(dir_path.glob("*Training_Data.csv"))
        
        for training_file in training_data_files:
            file_name = training_file.name
            
            # Training_Dataフォルダに既に存在する場合はスキップ
            if file_name in existing_files:
                print(f"スキップ: {file_name} (既にTraining_Dataフォルダに存在)")
                skipped_count += 1
                continue
            
            # Training_Dataフォルダに移動
            target_path = training_data_dir / file_name
            if target_path.exists():
                print(f"警告: {file_name} は既にTraining_Dataフォルダに存在します（上書きしません）")
                skipped_count += 1
                continue
            
            shutil.move(str(training_file), str(target_path))
            print(f"移動: {file_name} -> Training_Data/")
            moved_count += 1
    
    print("\n" + "=" * 60)
    print(f"処理完了:")
    print(f"  移動したファイル: {moved_count}個")
    print(f"  スキップしたファイル: {skipped_count}個")

if __name__ == "__main__":
    main()

