# RIZZLERS ProxyLogger

A production-grade **Velocity 4.3.0** proxy gatekeeper plugin designed for the **RIZZLERS** Minecraft network running on **Java 25**.

ProxyLogger enforces pre-login network access control based on Discord role verification, supports dual-party account linking with hashed one-time tokens, and audits all network connections, denies, and staff actions to local files and Discord channels.

---

## Key Architecture & Features

- **Pre-Login Gatekeeping**: Hooks into Velocity's `LoginEvent` using `EventTask.async()` continuation semantics. Never blocks Velocity's Netty event loop and fails closed if database or Discord API outages occur.
- **Strict Role Precedence**:
  1. **Blacklist Deny (Highest)**: If the member has any blacklisted role, they are denied immediately. Possessing an allow role **never** bypasses a blacklist.
  2. **Required Allow Role**: If whitelist roles are configured, the member must possess at least one to be admitted.
  3. **Allow (Lowest)**: Verified linked members without blacklist roles are allowed.
- **Two-Account Proof of Control**:
  - In-game: `/plog link` generates a secure random 6-character code (hashed using SHA-256, 5-minute expiry, rate-limited to 5/hour).
  - Discord: `/link <code>` slash command proves Discord account control and atomically links the identities in a single transaction.
  - Raw Discord IDs typed into Minecraft chat are never trusted.
- **Offline & Cracked Account Safety**:
  - Offline accounts are stored with `account_type = 'offline'`.
  - Does not assume offline UUIDs represent Mojang identity. Account linking binds only after both in-game and Discord control are verified.
- **Dual Persistence Engines**:
  - **MySQL / MariaDB**: Connection pooled with HikariCP for multi-proxy high-availability networks.
  - **SQLite**: Local single-file persistence (`proxylogger.db`) with zero external infrastructure required.
- **Auditing & Discord Embeds**:
  - Records every login, deny, blacklist hit, link, and admin command to the `access_audit` database table.
  - Asynchronously dispatches rich colored embeds to configured Discord log channels.
  - Appends rotating audit log lines to `plugins/RizzlersProxyLogger/audit.log`.

---

## Commands & Permissions

| Command | Description | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/plog link` | Generate a 5-minute one-time linking code | `rizzlersproxylogger.link` | All Players |
| `/plog unlink` | Disconnect own linked Discord account | `rizzlersproxylogger.link` | All Players |
| `/plog status` | View database pool, Discord bot, and rule metrics | `rizzlersproxylogger.admin` | Console / OP |
| `/plog reload` | Hot-reload `config.yml` | `rizzlersproxylogger.admin` | Console / OP |
| `/plog log <guild> <channel>` | Set Discord audit log destination | `rizzlersproxylogger.admin` | Console / OP |
| `/plog whitelist <guild> <role>` | Set or add required access role | `rizzlersproxylogger.admin` | Console / OP |
| `/plog whitelist <add\|remove\|list>` | Manage whitelist access rules | `rizzlersproxylogger.admin` | Console / OP |
| `/plog blacklist <guild> <role>` | Set or add blacklist deny role | `rizzlersproxylogger.admin` | Console / OP |
| `/plog blacklist <add\|remove\|list>` | Manage blacklist deny rules | `rizzlersproxylogger.admin` | Console / OP |
| `/plog unlink <target>` | Admin unlinking of target UUID or Discord ID | `rizzlersproxylogger.admin` | Console / OP |

*Aliases: `/proxylogger`, `/rplog`*

---

## Discord Slash Commands

- `/link code: <6-char-code>`: Ephemerally consumes the generated one-time code and links the player.
- `/unlink`: Ephemerally unlinks the Discord account from the RIZZLERS network.

---

## Configuration (`config.yml`)

```yaml
network:
  name: "RIZZLERS"
  require-link: true
  access-check-scope: "network"
  protected-servers: ["lobby", "survival"]
  deny-on-provider-error: true

discord:
  bot-token-env: "RIZZLERS_DISCORD_TOKEN"
  bot-token: ""
  guild-id: "YOUR_DISCORD_GUILD_ID"
  access-role-id: "YOUR_ACCESS_ROLE_ID"
  blacklist-role-id: "YOUR_BLACKLIST_ROLE_ID"
  log-guild-id: "YOUR_LOG_GUILD_ID"
  log-channel-id: "YOUR_LOG_CHANNEL_ID"
  link-instructions: "Join the RIZZLERS Discord and run /link <code>."

linking:
  code-expiry-seconds: 300
  max-attempts-per-hour: 5
  max-active-links-per-discord: 1
  allow-unlink: true

storage:
  driver: "mysql" # or "sqlite"
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "rizzlers_proxylogger"
    username: "CHANGE_ME"
    password-env: "RIZZLERS_DB_PASSWORD"
    ssl: true
  sqlite:
    file: "proxylogger.db"

logging:
  local-file: true
  log-joins: true
  log-quits: true
  log-denied-attempts: true
  log-role-check-failures: true
  log-link-events: true
  log-admin-changes: true
  include-ip-address: false
```

---

## Installation Guide (Velocity on Pelican / Plugged.host)

1. Download `RizzlersProxyLogger-1.0.0.jar` from GitHub Actions artifacts or GitHub Releases.
2. Open your Velocity proxy server in the Pelican / Plugged.host panel.
3. Stop the proxy server.
4. Upload `RizzlersProxyLogger-1.0.0.jar` into the proxy's `plugins/` folder.
5. In the **Startup** / **Environment Variables** tab:
   - Set `RIZZLERS_DISCORD_TOKEN` to your Discord Bot Token.
   - Set `RIZZLERS_DB_PASSWORD` to your database password (if using MySQL/MariaDB).
6. Start the proxy server.
7. Confirm clean initialization in the console:
   ```text
   [INFO] Starting RIZZLERS ProxyLogger initialization...
   [INFO] Database persistence initialized successfully (Driver: MYSQL).
   [INFO] Discord JDA bot initialized successfully.
   [INFO] Registered /link and /unlink Discord slash commands.
   [INFO] RIZZLERS ProxyLogger v1.0.0 enabled successfully. Pre-login access enforcement is active.
   ```

### Rollback Instructions
If issues arise:
1. Stop the Velocity proxy.
2. Remove `plugins/RizzlersProxyLogger-1.0.0.jar`.
3. Restart the proxy.
