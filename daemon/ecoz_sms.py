#!/usr/bin/env python3
"""Texts window: read and answer your phone's SMS on the laptop. Opened with `ecoz sms`.

Everything goes through the running ecoz service, which asks the phone. Nothing is stored on the
laptop. Texts you send from here are shown in the window for as long as it stays open, because
Android only lets the default messaging app write to the phone's own text history.
"""
import json
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, GLib, Gtk, Pango  # noqa: E402

MINE_CSS = b".mine { background: alpha(@accent_bg_color, 0.45); color: @accent_fg_color; }"
DAEMON = Path(__file__).with_name("ecoz_daemon.py")
REFRESH_SECONDS = 8


def call(request):
    """One request to the phone via the service. Always returns a dict with "ok"."""
    try:
        out = subprocess.run([sys.executable, "-I", str(DAEMON), "sms-call", json.dumps(request)],
                             capture_output=True, text=True, timeout=30).stdout
        return json.loads(out)
    except (OSError, ValueError, subprocess.SubprocessError):
        return {"ok": False, "error": "Couldn't reach the ecoz service."}


def when(millis):
    moment = datetime.fromtimestamp(millis / 1000)
    today = datetime.now().date()
    if moment.date() == today:
        return moment.strftime("%H:%M")
    if (today - moment.date()).days < 7:
        return moment.strftime("%a %H:%M")
    return moment.strftime("%d %b")


