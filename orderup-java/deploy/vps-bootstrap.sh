#!/usr/bin/env bash
# =============================================================================
# vps-bootstrap.sh — one-shot provisioner for a fresh Oracle Cloud Ampere VM
# (Oracle Linux 9 or Ubuntu 22.04 ARM64). Idempotent: safe to re-run.
#
# Usage (as the non-root default user, e.g. `opc` on Oracle Linux, `ubuntu` on
# Ubuntu — NOT as root; the whole stack runs as a systemd --user service):
#
#     curl -fsSL https://raw.githubusercontent.com/pranavkestur/OrderUp/main/orderup-java/deploy/vps-bootstrap.sh -o bootstrap.sh
#     chmod +x bootstrap.sh
#     ./bootstrap.sh
#
# Or clone first, then run ./orderup-java/deploy/vps-bootstrap.sh.
#
# What it does:
#   1. Install JDK 21 (Amazon Corretto ARM64), git, python3, ngrok agent
#   2. Clone / update the repo into /opt/orderup (chown to $USER)
#   3. Build the chartink jar with Maven wrapper
#   4. Prompt for secrets → /opt/orderup/orderup.env (chmod 600)
#   5. ngrok config add-authtoken
#   6. Install the 4 systemd --user units + orderup.target
#   7. loginctl enable-linger so units survive logout
#   8. Open firewall port 8080 (ufw or firewalld) + patch OL iptables REJECT
#   9. Enable + start orderup.target
#  10. Print the VM's public IP for the Kite whitelist step
# =============================================================================
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/pranavkestur/OrderUp.git}"
REPO_BRANCH="${REPO_BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-/opt/orderup}"
ENV_FILE="${INSTALL_DIR}/orderup.env"

log()  { printf '\033[1;34m[bootstrap]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fatal]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] && die "Run as a regular user with sudo, not as root."
sudo -v || die "Need sudo for package install + firewall changes."

# ---- 1. Detect distro so we know which package manager to use -----------------
if [[ -f /etc/os-release ]]; then . /etc/os-release
else die "/etc/os-release missing — unsupported distro."
fi
log "Detected: $PRETTY_NAME ($ID)"

case "$ID" in
  ol|rhel|rocky|almalinux) PKG=dnf ;;
  ubuntu|debian)           PKG=apt ;;
  *) die "Unsupported distro: $ID. Extend the case block if you know what you're doing." ;;
esac

# ---- 2. Install prerequisites -------------------------------------------------
log "Installing JDK 21, git, python3, unzip, curl..."
if [[ $PKG == dnf ]]; then
  sudo rpm --import https://yum.corretto.aws/corretto.key
  sudo curl -fsSL -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
  sudo dnf install -y java-21-amazon-corretto-devel git python3 unzip curl firewalld
  JAVA_HOME_GUESS=/usr/lib/jvm/java-21-amazon-corretto
else
  sudo apt-get update -y
  sudo apt-get install -y wget gnupg software-properties-common curl unzip git python3 ufw
  wget -qO- https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto.gpg
  echo "deb [signed-by=/usr/share/keyrings/corretto.gpg] https://apt.corretto.aws stable main" \
    | sudo tee /etc/apt/sources.list.d/corretto.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y java-21-amazon-corretto-jdk
  JAVA_HOME_GUESS=/usr/lib/jvm/java-21-amazon-corretto
fi

# ---- 3. Install ngrok agent (ARM64) ------------------------------------------
if ! command -v ngrok >/dev/null 2>&1; then
  log "Installing ngrok agent (linux arm64)..."
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/ngrok.tgz" https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz
  sudo tar -xzf "$tmp/ngrok.tgz" -C /usr/local/bin ngrok
  sudo chmod +x /usr/local/bin/ngrok
  rm -rf "$tmp"
else
  log "ngrok already installed: $(ngrok version | head -1)"
fi

# ---- 4. Clone / update repo ---------------------------------------------------
sudo mkdir -p "$INSTALL_DIR"
sudo chown "$USER":"$USER" "$INSTALL_DIR"
if [[ -d "$INSTALL_DIR/.git" ]]; then
  log "Repo exists — pulling latest on $REPO_BRANCH"
  git -C "$INSTALL_DIR" fetch --all --prune
  git -C "$INSTALL_DIR" checkout "$REPO_BRANCH"
  git -C "$INSTALL_DIR" pull --ff-only
else
  log "Cloning $REPO_URL into $INSTALL_DIR"
  git clone --branch "$REPO_BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi

# ---- 5. Build the chartink jar ------------------------------------------------
log "Building orderup-chartink-app (this takes ~2 min on Ampere)..."
pushd "$INSTALL_DIR/orderup-java" >/dev/null
export JAVA_HOME="$JAVA_HOME_GUESS"
./mvnw -pl orderup-chartink-app -am -DskipTests package
popd >/dev/null

# ---- 6. Provision /opt/orderup/orderup.env -----------------------------------
if [[ -f "$ENV_FILE" ]]; then
  log "Env file already exists at $ENV_FILE — leaving it alone."
  log "Delete it and re-run this script to re-prompt for secrets."
