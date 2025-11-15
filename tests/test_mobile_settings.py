from pathlib import Path

from mobile.daymind.store.settings_store import SettingsStore


def test_settings_store_roundtrip(tmp_path):
    path = tmp_path / "settings.json"
    store = SettingsStore(path)
    assert store.get().server_url == ""
    assert store.get().vad_threshold == 3500

    store.update(server_url="https://example.com", api_key="abc", vad_threshold=4200, noise_gate=0.2)
    data = path.read_text()
    assert "example.com" in data
    assert "4200" in data

    store2 = SettingsStore(path)
    assert store2.get().api_key == "abc"
    assert store2.get().vad_threshold == 4200
    assert store2.get().noise_gate == 0.2
