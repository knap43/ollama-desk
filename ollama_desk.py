#!/usr/bin/env python3
"""
Ollama Desk — a GTK4 / libadwaita chat client for Ollama, with desktop agent tools.

Arch:      sudo pacman -S python-gobject gtk4 libadwaita
Optional:  the "Window Calls" GNOME Shell extension (github.com/ickyicky/window-calls)
           enables listing, focusing and closing windows.
"""

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

try:  # GLib >= 2.80 moved DesktopAppInfo into GioUnix
    gi.require_version("GioUnix", "2.0")
    from gi.repository import GioUnix

    DesktopAppInfo = GioUnix.DesktopAppInfo
except (ValueError, ImportError):
    DesktopAppInfo = Gio.DesktopAppInfo

import glob
import http.client
import json
import os
import platform
import re
import shlex
import shutil
import socket
import subprocess
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

APP_ID = "io.github.ollamadesk.OllamaDesk"
APP_NAME = "Ollama Desk"
CONFIG_DIR = Path(GLib.get_user_config_dir()) / "ollama-desk"
DATA_DIR = Path(GLib.get_user_data_dir()) / "ollama-desk" / "conversations"

DEFAULT_CONFIG = {
    "host": "http://localhost:11434",
    "model": "",
    "agent": True,
    "auto_approve_reads": True,
    "auto_run_harmless": False,   # run provably read-only shell commands without asking
    "workspaces": [],             # folders the model may change freely; commands start in the first
    "shell_timeout": 60,
    "num_ctx": 8192,          # fallback context length when a model doesn't specify one
    "show_stats": True,
    "system_prompt": "",
    "model_options": {},      # per-model overrides: {model: {option: value, "keep_alive": ...}}
}
MAX_TOOL_OUTPUT = 8000
MAX_READ_BYTES = 256 * 1024
MAX_AGENT_STEPS = 12

SUGGESTIONS = [
    "What's taking up the most space in my home folder?",
    "Summarize whatever is on my clipboard",
    "Open the Files app in my Downloads folder",
]

# Accent: #e1a34f, darkened to #946c34 wherever it sits behind white text or on light backgrounds
# (4.5:1 contrast). On dark backgrounds the original #e1a34f is readable as is.
CSS = """
@define-color accent_bg_color #946c34;
@define-color accent_fg_color #ffffff;
@define-color accent_color #946c34;
:root {
  --accent-bg-color: #946c34;
  --accent-fg-color: #ffffff;
  --accent-color: #946c34;
}
@media (prefers-color-scheme: dark) {
  :root { --accent-color: #e1a34f; }
}

.chat-column { padding: 28px 18px 12px 18px; }

.bubble-user {
  background-color: @accent_bg_color;
  background-color: var(--accent-bg-color);
  color: @accent_fg_color;
  color: var(--accent-fg-color);
  border-radius: 20px 20px 6px 20px;
  padding: 10px 16px;
}

.md { line-height: 1.5; }

.codeblock {
  background-color: alpha(currentColor, 0.06);
  border-radius: 12px;
}
.codeblock .code-header { padding: 2px 4px 0 14px; }
.codeblock .code { font-family: monospace; padding: 2px 14px 14px 14px; }

.composer {
  background-color: @view_bg_color;
  background-color: var(--view-bg-color);
  border-radius: 24px;
  padding: 4px 6px 4px 16px;
  box-shadow: 0 0 0 1px alpha(currentColor, 0.10), 0 4px 16px alpha(black, 0.08);
}
textview.composer-text, textview.composer-text > text { background: none; }

.thinking-text { font-size: 0.92em; }
.tool-output { font-family: monospace; font-size: 0.88em; }

row .row-delete { opacity: 0; transition: opacity 150ms ease-out; }
row:hover .row-delete, row:selected .row-delete { opacity: 1; }
"""


# ───────────────────────────── persistence ─────────────────────────────

def load_config():
    cfg = dict(DEFAULT_CONFIG)
    try:
        cfg.update(json.loads((CONFIG_DIR / "config.json").read_text()))
    except (OSError, ValueError):
        pass
    return cfg


def save_config(cfg):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    (CONFIG_DIR / "config.json").write_text(json.dumps(cfg, indent=2))


def load_conversations():
    convs = []
    if DATA_DIR.is_dir():
        for f in DATA_DIR.glob("*.json"):
            try:
                convs.append(json.loads(f.read_text()))
            except (OSError, ValueError):
                pass
    convs.sort(key=lambda c: c.get("updated", 0), reverse=True)
    return convs


def save_conversation(conv):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    (DATA_DIR / f"{conv['id']}.json").write_text(json.dumps(conv, ensure_ascii=False, indent=1))


def delete_conversation_file(cid):
    try:
        (DATA_DIR / f"{cid}.json").unlink()
    except OSError:
        pass


# ───────────────────────────── threading helpers ─────────────────────────────

def on_main(fn, *args):
    """Run fn on the GTK main loop and block the calling (worker) thread until it returns."""
    box, ev = {}, threading.Event()

    def wrapper():
        try:
            box["v"] = fn(*args)
        except Exception as e:  # re-raised in the worker
            box["e"] = e
        ev.set()
        return False

    GLib.idle_add(wrapper)
    ev.wait()
    if "e" in box:
        raise box["e"]
    return box.get("v")


def on_main_async(fn):
    """fn(done) runs on the main loop; the worker blocks until done(value) is called."""
    box, ev = {}, threading.Event()

    def done(value):
        box["v"] = value
        ev.set()

    def wrapper():
        try:
            fn(done)
        except Exception as e:
            done(e)
        return False

    GLib.idle_add(wrapper)
    ev.wait()
    return box.get("v")


class Throttle:
    """Coalesce rapid worker-thread updates into at most one UI update per interval."""

    def __init__(self, fn, interval=60):
        self.fn, self.interval = fn, interval
        self.value, self.scheduled, self.cancelled = None, False, False
        self.lock = threading.Lock()

    def push(self, value):
        with self.lock:
            self.value = value
            if self.scheduled:
                return
            self.scheduled = True
        GLib.timeout_add(self.interval, self._flush)

    def _flush(self):
        with self.lock:
            self.scheduled, value = False, self.value
        if not self.cancelled:
            self.fn(value)
        return False

    def cancel(self):
        self.cancelled = True


# ───────────────────────────── Ollama client ─────────────────────────────

class OllamaError(Exception):
    pass


class Ollama:
    def __init__(self, host):
        u = urlsplit(host if "://" in host else "http://" + host)
        self.https = u.scheme == "https"
        self.hostname = u.hostname or "localhost"
        self.port = u.port or (443 if self.https else 11434)
        self.base = u.path.rstrip("/")
        self.conn = None

    def _connect(self, timeout):
        cls = http.client.HTTPSConnection if self.https else http.client.HTTPConnection
        return cls(self.hostname, self.port, timeout=timeout)

    @staticmethod
    def _error(resp):
        raw = resp.read().decode(errors="replace")
        try:
            return OllamaError(json.loads(raw).get("error", raw))
        except ValueError:
            return OllamaError(raw or f"HTTP {resp.status}")

    def models(self):
        c = self._connect(5)
        try:
            c.request("GET", self.base + "/api/tags")
            r = c.getresponse()
            if r.status != 200:
                raise self._error(r)
            return sorted(m["name"] for m in json.loads(r.read()).get("models", []))
        finally:
            c.close()

    def show(self, model):
        c = self._connect(10)
        try:
            c.request("POST", self.base + "/api/show", body=json.dumps({"model": model}),
                      headers={"Content-Type": "application/json"})
            r = c.getresponse()
            if r.status != 200:
                raise self._error(r)
            return json.loads(r.read())
        finally:
            c.close()

    def chat(self, payload):
        self.conn = c = self._connect(900)
        try:
            c.request("POST", self.base + "/api/chat", body=json.dumps(payload),
                      headers={"Content-Type": "application/json"})
            r = c.getresponse()
            if r.status != 200:
                raise self._error(r)
            for line in r:
                if line.strip():
                    yield json.loads(line)
        finally:
            c.close()

    def abort(self):
        """Interrupt a blocking read from another thread; Ollama stops generating on disconnect."""
        c = self.conn
        if c is not None and c.sock is not None:
            try:
                c.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


# ───────────────────────────── tools ─────────────────────────────

def _tool(name, description, props=None, required=()):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object",
                       "properties": {k: {"type": t, "description": d} for k, (t, d) in (props or {}).items()},
                       "required": list(required)}}}


TOOLS = [
    _tool("run_shell", "Run a bash command on the user's computer. Returns exit code, stdout and stderr. "
          "May require user approval; there is no interactive stdin, so avoid commands that prompt.",
          {"command": ("string", "The bash command to run"),
           "cwd": ("string", "Working directory; defaults to the first workspace folder, else home")}, ["command"]),
    _tool("read_file", "Read a text file and return its contents.",
          {"path": ("string", "File path; ~ is allowed")}, ["path"]),
    _tool("write_file", "Write text to a file, creating parent folders as needed. Free inside workspace folders; "
          "elsewhere it needs user approval.",
          {"path": ("string", "File path; ~ is allowed"), "content": ("string", "Text to write"),
           "append": ("boolean", "Append instead of overwriting (default false)")}, ["path", "content"]),
    _tool("edit_file", "Replace an exact piece of text in an existing file. Prefer this to rewriting whole files. "
          "Free inside workspace folders; elsewhere it needs user approval.",
          {"path": ("string", "File path; ~ is allowed"),
           "old_text": ("string", "Exact text to replace, copied from the file"),
           "new_text": ("string", "Replacement text"),
           "replace_all": ("boolean", "Replace every occurrence instead of exactly one (default false)")},
          ["path", "old_text", "new_text"]),
    _tool("delete_path", "Delete a file or folder. Free inside workspace folders; elsewhere it needs user approval.",
          {"path": ("string", "Path to delete; ~ is allowed"),
           "recursive": ("boolean", "Required to delete a folder that isn't empty")}, ["path"]),
    _tool("move_path", "Move or rename a file or folder. Free when both ends are inside workspace folders.",
          {"source": ("string", "Current path"), "destination": ("string", "New path or target folder")},
          ["source", "destination"]),
    _tool("make_directory", "Create a folder, including missing parents. Free inside workspace folders.",
          {"path": ("string", "Folder path; ~ is allowed")}, ["path"]),
    _tool("list_directory", "List the entries of a folder.",
          {"path": ("string", "Folder path; ~ is allowed")}, ["path"]),
    _tool("get_clipboard", "Return the text currently on the clipboard."),
    _tool("set_clipboard", "Put text on the clipboard.", {"text": ("string", "Text to copy")}, ["text"]),
    _tool("send_notification", "Show a desktop notification.",
          {"title": ("string", "Notification title"), "body": ("string", "Notification body")}, ["title"]),
    _tool("list_applications", "List installed applications, optionally filtered by a search query.",
          {"query": ("string", "Optional search text")}),
    _tool("launch_application", "Launch an installed application by desktop ID (e.g. org.gnome.Nautilus) or name.",
          {"app": ("string", "Desktop ID or application name")}, ["app"]),
    _tool("open_uri", "Open a URL, file or folder with its default application.",
          {"target": ("string", "A URL or a filesystem path")}, ["target"]),
    _tool("list_windows", "List open windows with their IDs, titles and application classes."),
    _tool("focus_window", "Bring a window to the front.",
          {"window_id": ("integer", "Window ID from list_windows")}, ["window_id"]),
    _tool("close_window", "Close a window. Requires user approval.",
          {"window_id": ("integer", "Window ID from list_windows")}, ["window_id"]),
]

