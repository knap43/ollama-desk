# Ollama Desk

A native GNOME app for chatting with local models through [Ollama](https://ollama.com), written in Python with GTK 4 and libadwaita. In agent mode the model can work on your desktop too: running commands, editing files, using the clipboard, opening apps, reading web pages. Anything consequential asks you first, and file changes can be undone.

Version 2.0.0 · MIT licence

## Installing

You need Ollama running (`sudo pacman -S ollama`, then `systemctl enable --now ollama`) and at least one model, which you can download from inside the app.

Put all the files in one folder, then choose one way to install:

```bash
makepkg -si      # a proper pacman package, installed system-wide
./install.sh     # only for your user, into ~/.local; no root needed
```

The required packages are `python`, `python-gobject`, `gtk4` and `libadwaita`. The optional ones each switch on a feature:

| Package | Enables |
|---|---|
| `gtksourceview5` | Syntax highlighting in code blocks |
| `poppler` | Attaching PDFs, and the agent reading PDFs online |
| `webp-pixbuf-loader` | Attaching WebP images |
| `xdg-desktop-portal-gnome` | The agent's screenshot tool |
| `pipewire` | Recording and playing speech |
| `uv` | Quicker, sturdier voice setup |

For window control (listing, focusing and closing windows), install the [Window Calls](https://github.com/ickyicky/window-calls) GNOME Shell extension. GNOME offers apps no other way to do this on Wayland.

## Running

```bash
ollama-desk                     # open the app
ollama-desk notes.md photo.png  # open it with files attached
ollama-desk --quick             # open the quick-ask popup
ollama-desk --tasks             # open the scheduled tasks window
ollama-desk --run-task ID       # run a scheduled task now (what the timers call)
ollama-desk --serve [PORT]      # the phone access server (what the background service runs)
```

If the app is already open, these hand over to the running window. If your shell says *command not found* after `install.sh`, add `export PATH="$HOME/.local/bin:$PATH"` to `~/.bashrc`.

## Features

**Chatting**
- Streamed replies with Markdown, syntax-highlighted code blocks with a copy button, and tables.
- Copy, read aloud or regenerate any reply; hover over your own message to edit and resend it.
- A collapsible view of the model's reasoning, and a *Thinking* control for models that support it.
- Attachments: text, code, PDFs and images, through the paperclip, drag and drop, or Ctrl+V.
- A context meter showing how much of the model's memory the chat uses. Near the limit, older messages are summarized automatically, and a divider marks where.
- Speed and token counts under each reply.

**Chats**
- Search with Ctrl+F, plus rename, pin and delete (right-click a chat). New chats are named by the model.

**Models**
- The model manager (Ctrl+M) downloads, deletes and unloads models, and shows which are loaded and how much sits on the GPU.
- Per-model tuning (Ctrl+T): temperature, top P/K, min P, repeat penalty, reply length, seed, context length, GPU layers, threads, batch size and keep-alive. Every setting explains itself on hover.

**Agent**
- Tools for shell commands, files, the clipboard, notifications, applications, windows, screenshots, reading web pages and searching DuckDuckGo.
- Agent activity (Ctrl+Shift+A) keeps a searchable record of every action.

**Scheduled tasks** (Ctrl+Shift+S)
- The model carries out a prompt on a schedule: every hour, every day, weekdays, every week, or any systemd calendar expression such as `*-*-01 09:00`.
- Runs happen in the background through systemd user timers, even while the app is closed, as long as you're logged in. A run missed while the computer was off happens shortly after you log in.
- Each run starts fresh and is added to the task's own chat. A notification brings a short summary; clicking it opens that chat.
- Runs are unattended, so anything that would normally ask is refused, and the result says what was refused. What still works is reading (if allowed), changes inside workspace folders, and web access (if allowed). Screenshots, the clipboard, apps and windows aren't available to scheduled runs.

**Phone access** (Preferences → Phone access)
- Use Ollama Desk from an Android phone on your home network with **Ollama Desk Remote** (in the `ollama-desk-remote` folder). You can chat, with streaming and agent mode, use voice (through this computer's whisper.cpp and Piper), pick, download and unload models, and run or pause scheduled tasks.
- Chats run on the computer and show up in both places. When the agent wants to do something that needs approval, the request goes to the phone, as a notification if the app is in the background, and is refused after 5 minutes without an answer.
- Turning it on starts a background service (`ollama-desk --serve`, port 8765). Pair a phone by scanning the one-time QR code. Paired phones are listed there and can be unpaired at any time.
- **How it's secured:**
  - The connection is TLS, with a certificate made for this computer. The phone pins its fingerprint from the QR code, so it can't be fooled by another machine on the network.
  - The QR code's secret works once and expires after 10 minutes.
  - Each phone gets its own token, and only its hash is stored on the computer.
  - Connections from outside private network ranges are refused.
- **What a phone can do:** remote runs can use commands, files and the web. The clipboard, screenshots, apps and windows stay on the computer.
- **Firewall:** if you run one, allow TCP port 8765 on your home network, for example `sudo ufw allow from 192.168.0.0/16 to any port 8765 proto tcp`.

**Desktop**
- Quick ask: a small popup on a global shortcut (Ctrl+Super+Space by default), with *Continue in chat*.
- Voice: speak instead of typing, and hear replies in English or Russian. Everything runs offline after a one-time download of about 480 MB.

## Safety model

The agent is useful because it can act, so the rules for when it may act on its own are deliberately narrow:

| Action | Default |
|---|---|
| Reading files and folders, the clipboard, and read-only commands | Runs without asking (one switch turns this off) |
| Creating, editing, moving or deleting inside your **workspace folders** | Runs without asking |
| Any other change, any other command, closing windows | Always asks |
| Screenshots | Always asks |
| Reading web pages and searching | Asks (can be switched off) |
| Local network and localhost addresses | Always blocked |

- **Read-only commands:** a command counts as read-only only if every part of it is a known read-only tool used in a read-only way, such as `ls`, `grep`, `du`, `git status` or `pacman -Q`. Redirection, substitutions, variables and `sudo` all count as unsure and ask. The check uses an allowlist, not a blocklist, so new tricks fall back to asking.
- **Workspace commands:** inside a workspace, `rm`, `mv`, `cp`, `mkdir` and `touch` run freely only when every path provably stays inside it. The app follows globs, `cd` and symlinks to check this.
- **Folders that can't be workspaces:** your whole home folder, hidden settings folders, folders on your `PATH`, and anything inside `.git`. Writing to any of these could let the model change its own permissions or hijack commands.
- **Undo:** before the agent changes files, the affected paths are backed up (up to 500 MB per action, kept for 14 days). Each change gets an *Undo* button.
- **Why the web asks by default:** an address can carry data out. A page or file with hidden instructions could otherwise trick the model into sending your private files to a website.

## Keyboard shortcuts

| Keys | Action |
|---|---|
| Enter / Shift+Enter | Send / new line |
| Ctrl+N | New chat |
| Ctrl+F | Search chats |
| Ctrl+O | Attach files |
| Ctrl+T | Tune the current model |
| Ctrl+M | Models |
| Ctrl+Shift+A | Agent activity |
| Ctrl+Shift+S | Scheduled tasks |
| Ctrl+, | Preferences |
| Ctrl+Q | Quit |

## Where things are kept

| Path | Contents |
|---|---|
| `~/.config/ollama-desk/config.json` | Settings |
| `~/.local/share/ollama-desk/conversations/` | Chats, one JSON file each |
| `~/.local/share/ollama-desk/actions.jsonl` | The agent activity log |
| `~/.local/share/ollama-desk/tasks.json` | Scheduled tasks |
| `~/.local/share/ollama-desk/remote/` | Phone access: certificate, key and paired phones (readable only by you) |
| `~/.config/systemd/user/ollama-desk-task-*` | The tasks' timers; `systemctl --user list-timers` shows them |
| `~/.local/share/ollama-desk/undo/` | Backups for undo |
| `~/.local/share/ollama-desk/voice/` | Speech programs and models; removable from Preferences |
| `~/.cache/ollama-desk/` | Pasted images and temporary audio |

Nothing leaves your computer except requests to your Ollama server, web pages the agent reads, one-time downloads of models and voices, and, if you turn on phone access, traffic to your paired phones on your own network.

## Troubleshooting

- **“Ollama isn't reachable”:** start it with `systemctl start ollama` or `ollama serve`. If it runs elsewhere, set its address in Preferences.
- **A model ignores tools:** not every model supports them; the app says so and carries on without. Qwen 3 and Llama 3.x handle tools well.
- **Voice setup fails at a download:** the models come from huggingface.co, which can be slow or blocked in some countries.
  - The error dialog offers *Use hf-mirror.com*, a public mirror. You can also switch the source in Preferences → Voice → *Download from*.
  - Downloads resume where they stopped, so *Try again* never starts over.
  - The setup builds its own Python 3.12 environment with `uv`, so Arch's newer system Python doesn't matter. `uv` comes from the Arch repositories (`sudo pacman -S uv`, already a dependency of the package). Without it, setup downloads `uv` from PyPI and checks it against PyPI's checksum.
  - Every step shows its progress. A step where nothing happens for a while stops with the reason instead of waiting forever.
- **A scheduled task didn't run:** check `systemctl --user list-timers` and `journalctl --user -u 'ollama-desk-task-*'`. Timers only run while you're logged in; to run them when logged out too, enable lingering with `loginctl enable-linger`.
- **The phone can't connect:**
  - Check that both devices are on the same network, and that `systemctl --user status ollama-desk-remote` says it's running.
  - Check that a firewall isn't blocking port 8765.
  - If the computer's address changed, pair again; the QR code carries its current addresses.
- **Screenshots fail:** install `xdg-desktop-portal-gnome`; GNOME may also ask once for permission.
