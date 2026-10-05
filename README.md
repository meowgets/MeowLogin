# MeowLogIn

Auth plugin for Paper servers with dialog GUI, Telegram bot integration, and password reset via bot.

[Русская версия](README.ru.md)

## Features

- Dialog-based authentication
- Chat fallback mode (`/login`, `/register`)
- Multi-language support (en, ru + custom via `lang/` folder)
- Telegram bot integration:
    - Link account via `/link`
    - Password reset via `/resetpassword`
    - Change password from Telegram (`/newpass`)
    - Unlink (`/unlink` both in-game and in bot)
- Optional Telegram link requirement (`require-telegram-link`)
- Flow: login → link Telegram → play (when `require-telegram-link: true`)
- Admin commands:
    - `/unreg <player>` — remove player from database (permission `meowlogin.unreg`)
    - `/meowlogin reload` — reload config & languages
    - `/meowlogin lang <code>` — change language
- Configurable blocking for unauthenticated players
- Audit log file (`meowlogin.log`): registrations, logins, failed logins, password resets, TG link/unlink, admin unreg
- Unique Telegram link: 1 chat = 1 account, 1 account = 1 chat

## Requirements

- Paper 26.2 (not tested on other versions)
- Java 25

## Installation

1. Drop `MeowLogIn.jar` into `plugins/`.
2. Start the server once to generate configs.
3. Edit `plugins/MeowLogIn/config.yml`.
4. (Optional) Configure Telegram in `config.yml`:

   ```yaml
   telegram:
     enabled: true
     token: "YOUR_BOT_TOKEN"
     bot-username: "YourBot"
   ```

5. Restart the server.

> **Important:** after setting `telegram.token` and `telegram.bot-username`, a **full server restart is required**. The `/meowlogin reload` command does not apply these settings — the bot is started only on plugin startup.

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/login <password>` | Login | — |
| `/register <password> <password>` | Register | — |
| `/link` | Link Telegram (after login) | — |
| `/unlink` | Unlink Telegram (after login) | — |
| `/resetpassword` | Request password reset via Telegram | — |
| `/meowlogin [mode\|lang\|reload]` | Help / personal auth mode or language switch / config reload | — |
| `/unreg <player>` | Remove player from database (admin only) | `meowlogin.unreg` |

## Telegram Bot Commands

| Command | Description |
|---------|-------------|
| `/start <code>` | Link Minecraft account using code from `/link` |
| `/newpass <password>` | Change password |
| `/unlink` | Unlink Telegram from Minecraft account |

## Configuration

Key options in `config.yml`:

- `default-language` — default language code
- `spawn-mode` — `DEFAULT` (player stays where they logged out) or `FIXED` (teleport to fixed point until authenticated)
- `auth-timeout` — seconds before kick, `0` to disable
- `min-password-length` — minimum password length
- `require-telegram-link` — if `true`, players must link Telegram to play (default `false`)
- `telegram-hint-every` — how often to show the `/link` hint (`0` — never, `1` — every join, `3` — every 3rd join)
- `block.*` — what to block for unauthenticated players
- `telegram.enabled` / `telegram.token` / `telegram.bot-username` — bot settings

## License

[MIT](LICENSE)

## Feedback

Found a bug or have a suggestion? Open an issue on GitHub — I read everything.