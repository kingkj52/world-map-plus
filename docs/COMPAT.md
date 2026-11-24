# How other plugins and this map work together

## Plugin API v1

This is the contract for a plugin that wants to put things on World Map Plus, version **1**. It is made of RuneLite
`PluginMessage` events, so a plugin needs no compile dependency on this one (Hub plugins cannot depend on each other) and
nothing happens when this plugin is not installed: nobody receives the messages.

**Compatibility promise.** Within version 1 no message name, key or meaning listed here is removed or changed. New keys and
new messages may be added; they raise the version number, and a plugin that does not know them is not affected. Unknown
message names and unknown keys are ignored, so a plugin may send keys of a later version to an older World Map Plus. Test
for a feature by its message name in the `hello_reply` (`messages`), not by comparing versions.

**Rules for every message.** Namespace `worldmapplus` only. Every value is checked on arrival (type by `instanceof`, texts
trimmed, cut and cleaned of control characters, lists copied before reading); a value of the wrong type is treated as missing.
Ids, where an id is asked for, are 1 to 64 characters from `A-Z a-z 0-9 _ . : -`. Counts are capped (see `limits` below).
Answers are never posted from inside the sender's own post: they are queued and posted by this plugin on a later client tick
(which also runs at the login screen), always in the `worldmapplus` namespace (no message can choose where an answer goes),
and dropped after 5 seconds. Every plugin subscribed to the namespace sees every answer, so answers carry no player data.

### Messages a plugin sends

| Name | Data | What it does |
| --- | --- | --- |
| `hello` | `id` String (required) | Asks what is supported. Answered with `hello_reply`. At most 4 a second are taken, whoever asks |
| `marker` | `id` String, `point` WorldPoint (both required); `source` String, `name` String, `tooltip` String (lines split on new lines), `color` Color, `image` BufferedImage (at most 64 by 64, copied), `clickable` Boolean, `hint` String, `menu` List of String, `layer` String | Shows a marker, or moves it when the same `source` and `id` are sent again. The card shows the name and tooltip. With `clickable` true, a left click on it posts `marker_clicked` (and the map does not move); `hint` is the card's line for that click (default "Click to choose"). `menu` adds up to 4 entries (40 characters each) to this map's own right click menu on the marker; choosing one posts `marker_menu`. `clickable` and `menu` are only taken when `source` and `id` are both plain ids as above. `layer` puts the marker under a tick box of that name in the layers list ("Sent to this map by other plugins") |
| `marker_clear` | `source` String, `id` String, both optional | Removes the markers that match; neither given removes every marker |
| `line` | `id` String, `points` List of WorldPoint (at least 2, at most 5000); `source` String, `color` Color, `label` String, `layer` String | Draws a line, or replaces it |
| `line_clear` | `source` String, `id` String, both optional | Removes the lines that match; neither given removes every line |
| `focus` | `point` WorldPoint (required); `zoom` Number (pixels per tile), `name` String, `id` String, `exact` Boolean | Brings the map to a place. With an `id` the result is posted as `focus_result`. With `exact` the map moves only if a map has pictures of that tile |
| `covers` | `id` String (required); `points` List of WorldPoint (up to 16) or `point` WorldPoint | Asks whether the maps have pictures of the tiles. Answered with `covers_reply`. At most 8 a second are taken, whoever asks |

### Messages this plugin posts

