# Changelog

All notable changes to this project will be documented in this file.

## [1.1] - 2026-10-05

First public release.

### Features

- Dialog-based authentication (Paper 1.21.6+)
- Chat fallback mode (`/login`, `/register`)
- Multi-language support (`en`, `ru` + custom via `lang/` folder)
- Telegram bot integration:
    - Link Minecraft account to Telegram via `/link`
    - Password reset via `/resetpassword`
    - Change password from Telegram via `/newpass`
    - Unlink via `/unlink` (both in-game and in bot)
- Optional Telegram link requirement (`require-telegram-link` in config)
- Flow: login → link Telegram → play (when `require-telegram-link: true`)
- Admin commands:
    - `/unreg <player>` — remove player from database (permission `meowlogin.unreg`)
    - `/meowlogin reload` — reload config and languages
    - `/meowlogin lang <code>` — change language
- Configurable blocking for unauthenticated players
- Audit log file (`meowlogin.log`) with events: registration, login, failed login, password reset, TG link/unlink, admin unreg
- Tab-completion for `/unreg` is permission-aware

### Security

- `/link` and `/unlink` are only available after authentication
- `/unreg` is only available after authentication
- Telegram link is unique: 1 chat = 1 Minecraft account, 1 Minecraft account = 1 chat
- Telegram link codes expire after 10 minutes
- Minimum password length (`min-password-length` in config)

## License

[MIT](LICENSE)