# name → (label, icon, risk). risk: "confirm" always asks; "read" asks unless auto-approved; "safe" never asks.
TOOL_META = {
    "run_shell": ("Run command", "utilities-terminal-symbolic", "confirm"),
    "read_file": ("Read file", "document-open-symbolic", "read"),
    "write_file": ("Write file", "document-save-symbolic", "confirm"),
    "edit_file": ("Edit file", "document-edit-symbolic", "confirm"),
    "delete_path": ("Delete", "user-trash-symbolic", "confirm"),
    "move_path": ("Move", "edit-cut-symbolic", "confirm"),
    "make_directory": ("Create folder", "folder-new-symbolic", "confirm"),
    "list_directory": ("List folder", "folder-symbolic", "read"),
    "get_clipboard": ("Read clipboard", "edit-paste-symbolic", "read"),
    "set_clipboard": ("Copy to clipboard", "edit-copy-symbolic", "safe"),
    "send_notification": ("Send notification", "preferences-system-notifications-symbolic", "safe"),
    "list_applications": ("List applications", "view-app-grid-symbolic", "safe"),
    "launch_application": ("Launch application", "system-run-symbolic", "safe"),
    "open_uri": ("Open", "document-open-symbolic", "safe"),
    "list_windows": ("List windows", "view-grid-symbolic", "safe"),
    "focus_window": ("Focus window", "view-reveal-symbolic", "safe"),
    "close_window": ("Close window", "window-close-symbolic", "confirm"),
}
DESTRUCTIVE = {"run_shell", "write_file", "edit_file", "delete_path", "move_path", "close_window"}

PERMISSION_BODY = {
    "edit_file": "The model wants to change a file.",
    "delete_path": "The model wants to delete this.",
    "move_path": "The model wants to move this.",
    "make_directory": "The model wants to create a folder.",
    "run_shell": "The model wants to run this command on your computer.",
    "write_file": "The model wants to write to a file.",
    "read_file": "The model wants to read a file.",
    "list_directory": "The model wants to look inside a folder.",
    "get_clipboard": "The model wants to read your clipboard.",
    "close_window": "The model wants to close a window.",
}

class Param:
    """A tunable Ollama option. Params with `off` show as a switch; `off` is the value sent when disabled."""

    def __init__(self, key, group, title, subtitle, lo, hi, step, digits, default, off=None, suggested=None, unit=""):
        self.key, self.group, self.title, self.subtitle = key, group, title, subtitle
        self.lo, self.hi, self.step, self.digits, self.default = lo, hi, step, digits, default
        self.off, self.suggested, self.unit = off, suggested, unit


PARAMS = [
    Param("temperature", "style", "Temperature", "Higher is more varied, lower is more focused",
          0, 2, 0.05, 2, 0.8),
    Param("top_p", "style", "Top P", "Sample only from the likeliest words that make up this share",
          0, 1, 0.01, 2, 0.9),
    Param("top_k", "style", "Top K", "Sample only from this many of the likeliest words; 0 turns it off",
          0, 200, 1, 0, 40),
    Param("min_p", "style", "Min P", "Skip words far less likely than the top choice; 0 turns it off",
          0, 1, 0.01, 2, 0.0),
    Param("repeat_penalty", "style", "Repeat penalty", "Discourage repeating recent words; 1 turns it off",
          0.5, 2, 0.05, 2, 1.1),
    Param("num_predict", "style", "Limit reply length", "Cap how many tokens one reply may use",
          16, 32768, 64, 0, -1, off=-1, suggested=1024, unit="Tokens"),
    Param("seed", "style", "Fixed seed", "The same seed and prompt give the same reply",
          0, 2 ** 31 - 1, 1, 0, -1, off=-1, suggested=42, unit="Seed"),
    Param("num_ctx", "speed", "Context length", "Tokens the model can see at once; more uses more memory",
          512, 262144, 512, 0, 8192),
    Param("num_gpu", "speed", "Set GPU layers", "Automatic when off; fewer layers frees video memory",
          0, 999, 1, 0, -1, off=-1, suggested=99, unit="Layers"),
    Param("num_thread", "speed", "Set CPU threads", "Automatic when off",
          1, 256, 1, 0, 0, off=0, suggested=os.cpu_count() or 4, unit="Threads"),
    Param("num_batch", "speed", "Batch size", "Prompt tokens read per step; larger is faster but uses more memory",
          32, 4096, 32, 0, 512),
]
PARAM_BY_KEY = {p.key: p for p in PARAMS}

# Hover help, written for people new to running models locally.
TIPS = {
    "temperature": "Controls how adventurous the model is when choosing each word. Around 0.2 gives precise, "
                   "repeatable answers, good for code and facts. Around 1.0 gives livelier, more creative text. "
                   "Much higher and replies start to ramble.",
    "top_p": "Before each word, the model ranks the candidates and keeps only the likeliest ones whose chances "
             "add up to this share. 0.9 means it ignores the unlikeliest 10%. Lower is safer but blander; "
             "1.0 turns the filter off. Most people adjust temperature and leave this alone.",
    "top_k": "Keeps only this many of the best candidates for each word. Lower values make the text more "
             "predictable. 0 turns the filter off.",
    "min_p": "Drops any word whose chance is below this fraction of the best word's chance. Around 0.05 trims "
             "nonsense while keeping variety, and pairs well with a higher temperature. 0 turns it off.",
    "repeat_penalty": "Makes recently used words less likely to be picked again. If the model loops or repeats "
                      "itself, try 1.15 to 1.2. Set it too high and it avoids words it genuinely needs.",
    "num_predict": "Stops the reply after this many tokens. A token is a piece of a word: about three quarters "
                   "of an English word, and usually less in other languages. Replies that hit the limit end "
                   "mid-sentence.",
    "seed": "The model's randomness starts from this number. With the same seed, settings and message you get "
            "the same reply every time, which makes it easy to compare settings fairly. When off, every reply "
            "starts from a fresh random number.",
    "num_ctx": "The model's working memory: everything it can see at once, including earlier messages and tool "
               "results. When a chat grows past it, the oldest parts are forgotten. Larger values use more "
               "memory and make replies slower to start. 8192 suits most chats; raise it for long documents "
               "or long agent sessions.",
    "num_gpu": "A model is a stack of layers. Layers on the GPU run far faster than on the CPU but take up "
               "video memory. Ollama chooses automatically. Set it yourself to lower it if you run out of video "
               "memory, or to a high number like 99 to put the whole model on the GPU.",
    "num_thread": "How many CPU threads work on the parts of the model that aren't on the GPU. Ollama usually "
                  "chooses well. Matching your number of physical cores can help; going above that tends to "
                  "make things slower.",
    "num_batch": "How many tokens of your message the model reads in one go before it starts replying. Larger "
                 "values read long messages faster but need more memory. Lower it if long chats fail with "
                 "out-of-memory errors.",
    "keep_alive": "Loading a model into memory takes a few seconds. Keeping it loaded means the next reply "
                  "starts right away, but the memory stays occupied for other programs. Ollama's default is "
                  "5 minutes.",
}

KEEP_ALIVE = [("Ollama's default", None), ("Unload right away", 0), ("1 minute", "1m"),
              ("30 minutes", "30m"), ("1 hour", "1h"), ("Always", -1)]

STAT_KEYS = ("total_duration", "load_duration", "prompt_eval_count", "prompt_eval_duration",
             "eval_count", "eval_duration")

TUNE_ICON = """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16" fill="#2e3436">
<rect x="1" y="3" width="14" height="1.5" rx=".75"/><circle cx="10.5" cy="3.75" r="2.25"/>
<rect x="1" y="7.25" width="14" height="1.5" rx=".75"/><circle cx="5" cy="8" r="2.25"/>
<rect x="1" y="11.5" width="14" height="1.5" rx=".75"/><circle cx="11" cy="12.25" r="2.25"/>
</svg>"""


def parse_model_info(raw):
    defaults = {}
    for line in (raw.get("parameters") or "").splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2 and parts[0] in PARAM_BY_KEY:
            try:
                defaults[parts[0]] = float(parts[1].strip().strip('"'))
            except ValueError:
                pass
    ctx_max = None
    for k, v in (raw.get("model_info") or {}).items():
        if k.endswith(".context_length"):
            try:
                ctx_max = int(v)
            except (TypeError, ValueError):
                pass
    d = raw.get("details") or {}
    caps = raw.get("capabilities")
    return {"defaults": defaults, "ctx_max": ctx_max,
            "summary": " ".join(str(b) for b in (d.get("family"), d.get("parameter_size"),
                                                 d.get("quantization_level")) if b),
            "tools": None if caps is None else "tools" in caps}


EMPTY_INFO = {"defaults": {}, "ctx_max": None, "summary": "", "tools": None}


def format_stats(s):
    s = s or {}
    parts = []
    ec, ed = s.get("eval_count") or 0, s.get("eval_duration") or 0
    pc, pd = s.get("prompt_eval_count") or 0, s.get("prompt_eval_duration") or 0
    load = s.get("load_duration") or 0
    if ec and ed:
        parts.append(f"{ec / (ed / 1e9):.1f} tokens/s")
    if ec:
        parts.append(f"{ec} tokens")
    if pc and pd:
        parts.append(f"prompt read at {pc / (pd / 1e9):.0f} tokens/s")
    if load > 5e8:
        parts.append(f"loaded in {load / 1e9:.1f} s")
    return "     ".join(parts)


def stats_tooltip(s):
    if not s:
        return None
    return (f"Total time: {(s.get('total_duration') or 0) / 1e9:.2f} s\n"
            f"Model load: {(s.get('load_duration') or 0) / 1e9:.2f} s\n"
            f"Prompt: {s.get('prompt_eval_count') or 0} tokens in {(s.get('prompt_eval_duration') or 0) / 1e9:.2f} s\n"
            f"Reply: {s.get('eval_count') or 0} tokens in {(s.get('eval_duration') or 0) / 1e9:.2f} s")


# ── harmless-command check ──
# An allowlist, not a denylist: a command runs unasked only if every part of it is a known read-only
# tool used in a read-only way. Anything the check can't vouch for falls back to asking.

