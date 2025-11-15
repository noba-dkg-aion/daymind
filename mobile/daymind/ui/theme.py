"""Central theme tokens for the Kivy client."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Tuple

from kivy.metrics import dp

Color = Tuple[float, float, float, float]


def rgba(value: str, alpha: float = 1.0) -> Color:
    """Convert hex to normalized RGBA."""
    value = value.lstrip("#")
    if len(value) != 6:
        raise ValueError(f"Expected 6 hex chars, got {value!r}")
    r = int(value[0:2], 16) / 255.0
    g = int(value[2:4], 16) / 255.0
    b = int(value[4:6], 16) / 255.0
    return (r, g, b, alpha)


@dataclass(frozen=True)
class Palette:
    background: Color
    backdrop: Color
    surface: Color
    surface_alt: Color
    card: Color
    outline: Color
    divider: Color
    accent: Color
    accent_soft: Color
    accent_muted: Color
    success: Color
    warning: Color
    danger: Color
    text_primary: Color
    text_secondary: Color
    text_muted: Color


@dataclass(frozen=True)
class Typography:
    hero: str
    title: str
    subtitle: str
    body: str
    caption: str


@dataclass(frozen=True)
class Spacing:
    grid: float
    section: float
    card_padding: float
    toolbar: float


@dataclass(frozen=True)
class DayMindTheme:
    palette: Palette
    typography: Typography
    spacing: Spacing

    @staticmethod
    def default() -> "DayMindTheme":
        palette = Palette(
            background=rgba("#030711"),
            backdrop=rgba("#050C1C"),
            surface=rgba("#0B1324"),
            surface_alt=rgba("#121C30"),
            card=rgba("#141F36"),
            outline=rgba("#2A3A57"),
            divider=rgba("#1F2C43", 0.7),
            accent=rgba("#4C8DFF"),
            accent_soft=rgba("#3FC1C9"),
            accent_muted=rgba("#66A8FF"),
            success=rgba("#4AD991"),
            warning=rgba("#F3B755"),
            danger=rgba("#F9707A"),
            text_primary=rgba("#F5F7FF"),
            text_secondary=rgba("#C0CCE6"),
            text_muted=rgba("#7F8EA3"),
        )
        typography = Typography(
            hero="H4",
            title="H5",
            subtitle="Subtitle1",
            body="Body1",
            caption="Caption",
        )
        spacing = Spacing(
            grid=dp(12),
            section=dp(18),
            card_padding=dp(20),
            toolbar=dp(8),
        )
        return DayMindTheme(palette=palette, typography=typography, spacing=spacing)


__all__ = ["DayMindTheme", "Palette", "Typography", "Spacing", "rgba"]
