#!/usr/bin/env python3
"""Progress window for a file arriving from the phone. Started by the ecoz service.

Usage: ecoz_progress.py <total-bytes>

The service writes one JSON line per update to this process's stdin:
  {"name": "photo.jpg", "done": 123456}     bytes received so far, across the whole batch
  {"end": true, "failed": false}            the transfer is over (close the window)
It also closes by itself if the service goes away.
"""
import json
import sys
import threading
import time

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk, Pango  # noqa: E402


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


class ProgressWindow(Adw.ApplicationWindow):
    def __init__(self, app, total):
        super().__init__(application=app, title="Receiving from phone", default_width=420, resizable=False)
        self._total, self._began, self._shown = max(total, 1), time.monotonic(), 0
        self._title = Gtk.Label(label="Receiving…", xalign=0, ellipsize=Pango.EllipsizeMode.MIDDLE, css_classes=["title-4"])
        self._bar = Gtk.ProgressBar(show_text=True)
        self._detail = Gtk.Label(label="", xalign=0, css_classes=["dim-label", "caption"])
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=14, margin_bottom=20,
                      margin_start=20, margin_end=20)
        for widget in (self._title, self._bar, self._detail):
            box.append(widget)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar(show_end_title_buttons=False, show_start_title_buttons=False))
        view.set_content(box)
        self.set_content(view)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in sys.stdin:
            try:
                message = json.loads(line)
            except ValueError:
                continue
            GLib.idle_add(self._apply, message)
        GLib.idle_add(self._finish, False)  # the service went away

    def _apply(self, message):
        if message.get("end"):
            self._finish(bool(message.get("failed")))
            return False
        done = max(0, min(int(message.get("done", 0)), self._total))
        self._title.set_label(str(message.get("name", "")) or "Receiving…")
        self._bar.set_fraction(done / self._total)
        self._bar.set_text(f"{done * 100 // self._total}%")
        elapsed = max(time.monotonic() - self._began, 0.001)
        self._detail.set_label(f"{human(done)} of {human(self._total)}  ·  {human(done / elapsed)}/s")
        return False

    def _finish(self, failed):
        if failed:
            self._title.set_label("Didn't finish")
            self._detail.set_label("The transfer was interrupted.")
            GLib.timeout_add_seconds(4, self.close)
        else:
            self._bar.set_fraction(1.0)
            self._bar.set_text("100%")
            GLib.timeout_add(600, self.close)
        return False


class ProgressApp(Adw.Application):
    def __init__(self, total):
        super().__init__(application_id="app.ecoz.Progress")
        self._total = total

    def do_activate(self):
        win = self.props.active_window or ProgressWindow(self, self._total)
        win.present()


if __name__ == "__main__":
    ProgressApp(int(sys.argv[1])).run(None)