FIND_UNSAFE = {"-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls"}
SAFE_REDIRECT = re.compile(r"(?:(?<=\s)|^)(?:[12&]?>>?\s*/dev/null|[12]?>&[12])(?=\s|$|[;|&])")
ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
SAFE_ASSIGNMENT = re.compile(r"^(LANG|LC_[A-Z]+|TZ|NO_COLOR)=")


def _short_has(args, letters):
    return any(a.startswith("-") and not a.startswith("--") and any(c in a[1:] for c in letters) for a in args)


def _subcommand(allowed):
    def check(args):
        positional = [a for a in args if not a.startswith("-")]
        return not positional or positional[0] in allowed
    return check


def _git(args):
    args = list(args)
    while args and args[0] in ("--no-pager", "-P"):
        args = args[1:]
    if len(args) >= 2 and args[0] == "-C":
        args = args[2:]
    if not args:
        return True
    sub, rest = args[0], args[1:]
    if any(a.startswith(("--output", "--ext-diff", "--exec", "--upload-pack", "--receive-pack")) for a in rest):
        return False
    if sub in {"status", "log", "diff", "show", "ls-files", "rev-parse", "blame", "shortlog", "describe"}:
        return True
    if sub in {"branch", "tag", "remote"}:
        return all(a in {"-a", "-r", "-v", "-vv", "-l", "--list", "--all", "--remotes", "--verbose",
                         "--show-current"} for a in rest)
    return False


def _pacman(args):
    if not args or not re.fullmatch(r"-(Q[a-zA-Z]*|S[si]+|F[sl]*)", args[0]):
        return False
    return not any(a.startswith(("--refresh", "--sysupgrade")) or re.fullmatch(r"-[a-zA-Z]*[yu][a-zA-Z]*", a)
                   for a in args[1:])


ARG_CHECKS = {
    "find": lambda a: not any(x in FIND_UNSAFE for x in a),
    "fd": lambda a: not (_short_has(a, "xX") or any(x.startswith("--exec") for x in a)),
    "rg": lambda a: not any(x.startswith("--pre") for x in a),
    "sort": lambda a: not (_short_has(a, "o") or any(x.startswith(("--output", "--compress-program")) for x in a)),
    "uniq": lambda a: len([x for x in a if not x.startswith("-")]) <= 1,
    "tree": lambda a: not (_short_has(a, "o") or any(x.startswith("--output") for x in a)),
    "file": lambda a: not (_short_has(a, "C") or "--compile" in a),
    "date": lambda a: not (_short_has(a, "s") or any(x.startswith("--set") for x in a)),
    "sensors": lambda a: not _short_has(a, "s"),
    "hostname": lambda a: all(x in ("-f", "-s", "-i", "-I", "-d", "--fqdn", "--short") for x in a),
    "command": lambda a: bool(a) and a[0] in ("-v", "-V"),
    "journalctl": lambda a: not any(x.startswith(("--vacuum", "--rotate", "--flush", "--sync", "--relinquish",
                                                   "--smart-relinquish", "--setup-keys", "--update-catalog",
                                                   "--new-id")) for x in a),
    "systemctl": _subcommand({"status", "is-active", "is-enabled", "is-failed", "list-units", "list-unit-files",
                              "list-timers", "list-sockets", "list-dependencies", "show", "cat"}),
    "ollama": _subcommand({"list", "ls", "ps", "show"}),
    "gsettings": _subcommand({"get", "list-schemas", "list-keys", "list-children", "list-recursively", "range",
                              "describe", "writable"}),
    "dconf": _subcommand({"read", "list", "dump"}),
    "flatpak": _subcommand({"list", "info", "search", "remotes", "history"}),
    "nvidia-smi": lambda a: all(x.startswith(("-L", "--list-gpus", "-q", "--query", "--format", "-i", "--id"))
                                or x.isdigit() for x in a),
    "git": _git,
    "pacman": _pacman,
}

SAFE_COMMANDS = {
    "ls", "cat", "head", "tail", "wc", "du", "df", "free", "uptime", "uname", "whoami", "id", "groups", "pwd",
    "echo", "printf", "stat", "which", "whereis", "type", "realpath", "readlink", "basename", "dirname", "ps",
    "pgrep", "lsblk", "lscpu", "lsusb", "lspci", "lsmod", "nproc", "printenv", "diff", "cmp", "md5sum", "sha1sum",
    "sha256sum", "sha512sum", "tr", "cut", "column", "nl", "grep", "egrep", "fgrep", "test", "[", "true", "false",
    "cd", "lsof", "ss", "fc-list", "locale", "who", "w", "last", "getent", "cal",
} | set(ARG_CHECKS)


def _harmless_simple(words):
    i = 0
    while i < len(words) and ASSIGNMENT.match(words[i]):
        if not SAFE_ASSIGNMENT.match(words[i]):
            return False
        i += 1
    if i == len(words):
        return True
    name, args = words[i], words[i + 1:]
    if name not in SAFE_COMMANDS:
        return False
    if any(a.startswith("-") and any(c in a for c in "*?[") for a in args):  # globbed options
        return False
    check = ARG_CHECKS.get(name)
    return check(args) if check else True