| Name | Data | When |
| --- | --- | --- |
| `hello_reply` | `id` (the asker's), `apiVersion` Integer, `plugin` String, `messages` List of String, `replies` List of String, `limits` Map of String to Integer | After a `hello` |
| `ready` | The same as `hello_reply` without `id` | Once when this plugin starts. It forgets every marker and line when it stops, so a plugin that hears `ready` sends its markers again |
| `covers_reply` | `id`, `covered`, `exact`, `mapNames`, `mapKinds` (lists parallel to the points) | After a `covers` (details at the end of this page) |
| `focus_result` | `id`, `ok` Boolean, `mapName`, `mapKind`, `reason` (`ok` or `no_map`) | After a `focus` that carried an id, once the map has acted |
| `marker_clicked` | `source`, `id` | The player left clicked a marker the plugin sent with `clickable` true |
| `marker_menu` | `source`, `id`, `option` (the entry exactly as sent) | The player chose an entry the plugin added with `menu` |

A click or menu choice is only reported for a marker that is still the one its plugin last sent under that source and id.
Nothing is reported for points on the game's own map, which belong to plugins that never asked.

`limits` today: `markers` 500, `lines` 60, `linePoints` 5000, `text` 120, `image` 64, `idLength` 64, `menuEntries` 4,
`menuText` 40, `layerText` 40, `hintText` 60, `coverPoints` 16, `coversPerSecond` 8, `hellosPerSecond` 4, `answerMillis` 5000.

### A plugin in twenty lines

Copy `docs/api/WorldMapPlusApi.java` into your plugin (one file, change its package line). Then:

```java
@Inject private EventBus eventBus;

private static final String ME = "myplugin";

@Override protected void startUp() { WorldMapPlusApi.hello(eventBus, ME + ":hello"); }

@Override protected void shutDown() { WorldMapPlusApi.clearMarkers(eventBus, ME, null); }

private void showSpot(WorldPoint spot)
{
    Map<String, Object> m = WorldMapPlusApi.marker(ME, "spot", spot);
    m.put("name", "My spot");
    m.put("clickable", true);
    m.put("menu", WorldMapPlusApi.menu("Route here", "Forget it"));
    m.put("layer", "My plugin");
    WorldMapPlusApi.send(eventBus, "marker", m);
}

@Subscribe public void onPluginMessage(PluginMessage e)
{
    if (WorldMapPlusApi.apiVersion(e) > 0) { mapPresent = true; resendMarkers(); }   // hello_reply or ready
    if (WorldMapPlusApi.isAboutMine(e, ME) && "Forget it".equals(WorldMapPlusApi.menuOption(e))) { forget(); }
}
```

`WorldMapPlusApiTest` compiles a copy of the helper and checks it against the bridge through a real event bus, and that the copy
under `docs/api` is the same file.

### What is read from the game's own map, and what cannot be

Without any message, every `WorldMapPoint` another plugin gives RuneLite's `WorldMapPointManager` is drawn here
(`NativePoints`): its picture and the picture's anchor (`imagePoint`), its name and multi line tooltip as a card, snapping to
the edge of the view (`snapToEdge`), and a click on a point with `jumpOnClick` takes this map to its target (or its own place),
with **Focus on** in this map's right click menu as RuneLite's own world map overlay offers. A point on a tile underground, in a
dungeon or in an area drawn from the game cache shows on whichever map of ours holds that tile, so points on other maps than
the surface need nothing. The World Map plugin's own points are left out because this map draws those itself, from the game's
own icon list. RuneLite's Party plugin points are points like any other, and this map can follow one (see the README).

What cannot be read, and is therefore not drawn here except through line up mode:

- Anything a plugin paints straight onto the world map widget itself, from an overlay of its own (Quest Helper's lines and
  arrows, Port Tasks, Herbi AFK, Shortest Path's own path drawing). Nothing records what such an overlay drew. Line up mode
  keeps this map exactly under the game's map so those drawings fall on the right tiles. Shortest Path's route is redrawn here
  from its messages instead.
- What a point does in `onEdgeSnap` and `onEdgeUnsnap` (for example a changed picture). Those are the plugin's own code; this
  map does not call them. It reads the picture the point has, at most every tenth of a second.
- Menu entries a plugin adds to the game's world map through RuneLite's menu events. This map adds no game menu entries to
  the world map and reads none; its own right click menu offers only what is listed here. (The one game menu entry of the
  plugin is "Open in World Map Plus" on a chat line that holds a share code, of RuneLite's own type.)
- Anything a plugin does with its own mouse listener on the game's map. In client 1.13.6 `WorldMapPoint` has no click
  callback (only `jumpOnClick` and `target`), so there is nothing else to translate.

## Background: what plugins do to the game's map

This map covers the game's own world map. Other plugins were written for the game's map, not this one, and RuneLite gives
no way to move what they draw onto another map. This page says what a plugin can do to the game's map, what this plugin
does about each, and why that is safe. Everything here follows the Plugin Hub and Jagex rules: nothing is sent to the game server or any other server,
no reflection, no widget is created or changed, no menu entry is added to the map, no other plugin's code is called, and nothing a
plugin does is blocked or changed. This plugin looks and draws its own map. The one thing it does to the game's own map is
to move where that map looks (`WorldMap.setWorldMapPositionTarget`, see below). Its other writes to the client are the text
of a received share code in chat (a label added after the code), RuneLite's chat area prompt and the client side "Open in
World Map Plus" entry on a chat line. The plugin API messages are unchanged by
the share codes: a shared point is shown with markers of its own source (`World Map Plus share`), which no message makes.

## What a plugin can do on the game's map

A survey of the 159 plugins installed on the machine this was built on (the ones that touch the map at all) shows five
ways, and every plugin found uses one or more of them.

| Way | What it looks like | Plugins seen | What this map does |
| --- | --- | --- | --- |
| **A point** | A `WorldMapPoint` given to RuneLite's `WorldMapPointManager`, which RuneLite draws with an icon, a name and a tooltip, and which can jump the map when clicked | Golems Don't Die, Sailing, Bounty Sailing Map, Mahogany Homes, Turael Skipping, the Group Ironmen plugin, Quest Helper (step markers), Shortest Path (target) | Reads the points (`NativePoints`) and draws them as markers with their pictures, tooltips and clicks. Says which plugin each came from (`MapCensus`), lists the plugins under the layers button with a tick box each, and keeps the game's map with this one so a plugin that only places points near where that map looks (Golems Don't Die) still fills this map |
| **A message** | A `PluginMessage` to Shortest Path, or to this plugin's own `worldmapplus` namespace | Quest Helper and others ask Shortest Path for a route; plugins can send markers, lines and focus requests | Reads the route requests that go by, draws the route, its jumps and steps (`MapActions`, `ExternalOverlays`). The `worldmapplus` messages are "Plugin API v1" above |
| **An overlay hooked to the map** | An overlay drawn after the world map interface or its map layer | Shortest Path's path, RuneLite's own world map points | Shortest Path's path is drawn here from its messages. A hooked overlay of any other plugin is listed by the census, and the line up button makes it fit this map |
| **An overlay of its own on the always-on-top layer** | An overlay that works out where the game's map is looking and draws on it itself, above this map | Quest Helper's lines and arrows, Port Tasks' map overlay, Herbi AFK's lines | Cannot be redrawn here, because nothing says what it drew. The **line up** mode keeps this map exactly where the game's map is, so what they draw falls on the right tiles over this map |
| **A reading** | A plugin reads where the game's map is, its zoom or the area it shows, and adapts | Golems Don't Die (which points to build, how to group them), Port Tasks and Herbi AFK (where to draw), Quest Helper | Nothing to do: the game's map is still there. The census and the keep-with setting make what they read agree with this map |

The map drawn by this plugin and the game's map can be set to the same zoom and place, which is the one thing every way
above depends on. That is the whole design: **make the two agree where an agreement is needed, and draw what can be drawn.**

## The pieces

- **`MapCensus`** (`compat`). Reads RuneLite's public, read only lists of plugins and overlays about once a second, while
  this map is showing, and says which plugins have points on the game's map, which have an overlay hooked to the map,
  and which are known to draw on it themselves. A point is traced to its plugin by the package its class is in, so no
  code of the plugin's is called. A plugin that is switched off is never named. With the map not showing it reads nothing.
- **Hiding a plugin's points.** The layers button lists the plugins that have points on the game's map, each with a tick
  box (the `hiddenMapSources` setting). A hidden plugin's points are not drawn here and still work in the game's own map.
- **Keeping the game's map with this one** (`NativeMapSync.drive`, setting `syncGameMap`, on by default). Some plugins only
  put points near where the game's map is looking: Golems Don't Die gathers the golems within a screen's width of it,
  grouped by its zoom. This map covers the game's, which then never moves, so those plugins would show only what is near
  where it was when the map opened. The game's own map is therefore sent to this map's centre, with RuneLite's own
  `WorldMap.setWorldMapPositionTarget`, even though the zooms differ (the plugins that need only the place are served by
  that). The trigger is sticky. Such a plugin has points only near where the game's map already looks, so at a new place it
  has none, and a test of "does a plugin have points now" would never pass there. The census therefore keeps a record, for
  the session, of every plugin that has had a point on the game's map (`MapCensus.hasHadPoints`, which `Snapshot.needsGameMap`
  reports), including a plugin on `KNOWN_DRAWERS` once it has had one and points that are not shown here because the player
  turned off showing other plugins' points (`MapCensus.remember`). A plugin that is switched off leaves the record. With no
  plugin ever having had a point, the game's map is never asked to move. When it does run, it waits until this map has
  stayed still for 8 frames, sends only when the game's map is more than 8 tiles from this map's centre, and sends no more
  often than every 45 frames. It never fights: it ignores the game's map while it is on its way, follows (and does not
  undo) a move that someone else made, and gives up if the game's map cannot look there. It does not run when this map is
  not showing, while line up drives, or when the setting is off.
