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
Exec=$BIN
Icon=utilities-terminal
Terminal=false
Categories=Utility;GTK;
StartupNotify=true
DESKTOP
update-desktop-database "$APPS" 2>/dev/null || true
echo "Installed. Find “Ollama Desk” in Activities, or run: $BIN"