def _segments(command):
    """Split a command into simple commands, or None if it uses anything that can hide what really runs."""
    if not command.strip() or "\n" in command or "\r" in command:
        return False
    text = SAFE_REDIRECT.sub(" ", command)
    # substitutions, variables, redirection to files and brace tricks can hide what really runs
    if re.search(r"[`$<>{}]", text):
        return False
    lexer = shlex.shlex(text, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    lexer.commenters = ""
    try:
        tokens = list(lexer)
    except ValueError:
        return False
    segments, current = [], []
    for tok in tokens:
        if tok in ("|", "||", "&&", ";"):
            segments.append(current)
            current = []
        elif tok and set(tok) <= set("();<>|&"):  # subshells, background jobs and other operators
            return False
        else:
            current.append(tok)
    segments.append(current)
    return [seg for seg in segments if seg] or None


def is_harmless_command(command):
    """True only if the command can do nothing but read. Unsure means False, and False means ask."""
    segments = _segments(command)
    return bool(segments) and all(_harmless_simple(seg) for seg in segments)


# ── workspace folders ──

def workspace_problem(path):
    """Why a folder can't be a workspace, or None if it can."""
    home = Path.home().resolve()
    if path == Path(path.anchor) or home.is_relative_to(path):
        return "That folder contains your whole home folder. Choose something more specific."
    if path.is_relative_to(home):
        rel = path.relative_to(home).parts
        if rel and rel[0].startswith("."):
            return "Hidden folders in your home hold settings and programs, so they can't be workspaces."
    for d in os.environ.get("PATH", "").split(os.pathsep):
        if d:
            dp = Path(os.path.expanduser(d)).resolve()
            if dp.is_relative_to(path) or path.is_relative_to(dp):
                return "That folder holds programs you run, so it can't be a workspace."
    if Path(__file__).resolve().is_relative_to(path):
        return "That folder contains Ollama Desk itself."
    return None


def valid_workspaces(paths):
    out = []
    for raw in paths:
        p = Path(os.path.expanduser(str(raw))).resolve()
        if p.is_dir() and workspace_problem(p) is None and p not in out:
            out.append(p)
    return out


def in_workspace(path, workspaces, strict=False, writing=True):
    """strict: must be below a workspace, not the workspace itself. writing: .git internals are off limits,
    since git config there can make later read-only git commands run arbitrary programs."""
    for w in workspaces:
        if path.is_relative_to(w):
            rel = path.relative_to(w).parts
            if strict and not rel:
                continue
            if writing and ".git" in rel:
                return False
            return True
    return False


WS_WRITE_FLAGS = {  # command → (allowed short flags, allowed long flags); anything else falls back to asking
    "rm": ("frRdv", {"--force", "--recursive", "--dir", "--verbose"}),
    "rmdir": ("v", {"--verbose", "--ignore-fail-on-non-empty"}),
    "mkdir": ("pv", {"--parents", "--verbose"}),
    "mv": ("finvu", {"--force", "--no-clobber", "--verbose", "--update"}),
    "cp": ("rRafnvupdP", {"--recursive", "--archive", "--force", "--no-clobber", "--verbose", "--update"}),
    "touch": ("c", {"--no-create"}),
}
WS_READERS = {"ls", "cat", "head", "tail", "wc", "stat"}
WS_NEUTRAL = {"echo", "printf", "pwd", "true", "false"}


def _expand(arg, cwd):
    """Every real path an argument can refer to, symlinks followed; None if it can't be pinned down."""
    p = os.path.expanduser(arg)
    if not os.path.isabs(p):
        p = os.path.join(cwd, p)
    if not any(c in arg for c in "*?["):
        return [Path(p).resolve()]
    if any(re.match(r"^\.[*?\[]", part) for part in Path(arg).parts):  # might match ".."
        return None
    return [Path(p).resolve()] + [Path(m).resolve() for m in glob.glob(p)]


def _ws_segment_ok(name, args, cwds, workspaces):
    if name in WS_READERS:
        paths, writing = [a for a in args if not a.startswith("-")], False
    else:
        short, longs = WS_WRITE_FLAGS[name]
        paths, writing, ended = [], True, False
        for a in args:
            if not ended and a == "--":
                ended = True
            elif not ended and a.startswith("-") and a != "-":
                if (a not in longs) if a.startswith("--") else not set(a[1:]) <= set(short):
                    return False
            else:
                paths.append(a)
    if not paths:
        return all(in_workspace(c, workspaces, writing=False) for c in cwds)
    for i, arg in enumerate(paths):
        strict = name in ("rm", "rmdir") or (name == "mv" and i < len(paths) - 1)
        for cwd in cwds:
            found = _expand(arg, cwd)
            if found is None or not all(in_workspace(f, workspaces, strict, writing) for f in found):
                return False
    return True


def shell_risk(command, cwd, workspaces, harmless_ok):
    """'read' or 'safe' when a command may run without asking, else None (ask)."""
    segments = _segments(command)
    if not segments:
        return None
    if harmless_ok and all(_harmless_simple(seg) for seg in segments):
        return "read"
    if not workspaces:
        return None
    cwds, used_read = {Path(cwd).resolve()}, False  # every directory the shell might be in at each step
    for seg in segments:
        words = list(seg)
        while words and ASSIGNMENT.match(words[0]):
            if not SAFE_ASSIGNMENT.match(words[0]):
                return None
            words = words[1:]
        if not words:
            continue
        name, args = words[0], words[1:]
        if name == "cd":
            if len(args) > 1 or (args and (args[0].startswith("-") or any(c in args[0] for c in "*?["))):
                return None
            new = {_expand(args[0] if args else "~", c)[0] for c in cwds}
            if not all(in_workspace(n, workspaces, writing=False) for n in new):
                return None
            cwds |= new  # a cd can fail, so keep the old directories too
        elif name in WS_NEUTRAL:
            continue
        elif name in WS_WRITE_FLAGS or name in WS_READERS:
            if not _ws_segment_ok(name, args, cwds, workspaces):
                return None
        elif harmless_ok and _harmless_simple(words):
            used_read = True
        else:
            return None
    return "read" if used_read else "safe"


WINDOW_CALLS = ("org.gnome.Shell", "/org/gnome/Shell/Extensions/Windows", "org.gnome.Shell.Extensions.Windows")


def clip(text, limit=MAX_TOOL_OUTPUT):
    return text if len(text) <= limit else text[:limit] + f"\n… [truncated {len(text) - limit} characters]"


def parse_args(raw):
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            v = json.loads(raw)
            return v if isinstance(v, dict) else {}
        except ValueError:
            return {}
    return {}


def summarize(name, args):
    for key in ("command", "path", "source", "target", "app", "title", "query", "text"):
        if args.get(key):
            return str(args[key]).splitlines()[0][:120]
    if "window_id" in args:
        return f"Window {args['window_id']}"
    return ""


def permission_detail(name, args):
    if name == "run_shell":
        cwd = f"\n\n# in {args['cwd']}" if args.get("cwd") else ""
        return str(args.get("command", "")) + cwd
    if name == "write_file":
        content = str(args.get("content", ""))
        verb = "Append to" if args.get("append") else "Write to"
        return f"{verb} {args.get('path')}\n\n{content[:2000]}{'…' if len(content) > 2000 else ''}"
    if name == "edit_file":
        old, new = str(args.get("old_text", "")), str(args.get("new_text", ""))
        return f"{args.get('path')}\n\n− {old[:800]}\n\n+ {new[:800]}"
    if name == "delete_path":
        return str(args.get("path", "")) + ("\n\nand everything inside it" if args.get("recursive") else "")
    if name == "move_path":
        return f"{args.get('source')}\n→ {args.get('destination')}"
    if name in ("read_file", "list_directory", "make_directory"):
        return str(args.get("path", ""))
    if name == "close_window":
        return f"Window {args.get('window_id')}"
    return json.dumps(args, indent=2, ensure_ascii=False) if args else ""


def _human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


class ToolExecutor:
    """Runs tool calls on the worker thread; anything touching GTK is marshalled to the main loop."""

    def __init__(self, win):
        self.win = win

    @property
    def cfg(self):
        return self.win.cfg

    def run(self, name, args):
        impl = getattr(self, "t_" + name, None)
        if impl is None or name not in TOOL_META:
            return "error", f"Unknown tool: {name}"
        risk = self.risk(name, args)
        if risk == "confirm" or (risk == "read" and not self.cfg["auto_approve_reads"]):
            if not self.win.request_permission(name, args):
                return "denied", "The user denied this action."
        try:
            return "ok", clip(str(impl(**args)))
        except TypeError as e:
            return "error", f"Bad arguments: {e}"
        except Exception as e:
            return "error", f"{type(e).__name__}: {e}"

    def workspaces(self):
        return valid_workspaces(self.cfg.get("workspaces", []))

    def start_dir(self):
        ws = self.workspaces()
        return ws[0] if ws else Path.home()

    def _p(self, path):
        """Resolve a path; relative ones are taken from the start folder, like shell commands."""
        p = Path(os.path.expanduser(str(path)))
        return (p if p.is_absolute() else self.start_dir() / p).resolve()

    def _location(self, path):
        """Where a path itself lives, without following a final symlink (so deleting a link removes only it)."""
        p = Path(os.path.expanduser(str(path)))
        p = p if p.is_absolute() else self.start_dir() / p
        if p.name in ("", ".", ".."):
            raise ValueError("Name a specific file or folder.")
        return p.parent.resolve() / p.name

    def risk(self, name, args):
        ws = self.workspaces()
        try:
            if ws and name in ("write_file", "edit_file", "make_directory") and args.get("path"):
                if in_workspace(self._p(args["path"]), ws):
                    return "safe"
            elif ws and name == "delete_path" and args.get("path"):
                if in_workspace(self._p(args["path"]), ws, strict=True):
                    return "safe"
            elif ws and name == "move_path" and args.get("source") and args.get("destination"):
                if (in_workspace(self._p(args["source"]), ws, strict=True)
                        and in_workspace(self._p(args["destination"]), ws)):
                    return "safe"
            elif name == "run_shell":
                cwd = self._p(args["cwd"]) if args.get("cwd") else self.start_dir()
                verdict = shell_risk(str(args.get("command", "")), cwd, ws, self.cfg.get("auto_run_harmless"))
                if verdict:
                    return verdict
        except (OSError, ValueError, RuntimeError):
            pass
        return TOOL_META[name][2]

    # shell & files
    def t_run_shell(self, command, cwd=None, **_):
        workdir = self._p(cwd) if cwd else self.start_dir()
        timeout = int(self.cfg["shell_timeout"])
        try:
            p = subprocess.run(["bash", "-c", command], cwd=workdir, capture_output=True, text=True,
                               timeout=timeout, stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            return f"Timed out after {timeout} s."
        out = f"exit code: {p.returncode}"
        if p.stdout:
            out += f"\n--- stdout ---\n{p.stdout.rstrip()}"
        if p.stderr:
            out += f"\n--- stderr ---\n{p.stderr.rstrip()}"
        return out

    def t_read_file(self, path, **_):
        p = self._p(path)
        with open(p, "rb") as f:
            data = f.read(MAX_READ_BYTES)
        if b"\0" in data[:4096]:
            return f"{p} looks like a binary file ({_human(p.stat().st_size)})."
        return data.decode("utf-8", errors="replace") or "(empty file)"

    def t_write_file(self, path, content, append=False, **_):
        p = self._p(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, "a" if append else "w", encoding="utf-8") as f:
            f.write(content)
        return f"{'Appended' if append else 'Wrote'} {len(content)} characters to {p}."

    def t_edit_file(self, path, old_text, new_text, replace_all=False, **_):
        p = self._p(path)
        text = p.read_text(encoding="utf-8")
        if not old_text:
            raise ValueError("old_text is empty.")
        count = text.count(old_text)
        if count == 0:
            raise ValueError("old_text wasn't found. Read the file and copy the text exactly.")
        if count > 1 and not replace_all:
            raise ValueError(f"old_text appears {count} times. Include more surrounding text, or set replace_all.")
        p.write_text(text.replace(old_text, new_text, -1 if replace_all else 1), encoding="utf-8")
        return f"Replaced {count if replace_all else 1} occurrence(s) in {p}."

    def t_delete_path(self, path, recursive=False, **_):
        loc = self._location(path)
        if loc.is_symlink() or loc.is_file():
            loc.unlink()
        elif loc.is_dir():
            shutil.rmtree(loc) if recursive else loc.rmdir()
        else:
            raise FileNotFoundError(f"{loc} doesn't exist.")
        return f"Deleted {loc}."

    def t_move_path(self, source, destination, **_):
        src, dst = self._location(source), self._p(destination)
        dst.parent.mkdir(parents=True, exist_ok=True)
        return f"Moved {src} to {shutil.move(str(src), str(dst))}."

    def t_make_directory(self, path, **_):
        p = self._p(path)
        p.mkdir(parents=True, exist_ok=True)
        return f"Created {p}."

    def t_list_directory(self, path, **_):
        p = self._p(path)
        entries = sorted(p.iterdir(), key=lambda e: (not e.is_dir(), e.name.lower()))
        lines = []
        for e in entries[:400]:
            try:
                lines.append(f"{e.name}/" if e.is_dir() else f"{e.name}  ({_human(e.stat().st_size)})")
            except OSError:
                lines.append(e.name)
        if len(entries) > 400:
            lines.append(f"… and {len(entries) - 400} more")
        return f"{p}\n" + ("\n".join(lines) if lines else "(empty)")

    # clipboard & notifications
    def t_get_clipboard(self, **_):
        def read(done):
            def finish(cb, res):
                try:
                    done(cb.read_text_finish(res))
                except GLib.Error as e:
                    done(None if "not supported" in e.message else RuntimeError(e.message))
            self.win.get_clipboard().read_text_async(None, finish)

        value = on_main_async(read)
        if isinstance(value, Exception):
            raise value
        return value or "(the clipboard is empty or holds no text)"

    def t_set_clipboard(self, text, **_):
        on_main(lambda: self.win.get_clipboard().set(text))
        return "Copied to the clipboard."

    def t_send_notification(self, title, body="", **_):
        def send():
            n = Gio.Notification.new(title)
            if body:
                n.set_body(body)
            self.win.get_application().send_notification(None, n)
        on_main(send)
        return "Notification shown."

    # applications
    def t_list_applications(self, query=None, **_):
        if query:
            apps = []
            for group in DesktopAppInfo.search(query):
                for app_id in group:
                    try:
                        info = DesktopAppInfo.new(app_id)
                    except TypeError:
                        info = None
                    if info:
                        apps.append(info)
            apps = apps[:30]
        else:
            apps = [a for a in Gio.AppInfo.get_all() if a.should_show()]
        lines = sorted({f"{a.get_display_name()} — {a.get_id()}" for a in apps}, key=str.lower)
        return "\n".join(lines) or "No matching applications."

    def t_launch_application(self, app, **_):
        info = None
        for candidate in (app, app + ".desktop"):
            try:
                info = DesktopAppInfo.new(candidate)
            except TypeError:
                info = None
            if info:
                break
        if not info:
            for group in DesktopAppInfo.search(app):
                for app_id in group:
                    try:
                        info = DesktopAppInfo.new(app_id)
                    except TypeError:
                        info = None
                    if info:
                        break
                if info:
                    break
        if not info:
            raise RuntimeError(f"No application matches “{app}”.")
        on_main(lambda: info.launch([], self.win.get_display().get_app_launch_context()))
        return f"Launched {info.get_display_name()}."

    def t_open_uri(self, target, **_):
        target = str(target)
        if re.match(r"^[a-zA-Z][\w+.-]*:", target):
            uri = target
        else:
            uri = Gio.File.new_for_path(str(self._p(target))).get_uri()
        on_main(lambda: Gio.AppInfo.launch_default_for_uri(uri, self.win.get_display().get_app_launch_context()))
        return f"Opened {target}."

    # windows (via the Window Calls extension)
    def _wc(self, method, *ids):
        params = GLib.Variant("(" + "u" * len(ids) + ")", tuple(int(i) for i in ids)) if ids else None
        try:
            bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
            res = bus.call_sync(*WINDOW_CALLS, method, params, None, Gio.DBusCallFlags.NONE, 3000, None)
        except GLib.Error as e:
            raise RuntimeError(f"{e.message} — window control needs the “Window Calls” GNOME Shell "
                               "extension (github.com/ickyicky/window-calls).")
        return res.unpack() if res is not None else ()

    def t_list_windows(self, **_):
        windows = json.loads(self._wc("List")[0])
        lines = []
        for w in windows:
            title = w.get("title")
            if title is None:
                try:
                    title = self._wc("GetTitle", w["id"])[0]
                except RuntimeError:
                    title = "?"
            focus = "  (focused)" if w.get("focus") else ""
            lines.append(f"[{w['id']}] {title} — {w.get('wm_class', '')}{focus}")
        return "\n".join(lines) or "No windows are open."

    def t_focus_window(self, window_id, **_):
        self._wc("Activate", window_id)
        return f"Focused window {window_id}."

    def t_close_window(self, window_id, **_):
        self._wc("Close", window_id)
        return f"Closed window {window_id}."


def os_name():
    try:
        for line in Path("/etc/os-release").read_text().splitlines():
            if line.startswith("PRETTY_NAME="):
                return line.split("=", 1)[1].strip('"')
    except OSError:
        pass
    return platform.system()


def build_system_prompt(cfg, tools_on):
    parts = [
        "You are a capable, concise assistant running locally through Ollama in a desktop chat app. "
        f"The user is on {os_name()} with the {os.environ.get('XDG_CURRENT_DESKTOP', 'unknown')} desktop. "
        f"Their home folder is {Path.home()}. Local time: {datetime.now():%Y-%m-%d %H:%M}. "
        "Format replies in Markdown."
    ]
    if tools_on:
        parts.append(
            "You can act on the user's desktop with the provided tools: shell commands, files, clipboard, "
            "notifications, applications and windows. Use them when they genuinely help, and prefer inspecting "
            "before changing anything. Some actions need the user's approval; if one is denied, do not retry it — "
            "explain or suggest an alternative. After using tools, answer briefly and plainly."
        )
        workspaces = valid_workspaces(cfg.get("workspaces", []))
        if workspaces:
            parts.append(
                "Workspace folders, where you may create, edit, move and delete files without asking: "
                + ", ".join(str(w) for w in workspaces) + f". Shell commands start in {workspaces[0]}, and "
                "relative file paths are taken from there. Do your file work inside these folders when you "
                "can, and use edit_file for small changes to existing files."
            )
    if cfg.get("system_prompt"):
        parts.append(cfg["system_prompt"])
    return "\n\n".join(parts)


def strip_private(msg):
    return {k: v for k, v in msg.items() if not k.startswith("_")}


# ───────────────────────────── markdown → Pango ─────────────────────────────

def _inline(text):
    t = GLib.markup_escape_text(text)
    codes = []

    def keep(m):
        codes.append(m.group(1))
        return f"\x00{len(codes) - 1}\x00"

    t = re.sub(r"`([^`\n]+)`", keep, t)
    t = re.sub(r"\[([^\]\n]+)\]\((https?://[^)\s]+)\)", r'<a href="\2">\1</a>', t)
    t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<i>\1</i>", t)
    t = re.sub(r"~~(.+?)~~", r"<s>\1</s>", t)
    return re.sub(r"\x00(\d+)\x00",
                  lambda m: f'<span font_family="monospace" background="#888888" bgalpha="22%">'
                            f'{codes[int(m.group(1))]}</span>', t)


def _line(line):
    m = re.match(r"^(#{1,6})\s+(.*)", line)
    if m:
        size = {1: "x-large", 2: "large", 3: "large"}.get(len(m.group(1)), "medium")
        return f'<span size="{size}" weight="bold">{_inline(m.group(2))}</span>'
    m = re.match(r"^(\s*)[-*+]\s+(.*)", line)
    if m:
        return f"{m.group(1)}  •  {_inline(m.group(2))}"
    m = re.match(r"^\s*>\s?(.*)", line)
    if m:
        return f'<span alpha="70%"><i>{_inline(m.group(1))}</i></span>'
    if re.match(r"^\s*(-{3,}|\*{3,}|_{3,})\s*$", line):
        return '<span alpha="40%">────────────</span>'
    if line.lstrip().startswith("|"):
        return f'<span font_family="monospace">{GLib.markup_escape_text(line)}</span>'
    return _inline(line)


def md_to_markup(text):
    out, in_code = [], False
    for line in text.split("\n"):
        if line.strip().startswith("```"):
            in_code = not in_code
            continue
        out.append(f'<span font_family="monospace">{GLib.markup_escape_text(line)}</span>' if in_code else _line(line))
    return "\n".join(out)


def split_blocks(text):
    """Split markdown into ('text', str) and ('code', lang, str) segments."""
    blocks, buf, code, lang = [], [], None, ""
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith("```"):
            if code is None:
                if buf:
                    blocks.append(("text", "\n".join(buf)))
                    buf = []
                code, lang = [], stripped[3:].strip()
            else:
                blocks.append(("code", lang, "\n".join(code)))
                code = None
            continue
        (code if code is not None else buf).append(line)
    if code is not None:
        blocks.append(("code", lang, "\n".join(code)))
    if buf:
        blocks.append(("text", "\n".join(buf)))
    return blocks


def set_markup_safe(label, markup, plain):
    probe = re.sub(r"</?a\b[^>]*>", "", markup)  # GtkLabel understands <a>, Pango doesn't
    try:
        Pango.parse_markup(probe, -1, "\0")
        label.set_markup(markup)
    except GLib.Error:
        label.set_text(plain)


# ───────────────────────────── widgets ─────────────────────────────

def make_spinner():
    if hasattr(Adw, "Spinner"):
        s = Adw.Spinner()
    else:
        s = Gtk.Spinner(spinning=True)
    s.set_size_request(20, 20)
    s.set_halign(Gtk.Align.START)
    return s


def text_label(text="", *classes, selectable=True):
    lbl = Gtk.Label(label=text, xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, selectable=selectable)
    for c in classes:
        lbl.add_css_class(c)
    return lbl


class CodeBlock(Gtk.Box):
    def __init__(self, lang, code):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.add_css_class("codeblock")
        self.code = code

        head = Gtk.Box()
        head.add_css_class("code-header")
        lang_lbl = Gtk.Label(label=lang or "code", xalign=0, hexpand=True)
        lang_lbl.add_css_class("dim-label")
        lang_lbl.add_css_class("caption")
        copy = Gtk.Button(icon_name="edit-copy-symbolic", tooltip_text="Copy")
        copy.add_css_class("flat")
        copy.connect("clicked", self._copy)
        head.append(lang_lbl)
        head.append(copy)

        body = Gtk.Label(label=code, xalign=0, selectable=True)
        body.add_css_class("code")
        scroller = Gtk.ScrolledWindow(vscrollbar_policy=Gtk.PolicyType.NEVER, child=body)
        self.append(head)
        self.append(scroller)

    def _copy(self, btn):
        btn.get_clipboard().set(self.code)
        btn.set_icon_name("object-select-symbolic")
        GLib.timeout_add(1200, lambda: btn.set_icon_name("edit-copy-symbolic") or False)


class MarkdownView(Gtk.Box):
    def __init__(self):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self.live = text_label("", "md")
        self.append(self.live)

    def set_streaming(self, text):
        set_markup_safe(self.live, md_to_markup(text), text)

    def finalize(self, text):
        child = self.get_first_child()
        while child:
            nxt = child.get_next_sibling()
            self.remove(child)
            child = nxt
        for block in split_blocks(text):
            if block[0] == "code":
                self.append(CodeBlock(block[1], block[2]))
            else:
                chunk = block[1].strip("\n")
                if chunk.strip():
                    lbl = text_label("", "md")
                    set_markup_safe(lbl, md_to_markup(chunk), chunk)
                    self.append(lbl)


class ToolCard(Adw.ExpanderRow):
    def __init__(self, name, args):
        label, icon, _ = TOOL_META.get(name, (name or "Unknown tool", "system-run-symbolic", "confirm"))
        super().__init__(title=label, subtitle=summarize(name, args), use_markup=False)
        self.set_subtitle_lines(1)
        self.add_prefix(Gtk.Image.new_from_icon_name(icon))

        self.spinner = make_spinner()
        self.spinner.set_valign(Gtk.Align.CENTER)
        self.status = Gtk.Image(visible=False)
        self.add_suffix(self.spinner)
        self.add_suffix(self.status)

        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6,
                       margin_top=12, margin_bottom=12, margin_start=12, margin_end=12)
        if args:
            body.append(text_label("Arguments", "caption-heading", selectable=False))
            body.append(text_label(json.dumps(args, indent=2, ensure_ascii=False), "tool-output"))
        body.append(text_label("Result", "caption-heading", selectable=False))
        self.output = text_label("Waiting…", "tool-output", "dim-label")
        body.append(Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True,
                                       max_content_height=320, child=self.output))
        self.add_row(body)

    def set_result(self, status, output):
        self.spinner.set_visible(False)
        icon, css = {"ok": ("object-select-symbolic", "success"),
                     "denied": ("action-unavailable-symbolic", "warning")}.get(status, ("dialog-error-symbolic", "error"))
        self.status.set_from_icon_name(icon)
        self.status.add_css_class(css)
        self.status.set_visible(True)
        self.output.remove_css_class("dim-label")
        self.output.set_text(output)