- **Line up with the game map** (`LineUp`, setting `lineUpWithGameMap`, a button). This map takes the game's zoom and place
  every frame, by the arithmetic Quest Helper uses (tested against a copy of its own to a pixel and a half). The game's map
  then zooms and moves this one: the wheel and dragging on the map are left for the game, which is the only way to change
  its zoom, and this map follows. This map's own controls, and Ctrl with a right click for its menu, are still this map's.
  A right click otherwise opens the game's own menu, which has other plugins' entries. Moves made here (a search result, a
  link, Locate me, a pin) send the game's map after them. If the game's map shows another map (it was changed from its
  map list), so does this one.

## Why it is safe

- Looking is public API: `OverlayManager.anyMatch` and `PluginManager.getPlugins` are RuneLite's own calls, used here only
  to read. `WorldMapPointManager.removeIf` with a filter that keeps everything is used the same way to read points.
- No reflection and no other plugin's class is touched: attribution compares class names as text.
- The only thing done to the game's map is `setWorldMapPositionTarget`, the call RuneLite's own world map points use when
  clicked, and only after the player has moved this map or asked to line up.
- Mouse events are never made up. In line up mode events are simply not consumed, so the game gets them as it always did.
- Nothing is changed in another plugin or stopped from running; with this plugin off, or its map switched off (the
  `replaceWorldMap` setting), none of this runs and every plugin works on the game's map exactly as before.