else
  log "Creating $ENV_FILE from template. Press Enter to accept the [default]."
  cp "$INSTALL_DIR/orderup-java/deploy/systemd/orderup.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"

  prompt_and_replace() {
    local key="$1" current
    current=$(grep -E "^${key}=" "$ENV_FILE" | head -1 | cut -d= -f2- || true)
    read -rp "$key [${current}]: " val
    val="${val:-$current}"
    # Use a delimiter unlikely to appear in secrets.
    sed -i "s|^${key}=.*|${key}=${val}|" "$ENV_FILE"
  }

  for k in KITE_API_KEY KITE_API_SECRET KITE_USER_ID \
           TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID \
           CHARTINK_SECRET LAUNCHER_SECRET \
           NGROK_AUTHTOKEN NGROK_STATIC_DOMAIN; do
    prompt_and_replace "$k"
  done

  # Correct JAVA_HOME regardless of what the template said.
  sed -i "s|^JAVA_HOME=.*|JAVA_HOME=${JAVA_HOME_GUESS}|" "$ENV_FILE"
fi

# ---- 7. Configure ngrok authtoken --------------------------------------------
NGROK_TOKEN=$(grep -E '^NGROK_AUTHTOKEN=' "$ENV_FILE" | cut -d= -f2-)
[[ -z $NGROK_TOKEN ]] && die "NGROK_AUTHTOKEN missing from $ENV_FILE"
log "Registering ngrok authtoken..."
ngrok config add-authtoken "$NGROK_TOKEN"

# ---- 8. Install systemd --user units -----------------------------------------
UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
cp "$INSTALL_DIR/orderup-java/deploy/systemd/"orderup-*.service "$UNIT_DIR/"
cp "$INSTALL_DIR/orderup-java/deploy/systemd/orderup.target"    "$UNIT_DIR/"
log "Installed units: $(ls "$UNIT_DIR" | grep '^orderup' | tr '\n' ' ')"

# ---- 9. Enable lingering so units survive logout -----------------------------
log "Enabling loginctl linger for $USER (units keep running after SSH exit)..."
sudo loginctl enable-linger "$USER"

# ---- 10. Open firewall port 8080 ---------------------------------------------
# ngrok itself is outbound so it doesn't need this, but exposing 8080 lets you
# probe the launcher directly during debugging (curl http://<vm-ip>:8080/health).
log "Opening firewall for tcp/8080..."
if command -v ufw >/dev/null 2>&1; then
  sudo ufw allow 8080/tcp || true
elif command -v firewall-cmd >/dev/null 2>&1; then
  sudo systemctl enable --now firewalld
  sudo firewall-cmd --permanent --add-port=8080/tcp
  sudo firewall-cmd --reload
fi
# Oracle Linux images ship with an INPUT ... REJECT rule that traps everyone
# new to OCI; insert an ACCEPT before it.
if command -v iptables >/dev/null 2>&1 && sudo iptables -S INPUT | grep -q 'REJECT'; then
  sudo iptables -I INPUT 6 -p tcp --dport 8080 -j ACCEPT || true
  if   command -v netfilter-persistent >/dev/null 2>&1; then sudo netfilter-persistent save
  elif [[ -f /etc/sysconfig/iptables ]];              then sudo iptables-save | sudo tee /etc/sysconfig/iptables >/dev/null
  fi
fi
warn "Also open port 8080 in the Oracle Cloud VCN Security List"
warn "(Networking → VCN → Security Lists → Default → Add Ingress: 0.0.0.0/0 tcp 8080)."

# ---- 11. Reload & start ------------------------------------------------------
log "Reloading systemd --user daemon..."
systemctl --user daemon-reload
systemctl --user enable orderup.target
systemctl --user start  orderup.target

sleep 3
systemctl --user --no-pager status orderup-launcher orderup-chartink orderup-ngrok || true

# ---- 12. Report --------------------------------------------------------------
PUBIP=$(curl -fsS https://api.ipify.org || echo "<unknown>")
NGROK_DOMAIN=$(grep NGROK_STATIC_DOMAIN "$ENV_FILE" | cut -d= -f2-)
LSECRET=$(grep LAUNCHER_SECRET "$ENV_FILE" | cut -d= -f2-)
cat <<EOF

============================================================
  OrderUp bootstrap complete.

  Public IP:              $PUBIP
  Ngrok URL:              https://$NGROK_DOMAIN
  Health:                 https://$NGROK_DOMAIN/health
  Chartink status:        https://$NGROK_DOMAIN/lifecycle/$LSECRET/chartink/status

  NEXT STEPS
  ----------
  1. Add $PUBIP to Kite's Allowed-IPs list:
        https://developers.kite.trade/apps  → your app → Edit → Allowed IPs
     (Remove the old MacBook IP 49.37.180.7 after cutover verifies.)

  2. If migrating from the Mac, scp the H2 db to preserve today's state:
        scp orderup-chartink-app/data/orderup.mv.db \\
            $USER@$PUBIP:$INSTALL_DIR/orderup-java/orderup-chartink-app/data/
     then: systemctl --user restart orderup-chartink

  3. Stop the Mac's launchd jobs so the ngrok domain moves to this VM:
        launchctl bootout gui/\$(id -u) ~/Library/LaunchAgents/com.orderup.ngrok.plist
        launchctl bootout gui/\$(id -u) ~/Library/LaunchAgents/com.orderup.chartink.plist
        launchctl bootout gui/\$(id -u) ~/Library/LaunchAgents/com.orderup.launcher.plist

  4. Verify Chartink → VM: send a "Test webhook" from Chartink, then:
        journalctl --user -u orderup-chartink -f

  5. Log into Kite fresh (one-time daily access token) — the login redirect
     will hit https://$NGROK_DOMAIN/kite/callback which now lands on this VM.
============================================================
EOF

