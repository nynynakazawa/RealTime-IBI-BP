#!/usr/bin/env python3
"""
AROB論文用の波形比較図を生成するスクリプト。
Raw Green channel と sinWave fit の3つの代表的なビート例を表示する図を作成し、
AROB/AROB_Nakazawa_Japasese/figures ディレクトリに保存する。

修正: Androidアプリの出力(SinWaveカラム)にアーティファクトが含まれる場合があるため、
本スクリプト内でPythonによる堅牢な「非対称正弦波モデルフィッティング」を再実行し、
理想的なモデル波形を生成して描画するように変更。

使用例:
    python generate_waveform_comparison.py
"""

from __future__ import annotations

from pathlib import Path
from typing import List, Tuple, Optional

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy.signal import find_peaks

# パス設定
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR.parent / "Data" / "Smartphone" / "Data"
AROB_FIGURES_DIR = BASE_DIR.parent.parent / "AROB" / "AROB_Nakazawa_Japasese" / "figures"


def fit_asymmetric_sine_wave(
    time: np.ndarray,
    values: np.ndarray,
    alpha_range: Tuple[float, float] = (0.2, 0.5),
    alpha_step: float = 0.02
) -> np.ndarray:
    """
    非対称正弦波モデルをデータにフィッティングする。
    論文の記述通り、alphaを線形探索し、各alphaで線形回帰を行って最適なパラメータを決定する。
    
    Args:
        time: 時刻配列 (秒)
        values: 信号値配列
        alpha_range: alphaの探索範囲 (min, max)
        alpha_step: alphaの探索ステップ
        
    Returns:
        fitted_curve: フィッティングされたモデル波形
    """
    if len(time) < 5 or len(values) < 5:
        return np.zeros_like(values)
    
    # 時間を0から始まるように正規化し、周期Tを取得
    t0 = time[0]
    t_norm = time - t0
    T = t_norm[-1]
    
    if T <= 0:
        return np.zeros_like(values)
        
    best_resid = float('inf')
    best_fit = np.zeros_like(values)
    
    # alphaの線形探索
    for alpha in np.arange(alpha_range[0], alpha_range[1] + alpha_step/2, alpha_step):
        T_sys = alpha * T
        T_dia = T - T_sys
        
        # 位相関数 theta(t) の計算
        theta = np.zeros_like(t_norm)
        
        # Rising phase (0 <= t < T_sys)
        mask_rise = t_norm < T_sys
        theta[mask_rise] = -np.pi/2 + np.pi * (t_norm[mask_rise] / T_sys)
        
        # Falling phase (T_sys <= t <= T)
        mask_fall = ~mask_rise
        if T_dia > 0:
            theta[mask_fall] = np.pi/2 + np.pi * ((t_norm[mask_fall] - T_sys) / T_dia)
        else:
            theta[mask_fall] = np.pi/2 # Should not happen if alpha < 1
            
        # 線形回帰のための基底関数
        # Model: y = Mean + a*sin(theta) + b*cos(theta)
        # A = sqrt(a^2 + b^2), phi = atan2(b, a)
        X = np.column_stack([
            np.ones_like(theta),
            np.sin(theta),
            np.cos(theta)
        ])
        
        # 最小二乗法
        try:
            # coeffs = [Mean, a, b]
            coeffs, resid, _, _ = np.linalg.lstsq(X, values, rcond=None)
            
            current_resid = resid[0] if len(resid) > 0 else np.sum((values - X @ coeffs)**2)
            
            if current_resid < best_resid:
                best_resid = current_resid
                best_fit = X @ coeffs
                
        except Exception:
            continue
            
    return best_fit


def detect_beats(
    time: np.ndarray,
    values: np.ndarray,
    min_period_s: float = 0.5,
    max_period_s: float = 1.5,
) -> List[Tuple[int, int]]:
    """
    ピーク検出によりビート区間を特定する。
    """
    valid_mask = np.isfinite(values)
    if valid_mask.sum() < 10:
        return []
    
    dt = np.median(np.diff(time[valid_mask]))
    if dt <= 0:
        return []
    
    min_distance = int(min_period_s / dt)
    if min_distance < 1:
        min_distance = 1
    
    # 簡易的なスムージングをしてからピーク検出
    from scipy.ndimage import gaussian_filter1d
    smoothed = gaussian_filter1d(values, sigma=2.0)
    
    peaks, _ = find_peaks(smoothed, distance=min_distance)
    
    if len(peaks) < 2:
        return []
    
    beats: List[Tuple[int, int]] = []
    for i in range(len(peaks) - 1):
        start_peak = peaks[i]
        end_peak = peaks[i + 1]
        
        period = time[end_peak] - time[start_peak]
        if period < min_period_s or period > max_period_s:
            continue
        
        # 谷（Valley）を探して、Valley始まりValley終わりの区間に補正する
        # 通常PPGはValley-to-Valleyで1拍とするため
        segment_indices = np.arange(start_peak, end_peak + 1)
        if len(segment_indices) < 3:
            continue
            
        # ピーク間の最小値を谷とする
        valley_idx = start_peak + np.argmin(smoothed[start_peak:end_peak])
        
        # 次の区間の谷も探す
        if i + 1 < len(peaks) - 1:
            next_peak = peaks[i+2]
            next_valley_idx = end_peak + np.argmin(smoothed[end_peak:next_peak])
            
            # ビート区間: valley_idx -> next_valley_idx
            # ただし、データ長チェック
            if next_valley_idx > valley_idx:
                beats.append((valley_idx, next_valley_idx))
    
    return beats


