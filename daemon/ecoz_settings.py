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


def flag_switch(title, subtitle, flag, on_when_present):
    """A switch backed by a flag file in ~/.config/ecoz.

    [on_when_present] says which way the file reads: for "ask before accepting files" the file's
    presence means the setting is OFF; for "lock when my phone walks away" its presence means ON.
    (Adw.SwitchRow can't be subclassed, so this builds a plain row.)
    """
    row = Adw.SwitchRow(title=title, subtitle=subtitle, active=flag.exists() == on_when_present)

    def changed(widget, *_):
        CONF.mkdir(parents=True, exist_ok=True)
        want_file = widget.get_active() == on_when_present
        if want_file:
            flag.touch()
        else:
            flag.unlink(missing_ok=True)

    row.connect("notify::active", changed)
    return row


class SettingsWindow(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="ecoz", default_width=520, default_height=700)

        files = Adw.PreferencesGroup(title="Files from the phone")
        files.add(flag_switch(
            "Ask before accepting files",
            "A notification with Accept and Decline appears before anything is saved. "
            "Turn this off to accept files from the phone automatically.",
            CONF / "confirm-incoming-off", on_when_present=False))
        note = Gtk.Label(
            label="Whether the phone asks before accepting files from the laptop is set in the ecoz app on the phone.",
            wrap=True, xalign=0, css_classes=["dim-label", "caption"], margin_top=6)
        files.add(note)

        clipboard = Adw.PreferencesGroup(title="Clipboard")
        clipboard.add(flag_switch(
            "Share what I copy with the phone",
            "Text you copy on the laptop appears on the phone's clipboard. Password-manager copies are never sent.",
            CONF / "clipboard-sync-off", on_when_present=False))

        proximity = Adw.PreferencesGroup(title="Phone proximity")
        proximity.add(flag_switch(
            "Lock when my phone walks away",
            "Locks the screen about 12 seconds after your phone is clearly out of range, and never while you are "
            "using the laptop. Needs “Tell the laptop when I'm close” turned on in the phone app.",
            CONF / "autolock-on", on_when_present=True))
        proximity.add(flag_switch(
            "Unlock with my phone",
            "Unlocks the screen when your phone is right next to the laptop, is itself unlocked, and proves it "
            "with a key kept in its secure hardware. Set up from the ecoz app on the phone first.",
            CONF / "unlock-on", on_when_present=True))

        network = Adw.PreferencesGroup(title="Internet")
        network.add(flag_switch(
            "Ask my phone for a hotspot when I'm offline",
            "When the laptop has had no internet for a few seconds and your phone is close, the phone offers a "
            "one-tap shortcut to its hotspot switch. Needs “Hotspot for the laptop” turned on in the phone app.",
            CONF / "hotspot-on", on_when_present=True))

        page = Adw.PreferencesPage()
        page.add(files)
        page.add(clipboard)
        page.add(proximity)
        page.add(network)
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