class AssistantStep(Gtk.Box):
    """One model response: optional reasoning, the reply, and any tool calls it made."""

    def __init__(self, show_stats=True):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        self._has_tools = False
        self.stats_enabled = show_stats
        self.stats = Gtk.Label(xalign=0, wrap=True, visible=False)
        for c in ("caption", "dim-label", "numeric"):
            self.stats.add_css_class(c)
        self.spinner = make_spinner()

        self.think_title = Gtk.Label(label="Thinking…")
        self.think_title.add_css_class("dim-label")
        self.think_text = text_label("", "dim-label", "thinking-text")
        self.think = Gtk.Expander(label_widget=self.think_title, child=self.think_text, visible=False)

        self.md = MarkdownView()
        self.md.set_visible(False)

        self.tools = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, visible=False)
        self.tools.add_css_class("boxed-list")

        for w in (self.spinner, self.think, self.md, self.stats, self.tools):
            self.append(w)

    def _show_stats(self, text):
        self.stats.set_text(text)
        self.stats.set_visible(self.stats_enabled and bool(text))

    def set_live_rate(self, rate):
        self._show_stats(f"≈ {rate:.0f} tokens/s")

    def set_stats(self, stats):
        self.stats.set_tooltip_text(stats_tooltip(stats))
        self._show_stats(format_stats(stats))

    def set_stats_enabled(self, on):
        self.stats_enabled = on
        self.stats.set_visible(on and bool(self.stats.get_text()))

    def set_thinking(self, text):
        self.spinner.set_visible(False)
        self.think.set_visible(True)
        self.think_text.set_text(text.strip())

    def set_content(self, text):
        self.spinner.set_visible(False)
        self.md.set_visible(True)
        self.md.set_streaming(text)

    def finish(self, content, thinking):
        self.spinner.set_visible(False)
        if thinking.strip():
            self.think.set_visible(True)
            self.think_text.set_text(thinking.strip())
            self.think_title.set_text("Thought process")
        if content.strip():
            self.md.set_visible(True)
            self.md.finalize(content.strip())
        else:
            self.md.set_visible(False)
        self.set_visible(bool(content.strip() or thinking.strip() or self._has_tools))

    def add_tool_card(self, name, args):
        self._has_tools = True
        self.set_visible(True)
        self.spinner.set_visible(False)
        self.tools.set_visible(True)
        card = ToolCard(name, args)
        self.tools.append(card)
        return card


