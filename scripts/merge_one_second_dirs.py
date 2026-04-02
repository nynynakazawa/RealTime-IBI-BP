#!/usr/bin/env python3
"""
1秒違いで分かれているディレクトリを統合するスクリプト
後ろのディレクトリ（秒数が大きい方）のファイルを前のディレクトリ（秒数が小さい方）に移動し、
空になった後ろのディレクトリを削除する
"""

import os
import shutil
from pathlib import Path
from collections import defaultdict

def parse_dir_name(name):
    """ディレクトリ名からプレフィックスとタイムスタンプを抽出"""
    parts = name.rsplit('_', 3)
    if len(parts) == 4:
        prefix = '_'.join(parts[:-3])
        hour, minute, second = parts[-3:]
        try:
            return prefix, int(hour), int(minute), int(second)
        except ValueError:
            return None
    return None

def find_one_second_pairs(base_dir):
    """1秒違いのディレクトリペアを探す"""
    base_path = Path(base_dir)
    dirs = [d for d in base_path.iterdir() if d.is_dir() and not d.name.startswith('.')]
    
    # プレフィックスごとにグループ化
    groups = defaultdict(list)
    for d in dirs:
        parsed = parse_dir_name(d.name)
        if parsed:
            prefix, h, m, s = parsed
            groups[prefix].append((h, m, s, d.name))
    
    # 1秒違いのペアを探す
    pairs = []
    for prefix, dir_list in groups.items():
        dir_list.sort(key=lambda x: (x[0], x[1], x[2]))
        for i in range(len(dir_list) - 1):
            h1, m1, s1, name1 = dir_list[i]
            h2, m2, s2, name2 = dir_list[i + 1]
            # 1秒違いかチェック
            if h1 == h2 and m1 == m2 and s2 == s1 + 1:
                pairs.append((name1, name2))
    
    return pairs

def merge_directories(base_dir, source_name, target_name):
    """後ろのディレクトリのファイルを前のディレクトリに移動"""
    base_path = Path(base_dir)
    source_dir = base_path / source_name
    target_dir = base_path / target_name
    
    if not source_dir.exists():
        print(f"警告: {source_name} が存在しません")
        return False
    
    if not target_dir.exists():
        print(f"警告: {target_name} が存在しません")
        return False
    
    # ソースディレクトリ内のファイルを移動
    moved_files = []
    for file_path in source_dir.iterdir():
        if file_path.is_file():
            target_file = target_dir / file_path.name
            if target_file.exists():
                print(f"警告: {target_file.name} は既に {target_name} に存在します。スキップします。")
                continue
            
            shutil.move(str(file_path), str(target_file))
            moved_files.append(file_path.name)
            print(f"移動: {file_path.name} -> {target_name}/")
    
    # ソースディレクトリが空になったら削除
    if not any(source_dir.iterdir()):
        source_dir.rmdir()
        print(f"削除: {source_name} (空になったため)")
        return True
    else:
        print(f"警告: {source_name} にまだファイルが残っています")
        return False

def main():
    base_dir = "/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/Smartphone"
    
    print("1秒違いのディレクトリペアを検索中...")
    pairs = find_one_second_pairs(base_dir)
    
    if not pairs:
        print("1秒違いのディレクトリペアが見つかりませんでした。")
        return
    
    print(f"\n{len(pairs)}組のペアが見つかりました:")
    for name1, name2 in pairs:
        print(f"  {name1} <-> {name2}")
    
    print("\n統合を開始します...")
    print("-" * 60)
    
    success_count = 0
    for name1, name2 in pairs:
        print(f"\n処理中: {name1} <- {name2}")
        if merge_directories(base_dir, name2, name1):
            success_count += 1
    
    print("\n" + "=" * 60)
    print(f"統合完了: {success_count}/{len(pairs)}組のディレクトリを統合しました")

if __name__ == "__main__":
    main()

