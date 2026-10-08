# DirekAntiAFK

Private Paper plugin that detects AFK players, including the usual tricks to look active, and runs your
own commands when someone stays AFK too long (for example, sending them to the AFK area). They're sent
back to where they were as soon as they're active again.

- **Server:** Paper 26.3 (also loads on 26.1 / 26.2)
- **Java:** 25
- **Required:** PlaceholderAPI, plus its Player expansion (`/papi ecloud download Player`, then `/papi reload`)

## Building

Open the folder in IntelliJ IDEA and let Gradle sync, then run `build`:

```
./gradlew build
```

The jar is written to `build/libs/DirekAntiAFK-<version>.jar`. `build` also runs the detection unit tests.

## How it works

```
real activity ──► idle ≥ afk-time ──► marked AFK (tag shows in TAB)
                  idle ≥ action-time ──► "Are you still there?" popup for check.timeout
                  no answer ──► actions run (their location is saved first)
any real activity ──► not AFK any more, sent back to the saved location
```

Both times are counted from the player's last real activity. With `afk-time: 5m` and `action-time: 10m`,
the tag appears after 5 minutes, the popup after 10, and the actions run at 10 minutes 30 seconds.

The actions only run once per AFK period. A player standing in the AFK area isn't sent there again.

## What counts as activity

Only things a person at the keyboard does:

| Counts | Doesn't count |
|---|---|
| Turning the camera to new directions | Tiny turns, or flicking between the same angles (look macros, spin macros) |
| Walking somewhere new while pressing movement keys | Being moved without pressing keys: **water currents, AFK pools, bubble columns, minecarts, boats, pistons, being pushed** |
| | Staying in the same small area: **jumping in one spot**, walking into a wall |
| | Walking or drifting in a loop back to recent spots |
| Breaking, placing, clicking, attacking, fishing, eating | Doing the exact same thing to the same target non-stop for longer than `max-streak` (e.g. **holding left-click on the oneblock**, a mob grinder) |
| | Clicks spaced with machine-like timing (**auto-clickers**, held-down buttons) |
| Chat, commands, inventory clicks | The same message or command over and over; commands in `ignored-commands` |

Teleports (warps, `/is go`, the AFK area) never count as activity.

## Commands

| Command | What it does |
|---|---|
| `/antiafk reload` | Reload `config.yml` |
| `/antiafk list` | Show AFK players |
| `/antiafk check <player>` | Status, idle time, last activity, and why recent actions were ignored |
| `/antiafk debug <player>` | Live feed of what counts and what doesn't for that player. Run again to stop |

Alias: `/aafk`. Commands, permissions (`antiafk.*`) and placeholders (`%antiafk_...%`) keep the short `antiafk` name.

| Permission | Default | Meaning |
|---|---|---|
| `antiafk.admin` | op | Use the commands above |
| `antiafk.bypass` | nobody | Never marked AFK |

OP players are also skipped while `exempt-ops: true` (the default). To test the plugin on yourself,
set it to `false` or de-op temporarily. If you use a wildcard (`*`) in LuckPerms, set `antiafk.bypass`
to false for anyone who should still be checked.

## Placeholders (PlaceholderAPI)

| Placeholder | Value |
|---|---|
| `%antiafk_tag%` | The `afk-tag` while AFK, otherwise empty |
| `%antiafk_afk%` | `true` / `false` |
| `%antiafk_idle%` | Time since last real activity, e.g. `4m 30s` |
| `%antiafk_idle_seconds%` | Same, in seconds |
| `%antiafk_afk_time%` | Idle time while AFK, otherwise empty |

### TAB setup

Add the tag to the suffix in TAB's `groups.yml` (or wherever you set suffixes):

```yaml
_DEFAULT_:
  tabsuffix: "%antiafk_tag%"
```

If you already use a suffix, put the tag next to it, e.g. `"%luckperms-suffix%%antiafk_tag%"`.
TAB refreshes placeholders every 500 ms by default, so the tag appears almost instantly.

## Placeholders in messages and actions

All config text goes through PlaceholderAPI for the player it's about, then MiniMessage. Use any
PlaceholderAPI placeholder, e.g. `%player_name%`, `%player_world%`, `%antiafk_idle%`. `<prefix>` inserts the
configured prefix.

## Actions

```yaml
actions:
  - "[message] <prefix><gray>Sending you to the AFK area."
  - "[console] warp AFKZone %player_name%"

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

Use `/antiafk debug <player>` while someone plays normally or tries an AFK setup, then adjust
`detection` in `config.yml`:

- Real players marked AFK while mining: raise `repeat.max-streak`.
- A movement setup isn't caught: raise `movement.radius` or `movement.loop-memory`.
- An auto-clicker isn't caught: raise `clicks.max-deviation-ms` a little (try 25).

If you also run EssentialsX, turn off its own `auto-afk` and `auto-afk-kick`, so the two don't conflict.

## Project layout

```
src/main/java/dev/antiafk/
  AntiAfkPlugin.java      plugin entry point
  Settings.java           reads and validates config.yml
  AfkManager.java         AFK timeline, check popup, actions, return
  ActivityListener.java   turns events into "counted" or "ignored"
  PlayerSession.java      per-player state
  Messenger.java          MiniMessage rendering
  command/                /antiafk (Brigadier)
  core/                   detection logic (no Paper code, unit tested)
  hook/                   PlaceholderAPI
```

Some detection ideas (repeated actions, click timing, confinement) were inspired by
[bentahsin/AntiAFK](https://github.com/bentahsin/AntiAFK) (MIT). No code was copied.
