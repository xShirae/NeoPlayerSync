# NeoPlayerSync

If you run several NeoForge servers connected by a proxy like Velocity, each server normally keeps its own copy of every player. When a player switches servers, they appear on the new server with an empty inventory.

NeoPlayerSync fixes this by keeping one copy of each player in a shared PostgreSQL database. When a player moves to another server, their character goes with them.

## What travels with the player

- inventory, armor, off-hand, and ender chest
- health, hunger, and potion effects
- experience and the recipe book
- your mods' player attachments and persistent data, such as Curios slots
- advancements and statistics, if you turn on `sync_advancements` or `sync_statistics`

A player's position and spawn point remain specific to each server.

Some mods save player data in the world file instead of attaching it to the player. This data stays in the world until the mod author changes it to save on the player (see [For mod developers](#for-mod-developers)), or until NeoPlayerSync adds support for that mod.

## What you need

- [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge) 6.4.0
- a PostgreSQL server (we test with 17.6)
- a proxy such as Velocity, with player forwarding set up
- a mod that lets NeoForge accept that forwarding, such as [Proxy Compatible Forge](https://modrinth.com/mod/proxy-compatible-forge)

Players and the proxy don't need to install anything.

## Setup

**1. Create a database.** In PostgreSQL, create an empty database and a user who owns it:

```sql
CREATE USER minecraft WITH PASSWORD 'pick-a-strong-password';
CREATE DATABASE minecraft OWNER minecraft;
```

NeoPlayerSync creates its own tables in the database.

**2. Install the mod** by putting the jar into each server's `mods` folder. Start the server once, then stop it. This creates the `config/neoplayersync.toml` file.

**3. Fill in the config** on each server:

```toml
enabled = true
host = "10.0.0.5"         # your database's address
port = 5432
database = "minecraft"
user = "minecraft"
password = "pick-a-strong-password"
require_ssl = true        # set false only if the database runs on the same machine
ssl_root_cert = ""        # see "Check the database's certificate" below
server_name = "lobby"     # any name; the database shows it as the player's current server
sync_advancements = false # true moves advancements with the player
sync_statistics = false   # true moves statistics with the player
```

The [docs/default-config.toml](docs/default-config.toml) file explains every setting. Restart the server to apply any changes.

This file has your database password, so don't include it in any modpacks you share.

**4. Start your servers.** Each log should show:

```
NeoPlayerSync is storing players in Postgres.
```

You can add this mod to a network that already has players. The first time the server saves a player, it adds their current character to the database.

## Keep your servers matched

- **Use the same Minecraft version on all servers.** A server will reject a character saved by a newer version.
- **Use the same mods on all servers.** If a server is missing a mod your players use, it will refuse them to prevent loss of items and data from that mod. To let players join a server with fewer mods (like a hub), set `enabled = false` on that server.
- **Use the same NeoPlayerSync version on all servers** and update them at the same time. A newer version might upgrade the database tables, so older versions could refuse to start. Servers that are already running will keep going.
- **Use the same `sync_advancements` and `sync_statistics` settings on all servers.** If you turn one on in a network with existing players, the first server to save a player decides what they keep and replaces the copies on other servers.

If `enabled = false` on a server, it keeps its own copy of each player and does not use the database.

## When a server won't start

A server won't start if it can't connect to the database, which stops it from using outdated character copies. It also won't start if the `config/neoplayersync.toml` file is broken, even if it broke while the server was running. In that case, NeoForge replaces the config with defaults that turn sync off and saves your file as `config/neoplayersync-1.toml.bak`. Fix that copy and put it back, or delete it if you meant to turn sync off.

## Commands

Both commands need operator permission level 2.

- `/nps` (or `/nps health`) checks the database. It reports how long the database took to respond, how many players the server holds, and how many saves are queued. It times out after 20 seconds without a response.
- `/nps sync` saves every player on this server right away and waits until the database has them all. It reports how long it took, or if a save failed or took longer than 20 seconds. The server keeps retrying slow saves. On a busy server, this can cause a short lag spike.

## How it works

Think of the database as a library that keeps one copy of each player. When a player joins a server, the server borrows their character and gives it back when they leave.

1. A player joins. The server pulls their character from the database and loads it.
2. While playing, the server sends their character about a second after inventory changes, once a minute, and when they respawn or leave. If nothing changes, it doesn't send any data.
3. When the player switches servers, the old server sends the character one last time, then returns it. The new server waits for that and then takes over.

Only one server can have a player at a time, so two servers won't overwrite each other's data.

If a server crashes, the next server waits about 15 seconds to make sure the previous one is offline before taking over the player. The player could lose up to a second of inventory changes and up to a minute of other data.

## Check the database's certificate

On a private network, `require_ssl = true` is sufficient. Over the internet or shared cloud networks, someone could impersonate your database and steal the password. To prevent this, give each server the PEM certificate of the authority (CA) that signed the database's certificate:

```toml
ssl_root_cert = "config/postgres-ca.crt"
```

The database certificate must include the address in the `host` setting. If you use a relative path, it starts from the server folder. On Windows, use forward slashes (like `"C:/certs/ca.crt"`) because backslashes break the config file.

## Database connections

Each server keeps 3 connections open to PostgreSQL. One loads players as they join and handles reads from other mods, one sends saves, and one keeps the server's hold on its players. Set `max_connections` in PostgreSQL to at least 3 per server, plus room for anything else that uses the database.

## For mod developers

Most mods don't need any changes. NeoPlayerSync moves your player attachments and persistent data with the player. It leaves behind anything stored in a world save (`SavedData`) or under your own key in the player file. To move data, store it in a player attachment.

Two cases require a few lines from you.

### Save important changes right away

The regular save happens once a minute. If a player gets something they can't lose to a crash (like an unlock or rare reward), save the player right away with:

```java
player.level().getServer().getPlayerList().getPlayerIo().save(player);
```

This is plain Minecraft code, so you don't need any dependency on NeoPlayerSync.

### Fix timers stored as game ticks

If you store a cooldown as "ends at game tick 5,000,600", that number won't mean anything on the next server because each one counts ticks from its own start. The player might wait hours or skip the cooldown.

When NeoPlayerSync loads a player from the database, it tells you how old the save is. In `PlayerEvent.LoadFromFile`, the player's persistent data contains a compound called `neoplayersync:restored`:

| Field | Meaning |
| --- | --- |
| `saved_game_time` | the old server's game tick when it saved the player |
| `elapsed_millis` | real time since that save, in milliseconds |

Shift your deadlines to this server's clock:

```java
@SubscribeEvent
public static void onLoad(PlayerEvent.LoadFromFile event) {
    event.getEntity().getPersistentData().getCompound("neoplayersync:restored").ifPresent(restored -> {
        long savedGameTime = restored.getLongOr("saved_game_time", 0L);
        long elapsedMillis = restored.getLongOr("elapsed_millis", 0L);
        long now = event.getEntity().level().getServer().overworld().getGameTime();
        long shift = now - savedGameTime - elapsedMillis / 50;
        // Add shift to every deadline you've stored as a game tick.
    });
}
```

- Listen at normal priority. NeoPlayerSync adds the tag at `HIGHEST` and removes it at `LOWEST`.
- The tag is only ever present once per join. A player's first load will not have a tag.
- Read the tag; don't remove it. Other mods may need it.

If NeoPlayerSync isn't installed, the tag doesn't appear, and your listener does nothing.

You can type the names in yourself as above, or use `NeoPlayerSyncApi.RESTORED_TAG`, `SAVED_GAME_TIME` and `ELAPSED_MILLIS` from the [API](#read-a-player-who-isnt-here). Your compiler copies those names into your mod, so they work without NeoPlayerSync installed and need no `isLoaded` check.

### Read a player who isn't here

`NeoPlayerSyncApi` reads a player's save from the database, even if that player is offline or on another server. You could use that for a command that shows someone's stats. It doesn't lock the player, so they're still free to join any server.

Add it to your `build.gradle`. The API requires NeoPlayerSync 1.2.0 or newer:

```groovy
repositories {
    maven {
        url = 'https://api.modrinth.com/maven'
        content { includeGroup 'maven.modrinth' }
    }
}

dependencies {
    compileOnly 'maven.modrinth:neoplayersync:1.2.0'
}
```

`compileOnly` lets your mod run without NeoPlayerSync. If your mod needs it, use `implementation` and mark `neoplayersync` as required in your `neoforge.mods.toml` file.

`NeoPlayerSyncApi` lives in `dev.xshirae.neoplayersync.api` and has two calls:

| Call | Returns |
| --- | --- |
| `isDatabaseEnabled()` | `true` if this server stores players in the database. `false` with `enabled = false`, in singleplayer or LAN, or while the server isn't running |
| `readStoredPlayer(UUID)` | a `CompletableFuture<Optional<CompoundTag>>` holding the player's save file, upgraded to this Minecraft version the way vanilla upgrades a `.dat` file |

- Call it on the server thread (your callbacks run there too).
- An empty result means the database has nothing for that player yet. If they haven't joined since you enabled the database, their save may still be in `playerdata/<uuid>.dat` on one of your servers.
- If `isDatabaseEnabled()` is `false`, read `playerdata/<uuid>.dat` instead.
- A player on another server may have up to a minute of changes the database hasn't caught up with. For a player on your server, read their live data.
- The result is read-only.
- Read just one player when asked. If you read all of them at once, joining players will have to wait.

When a read fails, the future fails with an `IllegalStateException` that explains why. You never get a blank player. A read fails if sync is off, the server isn't running, a newer NeoPlayerSync or Minecraft saved the player, the save is broken, or the database errors or takes longer than 20 seconds.

To keep NeoPlayerSync optional, put your API calls in a separate class and call it only after `ModList.get().isLoaded("neoplayersync")` returns `true`. Java loads that class on first use, so your mod runs fine without NeoPlayerSync:

```java
final class StoredPlayers {
    static CompletableFuture<Optional<CompoundTag>> read(UUID playerId) {
        return NeoPlayerSyncApi.readStoredPlayer(playerId);
    }

    static boolean databaseEnabled() {
        return NeoPlayerSyncApi.isDatabaseEnabled();
    }
}

boolean useDatabase = ModList.get().isLoaded("neoplayersync") && StoredPlayers.databaseEnabled();
```

Only the `api` package and the `neoplayersync:restored` tag are public. The rest can change in any release. `NeoPlayerSyncApi.API_VERSION` is 1 and only goes up when a change breaks mods built for an older API. Your mod reads it at runtime, so you can check it before calling anything newer.

## Building

You need Java 25. On Windows:

```powershell
.\tools\run-gradle.ps1 build
```

The script uses `JAVA_HOME` or a JDK unpacked into `tools/java25`. The jar is placed in `build/libs`.

Some tests need a database. Unpack the PostgreSQL Windows binaries so `tools/postgres/pgsql/bin` exists, then:

```powershell
.\tools\test-postgres.ps1 start
.\tools\run-gradle.ps1 build
.\tools\run-gradle.ps1 runGameTestServer --project-prop=includeGameTests=true
.\tools\test-postgres.ps1 stop
```

`test-postgres.ps1` runs a throwaway database on `127.0.0.1:5433`. The database tests skip themselves if it is missing.

Tests run on 26.1.2. To test a newer line, add `-Pmc=26.2` or `-Pmc=26.3`. Keep the quotes or PowerShell splits the version at the dot:

```powershell
.\tools\run-gradle.ps1 runGameTestServer '-Pmc=26.3' --project-prop=includeGameTests=true
```

## License

NeoPlayerSync is free software under the [GNU General Public License, version 2](LICENSE) or, at your option, any later version (`GPL-2.0-or-later`).
