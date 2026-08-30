# OrderUp on Oracle Cloud (Always-Free Ampere ARM)

Migrates the stack off the MacBook so it runs 24/7 and gets a **static public IP**
(kills the recurring Kite "IP not whitelisted" 403 error). Native systemd, no Docker.
Same ngrok reserved domain — Chartink alerts and iOS Shortcuts URLs do **not** change.

## Architecture on the VM

```
Internet ──► ngrok (marbles-pamphlet-wages.ngrok-free.dev)
              │  outbound tunnel from VM
              ▼
      orderup-ngrok.service  ──►  127.0.0.1:8080
                                        │
                                 orderup-launcher.service (Python)
                                   ├─ /lifecycle/*  handled locally
                                   ├─ /health       handled locally
                                   └─ /*            reverse-proxy ─►  127.0.0.1:8090
                                                                            │
                                                              orderup-chartink.service
                                                                (Spring Boot, Java 21)
```

All three units are bundled by `orderup.target` and run as **`systemctl --user`**
under your login user (`opc` on Oracle Linux, `ubuntu` on Ubuntu).

## 1. Oracle Cloud signup

1. https://cloud.oracle.com/ → **Start for free**.
2. Credit card required for identity only. Set spending limit to $0 in
   *Governance → Budgets* after signup if you're paranoid.
3. **Region matters for latency to NSE.** Pick, in preference order:
   - `ap-mumbai-1` (Mumbai) — ~20 ms to Zerodha
   - `ap-hyderabad-1` — ~30 ms
   - Anything else adds 100+ ms round trip

   You cannot change region for free-tier resources later; pick carefully.

## 2. Provision the VM

Console → **Compute → Instances → Create instance**:

| Field | Value |
|---|---|
| Name | `orderup-vm` |
| Image | Oracle Linux 9 (or Ubuntu 22.04 minimal) — **ARM64** |
| Shape | `VM.Standard.A1.Flex` |
| OCPU / RAM | 2 OCPU / 12 GB (well within Always-Free 4 OCPU / 24 GB pool) |
| Boot volume | 50 GB (default) |
| SSH key | Upload your `~/.ssh/id_ed25519.pub` |
| VCN | Create new (defaults are fine) |

**"Out of capacity" workaround.** Ampere free capacity fluctuates. If creation
fails with `Out of host capacity`, retry every ~15 min — or use OCI CLI in a
loop:

```bash
while ! oci compute instance launch --from-json file://instance.json 2>&1 | tee /tmp/oci; \
  grep -q OutOfCapacity /tmp/oci; do sleep 900; done
```

## 3. Open the security list

Networking → **Virtual Cloud Networks → your VCN → Security Lists → Default
Security List → Add Ingress Rules**:

| Source CIDR | Protocol | Dest Port |
|---|---|---|
| `0.0.0.0/0` | TCP | 22 |
| `0.0.0.0/0` | TCP | 8080 |

8080 is only needed if you want to bypass ngrok for debugging; ngrok itself is
outbound so it works without it. Kite webhooks come **from Chartink → ngrok**,
never direct to 8080.

## 4. SSH in and bootstrap

```bash
ssh opc@<public-ip>       # or ubuntu@<public-ip>

# On the VM:
curl -fsSL https://raw.githubusercontent.com/pranavkestur/OrderUp/main/orderup-java/deploy/vps-bootstrap.sh -o bootstrap.sh
chmod +x bootstrap.sh
./bootstrap.sh
```

The script prompts once for each secret (defaults come from `orderup.env.example`
— press Enter to accept, or paste a fresh value).

## 5. Whitelist the VM's IP in Kite

The bootstrap script prints the public IP at the end. Copy it, then:

1. https://developers.kite.trade/apps → your app → **Edit**
2. Add the VM IP under **Allowed IPs**
3. Remove the old MacBook IP `49.37.180.7` **only after cutover verifies**
4. Save

Log into Kite once (fresh daily access token). The redirect goes to
`https://marbles-pamphlet-wages.ngrok-free.dev/kite/callback`, which now lands
on the VM.

## 6. Preserve today's H2 database

Order dedup, GTT-ID audit, and `firedToday` reseed all live in the H2 file DB.
To avoid re-firing today's alerts on the VM:

```bash
# On the Mac — stop chartink first so the db isn't mid-write:
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.orderup.chartink.plist

scp /Users/pranavkestur/Projects/OrderUp/orderup-java/orderup-chartink-app/data/orderup.mv.db \
    opc@<vm-ip>:/opt/orderup/orderup-java/orderup-chartink-app/data/

# On the VM:
systemctl --user restart orderup-chartink
journalctl --user -u orderup-chartink -n 100 --no-pager
```

Look for `firedToday size=N` in the startup log — that confirms today's alerts
re-seeded from the DB and won't double-fire.

## 7. Cutover — move the ngrok domain to the VM

ngrok free tier permits **one agent per reserved domain**. To transfer:

```bash
# On the Mac:
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.orderup.ngrok.plist
launchctl bootout gui/$(id -u) ~/Library/LaunchAgents/com.orderup.launcher.plist

# On the VM — orderup-ngrok.service should already be running; if it started
# before the Mac agent quit, it will have been in a retry loop. Kick it:
systemctl --user restart orderup-ngrok
journalctl --user -u orderup-ngrok -f
```

Wait for the log line `started tunnel ... url=https://marbles-pamphlet-wages...`.

## 8. Verify end-to-end

From your phone or laptop:

```bash
# Health check — hits launcher on the VM through ngrok
curl https://marbles-pamphlet-wages.ngrok-free.dev/health

# Lifecycle status
curl https://marbles-pamphlet-wages.ngrok-free.dev/lifecycle/<LAUNCHER_SECRET>/chartink/status

# Chartink "Test webhook" from Chartink UI, then:
ssh opc@<vm-ip> 'journalctl --user -u orderup-chartink -f'
```

## Day-to-day operations

```bash
# Everything
systemctl --user status  orderup.target
systemctl --user restart orderup.target

# One component
systemctl --user restart orderup-chartink
journalctl --user -u orderup-chartink -f

# Or from your phone via iOS Shortcut:
#   POST https://marbles-pamphlet-wages.ngrok-free.dev/lifecycle/<LAUNCHER_SECRET>/chartink/restart
```

## Rollback

If anything goes wrong, flip back to the Mac in <60 s:

```bash
# On the VM:
systemctl --user stop orderup.target

# On the Mac:
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.orderup.launcher.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.orderup.chartink.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.orderup.ngrok.plist
```

ngrok domain moves back to the Mac within ~10 s of the VM agent quitting.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `curl` to public IP:8080 hangs | Oracle VCN security list rule missing → step 3 |
| `curl` still hangs after VCN fix | Distro iptables INPUT REJECT chain → bootstrap script patches it; if you skipped, run `sudo iptables -I INPUT 6 -p tcp --dport 8080 -j ACCEPT` |
| ngrok logs `ERR_NGROK_334` (domain already in use) | Old agent (Mac) still holds it → `launchctl bootout` on Mac |
| chartink 502 via launcher | Spring Boot didn't come up → `journalctl --user -u orderup-chartink -n 200` |
| Kite 403 "IP not whitelisted" | Add VM's public IP to Kite Allowed-IPs list — step 5 |
| Units die after logout | `sudo loginctl enable-linger $USER` (bootstrap does this) |

