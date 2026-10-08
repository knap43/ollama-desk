#!/usr/bin/env bash
# Installs Ollama Desk for the current user.
set -euo pipefail
APP_ID=io.github.ollamadesk.OllamaDesk
BIN="$HOME/.local/bin/ollama-desk"
APPS="$HOME/.local/share/applications"

install -Dm755 "$(dirname "$0")/ollama_desk.py" "$BIN"
install -d "$APPS"
cat > "$APPS/$APP_ID.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Ollama Desk
Comment=Chat with local models and let them lend a hand on your desktop
Exec=$BIN %F
Icon=utilities-terminal
Terminal=false
Categories=Utility;GTK;
StartupNotify=true
DESKTOP
update-desktop-database "$APPS" 2>/dev/null || true
echo "Installed. Find “Ollama Desk” in Activities, or run: ollama-desk"
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) echo "Note: ~/.local/bin isn't on your PATH, so the ollama-desk command won't be found yet. Add this to"
     echo "~/.bashrc (or your shell's config) and open a new terminal:"
     echo '  export PATH="$HOME/.local/bin:$PATH"' ;;
esac