- Everything is capped (400 points, 200 hidden plugin names, 8 targets) and an unreadable or changing list is ignored.

## Known limits

- A plugin that draws on the always-on-top layer is only right in line up mode, where the game's zoom range (not this
  map's wider one) applies and the game's own menu replaces this map's right click menu.
- Quest Helper, Port Tasks and the like draw only inside the game's map window and area, so a line off screen in the game's
  map is off screen here too.
- Golems Don't Die and plugins like it group their points at the game's zoom, so at another zoom the groups are the ones
  made for the game's. They show wherever this map looks without line up, since the game's map follows this map's place. Line
  up mode also makes the groups agree with what is shown.
- The census knows the plugins in its list by name (`MapCensus.KNOWN_DRAWERS`) and any plugin with an always-on-top overlay
  whose class name says it is for the world map. A plugin that draws on the map with neither is not named, but line up mode
  helps it just the same.

## Which tile to send for a player

Everything a plugin sends here, a world map point, a `marker` or a `focus`, is a `WorldPoint`, and this map shows it on
the map of ours that covers that tile (`MapCatalog.mapFor`, with the nearest map within 192 tiles for a tile no map covers).
The contract is one line: **send the tile where the player really is, converted with `WorldPoint.fromLocalInstance` when the
player is in an instance** (for a player outside one the two agree, but in an instance `getWorldLocation` gives the copy's
own coordinates). This plugin does the same for the player's own marker (`game/Positions`).

