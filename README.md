# NeoPlayerSync

You run several NeoForge servers behind a proxy like Velocity. Each server keeps its own copy of every player, so a player who switches servers arrives with an empty inventory.

NeoPlayerSync keeps one copy of each player in a PostgreSQL database that all your servers share. When a player switches servers, they bring their character with them.

## What travels with the player

- inventory, armor, off hand and ender chest
- health, hunger and potion effects
- experience and the recipe book
- your mods' player attachments and persistent data, such as Curios slots
- advancements and statistics, if you turn on `sync_advancements` or `sync_statistics`

Position and spawn point stay on each server.

Some mods store player data in the world save instead of on the player. That data stays behind until the mod's author moves it onto the player (see [For mod developers](#for-mod-developers)) or NeoPlayerSync adds support for that mod.

## What you need

- [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge) 6.4.0
- a PostgreSQL server (we test with 17.6)
- a proxy such as Velocity, with player forwarding set up
- a mod that lets NeoForge accept that forwarding, such as [Proxy Compatible Forge](https://modrinth.com/mod/proxy-compatible-forge)

Players and the proxy don't install anything.

## Setup

**1. Make a database.** In PostgreSQL, create an empty database and a user who owns it:

```sql
CREATE USER minecraft WITH PASSWORD 'pick-a-strong-password';
CREATE DATABASE minecraft OWNER minecraft;
```

NeoPlayerSync creates its own tables.

**2. Install the mod.** Put the jar in the `mods` folder of each server. Start each server once and stop it. This creates `config/neoplayersync.toml`.

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

[docs/default-config.toml](docs/default-config.toml) explains every setting. Changes need a restart.

The file holds your database password. Keep it out of modpacks you share.

**4. Start your servers.** Each log should show:

```
NeoPlayerSync is storing players in Postgres.
```

You can add the mod to a network that already has players. A player's current character goes into the database the first time a server saves them.

## Keep your servers matched

- **Same Minecraft version everywhere.** A server refuses a character saved by a newer version.
- **Same mods everywhere.** A server missing a mod your players use refuses them, so their items and data from that mod don't get lost. To let players join a server with fewer mods, such as a hub, set `enabled = false` there.
- **Same NeoPlayerSync version everywhere.** Update all servers together. A newer version may upgrade the database tables, and an older one then refuses to start. Servers already running keep working.
- **Same `sync_advancements` and `sync_statistics` everywhere.** If you turn one on for a network that already has players, the first server to save a player decides what they keep. Other servers' copies get replaced.

A server with `enabled = false` keeps its own separate copy of each player and never touches the database.

## When a server won't start

A server refuses to start if it can't reach the database, so players never load an old copy of their character. It also refuses if `config/neoplayersync.toml` is broken. NeoForge replaces a broken file with defaults, which turn sync off, and saves yours as `config/neoplayersync-1.toml.bak`. Fix that copy and put it back.

## Commands

Both need operator permission level 2.

- `/nps` (or `/nps health`) checks the database. It shows how long the database took to answer, how many players this server holds, and how many saves are waiting to go out. It gives up after 20 seconds.
- `/nps sync` saves every player on this server now and waits until the database has them all. It reports how long that took, or that a save failed or took more than 20 seconds. The server keeps retrying a slow save. On a busy server it can cause a short lag spike.

## How it works

Think of the database as a library with one copy of each player. A server borrows a player when they join and returns them when they leave.

1. A player joins. The server borrows their character from the database and loads it.
2. While they play, the server sends their character back about a second after their inventory changes, once a minute, and when they respawn or leave. If nothing changed, it sends nothing.
3. The player switches servers. The old server sends the character one last time and returns it. The new server waits for that, then borrows it.

Only one server can borrow a player at a time, so two servers never overwrite each other.

If a server crashes, the next server waits about 15 seconds to be sure the crashed one is gone, then takes the player. The player loses up to a second of inventory changes and up to a minute of everything else.

## Check the database's certificate

On a private network, `require_ssl = true` is enough. Over the internet or a shared cloud network, someone in between could pretend to be your database and steal the password. To prevent that, give each server the PEM certificate of the authority that signed your database's certificate:

```toml
ssl_root_cert = "config/postgres-ca.crt"
```

The database's certificate must name the address in `host`. A relative path starts from the server folder. On Windows, use forward slashes, like `"C:/certs/ca.crt"`. A backslash breaks the config file.

## For mod developers

Most mods need no changes. NeoPlayerSync moves your player attachments and persistent data with the player. It leaves behind anything in a world save (`SavedData`) or under your own key in the player file. To make data travel, keep it in a player attachment.

Two cases need a few lines from you.

### Save important changes right away

The regular save comes once a minute. When a player earns something they must not lose to a crash, like an unlock or a rare reward, save them at once:

```java
player.level().getServer().getPlayerList().getPlayerIo().save(player);
```

This is plain Minecraft code, so you need no dependency on NeoPlayerSync.

### Fix timers stored as game ticks

Say you store a cooldown as "ends at game tick 5,000,600". Each server counts ticks from its own start, so that number means nothing on the next server. The player might wait hours or skip the cooldown.

When NeoPlayerSync loads a player from the database, it tells you how old the save is. During `PlayerEvent.LoadFromFile`, the player's persistent data holds a compound named `neoplayersync:restored`:

| Field | Meaning |
| --- | --- |
| `saved_game_time` | the old server's game tick when it saved the player |
| `elapsed_millis` | real time since that save, in milliseconds |

Shift your deadlines onto this server's clock:

```java
@SubscribeEvent
public static void onLoad(PlayerEvent.LoadFromFile event) {
    event.getEntity().getPersistentData().getCompound("neoplayersync:restored").ifPresent(restored -> {
        long savedGameTime = restored.getLongOr("saved_game_time", 0L);
        long elapsedMillis = restored.getLongOr("elapsed_millis", 0L);
        long now = event.getEntity().level().getServer().overworld().getGameTime();
        long shift = now - savedGameTime - elapsedMillis / 50;
        // Add shift to every deadline you stored as a game tick.
    });
}
```

- Listen at normal priority. NeoPlayerSync adds the tag at `HIGHEST` and removes it at `LOWEST`.
- The tag appears at most once per join. A player's first load gets no tag.
- Read the tag; don't remove it. Other mods may need it.

Without NeoPlayerSync installed, the tag never appears and your listener does nothing.

## Building

You need Java 25. On Windows:

```powershell
.\tools\run-gradle.ps1 build
```

The script uses `JAVA_HOME`, or a JDK unpacked into `tools/java25`. The jar lands in `build/libs`.

Some tests need a database. Unpack the PostgreSQL Windows binaries so that `tools/postgres/pgsql/bin` exists, then:

```powershell
.\tools\test-postgres.ps1 start
.\tools\run-gradle.ps1 build
.\tools\run-gradle.ps1 runGameTestServer --project-prop=includeGameTests=true
.\tools\test-postgres.ps1 stop
```

`test-postgres.ps1` runs a throwaway database on `127.0.0.1:5433`. Without it, the database tests skip themselves.

Tests run on 26.1.2. To test a newer line, add `-Pmc=26.2` or `-Pmc=26.3`. Keep the quotes, or PowerShell splits the version at the dot:

```powershell
.\tools\run-gradle.ps1 runGameTestServer '-Pmc=26.3' --project-prop=includeGameTests=true
```

## License

NeoPlayerSync is free software under the [GNU General Public License, version 2](LICENSE) or, at your option, any later version (`GPL-2.0-or-later`).
