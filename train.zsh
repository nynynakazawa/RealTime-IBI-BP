#!/usr/bin/env zsh
# スリープさせずに学習を続行、Pythonの出力をノンバッファで流す
caffeinate -i python3 -u scripts/train_tinygpt.py \
  --data_dir tinygpt/tinystories \
  --out_dir model_out \
  --epochs 10 \
  --batch_size 250000 |& tee train.log