Inside an instance the converted point has the coordinates of the template the instance was copied from, so the map to
draw is the template's. The maps with ids from 11000 are drawn from the game cache for exactly these places. Two classes:

| Class | What the plugin sends | Maps of ours (id) |
| --- | --- | --- |
| **Real coordinates**: the area is where the cache puts it, nothing is copied | The tile the player stands on. Nothing to convert | Guardians of the Rift (11000), Motherlode Mine (11006), Giants' Foundry (11010), Kalphite Lair (11011), King Black Dragon Lair (11012), Zalcano (11013), Stronghold of Security levels 2 to 4 (11014 to 11016), the Cosmic, Chaos, Law, Body, Fire, Earth, Water, Mind, Air and Blood altars (11017 to 11026) |
| **Instance templates**: each party gets a copy of the template | `WorldPoint.fromLocalInstance` of the player's local point, which is the template tile. The copy's own coordinates mean nothing here | Chambers of Xeric (11001), Theatre of Blood (11002), Tombs of Amascut (11003), The Gauntlet (11004), The Corrupted Gauntlet (11005), The Inferno (11007), Duke Sucellus (11008), Hallowed Sepulchre floors 2 to 5 (11009) |

A point in an instance that was not converted lands in the region the instance copy was made in, where no map is drawn, so
the map falls back to the nearest one within 192 tiles or shows nothing. The list of these areas is
built into the plugin. The plane in the point is not used to choose a map. A map of ours has one picture
of an area, drawn from the cache floor with the most to show (and a chunk another floor fills in), so a point on any floor
of the area is drawn on it.

## Adding a plugin

Nothing needs adding for a plugin that uses points. For one that draws itself, add its name and a few words about what it
draws to `MapCensus.KNOWN_DRAWERS`, which makes the line up button say so. A plugin's author can also ask this map for
markers, lines and a focus directly: see "Plugin API v1" at the top of this page.

## Asking whether the map covers a place, and learning whether a focus worked

Two messages let another plugin ask before it acts, so it can use its own fallback when this map has no picture of a place. All messages
use the namespace `worldmapplus`. Answers are never sent from inside the plugin's message handler: a question is recorded, and the answer
is posted on a later game tick from the client thread, so no plugin is called back from inside its own post. Answers wait at most 5
seconds in the queue and are then dropped, so key on the id and give up after about 2 seconds. When this plugin is not installed nothing
answers, and silence means "not covered".

`covers`, data `{ "id": String, "points": List<WorldPoint> (up to 16) }` or `{ "id": String, "point": WorldPoint }`. The id is required, at
most 64 characters from letters, digits, `_ - . :`, and is echoed back unchanged. A question without a good id gets no answer. At most 8
questions a second are taken, whoever asks.

`covers_reply`, data `{ "id": String, "covered": List<Boolean>, "exact": List<Boolean>, "mapNames": List<String>, "mapKinds": List<String> }`,
parallel to the points asked. `exact` is true when a map has pictures of that exact tile. `covered` is true when `exact` is true or the
nearest map with pictures is within 192 tiles. `mapKinds` are words, never numbers: `surface`, `submap` (one of the game's own maps),
`extra` (one of the wiki's extra maps) and `area` (an area drawn from the game's data, such as a minigame, raid or boss). An uncovered tile
has empty strings.

`focus` keeps its old data (`point`, optional `zoom`, optional `name`) and gains optional `id` and `exact`. With `exact` true the map moves
only when a map has pictures of that exact tile; without it the nearest map within 192 tiles is used as before.

`focus_result`, sent only for a focus that carried an id, data `{ "id": String, "ok": Boolean, "mapName": String, "mapKind": String,
"reason": String }`. It is queued only after the map has acted, so `ok` true means the view has already moved. `reason` is `ok` or `no_map`
(nothing covered the tile and the view did not move).
