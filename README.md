# dkAFK

AFK detection for the **dk plugin suite** (formerly DirekAntiAFK). Author: **direk james**.

Detects AFK players, including the usual tricks to look active, and runs your own commands when someone
stays AFK too long (for example, sending them to the AFK area). They're sent back to where they were as
soon as they're active again.

dkAFK is dkCore's AFK provider: every other dk plugin asks dkCore whether a player is AFK, and dkCore asks
dkAFK. See [For other dk plugins](#for-other-dk-plugins).

- **Server:** Purpur 26.3 (also loads on Paper, and on 26.1 / 26.2)
- **Java:** 25
- **Required:** [dkCore](https://github.com/direkjames/dkCore) 1.1.1+, PlaceholderAPI
- **Package:** `com.direk.dkafk`

## Building

1. Build dkCore once so dkAFK can compile against it: in the dkCore project run `./gradlew publishToMavenLocal`.
2. Open this folder in IntelliJ IDEA (Project SDK and Gradle JVM: **Java 25**) and let Gradle sync.
3. Run `build`:

```
./gradlew build
```

The jar is written to `build/libs/dkAFK-<version>.jar`. `build` also runs the detection unit tests.
dkCore is never shaded into the jar; it stays its own plugin on the server.

## Going live

1. Install dkCore and PlaceholderAPI, then put `dkAFK-<version>.jar` in `plugins/` and start the server.
2. In `plugins/dkAFK/config.yml`, set `afk-time`, `action-time` and your `actions`
   (your AFK area warp). Keep `freeze-on-actions` a little longer than the warp's delay.
3. Add `%dkafk_tag%` to your TAB suffix.
4. If EssentialsX is installed, turn off its `auto-afk` and `auto-afk-kick`.
5. Test with a non-OP account in survival (OPs and creative players are never checked), using
   `/dkafk debug <player>` to watch what counts.
6. Run `/dkafk reload` after any config change.

New settings are added to your `config.yml` automatically on startup (dkCore's config loader), with
their comments, so you never need to regenerate it after an update.

## Upgrading from DirekAntiAFK

1. Stop the server, delete `DirekAntiAFK-<version>.jar` from `plugins/` (keep its folder), and put in
   dkCore and `dkAFK-<version>.jar`. Don't run both jars at once.
2. On first start dkAFK copies `plugins/DirekAntiAFK/` into `plugins/dkAFK/`, so your config carries over.
   Once you've checked it, you can delete the old folder.
3. Everything from DirekAntiAFK keeps working, so nothing else has to change on day one:

| Old | New | Old name still works? |
|---|---|---|
| `/antiafk`, `/aafk` | `/dkafk` | Yes, as aliases |
| `antiafk.admin`, `antiafk.bypass` | `dkafk.admin`, `dkafk.bypass` | Yes, they grant the new ones |
| `%antiafk_...%` | `%dkafk_...%` | Yes, both are registered |
| Saved return spots and rejoin timers on players | moved to dkAFK automatically when they join | - |
| Plugins with `depend: [DirekAntiAFK]` | | Yes, dkAFK `provides` that name |

When it suits you, switch TAB and LuckPerms to the new names.

## How it works

```
real activity ──► idle ≥ afk-time ──► marked AFK (tag shows in TAB), a random check time is picked
                  idle ≥ check time ──► "Are you still there?" popup for check.timeout
                  no answer ──► actions run (their location is saved first)
looking around, moving or chatting ──► not AFK any more, sent back to the saved location
```

All times are counted from the player's last real activity.

```yaml
afk-time: "5m"
action-time: "6m-10m"   # or a fixed time like "10m"
```

With a range, every AFK period picks its own random time inside it, so players can't learn when the
popup comes. Here the tag appears after 5 minutes and the popup somewhere between 6 and 10 minutes.

The actions only run once per AFK period. A player standing in the AFK area isn't sent there again.
Logging out and back in within `rejoin-memory` (10 minutes) keeps their idle time, so relogging or an
auto-reconnect mod can't reset the timer.

## What counts as activity

**Real input** resets the timer and ends AFK:

| Counts | Doesn't count |
|---|---|
| Turning the camera to new directions | Tiny turns, or looking where they looked recently: **look macros** (back-and-forth, spinning, random camera wiggle) |
| Walking somewhere new while pressing movement keys | Being moved without pressing keys: **water currents, AFK pools, bubble columns, minecarts, boats, pistons, being pushed** |
| | Staying in the same small area: **jumping in one spot**, walking into a wall |
| | Walking or drifting in a loop back to recent spots |
| Chatting, answering the AFK check | The same message over and over |

**Click-type activity** (breaking, placing, clicking, attacking, fishing, eating, dropping items, inventory
clicks, commands) keeps an active player active, but only for `click-only-limit` (1 minute) after their last
real input, and it can't end AFK. This is what stops **auto-clickers and macros at any speed**: someone who
only clicks becomes AFK `afk-time` after they last looked around or moved. On top of that:

- The same action on the same target non-stop for longer than `max-streak` stops counting (e.g. **holding
  left-click on the oneblock**, a mob grinder).
- Clicks with machine-like timing stop counting (**auto-clickers**, held-down buttons), even through lag.

When either is caught, the credit it earned is taken back to when it started.

Teleports (warps, `/is go`, the AFK area) never count as activity.

## Commands

| Command | What it does |
|---|---|
| `/dkafk reload` | Reload `config.yml` |
| `/dkafk list` | Show AFK players |
| `/dkafk check <player>` | Status, idle time, last activity, and why recent actions were ignored |
| `/dkafk debug <player>` | Live feed of what counts and what doesn't for that player. Run again to stop |
| `/afk` | Players: go AFK now, or come back (see below) |

Aliases of `/dkafk`: `/antiafk`, `/aafk`.

| Permission | Default | Meaning |
|---|---|---|
| `dkafk.admin` | op | Use the commands above |
| `dkafk.bypass` | nobody | Never marked AFK |
| `dkafk.afk` | everyone | Use `/afk` |

The old `antiafk.admin` and `antiafk.bypass` still work and grant the new permissions.

OP players are also skipped while `exempt-ops: true` (the default), and so are players in creative or
spectator mode. **To test the plugin on yourself, set `exempt-ops: false` and use survival mode.**
`/dkafk check <player>` and `/dkafk debug <player>` say when, and why, a player is exempt. If you use a wildcard (`*`) in LuckPerms, set `dkafk.bypass`
to false for anyone who should still be checked.

## /afk

Players can mark themselves AFK with `/afk` (permission `dkafk.afk`, everyone by default):

- The AFK tag shows, `broadcast-afk` is sent and other dk plugins see them as AFK straight away.
- Moving, looking around or chatting brings them back, the same as normal AFK.
- `/afk` again also brings them back, but only if they've been active within `afk-time`. Otherwise they're
  told to move or look around, so spamming `/afk` (or a macro doing it) can never reset the AFK timer.
- The check and actions still come at `action-time`, counted from their last real activity.
- OPs and other exempt players can use it too. They get the tag but never the check or actions.
- `afk-command.cooldown` (3s) limits how often it can be used.

If EssentialsX is installed it has its own `/afk`. Add `afk` to `disabled-commands` in Essentials'
`config.yml` so dkAFK's is used. `/dkafk:afk` always reaches dkAFK's.

## Placeholders (PlaceholderAPI)

| Placeholder | Value |
|---|---|
| `%dkafk_tag%` | The `afk-tag` while AFK, otherwise empty |
| `%dkafk_afk%` | `true` / `false` |
| `%dkafk_idle%` | Time since last real activity, e.g. `4m 30s` |
| `%dkafk_idle_seconds%` | Same, in seconds |
| `%dkafk_afk_time%` | Idle time while AFK, otherwise empty |

The same placeholders also answer to the old `%antiafk_...%` names.

### TAB setup

Add the tag to the suffix in TAB's `groups.yml` (or wherever you set suffixes):

```yaml
_DEFAULT_:
  tabsuffix: "%dkafk_tag%"
```

If you already use a suffix, put the tag next to it, e.g. `"%luckperms-suffix%%dkafk_tag%"`.
TAB refreshes placeholders every 500 ms by default, so the tag appears almost instantly.

## Placeholders in messages and actions

All config text goes through PlaceholderAPI for the player it's about, then MiniMessage.

- `%player%`: the player's name (built into dkAFK, no expansion needed)
- `%dkafk_idle%` and the other `%dkafk_...%` placeholders above
- Any other PlaceholderAPI placeholder, e.g. `%player_world%` (needs `/papi ecloud download Player`)
- `<prefix>`: the configured prefix

## Actions

```yaml
actions:
  - "[message] <prefix><gray>Sending you to the AFK area."
  - "[console] warp AFKZone %player%"

freeze-on-actions: "5s"
```

Line types: `[console]` (default), `[player]`, `[message]`, `[broadcast]`, and
`[teleport] world x y z [yaw pitch]`.

### Warps with a warmup

Many warp plugins cancel the teleport if the player moves during the countdown. AFK players in water
pools, bubble columns, minecarts or boats keep moving, so the warp would never happen. While
`freeze-on-actions` is set, the plugin takes the player out of any vehicle and holds them in place
(they can still turn their head) until the warp teleports them, or until that time runs out. Set it a
little longer than your warp delay.

`[teleport]` avoids the warmup entirely, if you'd rather use fixed coordinates.

## Tuning

Use `/dkafk debug <player>` while someone plays normally or tries an AFK setup, then adjust
`detection` in `config.yml`:

- Real players marked AFK while mining: raise `click-only-limit` (try 2m).
- A movement setup isn't caught: raise `movement.radius` or `movement.loop-memory`.
- An auto-clicker isn't caught (very laggy connection): raise `clicks.tolerance-ms` (try 200).
- Fast-clicking players get flagged: lower `clicks.tolerance-ms` (try 100).

Performance: each mouse or movement update costs about 0.1 microseconds in the detection code, which
allocates no memory, and everything else runs once per second. Empty minecarts and boats are skipped.

If you also run EssentialsX, turn off its own `auto-afk` and `auto-afk-kick`, so the two don't conflict.

## For other dk plugins

Other dk plugins never depend on dkAFK directly. They ask dkCore, which asks whichever AFK provider is
registered (dkAFK). If dkAFK isn't installed, `isAfk` is always `false` and the event never fires, so a
plugin keeps working without it.

Is a player AFK right now (safe from any thread, e.g. async chat):

```java
if (DkCore.get().isAfk(player.getUniqueId())) { ... }
// or, in a DkPlugin:
if (core().isAfk(player.getUniqueId())) { ... }
```

React the moment someone goes AFK or comes back:

```java
@EventHandler
public void onAfkChange(DkAfkChangeEvent e) {
    if (e.isAfk()) { /* just went AFK */ } else { /* just came back */ }
}
```

`DkAfkChangeEvent` fires on the main thread. It isn't fired when an AFK player logs out, so clear any
per-player state on `PlayerQuitEvent` as usual. `core().hasAfkProvider()` tells you if dkAFK is running.

## Startup banner

On startup the console shows the dkAFK logo, version, the dkCore link, PlaceholderAPI, the AFK timeline,
the check, the actions and who is exempt. dkCore's "dk suite ready" summary also lists dkAFK and shows
the AFK provider as registered. Turn the dkAFK banner off with `startup-banner: false`.

## Project layout

```
src/main/java/com/direk/dkafk/
  DkAfk.java              plugin entry point (extends dkCore's DkPlugin), registers the AFK service
  Settings.java           reads and validates config.yml
  AfkManager.java         AFK timeline, check popup, actions, return, DkAfkChangeEvent
  ActivityListener.java   turns events into "counted" or "ignored"
  PlayerSession.java      per-player state
  Messenger.java          MiniMessage rendering
  command/                /dkafk (Brigadier)
  core/                   detection logic (no Paper code, unit tested)
  hook/                   PlaceholderAPI
  startup/                console banner
```

Some detection ideas (repeated actions, click timing, confinement) were inspired by
[bentahsin/AntiAFK](https://github.com/bentahsin/AntiAFK) (MIT). No code was copied.