class SmsWindow(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="Texts", default_width=860, default_height=620)
        self._thread = None          # {"thread_id", "address", "name"} of the open conversation
        self._local = {}             # thread_id -> texts sent from here, not yet in the phone's database
        self._shown_threads = None
        self._alive = True
        self._busy = set()

        self._list = Gtk.ListBox(css_classes=["navigation-sidebar"], selection_mode=Gtk.SelectionMode.SINGLE)
        self._list.connect("row-selected", self._picked)
        left = Gtk.ScrolledWindow(child=self._list, hscrollbar_policy=Gtk.PolicyType.NEVER, width_request=300)
        self._left_status = Gtk.Label(wrap=True, css_classes=["dim-label"], margin_top=18, margin_start=12, margin_end=12)
        left_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        left_box.append(self._left_status)
        left_box.append(left)
        left.set_vexpand(True)

        self._messages = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=10, margin_bottom=10,
                                 margin_start=14, margin_end=14, valign=Gtk.Align.END)
        self._scroll = Gtk.ScrolledWindow(child=self._messages, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self._empty = Adw.StatusPage(icon_name="mail-unread-symbolic", title="Pick a conversation",
                                     description="Your phone's texts appear here.")
        self._entry = Gtk.Entry(hexpand=True, placeholder_text="Text message", sensitive=False)
        self._entry.connect("activate", self._send)
        self._send_button = Gtk.Button(label="Send", css_classes=["suggested-action"], sensitive=False)
        self._send_button.connect("clicked", self._send)
        self._error = Gtk.Label(wrap=True, xalign=0, css_classes=["error"], visible=False, margin_start=14, margin_end=14)
        compose = Gtk.Box(spacing=8, margin_top=8, margin_bottom=10, margin_start=14, margin_end=14)
        compose.append(self._entry)
        compose.append(self._send_button)
        self._stack = Gtk.Stack()
        self._stack.add_named(self._empty, "empty")
        self._stack.add_named(self._scroll, "chat")
        right = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        right.append(self._stack)
        right.append(self._error)
        right.append(compose)

        split = Adw.OverlaySplitView(sidebar=left_box, content=right, sidebar_width_fraction=0.34, min_sidebar_width=260)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        view.set_content(split)
        self.set_content(view)
        self.connect("close-request", self._closing)

        css = Gtk.CssProvider()
        css.load_from_data(MINE_CSS)
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self._refresh_threads()
        GLib.timeout_add_seconds(REFRESH_SECONDS, self._tick)

    # ---- polling -------------------------------------------------------------------
    def _closing(self, *_):
        self._alive = False
        return False

    def _tick(self):
        if not self._alive:
            return False
        self._refresh_threads()
        if self._thread:
            self._load_messages(self._thread["thread_id"], follow=False)
        return True

    def _background(self, key, work, done):
        """Run [work] off the UI thread, drop duplicates of the same [key], then call [done] on the UI thread."""
        if key in self._busy:
            return
        self._busy.add(key)

        def run():
            result = work()
            GLib.idle_add(finish, result)

        def finish(result):
            self._busy.discard(key)
            if self._alive:
                done(result)
            return False

        threading.Thread(target=run, daemon=True).start()

    # ---- conversation list ---------------------------------------------------------
    def _refresh_threads(self):
        self._background("threads", lambda: call({"op": "threads"}), self._show_threads)

    def _show_threads(self, reply):
        if not reply.get("ok"):
            self._left_status.set_label(reply.get("error", "Couldn't load texts."))
            self._left_status.set_visible(True)
            return
        threads = reply["data"]["threads"]
        self._left_status.set_visible(not threads)
        self._left_status.set_label("No texts found." if not threads else "")
        signature = json.dumps(threads, sort_keys=True)
        if signature == self._shown_threads:
            return
        self._shown_threads = signature
        keep = self._thread["thread_id"] if self._thread else None
        self._list.handler_block_by_func(self._picked)
        while (row := self._list.get_row_at_index(0)) is not None:
            self._list.remove(row)
        select = None
        for t in threads:
            row = self._thread_row(t)
            self._list.append(row)
            if t["thread_id"] == keep:
                select = row
        if select:
            self._list.select_row(select)
        self._list.handler_unblock_by_func(self._picked)

    def _thread_row(self, t):
        title = t.get("name") or t["address"] or "Unknown"
        name = Gtk.Label(label=title, xalign=0, hexpand=True, ellipsize=Pango.EllipsizeMode.END,
                         css_classes=["heading"] if t.get("unread") else [])
        stamp = Gtk.Label(label=when(t["date"]), css_classes=["dim-label", "caption"])
        top = Gtk.Box(spacing=6)
        top.append(name)
        top.append(stamp)
        snippet = ("You: " if t.get("outgoing") else "") + t["snippet"].replace("\n", " ")
        line = Gtk.Label(label=snippet, xalign=0, ellipsize=Pango.EllipsizeMode.END, css_classes=["dim-label", "caption"])
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=8, margin_bottom=8,
                      margin_start=10, margin_end=10)
        box.append(top)
        box.append(line)
        row = Gtk.ListBoxRow(child=box)
        row.thread = t
        return row

    def _picked(self, _list, row):
        if row is None:
            return
        self._thread = {k: row.thread.get(k) for k in ("thread_id", "address", "name")}
        self.set_title(self._thread["name"] or self._thread["address"] or "Texts")
        self._entry.set_sensitive(True)
        self._send_button.set_sensitive(True)
        self._entry.grab_focus()
        self._load_messages(self._thread["thread_id"], follow=True)

    # ---- one conversation ----------------------------------------------------------
    def _load_messages(self, thread_id, follow):
        self._background(("messages", thread_id), lambda: call({"op": "messages", "thread_id": thread_id}),
                         lambda reply: self._show_messages(thread_id, reply, follow))

    def _show_messages(self, thread_id, reply, follow):
        if not self._thread or self._thread["thread_id"] != thread_id:
            return  # the user moved on while this was loading
        if not reply.get("ok"):
            return self._fail(reply.get("error", "Couldn't load that conversation."))
        data = reply["data"]
        self._thread["address"] = data.get("address") or self._thread["address"]
        shown = list(data["messages"])
        last_body = {m["body"] for m in shown[-10:] if m["outgoing"]}
        for mine in self._local.get(thread_id, []):  # sent from here; only shown until the phone lists it
            if mine["body"] not in last_body:
                shown.append(mine)
        child = self._messages.get_first_child()
        while child is not None:
            nxt = child.get_next_sibling()
            self._messages.remove(child)
            child = nxt
        for m in shown:
            self._messages.append(self._bubble(m))
        self._stack.set_visible_child_name("chat")
        self._error.set_visible(False)
        if follow:
            GLib.timeout_add(80, self._to_bottom)

    def _to_bottom(self):
        adj = self._scroll.get_vadjustment()
        adj.set_value(adj.get_upper() - adj.get_page_size())
        return False

    def _bubble(self, m):
        mine = m["outgoing"]
        text = Gtk.Label(label=m["body"], wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, xalign=0, selectable=True,
                         max_width_chars=48)
        stamp = Gtk.Label(label=when(m["date"]), xalign=1, css_classes=["caption", "dim-label"])
        inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=7, margin_bottom=5,
                        margin_start=11, margin_end=11)
        inner.append(text)
        inner.append(stamp)
        bubble = Gtk.Frame(child=inner, css_classes=["card"] + (["mine"] if mine else []),
                           halign=Gtk.Align.END if mine else Gtk.Align.START)
        return bubble

    def _fail(self, message):
        self._error.set_label(message)
        self._error.set_visible(True)

    # ---- sending -------------------------------------------------------------------
    def _send(self, *_):
        text = self._entry.get_text().strip()
        if not text or not self._thread or not self._thread["address"]:
            return
        thread = dict(self._thread)
        self._entry.set_text("")
        self._entry.set_sensitive(False)
        self._send_button.set_sensitive(False)

        def done(reply):
            self._entry.set_sensitive(True)
            self._send_button.set_sensitive(True)
            if not reply.get("ok"):
                self._entry.set_text(text)  # nothing was sent: keep what was typed
                return self._fail(reply.get("error", "That didn't send."))
            self._local.setdefault(thread["thread_id"], []).append(
                {"body": text, "date": int(time.time() * 1000), "outgoing": True})
            self._entry.grab_focus()
            self._load_messages(thread["thread_id"], follow=True)

        self._background(("send", id(text), time.time()),
                         lambda: call({"op": "send", "address": thread["address"], "text": text}), done)


class SmsApp(Adw.Application):
    def __init__(self):
        super().__init__(application_id="app.ecoz.Texts")

    def do_activate(self):
        win = self.props.active_window or SmsWindow(self)
        win.present()


if __name__ == "__main__":
    SmsApp().run(None)
