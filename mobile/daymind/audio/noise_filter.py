"""Lightweight audio denoising helpers (high-pass + spectral gate)."""

from __future__ import annotations

import numpy as np


class NoiseReducer:
    """Applies a simple high-pass + noise gate to PCM buffers."""

    def __init__(self, sample_rate: int, cutoff_hz: float = 120.0, smoothing: float = 0.9) -> None:
        self.sample_rate = sample_rate
        self.cutoff = max(10.0, float(cutoff_hz))
        self.smoothing = max(0.0, min(float(smoothing), 0.999))
        self._prev_input = 0.0
        self._prev_output = 0.0

    def apply(self, pcm: np.ndarray) -> np.ndarray:
        if pcm.size == 0:
            return pcm
        filtered = self._high_pass(pcm.astype(np.float32, copy=False))
        gated = self._spectral_gate(filtered)
        minimal = 60.0
        gated[np.abs(gated) < minimal] = 0.0
        warmup = min(len(gated), int(self.sample_rate * 0.01))
        if warmup:
            gated[:warmup] = 0.0
        return gated.astype(pcm.dtype, copy=False)

    def _high_pass(self, data: np.ndarray) -> np.ndarray:
        rc = 1.0 / (2 * np.pi * self.cutoff)
        dt = 1.0 / self.sample_rate
        alpha = rc / (rc + dt)
        output = np.empty_like(data)
        prev_in = self._prev_input
        prev_out = self._prev_output
        for idx, sample in enumerate(data):
            out = alpha * (prev_out + sample - prev_in)
            output[idx] = out
            prev_out = out
            prev_in = sample
        self._prev_input = prev_in
        self._prev_output = prev_out
        return output

    def _spectral_gate(self, data: np.ndarray) -> np.ndarray:
        # Estimate running RMS and suppress bins below smoothing-weighted noise floor.
        window = max(64, int(self.sample_rate * 0.02))
        sq = data ** 2
        noise_floor = 0.0
        current_floor = 0.0
        thresholded = np.empty_like(data)
        for idx, sample in enumerate(data):
            noise_floor = (self.smoothing * noise_floor) + ((1 - self.smoothing) * sq[idx])
            if idx % window == 0:
                current_floor = np.sqrt(noise_floor)
            threshold = current_floor * 1.5
            gain = 0.0 if abs(sample) <= threshold else 1.0
            thresholded[idx] = sample * gain
        return thresholded


__all__ = ["NoiseReducer"]
