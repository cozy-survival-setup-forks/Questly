# Questly

A quest board for Paper 1.21+. A set of quests is up at the same time, every player takes part, and the first one to
reach the amount a quest asks for wins it and gets quest points. Points are spent in a shop. The board rests for a
while, then a new quest takes the place of the one that was won.

## Files

| File | What it holds |
| --- | --- |
| `quests.yml` | All the quests (300 come with the plugin) |
| `config.yml` | Size of the board, timing, chat rules, menu look |
| `lang.yml` | Every message |
| `shop.yml` | What quest points can buy |

## Quests

```yaml
quests:
  '1':
    title: Kill %required% Creepers
    chance: 50                 # how likely the quest is to come up, compared to the others
    settings:
      trigger: KILL
      extra: CREEPER           # what it is about, leave out for anything
      required: 75
      prevent-cheating: true
      win-points: 2
      rewards:                 # optional, commands for the places of the leaderboard
        1:
          commands: ["give %player% diamond 2"]
    display-item:
      material: CREEPER_HEAD
      name: '&#A8E6A3%title%'
      lore:
        - '&fProgress: &#AAA5FF%score%/%required%'
        - '&#FFE75C#1 &f%top_1_name%: &#FFE75C%top_1_score%'
```

Colours can be written as `&#rrggbb`, `&a` or MiniMessage. In `title`, `name` and `lore` these are filled in:
`%title%`, `%required%`, `%points%`, `%score%` (the viewer's own), `%top_1_name%` to `%top_5_name%` and
`%top_1_score%` to `%top_5_score%`.

### Triggers

| Trigger | Counts | `extra` |
| --- | --- | --- |
| `KILL` | Mobs the player killed | entity, such as `ZOMBIE` |
| `BREAK` | Blocks the player broke | block, such as `STONE` |
| `BREED_ENTITY` | Animals the player bred | entity |
| `TAME_ENTITY` | Animals the player tamed | entity |
| `FISH_CAUGHT` | Fish caught | the fish, such as `COD`. Without it any fish counts, junk does not |
| `CHAT` | Chat messages | none |
| `ENCHANT_ITEM` | Items enchanted at an enchanting table | none |
| `WALK`, `SPRINT`, `SWIM`, `AVIATE` | Blocks travelled | none |
| `RIDE_VEHICLE` | Blocks travelled in a boat or minecart | the vehicle, such as `OAK_BOAT` |

A `BREAK` quest for an ore, such as `COAL_ORE`, also counts the deepslate version of it.

### Cheating

With `prevent-cheating: true`, which every quest that comes with the plugin has:

- Blocks a player placed do not count when broken, even after a restart, and a piston moving them does not change that.
  Crops count only when they are ripe, whether they were planted by a player or not, so harvesting and replanting works.
- Mobs from a spawner or a spawn egg do not count.
- Chat messages have to be long enough, not too fast and not the same twice in a row (see `config.yml`).
- A jump of more distance than a player can cover in a second, such as a teleport, does not count.
- Actions that another plugin cancelled do not count.

Players in creative or spectator mode get no progress.

## Commands

| Command | Use |
| --- | --- |
| `/questly` | Open the board |
| `/questly shop` | Open the shop |
| `/questly points [player]` | See quest points |
| `/questly admin points give\|take\|set <player> <amount>` | Change points |
| `/questly admin setquest <slot> <quest id>` | Put a quest on the board |
| `/questly admin reset` | New quests and empty scores |
| `/questly admin reload` | Reload all the files |

`/quests` works the same as `/questly`.

## Permissions

- `questly.use`: open the board and shop (everybody)
- `questly.points.others`: see other players' points (op)
- `questly.admin`: everything under `/questly admin` (op)

## Placeholders (PlaceholderAPI)

`%questly_points%`, and for every place of the board, numbered from 1: `%questly_1_title%`, `%questly_1_required%`,
`%questly_1_points%`, `%questly_1_score%`, `%questly_1_time%` (time until a resting quest is replaced),
`%questly_1_status%` (`active` or `resting`), `%questly_1_top_1_name%` and `%questly_1_top_1_score%`.

## Good to know

- Everything is kept in `data.db` in the plugin folder, written in the background so the server never waits for the disk.
  Rewards for players who are offline are given when they join.
- Nothing is downloaded when the server starts, and no libraries are bundled.
- Everything runs on the main thread except writing to the file.

## Building

```
./gradlew build
```

The jar is in `build/libs`. Licensed under MIT.
