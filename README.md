# NeoPlayerSync

You run several NeoForge servers behind a proxy like Velocity. A player walks from one server to another. Without this mod, they arrive with an empty backpack, because each server keeps its own copy of the player.

NeoPlayerSync stores the player in one PostgreSQL database that all your servers share. When the player switches servers, the new server reads their character from that database, so they keep everything.

## What travels with the player

- inventory, armor, off hand and ender chest
- health, hunger and potion effects
- experience and the recipe book
- your mods' player attachments and persistent data, such as Curios slots

Position, spawn point, advancements and statistics stay on each server.

Some mods keep player data in a world save instead of on the player. That data stays on each server too. Sophisticated Backpacks stores backpack contents this way, so a backpack opens empty or out of date on the next server. Project MMO stores skill XP this way, so a player's skills don't follow them.

## What you need

- Minecraft 26.1.2, 26.2 or 26.3. One jar works on all three.
- NeoForge 26.1.2.114, 26.2.0.88 or 26.3.0.99, or a newer build of the same line
- [Kotlin for Forge](https://www.curseforge.com/minecraft/mc-mods/kotlin-for-forge) 6.4.0
- a PostgreSQL server (we test with 17.6)
- a proxy such as Velocity, with player forwarding set up

Players don't install anything. The proxy doesn't need the mod either.

Run the same Minecraft version on every server of a network. A server refuses to load a character that a newer version saved.

Update NeoPlayerSync on every server together, too. The first updated server can upgrade the database tables, and after that an older NeoPlayerSync refuses to start. Servers that are already running keep working.

## Setup

**1. Make a database.** In PostgreSQL, create an empty database and a user who owns it:

```sql
CREATE USER minecraft WITH PASSWORD 'pick-a-strong-password';
CREATE DATABASE minecraft OWNER minecraft;
```

NeoPlayerSync creates its own tables the first time it starts.

**2. Install the mod.** Copy the jar into the `mods` folder of each server behind your proxy. Start each server once, then stop it. That creates `config/neoplayersync.toml`.

**3. Fill in the config.** Open `config/neoplayersync.toml` on each server:

```toml
enabled = true
host = "10.0.0.5"        # your database's address
port = 5432
database = "minecraft"
user = "minecraft"
password = "pick-a-strong-password"
require_ssl = true       # set false only if the database runs on the same machine
ssl_root_cert = ""       # see "Check the database's certificate" below
server_name = "lobby"    # any name; the database shows it as the player's current server
```

[docs/default-config.toml](docs/default-config.toml) explains every setting.

**4. Start your servers.** Look for this line in each log:

```
NeoPlayerSync is storing players in Postgres.
```

If a server can't reach the database, it refuses to start rather than letting players load an old copy of their character. It also refuses to start if `config/neoplayersync.toml` is not valid TOML. NeoForge replaces a broken file with defaults, which turn sync off; it keeps your version as `config/neoplayersync-1.toml.bak`. Fix that copy and put it back.

The config file holds your database password. Keep it out of modpacks you share. NeoForge never sends it to players.

You can turn the mod on for a network that already has players. Each player's current character goes into the database the first time a server saves them.

### Check the database's certificate

`require_ssl = true` encrypts the connection, but it doesn't prove your server reached your database. On a private network that's fine. If your servers reach the database over the internet or a shared cloud network, someone in between could pose as the database, read the password and change characters.

To stop that, give each server the certificate of the authority that signed your database's certificate, as a PEM file:

```toml
ssl_root_cert = "config/postgres-ca.crt"
```

The database's certificate must name the address you put in `host`. A relative path starts from the server's folder.

On Windows, write the path with forward slashes, like `"C:/certs/ca.crt"`. A backslash breaks the config file, and the server then refuses to start. With this set, the server refuses to connect to anything that can't prove it is your database.

## How it works

Think of the database row as the one real copy of the player, and each server as borrowing it.

1. A player joins a server. That server borrows the player's row and copies the character into its normal save file. Minecraft loads the player from that file as usual.
2. While the player plays, the server writes their character back to the database. It does this about a second after the inventory changes, once a minute, and when they respawn or leave. It skips a save that matches the last one it sent, so an idle player's timed save sends no data.
3. The player switches servers. The old server writes their character one last time and hands the row back. The new server waits for that, then borrows the row.

Only one server can borrow a player at a time, so two servers can never overwrite each other. If a server crashes, the next server waits about 15 seconds to make sure the crashed one is gone, then takes the player. They lose up to a second of inventory changes and up to a minute of everything else.

## For mod developers

Most mods work with no changes. NeoPlayerSync moves your mod's player attachments and persistent data with the player. It leaves behind anything you store in a world save (`SavedData`) or under your own key in the player file. To make that data travel, keep it in a player attachment.

Two cases need a few lines from you.

### Save important changes right away

NeoPlayerSync uploads the player every time the server saves them and something changed, and the regular save comes once a minute. When a player earns something they must not lose to a crash, like an unlock or a rare reward, save them at once:

```java
player.level().getServer().getPlayerList().getPlayerIo().save(player);
```

NeoPlayerSync sends that save to the database within milliseconds. This is plain Minecraft code, so your mod needs no dependency on NeoPlayerSync, and it also protects players on a single server.

### Fix timers stored as game ticks

Say you store a cooldown as "ends at game tick 5,000,600". Each server counts game ticks from its own start, so that number means nothing on the next server. The player might wait hours, or skip the cooldown.

When NeoPlayerSync loads a player from the database, it tells you how old the save is. During `PlayerEvent.LoadFromFile`, the player's persistent data holds a compound named `neoplayersync:restored`:

| Field | Meaning |
| --- | --- |
| `saved_game_time` | the old server's game tick when it saved the player |
| `elapsed_millis` | real time since that save, in milliseconds |

Move your deadlines onto this server's clock:

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

A few rules:

- Use the normal event priority. NeoPlayerSync adds the tag at `HIGHEST` and removes it at `LOWEST`.
- The tag shows up at most once per join. A player loading for the first time gets no tag, and their timers stay as they are.
- Read the tag, don't remove it. Other mods may need it too.

You need no dependency on NeoPlayerSync. Without it, the tag never appears and your listener does nothing.

## Building

You need Java 25. On Windows:

```powershell
.\tools\run-gradle.ps1 build
```

The script uses `JAVA_HOME`, or a JDK you unpack into `tools/java25`. It builds the jar into `build/libs`, and that one jar runs on 26.1.2, 26.2 and 26.3.

Some tests need a real database. Unpack the PostgreSQL Windows binaries so that `tools/postgres/pgsql/bin` exists, then:

```powershell
.\tools\test-postgres.ps1 start
.\tools\run-gradle.ps1 build
.\tools\run-gradle.ps1 runGameTestServer --project-prop=includeGameTests=true
.\tools\test-postgres.ps1 stop
```

The tests run on 26.1.2. Add `-Pmc=26.2` or `-Pmc=26.3` to run them on a newer version. Keep the quotes, or PowerShell splits the version at the dot:

```powershell
.\tools\run-gradle.ps1 runGameTestServer '-Pmc=26.3' --project-prop=includeGameTests=true
```

The script runs a throwaway database on `127.0.0.1:5433`. Without it, the database tests skip themselves.

## License

NeoPlayerSync is free software under the [GNU General Public License, version 2](LICENSE) or, at your option, any later version (`GPL-2.0-or-later`).
