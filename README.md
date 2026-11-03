# NeoPlayerSync

NeoPlayerSync keeps a player's character in a PostgreSQL database, so they can move between NeoForge servers behind a proxy such as Velocity and arrive with everything they had. It is for networks of modded servers, where every mod's player data needs to travel too.

It moves the player's whole save file, so these travel together:

- inventory, armor, off hand and ender chest
- health, absorption, hunger and effects
- vanilla experience and the recipe book
- every mod's player attachments and persistent data

Position, spawn point, advancements and statistics stay on each server.

## Requirements

- Minecraft 26.3 with NeoForge 26.3.0.99 or a later 26.3 build
- [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge) 6.4.0
- PostgreSQL. It was tested with 17.6.
- A proxy that forwards players between backends, such as Velocity with modern forwarding

Install it on every backend server. Clients do not need it, and the proxy does not need it. It does nothing in singleplayer or on a LAN world.

## Setup

1. Create an empty database and a user who can create tables in it. NeoPlayerSync creates and upgrades its own tables.
2. Put the jar in each backend's `mods/` folder and start the server once to create `config/neoplayersync.toml`.
3. Set `enabled = true` and fill in the database settings. [docs/default-config.toml](docs/default-config.toml) lists every setting.
4. Restart. The log shows `NeoPlayerSync is storing players in Postgres.`

With `enabled = true`, a server that cannot reach the database stops at startup instead of falling back to local files, because that would split a player's data between servers. The file holds the database password, so keep it out of shared modpacks. It is a `LOCAL` config and is never sent to clients.

A player's existing data on a server is uploaded the first time that server saves them, so a network can turn the mod on without a reset.

## How it works

Before a player's data loads, NeoPlayerSync claims the player's row and writes the stored character into the local save file. Vanilla then loads it as usual. After each save, it uploads the new file. Only one server can hold a player at a time: a lease, renewed every 5 seconds, stops two servers from writing the same character, and a joining server waits until the previous one has finished saving.

Saves happen within about a second of an inventory change, every 60 seconds, on respawn, on logout and at server stop. A crashed server loses about a second of inventory changes and up to 60 seconds of everything else. [docs/design.md](docs/design.md) explains the design, the limits and the failure cases.

## For mod developers

Your mod's player attachments and persistent data travel with no extra work.

NeoPlayerSync uploads every save of the player file, whatever caused it. For a change that must not wait for the next timed save, such as an unlock, save the player with vanilla's `server.getPlayerList().getPlayerIo().save(player)`. The upload is queued at once and sent on a separate thread, so it is in the database within moments rather than up to a minute later.

Deadlines stored as an absolute game tick, such as cooldowns, need one adjustment, because each server's game time is different. While `PlayerEvent.LoadFromFile` is dispatched for a player whose stored character was just loaded, NeoPlayerSync puts a compound under `neoplayersync:restored` in the player's persistent data:

| Field | Meaning |
| --- | --- |
| `saved_game_time` | The old server's overworld game time when the character was saved |
| `elapsed_millis` | Real time since that save, including the join |

Read it in a `LoadFromFile` listener at any priority between `HIGHEST` and `LOWEST`, and move each deadline by `nowTick - saved_game_time - elapsed_millis / 50`. The tag is present at most once per join and is removed before anything can save it. Your mod needs no dependency on NeoPlayerSync.

## Building and testing

Use Java 25. On Windows, `.\tools\run-gradle.ps1 build` uses `JAVA_HOME` or a JDK under `tools/java25`.

The database tests need a local PostgreSQL. Extract the PostgreSQL Windows binaries so that `tools/postgres/pgsql/bin` exists, then start the disposable test database on `127.0.0.1:5433`:

```powershell
.\tools\test-postgres.ps1 start
.\tools\run-gradle.ps1 build
.\tools\run-gradle.ps1 runGameTestServer --project-prop=includeGameTests=true
.\tools\test-postgres.ps1 stop
```

Without the database, those tests are skipped: the unit tests report them as skipped, and each GameTest logs `SKIPPED a player data GameTest` and passes.

## License

[Apache License 2.0](LICENSE).
