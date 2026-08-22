# OrderUp (Java / Spring Boot)

Automated Zerodha Kite trading bot. Scans a configurable equity watchlist (default: Nifty 50 + Nifty Smallcap constituents you fill in) every 5 minutes during NSE market hours, computes **CCI(20)** and **Williams %R(14)** on 5‑minute candles, logs every symbol matching the criteria, and places **CNC (equity delivery)** market orders — with an automatic GTT fallback if markets are closed.

> Java rewrite of the original Python POC (`../*.py`). The Python code stays in the repo as a reference only; only this module runs in production.

---

## 1. Prerequisites

- **Java 21** (`brew install openjdk@21`)
- **Maven wrapper** — the `mvnw` script is used below; regenerate it once with `mvn -N wrapper:wrapper` if not present.
- **Zerodha Kite Connect** subscription with **historical data add‑on** enabled.

## 2. Configure credentials

Set env vars (or export in your shell profile / launchd plist):

```bash
export KITE_API_KEY=xxxxxxxx
export KITE_API_SECRET=xxxxxxxx
export KITE_USER_ID=AB1234           # your Zerodha client id
# optional
export TELEGRAM_BOT_TOKEN=...
export TELEGRAM_CHAT_ID=...
```

In your Kite app on <https://developers.kite.trade>, set the **Redirect URL** to exactly:

```
http://localhost:8080/kite/callback
```

## 3. Configure the watchlist

Edit these files (one symbol per line, exchange trading symbol as on NSE):

- `src/main/resources/watchlist/nifty50.txt`
- `src/main/resources/watchlist/nifty_smallcap.txt`

Add/remove file names in `application.yml → trading.watchlist-files` to enable/disable a list. Ad‑hoc symbols can go into `trading.extra-symbols`.

## 4. Run

```bash
cd orderup-java
./mvnw spring-boot:run
```

First‑run login (Kite mandates human OAuth once per day):

1. Open <http://localhost:8080/kite/login> → click the link → log in on Kite.
2. Kite redirects to `/kite/callback` and the token is stored in H2 (`./data/orderup.mv.db`).
3. From then on, restarts within the same trading day reuse the token.

## 5. Modes

- **Paper mode (safe, default recommendation for first run):**
  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=paper
  ```
  Would‑be orders are logged and stored in DB but never sent to Kite.
- **Live mode:** default profile. Real CNC market orders will be placed.

## 6. Endpoints

| Path | Purpose |
|---|---|
| `GET /health` | auth state, watchlist size, login URL |
| `GET /kite/login` | start Kite OAuth |
| `GET /kite/callback` | Kite OAuth landing (do not call manually) |
| `GET /watchlist` | resolved symbol list |
| `GET /orders/today` | orders placed today |
| `POST /scan/run` | run a scan immediately (useful for testing) |
| `POST /telegram/test` | send a test message to your Telegram |
| `GET /h2` | H2 console (dev only) |

## 7. Schedule

- Cron: `0 */5 9-15 * * MON-FRI` in `Asia/Kolkata`, gated in code to 09:15–15:30 IST and NSE holidays (`trading.holidays` list in `application.yml`).
- Signal dedupe: once a `symbol + indicator + side` fires on a given day, it will not re‑fire until the next trading day.

## 8. Auto‑start on macOS

```bash
# 1. edit deploy/com.orderup.trading.plist and replace REPLACE_ME values
cp deploy/com.orderup.trading.plist ~/Library/LaunchAgents/
launchctl load  ~/Library/LaunchAgents/com.orderup.trading.plist
# to stop:
launchctl unload ~/Library/LaunchAgents/com.orderup.trading.plist
```

## 9. Signal rules (kept identical to Python POC)

| Indicator | Prev | Current | Signal |
|---|---|---|---|
| CCI    | ≤ +100 | > +100 | SELL |
| CCI    | ≥ −100 | < −100 | BUY |
| W%R    | ≥ −20  | < −20  | SELL |
| W%R    | ≥ −80  | < −80  | BUY |

## 10. Notes / caveats

- The Kite Java SDK API surface (`OrderParams`, `GTTParams`, `getHistoricalData`) matches SDK **v3.5.0**. If Zerodha bumps the SDK and renames fields, a couple of lines in `OrderService`/`HistoricalDataService` may need touch‑ups.
- Rate limits: scanner sleeps ~350 ms between symbols; a Nifty‑50 + Smallcap 100 watchlist (~150 symbols) fits comfortably within Kite's ~3 req/sec limit per 5‑minute window.
- **F&O / MF / currency are excluded** — `InstrumentService` keeps only `instrument_type == "EQ"` on NSE.
- Access tokens still require a manual browser login **once per trading day** (Zerodha rule). A morning Telegram alert reminds you if it's stale.

