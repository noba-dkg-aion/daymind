"""Reusable Kivy components for the DayMind client."""

from __future__ import annotations

from kivy.factory import Factory
from kivy.lang import Builder
from kivy.properties import StringProperty
from kivymd.uix.card import MDCard


class InfoBannerCard(MDCard):
    """MDCard wrapper that exposes a message prop for KV templates."""

    message = StringProperty("")


Factory.register("InfoBannerCard", cls=InfoBannerCard)

COMPONENT_KV = """
<DMScaffold@MDBoxLayout>:
    orientation: "vertical"
    padding: app.theme.spacing.toolbar, 0, app.theme.spacing.toolbar, app.theme.spacing.toolbar
    canvas.before:
        Color:
            rgba: app.theme.palette.background
        Rectangle:
            pos: self.pos
            size: self.size
        Color:
            rgba: app.theme.palette.backdrop
        Rectangle:
            pos: self.x, self.top - self.height * 0.35
            size: self.width, self.height * 0.35

<DMToolbar@MDToolbar>:
    md_bg_color: 0, 0, 0, 0
    specific_text_color: app.theme.palette.text_primary
    elevation: 0
    left_action_items: []
    right_action_items: []
    anchor_title: "left"

<DMCard@MDCard>:
    size_hint_y: None
    adaptive_height: True
    padding: app.theme.spacing.card_padding
    spacing: app.theme.spacing.grid
    radius: [26]
    md_bg_color: app.theme.palette.card
    line_color: 0, 0, 0, 0
    shadow_softness: 8
    shadow_offset: 0, -2

<SectionHeading@MDLabel>:
    font_style: app.theme.typography.title
    theme_text_color: "Custom"
    text_color: app.theme.palette.text_primary
    bold: True
    size_hint_y: None
    height: self.texture_size[1]

<BodyText@MDLabel>:
    font_style: app.theme.typography.body
    theme_text_color: "Custom"
    text_color: app.theme.palette.text_secondary
    size_hint_y: None
    height: self.texture_size[1]

<PrimaryButton@MDFillRoundFlatIconButton>:
    size_hint_y: None
    height: "50dp"
    md_bg_color: app.theme.palette.accent
    text_color: app.theme.palette.text_primary
    icon_color: app.theme.palette.text_primary
    padding: app.theme.spacing.grid, 0

<SecondaryButton@MDFillRoundFlatIconButton>:
    size_hint_y: None
    height: "50dp"
    md_bg_color: app.theme.palette.surface_alt
    text_color: app.theme.palette.text_primary
    icon_color: app.theme.palette.accent_muted
    line_color: app.theme.palette.outline
    padding: app.theme.spacing.grid, 0

<GhostButton@MDFlatButton>:
    size_hint_y: None
    height: "48dp"
    text_color: app.theme.palette.accent_muted

<RecordButton@MDFillRoundFlatIconButton>:
    size_hint_y: None
    height: "74dp"
    icon: "stop" if app.is_recording else "microphone"
    text: "Stop capture" if app.is_recording else "Start capture"
    md_bg_color: app.theme.palette.danger if app.is_recording else app.theme.palette.accent
    text_color: app.theme.palette.text_primary
    icon_color: app.theme.palette.text_primary
    on_press: app.toggle_recording()
    canvas.before:
        PushMatrix
        Scale:
            origin: self.center
            x: app.record_pulse + (app.record_glow * 0.05)
            y: app.record_pulse + (app.record_glow * 0.05)
    canvas.after:
        PopMatrix

<StatusBadge@MDCard>:
    icon: "microphone"
    title: ""
    value: ""
    caption: ""
    size_hint_y: None
    adaptive_height: True
    padding: app.theme.spacing.grid
    radius: [20]
    md_bg_color: app.theme.palette.surface_alt
    line_color: 0, 0, 0, 0
    MDBoxLayout:
        orientation: "horizontal"
        spacing: app.theme.spacing.grid
        adaptive_height: True
        MDIcon:
            icon: root.icon
            theme_text_color: "Custom"
            text_color: app.theme.palette.accent_muted
        MDBoxLayout:
            orientation: "vertical"
            adaptive_height: True
            MDLabel:
                text: root.title
                theme_text_color: "Custom"
                text_color: app.theme.palette.text_secondary
                font_style: app.theme.typography.caption
            MDLabel:
                text: root.value
                theme_text_color: "Custom"
                text_color: app.theme.palette.text_primary
                font_style: app.theme.typography.subtitle
                bold: True
            MDLabel:
                text: root.caption
                theme_text_color: "Custom"
                text_color: app.theme.palette.text_muted
                font_style: app.theme.typography.caption

<QueueBadge@StatusBadge>:
    icon: "tray-full"
    md_bg_color: app.theme.palette.accent_soft if app.queue_size else app.theme.palette.surface_alt
    canvas.before:
        PushMatrix
        Scale:
            origin: self.center
            x: app.queue_pulse
            y: app.queue_pulse
    canvas.after:
        PopMatrix

<RoundedInput@MDTextField>:
    mode: "rectangle"
    helper_text_mode: "on_focus"
    color_mode: "custom"
    line_color_focus: app.theme.palette.accent_muted
    text_color_focus: app.theme.palette.text_primary
    text_color_normal: app.theme.palette.text_secondary
    current_hint_text_color: app.theme.palette.text_muted
    size_hint_y: None
    height: "72dp"
    radius: [16, 16, 16, 16]

<InfoBanner@InfoBannerCard>:
    size_hint_y: None
    adaptive_height: True
    padding: app.theme.spacing.grid
    spacing: app.theme.spacing.grid
    md_bg_color: app.theme.palette.surface_alt
    line_color: 0, 0, 0, 0
    radius: [20]
    MDIcon:
        icon: "information-outline"
        theme_text_color: "Custom"
        text_color: app.theme.palette.accent_muted
    MDLabel:
        text: root.message
        theme_text_color: "Custom"
        text_color: app.theme.palette.text_secondary
        font_style: app.theme.typography.body
        text_size: self.width, None
        size_hint_y: None
        height: self.texture_size[1]

<ActivityLog@MDCard>:
    size_hint_y: None
    adaptive_height: True
    padding: app.theme.spacing.card_padding
    radius: [24]
    md_bg_color: app.theme.palette.surface
    line_color: 0, 0, 0, 0
    spacing: app.theme.spacing.grid
    SectionHeading:
        text: "Live log"
    ScrollView:
        do_scroll_x: False
        MDLabel:
            text: '\\n'.join(app.log_lines)
            theme_text_color: "Custom"
            text_color: app.theme.palette.text_secondary
            text_size: self.width, None
            size_hint_y: None
            height: self.texture_size[1]
"""


def load_components() -> None:
    """Register shared KV component templates."""
    Builder.load_string(COMPONENT_KV)


__all__ = ["load_components"]
