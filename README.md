# NordFilter 1.2.0

Chat and private-message filtering for Paper 26.2 and Folia 26.2. One Java 25 JAR supports both platforms.

## Filtering

NordFilter applies a Latin-text policy while allowing digits, punctuation and emoji. A normalized immutable Aho-Corasick matcher checks banned words. Spam checks track repeats and alternating messages across reconnects until the history expires.

There is no packet interception or database. GitHub holds source and releases; installed configuration and player data stay private. Minecraft runtimes and synthetic test data belong only in isolated local fixtures.

Public chat and recognized private-message commands share the policy. Built-in private-message aliases are always checked, including `tell` and namespaced forms. Command-map canonicalization also checks commands rewritten by earlier handlers.

Default mute steps are 30 seconds, 5 minutes, 1 hour and 24 hours.

Bukkit player checks run on the player's owning region through a bounded bridge. One storage worker persists punishment data.

## Commands

- `/nordfilter health` reports readiness and storage state.
- `/nordfilter status <player>` inspects an account without creating an unknown one.
- `/nordfilter unmute <player>` removes the mute after successful persistence.
- `/nordfilter reset <player>` resets punishment state after successful persistence.
- `/nordfilter reload` requests an asynchronous settings reload. Check the outcome and `health`.

## Permissions

| Permission | Allows | Default |
| --- | --- | --- |
| `nordfilter.admin` | All commands above; checked in the executor | Operators |
| `nordfilter.bypass` | Bypass language, banned-word, spam and mute checks | Operators |

Admin access does not imply bypass. Removing OP does not remove permission attachments granted by another plugin.

If a command filter is installed, it must also allow the management command for the administrator.

## Persistence and failure handling

A pending mute restricts messages immediately. Unmute and reset take effect only after atomic persistence; a queued notice is not a durable-success confirmation.

Corrupt or wrongly typed punishment YAML fails closed rather than overwriting the file. Failed punishment-store initialization requires an offline repair and restart. Invalid settings reload keeps the last valid settings. The historical `SECURITY-1.1.0.md` report is excluded from the public repository.

## Build and tests

Use Maven 3.9+ and JDK 25. Run `mvn clean verify` or `./build.ps1`; the output is `target/NordFilter-1.2.0.jar`. Builds run regression tests without using active-server libraries. See [BUILDING.md](BUILDING.md) and [FOLIA.md](FOLIA.md).

`test-support/integration.cjs` uses loopback Paper, Velocity and NanoLimbo fixtures. Its `--old-chat` option accepts an unchanged NordChat JAR for a controlled compatibility test. `FilterTestProbe` injects synthetic faults only in the fixture and is excluded from the release JAR.

## Installation

Stop the server and back up the existing configuration, banned-word file and punishment data. Install one release JAR, retain those files and restart normally. Check `/nordfilter health` before admitting players.

Do not hot reload the plugin or copy synthetic fixture configuration to production.
