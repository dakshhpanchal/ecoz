#!/usr/bin/env python3
"""Reply window for a phone notification.

Usage: ecoz_reply.py <key> <action-index> <title> <app> <message> <image-path>

Opened when you press Reply on a mirrored notification. It shows who wrote to you and what
they said, and sends your answer back through the running ecoz service, which triggers the
phone app's own reply action.
"""
import subprocess
import sys
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gdk", "4.0")
from gi.repository import Adw, Gdk, Gtk, Pango  # noqa: E402

DAEMON = Path(__file__).with_name("ecoz_daemon.py")


class ReplyWindow(Adw.ApplicationWindow):
    def __init__(self, app, key, index, title, source, message, image):
        super().__init__(application=app, title=f"Reply to {title}", default_width=420, resizable=False)
        self._key, self._index = key, index

        # Who it's from: photo (or initials) next to the name and the app.
        avatar = Adw.Avatar(size=48, text=title, show_initials=True)
        if image:
            try:
                avatar.set_custom_image(Gdk.Texture.new_from_filename(image))
            except Exception:  # unreadable or unsupported image: initials are fine
                pass
        name = Gtk.Label(label=title, xalign=0, ellipsize=Pango.EllipsizeMode.END, css_classes=["title-3"])
        where = Gtk.Label(label=source, xalign=0, ellipsize=Pango.EllipsizeMode.END,
                          css_classes=["dim-label", "caption"])
        names = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, valign=Gtk.Align.CENTER, hexpand=True)
        names.append(name)
        names.append(where)
        header = Gtk.Box(spacing=12)
        header.append(avatar)
        header.append(names)

        # What they said, for context.
        quote = Gtk.Label(label=message, xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, lines=6,
                          ellipsize=Pango.EllipsizeMode.END, selectable=True, max_width_chars=48)
        bubble = Gtk.Box(css_classes=["card"])
        quote.set_margin_top(12)
        quote.set_margin_bottom(12)
        quote.set_margin_start(14)
        quote.set_margin_end(14)
        bubble.append(quote)
        bubble.set_visible(bool(message))

        # The reply: a rounded field and a round send button.
        self._entry = Gtk.Entry(hexpand=True, placeholder_text="Write a reply…", valign=Gtk.Align.CENTER)
        self._entry.connect("activate", self._send)
        self._entry.connect("changed", self._on_changed)
        self._send_button = Gtk.Button(icon_name="mail-send-symbolic", sensitive=False,
                                       css_classes=["circular", "suggested-action"], tooltip_text="Send",
                                       valign=Gtk.Align.CENTER)
        self._send_button.connect("clicked", self._send)
        row = Gtk.Box(spacing=8)
        row.append(self._entry)
        row.append(self._send_button)

        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin_top=6,
                       margin_bottom=18, margin_start=18, margin_end=18)
        body.append(header)
        body.append(bubble)
        body.append(row)

        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(Adw.HeaderBar(show_title=False))
        toolbar.set_content(body)
        self.set_content(toolbar)

        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.add_controller(keys)
        self._entry.grab_focus()

    def _on_key(self, _controller, keyval, *_):
        if keyval == Gdk.KEY_Escape:
            self.close()
            return True
        return False

    def _on_changed(self, entry):
        self._send_button.set_sensitive(bool(entry.get_text().strip()))

    def _send(self, *_):
        text = self._entry.get_text().strip()
        if not text:
            return
        subprocess.run([sys.executable, "-I", str(DAEMON), "notif-action", self._key, str(self._index), text],
                       stdin=subprocess.DEVNULL, check=False)
        self.close()


class ReplyApp(Adw.Application):
    def __init__(self, args):
        super().__init__(application_id="app.ecoz.Reply")
        self._args = args

    def do_activate(self):
        ReplyWindow(self, *self._args).present()


def main():
    if len(sys.argv) != 7:
        sys.exit(__doc__)
    key, index, title, source, message, image = sys.argv[1:7]
    sys.exit(ReplyApp((key, index, title[:60], source[:60], message[:400], image)).run([sys.argv[0]]))


if __name__ == "__main__":
    main()
