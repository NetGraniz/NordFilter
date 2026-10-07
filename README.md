# NordFilter 1.2.0

One release JAR for Paper 26.2 and Folia 26.2: [compatibility notes](FOLIA.md).

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).
> Older local paths below describe historical test fixtures, not the release build.

Paper/Folia 26.2 / Java 25 moderation plugin. No packet interception or database.
GitHub holds current sources and releases; installed configuration and player data stay private.
Minecraft runtimes and synthetic test data must stay in isolated LOCAL fixtures.

- Latin-script policy permits digits, punctuation and emoji.
- Normalized banned-word matching uses an immutable Aho-Corasick matcher.
- Repeated-message history includes alternating messages and survives reconnects
  until expiry. Public chat and recognized private commands share the same policy.
- Default mute progression remains 30 seconds, 5 minutes, 1 hour and 24 hours.
- Built-in PM aliases are always checked, including tell and namespaced variants;
  command-map canonical aliases and earlier command rewrites are recognized.
- Bukkit session/permission checks execute only on the main thread via one bounded
  pump; disk persistence executes on one owned background worker.
- Pending mutes apply immediately. Unmute/reset take effect only after atomic
  persistence. The console acknowledgement says queued, not durably completed.
- Corrupt typed YAML fails closed without overwriting it. Invalid settings reload
  keeps the previously valid settings. See SECURITY-1.1.0.md for limits/caveats.

Admin commands: /nordfilter health, status PLAYER, unmute PLAYER, reset PLAYER,
reload. Explicit nordfilter.admin is required even on a direct executor call.
Operators intentionally retain nordfilter.bypass by default. Removing op alone
is not a substitute for reviewing effective permission attachments.

Reload is asynchronous: check the console outcome and health. It is not plugin
hot-reload. Failed punishment initialization requires offline repair and restart.
Unknown accounts are not manufactured by a status lookup.

Build: run build.ps1 against an isolated local Paper 26.2 fixture; it compiles and
runs the assertion-enabled regression suite, then packages build/NordFilter-1.1.0.jar.
test-support/integration.cjs tests loopback-only Paper/Velocity/NanoLimbo;
--old-chat selects the unchanged production NordChat JAR for local compatibility.
FilterTestProbe provides LOCAL-only synthetic fault injection and must NEVER be
installed in production. It is excluded from the release JAR.

Deployment is a separate approved operation: stop Paper, take a private verified
backup, retain existing config/banwords/data, replace only the NordFilter JAR,
ensure exactly one version is installed, restart and verify health/logs. Never
copy synthetic test data, overwrite the word list, hot-reload or replace a running JAR.
Add nordfilter to CommandWhitelist only for an administrative group if needed.
