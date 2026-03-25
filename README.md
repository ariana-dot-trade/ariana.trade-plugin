# ariana.trade

A RuneLite plugin that syncs your Grand Exchange offers, bank, inventory, and equipment to [ariana.trade](https://ariana.trade) in real time — giving you a powerful companion dashboard for tracking flips, monitoring your wealth, and analyzing price trends.

## Features

- **Real-time GE sync** — Completed, buying, and selling offers stream to your dashboard as they happen
- **Bank & inventory tracking** — See your full bank, inventory, and equipment on ariana.trade
- **Right-click "Open in ariana"** — Instantly look up any item's price history and predictions without opening a new tab
- **Auto-launch options** — Open ariana.trade automatically on login or when visiting the GE
- **Secure relay connection** — Data flows through a WebSocket relay using a private pairing token; no direct localhost access needed
- **Every Item Event (EIE) pipeline** — Tracks loot drops, XP gains, kill counts, collection log entries, and more

## Setup

1. Install the plugin from the RuneLite Plugin Hub
2. Visit [ariana.trade](https://ariana.trade) — a relay token will appear in the banner
3. Open the plugin settings (wrench icon) and paste the token into the **Relay Token** field
4. Your game data will start syncing to the dashboard immediately

## How it works

The plugin connects to the ariana.trade relay server (`wss://api.ariana.trade/relay/plugin`) using a secure WebSocket. Your relay token pairs the plugin to your browser session — no account creation required. All data stays between your game client and your browser.

## Configuration

| Setting | Default | Description |
|---|---|---|
| Auto-launch on login | Off | Open ariana.trade when you log into the game |
| Auto-launch on GE open | Off | Open ariana.trade when you visit the Grand Exchange |
| Right-click "Open in ariana" | On | Add context menu option to items in inventory, bank, and equipment |
| Right-click only near bank | Off | Only show the context menu option when near a bank |
| Relay Token | — | Your private pairing token from ariana.trade |

## Links

- **Dashboard**: [ariana.trade](https://ariana.trade)
- **API Status**: [api.ariana.trade/prices/status](https://api.ariana.trade/prices/status)
