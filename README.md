# GTD 2K Tracker

A private Discord bot for tracking NBA 2K game stats with friends.

This project lets you:
- Create and manage games (DRAFT / FINAL)
- Add player stat lines manually
- Upload screenshots for **experimental OCR-based stat extraction**
- View game summaries with box scores
- See leaderboards ranked by fantasy points
- View player splits (with / vs teammates)

> Built for small private Discord servers. Not intended for public hosting.

---

## Features

### Game Management
- `/game create` — create a draft game
- `/game score` — add final scores
- `/game finalize` / `/game unfinalize`
- `/game list` — recent games
- `/game find` — games by player
- `/game summary` — box score + receipt image

### Player Stats
- `/player add`
- `/stat add` — manual stat entry
- `/player summary`
- `/player with`
- `/player vs`

### Leaderboard
- `/leaderboard`
- Ranked by Fantasy Points per Game
- Minimum games threshold enforced
- Shows W/L record

### OCR (Work in Progress)
- `/game ocr`
- Crops a known stats table region
- Attempts TSV-based OCR using Tesseract
- **Not reliable yet** — manual review required

---

## Tech Stack

- Java 17
- JDA (Discord API)
- SQLite (local file DB)
- Gradle
- Tesseract OCR (external binary)

---

## Project Structure

src/main/java/com/playnow  
- BotMain — bootstraps JDA and registers commands  
- Db — SQLite helper and schema  
- AccessControl — admin/channel gating  
- Listeners — one class per slash command  

data/  
- playnow.db (SQLite database)  
- images/ (game receipts + OCR debug crops)  

---

## Setup

1. Install Java 17+
2. Install Tesseract (required only for OCR)
3. Set environment variables:

DISCORD_TOKEN  
DISCORD_GUILD_ID  
ADMIN_USER_ID (optional)  
TRACKER_CHANNEL_ID (optional)

4. Run:

./gradlew run

---

## Notes

- This bot assumes **trust** — no anti-cheat
- OCR is intentionally isolated and safe to disable
- All writes are gated via AccessControl
- Designed for clarity over abstraction

---

## Status

Active development  
OCR: Experimental  
Everything else: Stable  

---

## License

Private / personal use
