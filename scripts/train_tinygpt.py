#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
# MPS の上限水位制限を外す（0.0 にすると無制限）
os.environ["PYTORCH_MPS_HIGH_WATERMARK_RATIO"] = "0.0"

import sys
from multiprocessing import freeze_support, set_start_method

def setup_paths():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    tinygpt_dir = os.path.join(project_root, "tinygpt")
    for p in (project_root, tinygpt_dir):
        if p not in sys.path:
            sys.path.insert(0, p)
    return project_root, tinygpt_dir

def main():
    freeze_support()
    try:
        set_start_method("fork")
    except RuntimeError:
        pass

    project_root, tinygpt_dir = setup_paths()

    # 1. データ準備
    try:
        from tinystories import main as prepare_stories
        print("▶ データセットの準備を開始…")
        prepare_stories()
    except Exception as e:
        print(f"⚠️ tinystories の実行中に問題: {e}")

    # 2. cwd を tinygpt に
    os.chdir(tinygpt_dir)

    # 3. PyTorch 確認
    try:
        import torch
        print(f"using device: {torch.device('mps' if torch.backends.mps.is_available() else 'cpu')}")
    except ImportError:
        print("⚠️ PyTorch が見つかりません。モデル訓練をスキップします。")
        return

    # 4. モデル訓練呼び出し
    try:
        import tinygpt2
        if hasattr(tinygpt2, "train"):
            print("▶ tinygpt2.train() を実行…")
            tinygpt2.train()
        elif hasattr(tinygpt2, "main"):
            print("▶ tinygpt2.main() を実行…")
            tinygpt2.main()
        else:
            print("❌ tinygpt2 に train()/main() が見つかりません。")
    except RuntimeError as oom:
        # MPS OOM はここでキャッチして CPU にフォールバックする例
        if "MPS backend out of memory" in str(oom):
            print("⚠️ MPS 上で OOM が発生したため、CPU トレーニングにフォールバックします。")
            os.environ["CUDA_VISIBLE_DEVICES"] = ""   # GPU 使わない
            import tinygpt2
            tinygpt2.train(device="cpu")
        else:
            print(f"❌ tinygpt2 の呼び出しに失敗: {oom}")
    except Exception as e:
        print(f"❌ tinygpt2 の呼び出しに失敗: {e}")

if __name__ == "__main__":
    main()