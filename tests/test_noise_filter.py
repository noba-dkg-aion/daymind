import numpy as np

from mobile.daymind.audio.noise_filter import NoiseReducer


def test_noise_reducer_high_passes_dc():
    reducer = NoiseReducer(sample_rate=16000)
    dc = np.ones(32000, dtype=np.int16) * 400
    result = reducer.apply(dc)
    assert np.max(np.abs(result)) < 50  # DC should be mostly removed


def test_noise_reducer_preserves_transient():
    reducer = NoiseReducer(sample_rate=16000)
    t = np.linspace(0, 1, 16000, endpoint=False)
    tone = (np.sin(2 * np.pi * 440 * t) * 12000).astype(np.int16)
    result = reducer.apply(tone)
    # Should still contain significant energy
    assert np.max(np.abs(result)) > 5000