def load_wave_data(path: Path) -> pd.DataFrame:
    """Wave_Data CSVを読み込む"""
    df = pd.read_csv(path)
    df.columns = [col.strip() for col in df.columns]
    
    time_col = "経過時間_秒"
    if time_col not in df.columns:
        raise ValueError(f"{time_col} カラムが存在しません: {path}")
    
    df[time_col] = pd.to_numeric(df[time_col], errors="coerce")
    df = df.dropna(subset=[time_col]).reset_index(drop=True)
    
    for ch in ["Green", "SinWave"]:
        if ch not in df.columns:
            raise ValueError(f"{ch} カラムが存在しません: {path}")
        df[ch] = pd.to_numeric(df[ch], errors="coerce")
    
    return df


def generate_waveform_comparison(output_path: Path) -> None:
    """
    複数セッションからデータを読み込み、3つの代表的なビート例を選択して
    波形比較図を生成する。
    """
    # データファイルを検索
    wave_files = list(DATA_DIR.glob("*/*_Wave_Data.csv"))
    if not wave_files:
        print(f"Error: Wave_Data ファイルが見つかりません: {DATA_DIR}")
        return
    
    print(f"検出されたWave_Dataファイル数: {len(wave_files)}")
    
    # 全ビートを収集
    all_beats_data = []  # [(time, green, sinwave, noise, amplitude), ...]
    
    # 処理するファイル数を増やす（良質なサンプルを見つけるため）
    for wave_file in wave_files[:10]:
        try:
            df = load_wave_data(wave_file)
            time = df["経過時間_秒"].to_numpy()
            green = df["Green"].to_numpy()
            
            # ビート検出
            beats = detect_beats(time, green)
            
            for start_idx, end_idx in beats:
                if end_idx <= start_idx:
                    continue
                
                t_seg = time[start_idx:end_idx]
                g_seg = green[start_idx:end_idx]
                
                valid = np.isfinite(g_seg)
                if valid.sum() < 8:
                    continue
                
                # Pythonで再フィッティングを行い、理想的なSinWaveを生成
                # ノイズ除去のため、Green信号を少しスムージングしてからフィッティングに使用してもよいが
                # モデル自体が平滑化効果を持つため、生のGreenにフィットさせる
                s_seg_fit = fit_asymmetric_sine_wave(t_seg, g_seg)
                
                # ノイズ指標 (RMSE)
                diff = g_seg - s_seg_fit
                noise = float(np.sqrt(np.mean(diff ** 2)))
                
                # 振幅指標
                amp = float(np.max(g_seg) - np.min(g_seg))
                
                if amp > 1.0:  # 有効な振幅のみ
                    all_beats_data.append({
                        'time': t_seg - t_seg[0],
                        'green': g_seg,
                        'sinwave': s_seg_fit, # 再計算したクリーンな波形を使用
                        'noise': noise,
                        'amplitude': amp,
                    })
        except Exception as e:
            print(f"Warning: {wave_file.name} の処理中にエラー: {e}")
            continue
    
    if len(all_beats_data) < 3:
        print(f"Error: 十分なビート数が収集できませんでした（{len(all_beats_data)} < 3）")
        return
    
    print(f"収集されたビート数: {len(all_beats_data)}")
    
    # ビートを特性で並べ替え
    noises = np.array([b['noise'] for b in all_beats_data])
    amplitudes = np.array([b['amplitude'] for b in all_beats_data])
    
    noise_sorted = np.argsort(noises)
    amp_sorted = np.argsort(amplitudes)
    
    # 3つの例を選択
    # (a) High-noise: ノイズが上位25%程度（極端な外れ値は避ける）
    high_noise_idx = noise_sorted[-max(1, int(len(noise_sorted) * 0.15))] 
    
    # (b) Typical: ノイズが中央値付近
    typical_idx = noise_sorted[len(noise_sorted) // 2]
    
    # (c) Low-amplitude: 振幅が下位25%程度
    low_amp_idx = amp_sorted[max(0, int(len(amp_sorted) * 0.25))]
    
    selected = [high_noise_idx, typical_idx, low_amp_idx]
    
    # 重複回避
    used = set()
    final_selection = []
    for idx in selected:
        if idx not in used:
            final_selection.append(idx)
            used.add(idx)
        else:
            # 代替を探す
            for offset in range(1, 100):
                alt_idx = idx + offset # 単純なオフセット
                if alt_idx < len(all_beats_data) and alt_idx not in used:
                    final_selection.append(alt_idx)
                    used.add(alt_idx)
                    break
    
    # Figure作成
    plt.rcParams['font.family'] = 'sans-serif'
    plt.rcParams['font.size'] = 10
    
    fig, axes = plt.subplots(1, 3, figsize=(10, 3.5), dpi=300)
    titles = ["(a) High-noise case", "(b) Typical quality case", "(c) Low-amplitude case"]
    
    for ax, beat_idx, title in zip(axes, final_selection[:3], titles):
        beat = all_beats_data[beat_idx]
        
        t_seg = beat['time'] * 1000  # msに変換
        g_seg = beat['green']
        s_seg = beat['sinwave']
        
        # 正規化 (0-1)
        # Greenのレンジを基準にする
        g_min, g_max = np.nanmin(g_seg), np.nanmax(g_seg)
        range_val = g_max - g_min
        if range_val <= 0: range_val = 1.0
            
        g_norm = (g_seg - g_min) / range_val
        
        # SinWaveも同じスケールで正規化（ただし、DCオフセットの違いは保持したいが、
        # 図の見栄えのため、SinWaveも自身のレンジで正規化しつつ、平均位置を合わせるなどの調整が可能。
        # ここでは単純にMin-Max正規化して重ねる（形状比較のため）
        s_min, s_max = np.nanmin(s_seg), np.nanmax(s_seg)
        s_range = s_max - s_min
        if s_range <= 0: s_range = 1.0
        s_norm = (s_seg - s_min) / s_range
        
        # プロット
        ax.plot(t_seg, g_norm, 'b-', linewidth=1.2, alpha=0.6, label='Raw Green')
        ax.plot(t_seg, s_norm, 'r-', linewidth=2.0, alpha=0.9, label='sinWave fit')
        
        # ピーク位置表示
        if len(g_norm) > 0:
            g_peak_idx = np.nanargmax(g_norm)
            s_peak_idx = np.nanargmax(s_norm)
            
            ax.axvline(t_seg[g_peak_idx], color='blue', linestyle=':', alpha=0.5, linewidth=1)
            ax.axvline(t_seg[s_peak_idx], color='red', linestyle=':', alpha=0.5, linewidth=1)
            
            # ピーク位置差
            # peak_diff_ms = t_seg[s_peak_idx] - t_seg[g_peak_idx]
            # 差が大きすぎる場合は表示しない（誤検出の可能性）
            # if 5 < abs(peak_diff_ms) < 100:
            #    ax.annotate(f'Δ={peak_diff_ms:.0f}ms', 
            #               xy=(t_seg[s_peak_idx], 1.05),
            #               fontsize=9, ha='center', color='black',
            #               bbox=dict(boxstyle="round,pad=0.2", fc="white", ec="gray", alpha=0.8))
        
        ax.set_xlabel('Time [ms]')
        if title.startswith("(a)"):
            ax.set_ylabel('Normalized amplitude')
        ax.set_title(title)
        ax.legend(loc='upper right', fontsize=8, framealpha=0.9)
        ax.grid(True, linestyle='--', alpha=0.3)
        ax.set_ylim(-0.1, 1.2)
    
    fig.suptitle('Waveform comparison: Raw Green channel vs sinWave fit', fontsize=12, y=0.98)
    fig.tight_layout()
    
    # 出力ディレクトリ作成
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    # 保存
    fig.savefig(output_path, format="png", dpi=300, bbox_inches='tight')
    print(f"★ 波形比較図を保存しました: {output_path}")
    
    # SVGも保存
    svg_path = output_path.with_suffix(".svg")
    fig.savefig(svg_path, format="svg", bbox_inches='tight')
    print(f"★ SVG版を保存しました: {svg_path}")
    
    plt.close(fig)


def main():
    print("=== AROB論文用 波形比較図生成 (Python Fitting版) ===")
    print(f"データディレクトリ: {DATA_DIR}")
    print(f"出力先: {AROB_FIGURES_DIR}")
    
    output_path = AROB_FIGURES_DIR / "waveform_comparison_examples.png"
    generate_waveform_comparison(output_path)
    
    print("\n完了しました。")


if __name__ == "__main__":
    main()
