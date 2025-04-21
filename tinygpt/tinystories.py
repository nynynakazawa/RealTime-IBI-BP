import os
import multiprocessing as mp
from multiprocessing import freeze_support, set_start_method
import numpy as np
from datasets import load_dataset
from tqdm import tqdm
from transformers import AutoTokenizer

# グローバルにトークナイザを定義しておく
tokenizer = AutoTokenizer.from_pretrained("EleutherAI/gpt-neo-125M")

def tokenize(doc):
    tokens = [tokenizer.eos_token_id]
    tokens.extend(tokenizer(doc["text"])["input_ids"])
    return np.array(tokens, dtype=np.uint16)

def write_datafile(filename, tokens_np):
    np.save(filename, tokens_np)

def main():
    # Windows の freeze 対策と macOS での fork の明示
    freeze_support()
    try:
        set_start_method("fork")
    except RuntimeError:
        pass

    data_dir_name = "tinystories"
    shard_size = int(1e8)  # 100M tokens per shard

    data_dir = os.path.join(os.path.dirname(__file__), data_dir_name)
    os.makedirs(data_dir, exist_ok=True)

    # データセットのダウンロード＆キャッシュ
    ts = load_dataset("roneneldan/TinyStories", cache_dir=data_dir)

    n_procs = max(1, os.cpu_count() // 2)
    for split in ["train", "validation"]:
        token_ctr    = 0
        shard_index  = 0
        progress_bar = None
        shard_tokens = np.empty((shard_size,), dtype=np.uint16)

        with mp.Pool(n_procs) as pool:
            for tokens in pool.imap(tokenize, ts[split], chunksize=16):
                # シャードにまだ収まる場合
                if token_ctr + len(tokens) < shard_size:
                    shard_tokens[token_ctr:token_ctr + len(tokens)] = tokens
                    token_ctr += len(tokens)
                    if progress_bar is None:
                        progress_bar = tqdm(
                            total=shard_size,
                            unit="tokens",
                            desc=f"Shard {split} #{shard_index}"
                        )
                    progress_bar.update(len(tokens))
                else:
                    # あふれた分をこのシャードに詰めて書き出し
                    remainder = shard_size - token_ctr
                    if progress_bar is None:
                        progress_bar = tqdm(
                            total=shard_size,
                            unit="tokens",
                            desc=f"Shard {split} #{shard_index}"
                        )
                    progress_bar.update(remainder)
                    shard_tokens[token_ctr:shard_size] = tokens[:remainder]
                    filename = os.path.join(
                        data_dir,
                        f"tinystories_{split}_{shard_index:06d}.npy"
                    )
                    write_datafile(filename, shard_tokens)
                    shard_index += 1
                    # 残りを次のシャード用バッファへ
                    token_ctr = len(tokens) - remainder
                    shard_tokens[:token_ctr] = tokens[remainder:]
                    progress_bar = None

        # まだ残っているトークンを最後のシャードとして書き出し
        if token_ctr != 0:
            filename = os.path.join(
                data_dir,
                f"tinystories_{split}_{shard_index:06d}.npy"
            )
            write_datafile(filename, shard_tokens[:token_ctr])

if __name__ == "__main__":
    main()