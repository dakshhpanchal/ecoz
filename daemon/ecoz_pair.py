#!/usr/bin/env python3
"""Pairing window: shows a QR code for the phone to scan. Opened with `ecoz pair`.

The code holds the secret the phone needs to talk to this laptop, so it is only shown for a few minutes
and the window says not to share a picture of it. The same text is available to copy, for the phone's
"paste" box.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "vendor"))
import segno  # noqa: E402

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gdk", "4.0")
from gi.repository import Adw, Gdk, GLib, Gtk  # noqa: E402

SHOW_SECONDS = 300
CELL = 8          # pixels per QR module
QUIET = 4         # modules of blank border the QR format requires


def qr_matrix(text):
    """The QR code as rows of booleans (True = dark), without the border."""
    code = segno.make(text, error="m", micro=False)
    return [[bool(cell) for cell in row] for row in code.matrix]


class PairWindow(Adw.ApplicationWindow):
    def __init__(self, app, code, name):
        super().__init__(application=app, title="Pair a phone", default_width=440, resizable=False)
        self._matrix = qr_matrix(code)
        size = (len(self._matrix) + 2 * QUIET) * CELL
        area = Gtk.DrawingArea(content_width=size, content_height=size, halign=Gtk.Align.CENTER)
        area.set_draw_func(self._draw)

        title = Gtk.Label(label="Scan with the ecoz app", css_classes=["title-2"], margin_top=6)
        steps = Gtk.Label(
            label=f"On the phone, open ecoz and tap Scan QR code.\n"
                  f"This pairs the phone with {name}.",
            wrap=True, justify=Gtk.Justification.CENTER, css_classes=["dim-label"])
        warning = Gtk.Label(
            label="This code is a password for your laptop. Don't share a picture of it. "
                  "It closes by itself in 5 minutes.",
            wrap=True, justify=Gtk.Justification.CENTER, css_classes=["caption", "dim-label"])
        copy = Gtk.Button(label="Copy code instead", halign=Gtk.Align.CENTER)
        copy.connect("clicked", lambda *_: self._copy(code, copy))

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=8, margin_bottom=24,
                      margin_start=24, margin_end=24)
        for widget in (title, steps, area, copy, warning):
            box.append(widget)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        view.set_content(box)
        self.set_content(view)
        GLib.timeout_add_seconds(SHOW_SECONDS, self._expire)

    def _draw(self, _area, cr, width, height):
        cr.set_source_rgb(1, 1, 1)  # a QR code must be dark on light, whatever the theme
        cr.paint()
        cr.set_source_rgb(0, 0, 0)
        for y, row in enumerate(self._matrix):
            for x, dark in enumerate(row):
                if dark:
                    cr.rectangle((x + QUIET) * CELL, (y + QUIET) * CELL, CELL, CELL)
        cr.fill()

    def _copy(self, code, button):
        Gdk.Display.get_default().get_clipboard().set(code)
        button.set_label("Copied")

    def _expire(self):
        self.close()
        return False


class PairApp(Adw.Application):
    def __init__(self, code, name):
        super().__init__(application_id="app.ecoz.Pair")
        self._code, self._name = code, name

    def do_activate(self):
        win = self.props.active_window or PairWindow(self, self._code, self._name)
        win.present()


def show(code, name):
    PairApp(code, name).run(None)


if __name__ == "__main__":
    show(sys.argv[1], sys.argv[2])