class UserBubble(Gtk.Box):
    def __init__(self, text):
        super().__init__(halign=Gtk.Align.END, margin_start=64)
        lbl = text_label(text, "bubble-user")
        lbl.set_max_width_chars(56)
        self.append(lbl)


class ConvRow(Gtk.ListBoxRow):
    def __init__(self, conv, on_delete):
        super().__init__()
        self.conv_id = conv["id"]
        box = Gtk.Box(spacing=6)
        title = Gtk.Label(label=conv.get("title") or "Untitled", xalign=0, hexpand=True,
                          ellipsize=Pango.EllipsizeMode.END)
        delete = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Delete chat")
        for c in ("flat", "circular", "row-delete"):
            delete.add_css_class(c)
        delete.connect("clicked", lambda *_: on_delete(self.conv_id))
        box.append(title)
        box.append(delete)
        self.set_child(box)


# ───────────────────────────── window ─────────────────────────────

class Window(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title=APP_NAME, default_width=1120, default_height=780,
                         width_request=360, height_request=420)
        self.cfg = app.cfg
        self.convs = load_conversations()
        self.conv = None
        self.models = []
        self.model_info = {}
        self.generating = False
        self.cancel = threading.Event()
        self.client = None
        self._suppress_select = False
        self._updating_models = False
        self._stick = True

        self.split = Adw.NavigationSplitView(min_sidebar_width=230, max_sidebar_width=300)
        self.split.set_sidebar(self._build_sidebar())
        self.split.set_content(self._build_content())
        self.set_content(self.split)

        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 640sp"))
        bp.add_setter(self.split, "collapsed", True)
        self.add_breakpoint(bp)

        self._refresh_sidebar()
        self.refresh_models()
        self.input.grab_focus()

    # ── layout ──
    def _build_sidebar(self):
        header = Adw.HeaderBar()
        self.new_btn = Gtk.Button(icon_name="list-add-symbolic", tooltip_text="New chat (Ctrl+N)",
                                  action_name="app.new-chat")
        header.pack_start(self.new_btn)
        menu = Gio.Menu()
        menu.append("Preferences", "app.preferences")
        menu.append(f"About {APP_NAME}", "app.about")
        header.pack_end(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, primary=True,
                                       tooltip_text="Main menu"))

        self.sidebar_list = Gtk.ListBox()
        self.sidebar_list.add_css_class("navigation-sidebar")
        self.sidebar_list.connect("row-selected", self._on_row_selected)

        view = Adw.ToolbarView(content=Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER,
                                                          child=self.sidebar_list))
        view.add_top_bar(header)
        return Adw.NavigationPage(title="Chats", child=view)

    def _build_content(self):
        header = Adw.HeaderBar()
        self.model_list = Gtk.StringList()
        self.model_dd = Gtk.DropDown(model=self.model_list, tooltip_text="Model")
        self.model_dd.add_css_class("flat")
        self.model_dd.connect("notify::selected", self._on_model_selected)
        header.set_title_widget(self.model_dd)

        self.agent_btn = Gtk.ToggleButton(icon_name="system-run-symbolic", active=self.cfg["agent"],
                                          tooltip_text="Agent mode: lets the model run commands, use files, the "
                                                       "clipboard, apps and windows. Anything risky asks first.")
        self.agent_btn.connect("toggled", lambda b: self._set_cfg("agent", b.get_active()))
        header.pack_end(self.agent_btn)
        tune = Gtk.Button(icon_name="ollama-desk-tune-symbolic", tooltip_text="Tune this model",
                          action_name="app.tune")
        header.pack_end(tune)

        self.banner = Adw.Banner(button_label="Retry")
        self.banner.connect("button-clicked", lambda *_: self.refresh_models())

        self.chat_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=22)
        self.chat_box.add_css_class("chat-column")
        self.scroller = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True,
                                           child=Adw.Clamp(maximum_size=820, tightening_threshold=600,
                                                           child=self.chat_box))
        vadj = self.scroller.get_vadjustment()
        vadj.connect("notify::upper", self._on_upper_changed)
        vadj.connect("value-changed", self._on_scrolled)

        self.stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE)
        self.stack.add_named(self._build_empty(), "empty")
        self.stack.add_named(self.scroller, "chat")

        view = Adw.ToolbarView(content=self.stack)
        view.add_top_bar(header)
        view.add_top_bar(self.banner)
        view.add_bottom_bar(self._build_composer())

        self.toasts = Adw.ToastOverlay(child=view)
        return Adw.NavigationPage(title=APP_NAME, child=self.toasts)

    def _build_empty(self):
        page = Adw.StatusPage(icon_name="computer-symbolic", title="Ask anything",
                              description="Your models run entirely on this machine. Turn on agent mode and "
                                          "they can work on your desktop too; anything consequential asks first.")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, halign=Gtk.Align.CENTER)
        for text in SUGGESTIONS:
            b = Gtk.Button(label=text)
            b.add_css_class("pill")
            b.connect("clicked", lambda _b, t=text: self.send_text(t))
            box.append(b)
        page.set_child(box)
        return page

    def _build_composer(self):
        self.input = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR, top_margin=10, bottom_margin=10,
                                  accepts_tab=False, hexpand=True)
        self.input.add_css_class("composer-text")
        placeholder = Gtk.Label(label="Message", xalign=0, valign=Gtk.Align.START, margin_top=10, can_target=False)
        placeholder.add_css_class("dim-label")
        self.input.get_buffer().connect("changed", lambda b: placeholder.set_visible(b.get_char_count() == 0))
        overlay = Gtk.Overlay(child=self.input)
        overlay.add_overlay(placeholder)

        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.input.add_controller(keys)

        self.send_btn = Gtk.Button(icon_name="go-up-symbolic", valign=Gtk.Align.END, margin_bottom=2,
                                   tooltip_text="Send (Enter)")
        self.send_btn.add_css_class("circular")
        self.send_btn.add_css_class("suggested-action")
        self.send_btn.connect("clicked", lambda *_: self.stop() if self.generating else self._send_from_input())

        frame = Gtk.Box(spacing=6)
        frame.add_css_class("composer")
        frame.append(Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True,
                                        max_content_height=220, hexpand=True, child=overlay))
        frame.append(self.send_btn)
        return Adw.Clamp(maximum_size=820, child=frame, margin_start=12, margin_end=12,
                         margin_top=6, margin_bottom=16)

    # ── small helpers ──
    def toast(self, message):
        self.toasts.add_toast(Adw.Toast(title=GLib.markup_escape_text(message), timeout=4))
        return False

    def _set_cfg(self, key, value):
        self.cfg[key] = value
        save_config(self.cfg)

    def _on_upper_changed(self, adj, _pspec):
        if self._stick:
            adj.set_value(adj.get_upper() - adj.get_page_size())

    def _on_scrolled(self, adj):
        self._stick = adj.get_value() >= adj.get_upper() - adj.get_page_size() - 48

    def _on_key(self, _ctrl, keyval, _code, state):
        if keyval in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and not state & Gdk.ModifierType.SHIFT_MASK:
            if not self.generating:
                self._send_from_input()
            return True
        return False

    def _clear_chat(self):
        child = self.chat_box.get_first_child()
        while child:
            nxt = child.get_next_sibling()
            self.chat_box.remove(child)
            child = nxt

    # ── models ──
    def refresh_models(self):
        host = self.cfg["host"]
        self.model_info.clear()

        def work():
            try:
                GLib.idle_add(self._set_models, Ollama(host).models(), None)
            except Exception as e:
                GLib.idle_add(self._set_models, [], str(e) or type(e).__name__)

        threading.Thread(target=work, daemon=True).start()

    def _set_models(self, models, error):
        self._updating_models = True
        self.models = models
        self.model_list.splice(0, self.model_list.get_n_items(), models or ["No models"])
        self.model_dd.set_sensitive(bool(models) and not self.generating)
        if models:
            want = self.cfg.get("model")
            self.model_dd.set_selected(models.index(want) if want in models else 0)
            if want not in models:
                self._set_cfg("model", models[0])
        self._updating_models = False

        if error:
            self.banner.set_title(f"Ollama isn't reachable at {self.cfg['host']}. Start it with “ollama serve”.")
        elif not models:
            self.banner.set_title("No models installed. Pull one with “ollama pull llama3.2”.")
        self.banner.set_revealed(bool(error) or not models)
        return False

    def current_model(self):
        i = self.model_dd.get_selected()
        return self.models[i] if self.models and 0 <= i < len(self.models) else None

    def _on_model_selected(self, *_):
        if not self._updating_models and self.models:
            self._set_cfg("model", self.current_model())

    # ── conversations ──
    def _refresh_sidebar(self):
        self._suppress_select = True
        self.sidebar_list.remove_all()
        for conv in self.convs:
            row = ConvRow(conv, self.delete_conv)
            self.sidebar_list.append(row)
            if conv is self.conv:
                self.sidebar_list.select_row(row)
        self._suppress_select = False

    def _on_row_selected(self, _lb, row):
        if self._suppress_select or row is None:
            return
        conv = next((c for c in self.convs if c["id"] == row.conv_id), None)
        if conv is not None and conv is not self.conv:
            self.open_conversation(conv)
        self.split.set_show_content(True)

    def new_chat(self):
        if self.generating:
            return
        self.conv = None
        self._clear_chat()
        self.stack.set_visible_child_name("empty")
        self._suppress_select = True
        self.sidebar_list.unselect_all()
        self._suppress_select = False
        self.split.set_show_content(True)
        self.input.grab_focus()

    def open_conversation(self, conv):
        self.conv = conv
        self._clear_chat()
        pending = []
        for m in conv.get("messages", []):
            role = m.get("role")
            if role == "user":
                self.chat_box.append(UserBubble(m.get("content", "")))
                pending = []
            elif role == "assistant":
                step = AssistantStep(self.cfg["show_stats"])
                self.chat_box.append(step)
                for call in m.get("tool_calls") or []:
                    fn = call.get("function") or {}
                    pending.append(step.add_tool_card(fn.get("name", ""), parse_args(fn.get("arguments"))))
                step.finish(m.get("content", ""), m.get("thinking", ""))
                step.set_stats(m.get("_stats"))
            elif role == "tool" and pending:
                pending.pop(0).set_result(m.get("_status", "ok"), m.get("content", ""))
        if conv.get("model") in self.models:
            self.model_dd.set_selected(self.models.index(conv["model"]))
        self._stick = True
        self.stack.set_visible_child_name("chat" if conv.get("messages") else "empty")
        self.input.grab_focus()

    def delete_conv(self, cid):
        if self.generating:
            return
        conv = next((c for c in self.convs if c["id"] == cid), None)
        if conv is None:
            return
        self.convs.remove(conv)
        delete_conversation_file(cid)
        if conv is self.conv:
            self.new_chat()
        self._refresh_sidebar()

        def undo(_toast):
            save_conversation(conv)
            self.convs.append(conv)
            self.convs.sort(key=lambda c: c.get("updated", 0), reverse=True)
            self._refresh_sidebar()

        toast = Adw.Toast(title="Chat deleted", button_label="Undo", timeout=5)
        toast.connect("button-clicked", undo)
        self.toasts.add_toast(toast)

    # ── sending ──
    def _send_from_input(self):
        buf = self.input.get_buffer()
        text = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
        if self.send_text(text):
            buf.set_text("")

    def send_text(self, text):
        text = text.strip()
        if not text or self.generating:
            return False
        model = self.current_model()
        if not model:
            self.toast("Choose a model first. Is Ollama running?")
            return False
        now = time.time()
        if self.conv is None:
            self.conv = {"id": uuid.uuid4().hex, "title": text.splitlines()[0][:60], "model": model,
                         "created": now, "updated": now, "messages": []}
            self.convs.insert(0, self.conv)
        self.conv["model"] = model
        self.conv["messages"].append({"role": "user", "content": text})

        self.stack.set_visible_child_name("chat")
        self._stick = True
        bubble = Gtk.Revealer(transition_type=Gtk.RevealerTransitionType.CROSSFADE, transition_duration=180,
                              child=UserBubble(text))
        self.chat_box.append(bubble)
        GLib.idle_add(lambda: bubble.set_reveal_child(True) or False)

        self.cancel = threading.Event()
        self._set_generating(True)
        self._refresh_sidebar()
        threading.Thread(target=self._agent_loop, daemon=True,
                         args=(self.conv, model, list(self.conv["messages"]), self.agent_btn.get_active())).start()
        return True

    def stop(self):
        self.cancel.set()
        if self.client:
            self.client.abort()

    def _set_generating(self, on):
        self.generating = on
        self.send_btn.set_icon_name("media-playback-stop-symbolic" if on else "go-up-symbolic")
        self.send_btn.set_tooltip_text("Stop" if on else "Send (Enter)")
        self.send_btn.remove_css_class("suggested-action" if on else "destructive-action")
        self.send_btn.add_css_class("destructive-action" if on else "suggested-action")
        self.sidebar_list.set_sensitive(not on)
        self.new_btn.set_sensitive(not on)
        self.model_dd.set_sensitive(not on and bool(self.models))

    def _new_step(self):
        step = AssistantStep(self.cfg["show_stats"])
        self.chat_box.append(step)
        return step

    # ── agent loop (worker thread) ──
    def _agent_loop(self, conv, model, msgs, tools_on):
        self.client = client = Ollama(self.cfg["host"])
        executor = ToolExecutor(self)
        error = None
        info = self.model_info.get(model) or self._fetch_info(model)
        try:
            for _ in range(MAX_AGENT_STEPS):
                if self.cancel.is_set():
                    break
                payload = {
                    "model": model, "stream": True,
                    "messages": [{"role": "system", "content": build_system_prompt(self.cfg, tools_on)}]
                                + [strip_private(m) for m in msgs],
                }
                payload["options"], keep_alive = self.request_options(model, info)
                if keep_alive is not None:
                    payload["keep_alive"] = keep_alive
                if tools_on:
                    payload["tools"] = TOOLS

                step = on_main(self._new_step)
                content, thinking, calls = "", "", []
                push_content, push_thinking = Throttle(step.set_content), Throttle(step.set_thinking)
                push_rate = Throttle(step.set_live_rate, 250)
                stats, first_token, tokens = {}, None, 0
                try:
                    for chunk in client.chat(payload):
                        if self.cancel.is_set():
                            break
                        if chunk.get("error"):
                            raise OllamaError(chunk["error"])
                        m = chunk.get("message") or {}
                        if m.get("thinking"):
                            thinking += m["thinking"]
                            push_thinking.push(thinking)
                        if m.get("content"):
                            content += m["content"]
                            push_content.push(content)
                        calls.extend(m.get("tool_calls") or [])
                        if m.get("thinking") or m.get("content"):
                            tokens += 1
                            now = time.monotonic()
                            if first_token is None:
                                first_token = now
                            elif now - first_token > 0.3:
                                push_rate.push((tokens - 1) / (now - first_token))
                        if chunk.get("done"):
                            stats = {k: chunk[k] for k in STAT_KEYS if k in chunk}
                except Exception as e:
                    if self.cancel.is_set():
                        pass
                    elif isinstance(e, OllamaError) and tools_on and "support tools" in str(e).lower():
                        tools_on = False
                        GLib.idle_add(self.toast, f"{model} can't use tools, so it will answer without them")
                        continue
                    else:
                        raise
                finally:
                    for t in (push_content, push_thinking, push_rate):
                        t.cancel()
                    on_main(step.finish, content, thinking)
                    on_main(step.set_stats, stats)

                reply = {"role": "assistant", "content": content}
                if thinking:
                    reply["thinking"] = thinking
                if stats:
                    reply["_stats"] = stats
                if calls and not self.cancel.is_set():
                    reply["tool_calls"] = calls
                msgs.append(reply)
                if "tool_calls" not in reply:
                    break

                for call in calls:
                    fn = call.get("function") or {}
                    name, args = fn.get("name", ""), parse_args(fn.get("arguments"))
                    card = on_main(step.add_tool_card, name, args)
                    if self.cancel.is_set():
                        status, output = "denied", "Cancelled by the user."
                    else:
                        status, output = executor.run(name, args)
                    on_main(card.set_result, status, output)
                    msgs.append({"role": "tool", "tool_name": name, "content": output, "_status": status})
                if self.cancel.is_set():
                    break
            else:
                GLib.idle_add(self.toast, f"Stopped after {MAX_AGENT_STEPS} tool steps")
        except (ConnectionRefusedError, socket.gaierror):
            error = f"Ollama isn't reachable at {self.cfg['host']}"
        except Exception as e:
            if not self.cancel.is_set():
                error = str(e) or type(e).__name__
        finally:
            GLib.idle_add(self._on_finished, conv, msgs, error)

    def _on_finished(self, conv, msgs, error):
        conv["messages"] = msgs
        conv["updated"] = time.time()
        save_conversation(conv)
        self.convs.sort(key=lambda c: c.get("updated", 0), reverse=True)
        self.client = None
        self._set_generating(False)
        self._refresh_sidebar()
        if error:
            self.toast(error)
        self.input.grab_focus()
        return False

    # ── permissions ──
    def request_permission(self, name, args):
        """Called from the worker; blocks until the user answers."""
        result = on_main_async(lambda done: self._permission_dialog(name, args, done))
        return result is True

    def _permission_dialog(self, name, args, done):
        label = TOOL_META.get(name, (name,))[0]
        dlg = Adw.AlertDialog(heading=f"{label}?",
                              body=PERMISSION_BODY.get(name, "The model wants to perform this action."))
        detail = permission_detail(name, args)
        if detail:
            lbl = Gtk.Label(label=detail, xalign=0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR,
                            margin_top=12, margin_bottom=12, margin_start=12, margin_end=12)
            lbl.add_css_class("monospace")
            scroller = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True,
                                          max_content_height=260, child=lbl)
            scroller.add_css_class("card")
            dlg.set_extra_child(scroller)
        dlg.add_response("deny", "Deny")
        dlg.add_response("allow", "Allow")
        dlg.set_response_appearance("allow", Adw.ResponseAppearance.DESTRUCTIVE if name in DESTRUCTIVE
                                    else Adw.ResponseAppearance.SUGGESTED)
        dlg.set_default_response("deny")
        dlg.set_close_response("deny")
        dlg.connect("response", lambda _d, response: done(response == "allow"))

        if not self.is_active():
            n = Gio.Notification.new(f"{APP_NAME} needs your approval")
            n.set_body(f"{label}: {summarize(name, args)}")
            n.set_priority(Gio.NotificationPriority.HIGH)
            self.get_application().send_notification("approval", n)
        dlg.present(self)

    # ── dialogs ──
    def show_preferences(self):
        dlg = Adw.PreferencesDialog()
        page = Adw.PreferencesPage()

        conn = Adw.PreferencesGroup(title="Connection")
        host = Adw.EntryRow(title="Ollama server", text=self.cfg["host"], show_apply_button=True,
                            tooltip_text="The address Ollama listens on. Change it only if Ollama runs on "
                                         "another computer or port. Press Enter or the check mark to apply.")
        host.connect("apply", self._on_host_applied)
        conn.add(host)
        page.add(conn)

        chat = Adw.PreferencesGroup(title="Chat")
        stats = Adw.SwitchRow(title="Show generation stats", subtitle="Speed and token counts under each reply",
                              active=self.cfg["show_stats"],
                              tooltip_text="Shows how fast the model writes, how long each reply was, and how "
                                           "long loading took. Handy for comparing models and settings.")
        stats.connect("notify::active", lambda r, _p: self._set_show_stats(r.get_active()))
        chat.add(stats)
        page.add(chat)

        agent = Adw.PreferencesGroup(title="Agent", description="Changing files outside your workspace folders, "
                                                                 "and closing windows, always ask for your approval.")
        reads = Adw.SwitchRow(title="Allow reading without asking",
                              subtitle="Files, folders and the clipboard", active=self.cfg["auto_approve_reads"],
                              tooltip_text="When on, the model can open files, list folders and see your "
                                           "clipboard without asking each time. It still can't change anything "
                                           "outside your workspace folders without your approval. Turn it off to approve every look.")
        reads.connect("notify::active", lambda r, _p: self._set_cfg("auto_approve_reads", r.get_active()))
        agent.add(reads)
        harmless = Adw.SwitchRow(
            title="Run harmless commands without asking",
            subtitle="Read-only commands such as ls, df or git status",
            active=self.cfg["auto_run_harmless"],
            tooltip_text="A command runs without asking only if every part of it is a known read-only tool used "
                         "in a read-only way: for example ls, cat, grep, du, git status or pacman -Q. Writing to "
                         "files, nested commands, variables and sudo all count as unsure. Anything else, including "
                         "rm and every program the app doesn't recognize, still asks, unless it only touches your "
                         "workspace folders. If reading needs approval (the switch above), these ask too.")
        harmless.connect("notify::active", lambda r, _p: self._set_cfg("auto_run_harmless", r.get_active()))
        agent.add(harmless)
        timeout = Adw.SpinRow.new_with_range(5, 600, 5)
        timeout.set_title("Command time limit")
        timeout.set_subtitle("Seconds before a command is stopped")
        timeout.set_tooltip_text("Commands still running after this many seconds are stopped, so a stuck "
                                 "command can't freeze the conversation.")
        timeout.set_value(self.cfg["shell_timeout"])
        timeout.connect("notify::value", lambda r, _p: self._set_cfg("shell_timeout", int(r.get_value())))
        agent.add(timeout)
        page.add(agent)

        ws_group = Adw.PreferencesGroup(
            title="Workspace folders",
            description="Inside these folders the model can create, edit, move and delete files without asking, "
                        "including with rm, mv, cp, mkdir and touch. Commands start in the starred folder.")
        add = Gtk.Button(icon_name="list-add-symbolic", valign=Gtk.Align.CENTER,
                         tooltip_text="Add a folder the model may change freely. Your whole home folder, hidden "
                                      "settings folders and folders holding programs aren't allowed.")
        add.add_css_class("flat")
        add.connect("clicked", lambda *_: self._pick_workspace(ws_group, dlg))
        ws_group.set_header_suffix(add)
        self._ws_rows = []
        self._fill_workspaces(ws_group)
        page.add(ws_group)

        instr = Adw.PreferencesGroup(title="Instructions")
        system = Adw.EntryRow(title="Extra instructions for every chat", text=self.cfg["system_prompt"],
                              show_apply_button=True,
                              tooltip_text="Text the model reads before every conversation, such as “Keep "
                                           "answers short” or “Reply in the language I write in”. Press Enter "
                                           "or the check mark to apply.")
        system.connect("apply", lambda r: self._set_cfg("system_prompt", r.get_text().strip()))
        instr.add(system)
        page.add(instr)

        dlg.add(page)
        dlg.present(self)

    def _set_show_stats(self, on):
        self._set_cfg("show_stats", on)
        child = self.chat_box.get_first_child()
        while child:
            if isinstance(child, AssistantStep):
                child.set_stats_enabled(on)
            child = child.get_next_sibling()

    # ── per-model tuning ──
    def _fetch_info(self, model):
        try:
            info = parse_model_info(Ollama(self.cfg["host"]).show(model))
        except Exception:
            info = dict(EMPTY_INFO)
        self.model_info[model] = info
        return info

    def _default_for(self, p, info):
        value = info["defaults"].get(p.key, self.cfg["num_ctx"] if p.key == "num_ctx" else p.default)
        if p.key == "num_ctx" and info.get("ctx_max"):
            value = min(value, info["ctx_max"])
        return value

    def request_options(self, model, info):
        overrides = dict(self.cfg.get("model_options", {}).get(model, {}))
        keep_alive = overrides.pop("keep_alive", None)
        options = {"num_ctx": int(self._default_for(PARAM_BY_KEY["num_ctx"], info))}
        options.update({k: v for k, v in overrides.items() if k in PARAM_BY_KEY})
        return options, keep_alive

    def _set_override(self, model, key, value, default):
        store = self.cfg.setdefault("model_options", {})
        overrides = store.setdefault(model, {})
        if value == default:
            overrides.pop(key, None)
        else:
            overrides[key] = value
        if not overrides:
            store.pop(model, None)
        save_config(self.cfg)

    def show_tuning(self):
        model = self.current_model()
        if not model:
            self.toast("Choose a model first.")
            return
        if model in self.model_info:
            self._open_tuning(model)
            return
        threading.Thread(target=lambda: (self._fetch_info(model),
                                         GLib.idle_add(lambda: self._open_tuning(model) or False)),
                         daemon=True).start()

    def _param_row(self, model, p, default, upper):
        overrides = self.cfg.get("model_options", {}).get(model, {})
        current = overrides.get(p.key, default)
        normalize = (lambda v: round(v, p.digits)) if p.digits else (lambda v: int(round(v)))
        adj = Gtk.Adjustment(lower=p.lo, upper=upper, step_increment=p.step, page_increment=p.step * 10)
        spin = Adw.SpinRow(adjustment=adj, digits=p.digits)

        if p.off is None:
            spin.set_title(p.title)
            spin.set_subtitle(p.subtitle)
            spin.set_value(current)
            spin.set_tooltip_text(TIPS.get(p.key))
            spin.connect("notify::value",
                         lambda r, _p: self._set_override(model, p.key, normalize(r.get_value()), default))
            return spin, lambda: spin.set_value(default)

        row = Adw.ExpanderRow(title=p.title, subtitle=p.subtitle, show_enable_switch=True,
                              tooltip_text=TIPS.get(p.key))
        spin.set_tooltip_text(TIPS.get(p.key))
        fallback = default if default != p.off else p.suggested
        spin.set_title(p.unit)
        spin.set_value(current if current != p.off else fallback)
        row.set_enable_expansion(current != p.off)
        row.set_expanded(False)
        row.add_row(spin)

        def sync(*_):
            value = normalize(spin.get_value()) if row.get_enable_expansion() else p.off
            self._set_override(model, p.key, value, default)

        row.connect("notify::enable-expansion", sync)
        spin.connect("notify::value", sync)

        def reset():
            spin.set_value(fallback)
            row.set_enable_expansion(default != p.off)
        return row, reset

    def _open_tuning(self, model):
        info = self.model_info.get(model) or dict(EMPTY_INFO)
        dlg = Adw.PreferencesDialog(title=f"Tune {model}")
        page = Adw.PreferencesPage()

        about_bits = [info["summary"]] if info["summary"] else []
        if info.get("ctx_max"):
            about_bits.append(f"context up to {info['ctx_max']:,} tokens")
        description = ", ".join(about_bits)
        if info.get("tools") is False:
            description += ("\n" if description else "") + "This model can't use agent tools."
        about = Adw.PreferencesGroup(title=model, description=description or None)
        reset_btn = Gtk.Button(label="Reset to defaults", valign=Gtk.Align.CENTER)
        reset_btn.add_css_class("flat")
        about.set_header_suffix(reset_btn)
        page.add(about)

        groups = {
            "style": Adw.PreferencesGroup(title="Response style",
                                          description="Defaults come from the model itself where it sets them."),
            "speed": Adw.PreferencesGroup(title="Speed and memory",
                                          description="A token is a piece of a word, roughly three quarters "
                                                      "of an English word. Hover over any setting for details."),
        }
        resets = []
        for p in PARAMS:
            upper = info["ctx_max"] if p.key == "num_ctx" and info.get("ctx_max") else p.hi
            row, reset = self._param_row(model, p, self._default_for(p, info), upper)
            groups[p.group].add(row)
            resets.append(reset)

        keep = Adw.ComboRow(title="Keep loaded", subtitle="How long the model stays in memory after a reply",
                            model=Gtk.StringList.new([label for label, _ in KEEP_ALIVE]),
                            tooltip_text=TIPS["keep_alive"])
        values = [v for _, v in KEEP_ALIVE]
        current = self.cfg.get("model_options", {}).get(model, {}).get("keep_alive")
        keep.set_selected(values.index(current) if current in values else 0)
        keep.connect("notify::selected",
                     lambda r, _p: self._set_override(model, "keep_alive", values[r.get_selected()], None))
        groups["speed"].add(keep)
        resets.append(lambda: keep.set_selected(0))

        for g in groups.values():
            page.add(g)

        def reset_all(_b):
            for r in resets:
                r()
            self.cfg.get("model_options", {}).pop(model, None)
            save_config(self.cfg)
            self.toast(f"{model} is back to its defaults")

        reset_btn.connect("clicked", reset_all)
        dlg.add(page)
        dlg.present(self)

    # ── workspace folders ──
    def _fill_workspaces(self, group):
        for row in self._ws_rows:
            group.remove(row)
        self._ws_rows = []
        folders = self.cfg.get("workspaces", [])
        if not folders:
            empty = Adw.ActionRow(title="No folders yet", subtitle="Use the + button to add one")
            empty.add_css_class("dim-label")
            self._ws_rows.append(empty)
        for i, folder in enumerate(folders):
            path = Path(folder)
            row = Adw.ActionRow(title=path.name or folder, use_markup=False,
                                subtitle=folder if path.is_dir() else f"{folder} (missing, ignored)")
            star = Gtk.Button(icon_name="starred-symbolic" if i == 0 else "non-starred-symbolic",
                              valign=Gtk.Align.CENTER,
                              tooltip_text="Commands start here" if i == 0 else "Start commands here instead")
            star.add_css_class("flat")
            star.connect("clicked", lambda _b, f=folder: self._ws_update(group, first=f))
            remove = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER,
                                tooltip_text="Stop treating this as a workspace. The folder itself is kept.")
            remove.add_css_class("flat")
            remove.connect("clicked", lambda _b, f=folder: self._ws_update(group, remove=f))
            row.add_suffix(star)
            row.add_suffix(remove)
            self._ws_rows.append(row)
        for row in self._ws_rows:
            group.add(row)

    def _ws_update(self, group, first=None, remove=None, add=None):
        folders = [f for f in self.cfg.get("workspaces", []) if f not in (first, remove, add)]
        if first:
            folders.insert(0, first)
        if add:
            folders.append(add)
        self._set_cfg("workspaces", folders)
        self._fill_workspaces(group)

    def _pick_workspace(self, group, prefs):
        chooser = Gtk.FileDialog(title="Choose a workspace folder",
                                 initial_folder=Gio.File.new_for_path(str(Path.home())))

        def chosen(dialog, result):
            try:
                folder = dialog.select_folder_finish(result)
            except GLib.Error:
                return  # cancelled
            path = Path(folder.get_path()).resolve()
            problem = workspace_problem(path)
            if problem:
                prefs.add_toast(Adw.Toast(title=GLib.markup_escape_text(problem), timeout=5))
            else:
                self._ws_update(group, add=str(path))

        chooser.select_folder(self, None, chosen)

    def _on_host_applied(self, row):
        self._set_cfg("host", row.get_text().strip() or DEFAULT_CONFIG["host"])
        self.refresh_models()

    def show_about(self):
        Adw.AboutDialog(application_name=APP_NAME, application_icon="utilities-terminal", version="1.0",
                        comments="Chat with local models through Ollama, and let them lend a hand on your desktop.",
                        license_type=Gtk.License.MIT_X11).present(self)


