"""Kivy entrypoint for the DayMind mobile client."""

from __future__ import annotations

import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

from kivy.animation import Animation
from kivy.clock import Clock
from kivy.lang import Builder
from kivy.properties import BooleanProperty, ListProperty, NumericProperty, ObjectProperty, StringProperty
from kivy.uix.screenmanager import ScreenManager
from kivymd.app import MDApp
from kivymd.uix.screen import MDScreen
from kivymd.uix.snackbar import Snackbar

from .audio.recorder import AudioRecorder
from .audio.types import RecordedChunk
from .config import CONFIG
from .services.logger import LogBuffer
from .services.network import ApiClient, ApiError
from .services.uploader import UploadWorker
from .store.queue_store import ChunkQueue
from .store.transcript_store import TranscriptStore
from .store.settings_store import SettingsStore
from .ui.components import load_components
from .ui.theme import DayMindTheme


SCREENS_KV = """
ScreenManager:
    RecordScreen:
        name: "record"
    SummaryScreen:
        name: "summary"
    SettingsScreen:
        name: "settings"

<RecordScreen@MDScreen>:
    DMScaffold:
        spacing: app.theme.spacing.section
        DMToolbar:
            title: "DayMind Live"
            left_action_items: [["microphone-message", lambda x: None]]
            right_action_items: [["clipboard-text-outline", lambda x: app.switch_screen('summary')], ["cog", lambda x: app.switch_screen('settings')]]
        ScrollView:
            do_scroll_x: False
            MDBoxLayout:
                orientation: "vertical"
                spacing: app.theme.spacing.section
                size_hint_y: None
                height: self.minimum_height
                DMCard:
                    SectionHeading:
                        text: "Ambient capture"
                    BodyText:
                        text: "Single tap capture with Whisper + VAD energy feedback."
                    RecordButton:
                    QueueBadge:
                        title: "Queued chunks"
                        value: "{} item(s)".format(app.queue_size) if app.queue_size else "Empty"
                        caption: "Ready to upload" if app.queue_size else "Everything synced"
                    StatusBadge:
                        icon: "record-circle"
                        title: "Recorder"
                        value: "Recording" if app.is_recording else "Idle"
                        caption: "{:.0%} signal".format(app.input_level)
                DMCard:
                    SectionHeading:
                        text: "Session insights"
                    StatusBadge:
                        icon: "waveform"
                        title: "Speech detection"
                        value: "Active" if app.input_level > 0.05 else "Waiting"
                        caption: "Live VAD sensitivity"
                    StatusBadge:
                        icon: "cloud-upload"
                        title: "Uploader"
                        value: "Awake"
                        caption: "Background worker keeps queue empty"
                    InfoBanner:
                        message: "Voice chunks stay on-device until you tap sync. JSONL metadata + FLAC archives follow the Text-First Storage policy."
                ActivityLog:
                MDBoxLayout:
                    size_hint_y: None
                    height: "60dp"
                    spacing: app.theme.spacing.grid
                    PrimaryButton:
                        text: "Clear queue"
                        icon: "trash-can-outline"
                        on_press: app.clear_queue()
                    SecondaryButton:
                        text: "Summary hub"
                        icon: "notebook-outline"
                        on_press: app.switch_screen('summary')
                    SecondaryButton:
                        text: "Settings"
                        icon: "cog-outline"
                        on_press: app.switch_screen('settings')

<SummaryScreen@MDScreen>:
    DMScaffold:
        spacing: app.theme.spacing.section
        DMToolbar:
            title: "Summaries"
            left_action_items: [["arrow-left", lambda x: app.switch_screen('record')]]
            right_action_items: [["refresh", lambda x: app.refresh_summary()]]
        ScrollView:
            do_scroll_x: False
            MDBoxLayout:
                orientation: "vertical"
                spacing: app.theme.spacing.section
                size_hint_y: None
                height: self.minimum_height
                DMCard:
                    SectionHeading:
                        text: "Daily snapshot"
                    BodyText:
                        text: "GPT-4o-mini condenses transcripts into structured notes and finance-ready deltas."
                    MDBoxLayout:
                        orientation: "horizontal"
                        adaptive_height: True
                        spacing: app.theme.spacing.grid
                        opacity: 1 if app.is_summary_loading else 0
                        MDLabel:
                            text: "Refreshing..."
                            theme_text_color: "Custom"
                            text_color: app.theme.palette.text_secondary
                            font_style: app.theme.typography.caption
                        MDCircularProgressIndicator:
                            size_hint: None, None
                            size: "32dp", "32dp"
                    ScrollView:
                        do_scroll_x: False
                        opacity: 0 if app.is_summary_loading else 1
                        MDLabel:
                            text: app.summary_text
                            theme_text_color: "Custom"
                            text_color: app.theme.palette.text_primary
                            text_size: self.width, None
                            size_hint_y: None
                            height: self.texture_size[1]
                InfoBanner:
                    message: "Summaries are synced into JSONL + CSV ledgers. FinanceAgent consumes them nightly for Beancount + Fava exports."
                MDBoxLayout:
                    size_hint_y: None
                    height: "60dp"
                    spacing: app.theme.spacing.grid
                    PrimaryButton:
                        text: "Refresh summary"
                        icon: "refresh"
                        on_press: app.refresh_summary()
                    SecondaryButton:
                        text: "Back to record"
                        icon: "arrow-left"
                        on_press: app.switch_screen('record')

<SettingsScreen@MDScreen>:
    DMScaffold:
        spacing: app.theme.spacing.section
        DMToolbar:
            title: "Connection"
            left_action_items: [["arrow-left", lambda x: app.switch_screen('record')]]
        ScrollView:
            do_scroll_x: False
            MDBoxLayout:
                orientation: "vertical"
                spacing: app.theme.spacing.section
                size_hint_y: None
                height: self.minimum_height
                DMCard:
                    SectionHeading:
                        text: "API endpoint"
                    BodyText:
                        text: "Set the server URL + API key issued via /data/api_keys.json exports."
                    RoundedInput:
                        id: server_input
                        text: app.server_url
                        hint_text: "https://api.example.com"
                        helper_text: "HTTPS strongly recommended"
                    RoundedInput:
                        id: api_input
                        text: app.api_key
                        hint_text: "Enter API key"
                        password: True
                        helper_text: "Keys map 1:1 to billing + usage counters"
                    PrimaryButton:
                        text: "Save"
                        icon: "content-save"
                        on_press: app.save_settings(server_input.text, api_input.text)
                    SecondaryButton:
                        text: "Test connection"
                        icon: "access-point-check"
                        on_press: app.test_connection()
                InfoBanner:
                    message: "Need a key? Use `python -m src.api.services.auth_service --store data/api_keys.json create mobile-demo` then paste it here."
                DMCard:
                    SectionHeading:
                        text: "Audio sensitivity"
                    BodyText:
                        text: "Tweak mic threshold, aggressiveness, and noise gate live to suppress background noise without losing speech."
                    MDBoxLayout:
                        orientation: "vertical"
                        spacing: app.theme.spacing.grid
                        MDLabel:
                            text: "Sensitivity (threshold: {} )".format(int(app.vad_threshold))
                            theme_text_color: "Custom"
                            text_color: app.theme.palette.text_secondary
                            font_style: app.theme.typography.caption
                        MDSlider:
                            min: 1500
                            max: 9000
                            value: app.vad_threshold
                            on_value: app.set_vad_threshold(self.value)
                    MDBoxLayout:
                        orientation: "vertical"
                        spacing: app.theme.spacing.grid
                        MDLabel:
                            text: "WebRTC VAD aggressiveness: {}".format(int(app.vad_aggressiveness))
                            theme_text_color: "Custom"
                            text_color: app.theme.palette.text_secondary
                            font_style: app.theme.typography.caption
                        MDSlider:
                            min: 0
                            max: 3
                            step: 1
                            value: app.vad_aggressiveness
                            on_value: app.set_vad_aggressiveness(self.value)
                    MDBoxLayout:
                        orientation: "vertical"
                        spacing: app.theme.spacing.grid
                        MDLabel:
                            text: "Noise gate {:.0%}".format(app.noise_gate)
                            theme_text_color: "Custom"
                            text_color: app.theme.palette.text_secondary
                            font_style: app.theme.typography.caption
                        MDSlider:
                            min: 0.0
                            max: 0.6
                            value: app.noise_gate
                            on_value: app.set_noise_gate(self.value)
"""



