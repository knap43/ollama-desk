#!/usr/bin/env bash
# Installs Ollama Desk for the current user (no root needed).
# To install system-wide as a pacman package instead, run: makepkg -si
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP_ID=io.github.ollamadesk.OllamaDesk
BIN="$HOME/.local/bin/ollama-desk"
APPS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/scalable/apps"

install -Dm755 "$HERE/ollama_desk.py" "$BIN"
install -Dm644 "$HERE/$APP_ID.svg" "$ICONS/$APP_ID.svg"
install -d "$APPS"
# The desktop session may not have ~/.local/bin on its PATH, so point at the script directly.
sed "s|^Exec=ollama-desk|Exec=$BIN|" "$HERE/$APP_ID.desktop" > "$APPS/$APP_ID.desktop"
update-desktop-database "$APPS" 2>/dev/null || true
gtk-update-icon-cache -q -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

echo "Installed. Find “Ollama Desk” in Activities, or run: ollama-desk"
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) echo "Note: ~/.local/bin isn't on your PATH, so the ollama-desk command won't be found yet. Add this to"
     echo "~/.bashrc (or your shell's config) and open a new terminal:"
     echo '  export PATH="$HOME/.local/bin:$PATH"' ;;
esac

missing=()
for pkg in gtksourceview5 poppler xdg-desktop-portal-gnome pipewire uv; do
  pacman -Qq "$pkg" &>/dev/null || missing+=("$pkg")
done
if ((${#missing[@]})); then
  echo
  echo "Optional extras not installed: ${missing[*]}"
  echo "  (code highlighting, PDFs, agent screenshots, voice, voice setup). Add them with:"
  echo "  sudo pacman -S --needed ${missing[*]}"
fi