# ───────────────────────────── application ─────────────────────────────

class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.cfg = load_config()

    def _win(self):
        win = self.props.active_window
        if win is None:
            win = Window(self)
        return win

    def do_startup(self):
        Adw.Application.do_startup(self)
        provider = Gtk.CssProvider()
        provider.load_from_string(CSS)
        display = Gdk.Display.get_default()
        Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        icons = Path(GLib.get_user_cache_dir()) / "ollama-desk" / "icons"
        try:
            icons.mkdir(parents=True, exist_ok=True)
            (icons / "ollama-desk-tune-symbolic.svg").write_text(TUNE_ICON)
            Gtk.IconTheme.get_for_display(display).add_search_path(str(icons))
        except OSError:
            pass
        actions = [
            ("new-chat", lambda *_: self._win().new_chat(), ["<primary>n"]),
            ("preferences", lambda *_: self._win().show_preferences(), ["<primary>comma"]),
            ("tune", lambda *_: self._win().show_tuning(), ["<primary>t"]),
            ("about", lambda *_: self._win().show_about(), []),
            ("quit", lambda *_: self.quit(), ["<primary>q"]),
        ]
        for name, callback, accels in actions:
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", callback)
            self.add_action(action)
            if accels:
                self.set_accels_for_action(f"app.{name}", accels)

    def do_activate(self):
        self._win().present()


if __name__ == "__main__":
    import sys
    sys.exit(App().run(sys.argv))
