# OrderUp

Automated trading tooling for Zerodha Kite. Monorepo with two independent
modules; pick whichever is relevant to your task.

## Modules

### `orderup-java/` — Spring Boot 3 / Java 21 (production)

Multi-module Maven project. Three sub-modules:

| Module | Purpose |
|---|---|
| `orderup-common` | Shared library: Kite auth, market-data cache, indicators, orders, notifications, shared config. Not run standalone. |
| `orderup-app` | WACE + legacy DAILY_MULTI/HOURLY_MULTI scanner. Cron-driven, scans the NSE EQ universe every 5 min during market hours. Boot main: `com.orderup.app.OrderUpApplication`. Artifact: `orderup-app/target/orderup-0.1.0.jar`. launchd label: `com.orderup.trading`. |
| `orderup-chartink-app` | Receives Chartink alert webhooks and places orders via `orderup-common`. Boot main: `com.orderup.chartink.ChartinkApplication`. Artifact: `orderup-chartink-app/target/orderup-chartink-app-0.1.0.jar`. launchd label: `com.orderup.chartink`. |

Both apps listen on port 8080 and run as launchd alternates (only one at a
time). They share a single daily Kite login via a file-based access-token
store at `orderup-java/data/kite-token.json`.

Build:

```bash
cd orderup-java && ./mvnw -q -DskipTests package
```

See `orderup-java/README.md` for details.

### `orderup-py/` — original Python bot (legacy / experimentation)

Standalone Python scripts targeting the same Kite Connect API. Predates the
Java rewrite and stays around for prototyping, ad-hoc GTT placement, and
integration tests. Not run in production alongside the Java apps.

See `orderup-py/README.md` for full setup / usage.

## Repo layout

```
OrderUp/
├── README.md            ← this file
├── orderup-java/        ← production Java trading apps
│   ├── orderup-common/
│   ├── orderup-app/
│   ├── orderup-chartink-app/
│   └── deploy/          ← launchd plists
└── orderup-py/          ← legacy Python bot + scripts
    ├── indicators.py
    ├── orderup.py
    ├── trading_bot.py
    ├── requirements.txt
    └── …
```

## Runtime state (git-ignored)

`data/`, `logs/`, `target/`, `venv/`, `__pycache__/`, `*.log` are all ignored.
Each launchd job's `WorkingDirectory` scopes its own `data/orderup.mv.db`
and `logs/orderup.log` under its module directory.