class RecordScreen(MDScreen):
    pass


class SummaryScreen(MDScreen):
    pass


class SettingsScreen(MDScreen):
    pass


class DayMindApp(MDApp):
    is_recording = BooleanProperty(False)
    queue_size = NumericProperty(0)
    summary_text = StringProperty("No summary yet")
    server_url = StringProperty("")
    api_key = StringProperty("")
    log_lines = ListProperty([])
    record_pulse = NumericProperty(1.0)
    queue_pulse = NumericProperty(1.0)
    is_summary_loading = BooleanProperty(False)
    input_level = NumericProperty(0.0)
    record_glow = NumericProperty(0.0)
    theme = ObjectProperty(DayMindTheme.default())
    vad_threshold = NumericProperty(3500)
    vad_aggressiveness = NumericProperty(2)
    noise_gate = NumericProperty(0.12)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.theme = DayMindTheme.default()
        self._pulse_anim: Animation | None = None
        self._queue_anim: Animation | None = None
        self._prev_queue_size = 0

    def build(self):
        load_components()
        Builder.load_string(SCREENS_KV)
        self.theme_cls.theme_style = "Dark"
        self.theme_cls.material_style = "M3"
        self.theme_cls.primary_palette = "BlueGray"
        self.base_dir = Path(self.user_data_dir or Path.home() / ".daymind")
        self.base_dir.mkdir(parents=True, exist_ok=True)
        self.settings_store = SettingsStore(self.base_dir / CONFIG.settings_file)
        settings = self.settings_store.get()
        self.server_url = settings.server_url
        self.api_key = settings.api_key
        self.vad_threshold = settings.vad_threshold
        self.vad_aggressiveness = settings.vad_aggressiveness
        self.noise_gate = settings.noise_gate
        self.queue = ChunkQueue(self.base_dir / CONFIG.queue_file)
        transcripts_dir = self.base_dir / "transcripts"
        transcripts_path = transcripts_dir / "daymind_transcripts.srt"
        self.transcript_store = TranscriptStore(transcripts_path)
        self.logger = LogBuffer(CONFIG.log_history)
        self.api_client = ApiClient(self.settings_store)
        self.uploader = UploadWorker(self.queue, self.api_client, self.logger, self.transcript_store)
        chunks_dir = self.base_dir / "chunks"
        self.recorder = AudioRecorder(
            chunks_dir,
            self.logger,
            self._handle_chunk,
            level_callback=self._update_input_level,
            amplitude_threshold=int(self.vad_threshold),
            vad_aggressiveness=int(self.vad_aggressiveness),
            noise_gate=float(self.noise_gate),
        )
        self._sync_state(initial=True)
        return ScreenManager()

    def on_start(self):
        self.uploader.start()
        Clock.schedule_interval(lambda dt: self._sync_state(), 1)

    def on_stop(self):
        self._stop_record_animation()
        self.recorder.stop()
        self.uploader.stop()
        self.api_client.close()

    def toggle_recording(self):
        if self.is_recording:
            self.recorder.stop()
            self.is_recording = False
            self._stop_record_animation()
            self.input_level = 0.0
            self.record_glow = 0.0
        else:
            self.recorder.start()
            self.is_recording = True
            self._start_record_animation()

    def _handle_chunk(self, chunk: RecordedChunk) -> None:
        payload = self._prepare_chunk_metadata(chunk)
        chunk_id = self.queue.enqueue(
            chunk.path,
            session_start=payload["session_start"],
            session_end=payload["session_end"],
            speech_segments=payload["speech_segments"],
        )
        self.logger.add(f"Chunk queued ({chunk_id[:6]})")
        self.uploader.wake()
        Clock.schedule_once(lambda dt: self._sync_state(), 0)
        self._show_snackbar("Chunk captured", icon="waveform")

    def clear_queue(self):
        self.queue.clear()
        self.logger.add("Queue cleared")
        self._show_snackbar("Queue cleared", icon="tray-remove")
        self._sync_state()

    def refresh_summary(self):
        self.logger.add("Refreshing summary...")
        self.is_summary_loading = True

        def worker():
            try:
                summary = self.api_client.fetch_summary()
                ok = True
            except ApiError as exc:
                self.logger.add(str(exc))
                summary = f"Error: {exc}"
                ok = False
            Clock.schedule_once(lambda dt: self._finish_summary(summary, ok), 0)

        threading.Thread(target=worker, daemon=True).start()

    def _finish_summary(self, text: str, ok: bool) -> None:
        self.summary_text = text
        self.is_summary_loading = False
        if ok:
            self._show_snackbar("Summary refreshed", icon="clipboard-check")
        else:
            self._show_snackbar("Summary error", icon="alert")

    def save_settings(self, server_url: str, api_key: str):
        self.settings_store.update(server_url=server_url.strip(), api_key=api_key.strip())
        self.server_url = self.settings_store.get().server_url
        self.api_key = self.settings_store.get().api_key
        self.logger.add("Settings saved")
        self._show_snackbar("Settings saved", icon="content-save")

    def set_vad_threshold(self, value: float):
        value = int(value)
        if value == self.vad_threshold:
            return
        self.vad_threshold = value
        self.settings_store.update(vad_threshold=value)
        self.recorder.set_vad_threshold(value)
        self.logger.add(f"VAD threshold set to {value}")

    def set_vad_aggressiveness(self, value: float):
        value = int(round(value))
        value = min(max(value, 0), 3)
        if value == self.vad_aggressiveness:
            return
        self.vad_aggressiveness = value
        self.settings_store.update(vad_aggressiveness=value)
        self.recorder.set_vad_aggressiveness(value)
        self.logger.add(f"VAD aggressiveness set to {value}")

    def set_noise_gate(self, value: float):
        clamped = max(0.0, min(float(value), 1.0))
        if abs(clamped - float(self.noise_gate)) < 0.005:
            return
        self.noise_gate = clamped
        self.settings_store.update(noise_gate=clamped)
        self.recorder.set_noise_gate(clamped)
        self.logger.add(f"Noise gate set to {clamped:.2f}")

    def test_connection(self):
        def worker():
            try:
                ok = self.api_client.test_connection()
                message = "Connection OK" if ok else "Connection failed"
                icon = "check-circle" if ok else "alert-circle"
            except ApiError as exc:
                message = f"Connection error: {exc}"
                icon = "alert-circle"
            self.logger.add(message)
            self._show_snackbar(message, icon=icon)

        threading.Thread(target=worker, daemon=True).start()

    def switch_screen(self, name: str):
        if self.root:
            self.root.current = name

    def _sync_state(self, initial: bool = False):
        current_queue = len(self.queue)
        if current_queue != self._prev_queue_size:
            self.queue_size = current_queue
            self._animate_queue_badge()
        else:
            self.queue_size = current_queue
        self._prev_queue_size = current_queue
        self.log_lines = self.logger.get()
        settings = self.settings_store.get()
        if initial:
            self.server_url = settings.server_url
            self.api_key = settings.api_key
            self.vad_threshold = settings.vad_threshold
            self.vad_aggressiveness = settings.vad_aggressiveness
            self.noise_gate = settings.noise_gate

    def _prepare_chunk_metadata(self, chunk: RecordedChunk) -> dict:
        start_iso = self._isoformat(chunk.session_start)
        end_iso = self._isoformat(chunk.session_end)
        segments = []
        for seg in chunk.speech_segments:
            seg_start = chunk.session_start + timedelta(milliseconds=seg.start_ms)
            seg_end = chunk.session_start + timedelta(milliseconds=seg.end_ms)
            segments.append(
                {
                    "start_ms": seg.start_ms,
                    "end_ms": seg.end_ms,
                    "start_utc": self._isoformat(seg_start),
                    "end_utc": self._isoformat(seg_end),
                }
            )
        return {
            "session_start": start_iso,
            "session_end": end_iso,
            "speech_segments": segments,
        }

    def _update_input_level(self, level: float) -> None:
        """Animate the record button glow with latest VAD energy."""
        level = max(0.0, min(1.0, level))

        def _apply(_dt):
            self.input_level = level
            self.record_glow = 0.2 + (0.8 * level)

        Clock.schedule_once(_apply, 0)

    @staticmethod
    def _isoformat(value: datetime) -> str:
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    def _start_record_animation(self) -> None:
        if self._pulse_anim:
            self._pulse_anim.stop(self)
        animation = Animation(record_pulse=1.12, duration=0.5) + Animation(record_pulse=1.0, duration=0.5)
        animation.repeat = True
        animation.start(self)
        self._pulse_anim = animation

    def _stop_record_animation(self) -> None:
        if self._pulse_anim:
            self._pulse_anim.stop(self)
            self._pulse_anim = None
        self.record_pulse = 1.0

    def _animate_queue_badge(self) -> None:
        if self._queue_anim:
            self._queue_anim.stop(self)
        animation = Animation(queue_pulse=1.1, duration=0.2) + Animation(queue_pulse=1.0, duration=0.2)
        animation.start(self)
        self._queue_anim = animation
        self._show_snackbar(f"{self.queue_size} chunk(s) queued", icon="tray-full")

    def _show_snackbar(self, text: str, *, icon: str = "information") -> None:
        def _display(*_):
            Snackbar(
                text=text,
                icon=icon,
                duration=1.8,
                radius=[14],
                bg_color=self.theme.palette.surface_alt,
                text_color=self.theme.palette.text_primary,
                icon_color=self.theme.palette.accent_muted,
                elevation=8,
            ).open()

        Clock.schedule_once(_display, 0)


if __name__ == "__main__":
    DayMindApp().run()
