#!/usr/bin/env python3
"""ecoz settings window (laptop side). Opened with `ecoz settings` or from the app grid.

Each switch is a flag file in ~/.config/ecoz, which the running service reads on every request,
so a change takes effect immediately. The phone has its own matching setting in the app; these
are deliberately separate because each device decides for itself what it will accept.
"""
import os
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

CONF = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "ecoz"


class FlagSwitch(Adw.SwitchRow):
    """A switch backed by a flag file: the file exists when the setting is OFF."""

    def __init__(self, title, subtitle, flag):
        super().__init__(title=title, subtitle=subtitle, active=not flag.exists())
        self._flag = flag
        self.connect("notify::active", self._changed)

    def _changed(self, *_):
        CONF.mkdir(parents=True, exist_ok=True)
        if self.get_active():
            self._flag.unlink(missing_ok=True)
        else:
            self._flag.touch()


class SettingsWindow(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="ecoz", default_width=520, default_height=460)

        files = Adw.PreferencesGroup(title="Files from the phone")
        files.add(FlagSwitch(
            "Ask before accepting files",
            "A notification with Accept and Decline appears before anything is saved. "
            "Turn this off to accept files from the phone automatically.",
            CONF / "confirm-incoming-off"))
        note = Gtk.Label(
            label="Whether the phone asks before accepting files from the laptop is set in the ecoz app on the phone.",
            wrap=True, xalign=0, css_classes=["dim-label", "caption"], margin_top=6)
        files.add(note)

        clipboard = Adw.PreferencesGroup(title="Clipboard")
        clipboard.add(FlagSwitch(
            "Share what I copy with the phone",
            "Text you copy on the laptop appears on the phone's clipboard. Password-manager copies are never sent.",
            CONF / "clipboard-sync-off"))

        page = Adw.PreferencesPage()
        page.add(files)
        page.add(clipboard)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        view.set_content(page)
        self.set_content(view)


class SettingsApp(Adw.Application):
    def __init__(self):
        super().__init__(application_id="app.ecoz.Settings")

    def do_activate(self):
        win = self.props.active_window or SettingsWindow(self)
        win.present()


if __name__ == "__main__":
    raise SystemExit(SettingsApp().run([]))
