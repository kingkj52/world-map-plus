# How other plugins and this map work together

## Plugin API v1

**Since round 57C, version 1 is answered by the World Map Plus Helper plugin and by nobody else.** The messages and their meaning are
unchanged. The Helper receives them in the namespace `worldmapplus`, keeps the markers and lines, answers `hello`, `covers`, `focus`
and the clicks, and shows everything on World Map Plus's map through version 3 (see "Plugin API v3", "How the Helper shows version 1").
World Map Plus itself reads none of them, so with the Helper missing another plugin simply gets no answer, which version 1 allows
(silence means "not there"). A `ready` from World Map Plus no longer promises that markers are taken: test for `marker` in the `messages` of
a `hello_reply` (the Helper's reply has `role` "helper") or in the Helper's own `ready`.

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

`WorldMapPlusApiTest` checks that the copy under `docs/api` is the same file as the one the tests hold; the Helper's own
`WorldMapPlusApiTest` runs that file against the Helper through a real event bus.

### What is read from the game's own map, and what cannot be

Without any message, and with the Helper installed, every `WorldMapPoint` another plugin gives RuneLite's `WorldMapPointManager` is drawn here
(the Helper's `Points`): its picture and the picture's anchor (`imagePoint`), its name and multi line tooltip as a card, snapping to
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

## Plugin API v3

Version **3** (round 60A; version 2 was never published and is gone) lets another plugin give this one parts of the interface and
behaviour, ask it questions and tell it what it knows. It was made so that part of World Map Plus lives in a second Plugin Hub plugin,
the **World Map Plus Helper** (the name is one constant, `Partners.HELPER_NAME`), and so that anyone's plugin can use the same doors.
The two plugins share no class: Hub plugins cannot, and cannot depend on each other. Everything crosses as a `PluginMessage` in the
namespace `worldmapplus`, and the data is only JDK, Swing, AWT and RuneLite API types (`WorldPoint`, `Color`, `Graphics2D`, `JComponent`,
the `java.util.function` types). One file of helpers for the partner side is `docs/api/WorldMapPlusPartner.java` (copy it like the v1
file); the Helper holds the same file as `link/PartnerApi`.

The whole vocabulary is ten message names, and it does not grow: a new feature is a new topic or a new kind, a row in a table below.

| Message | Who sends it | Data |
| --- | --- | --- |
| `partner_hello` | partner | `owner`, `role` ("part two" for the Helper, "other" for anyone else; "main" is taken as "other"), `apiVersion` 3 |
| `hello_reply` | World Map Plus | `id` (the partner's owner), `apiVersion` 3, `plugin`, `role` "main", `requests`, `states`, `pushes`, `kinds` (the topics and kinds below), `settingsGroup`, `helper`, `limits` |
| `ready` | World Map Plus | the same as `hello_reply` without `id`, once when it starts: a partner says hello again and registers again |
| `ping` | World Map Plus | none; every 30 seconds while a partner is known. Answer with `partner_hello`. A partner with no `hello_reply` should say hello again itself, after 2, 4, 8 up to 30 seconds (the Helper does); a hello over the 8 per second limit is answered on a later tick |
| `main_goodbye` | World Map Plus | none; it is stopping and forgets everything |
| `partner_goodbye` | partner | `owner`; everything it registered, asked and pushed is dropped at once |
| `register`, `unregister` | partner | a contribution (see "Kinds"); `unregister` takes `owner` and optionally `kind` and `id` |
| `request` | partner | `owner`, `topic`, `id` (optional), and the topic's keys |
| `reply` | World Map Plus | `id`, `topic`, `ok`, `reason`, and the topic's answer keys; only for a request that carried an id |
| `state` | both | `topic`, `seq`, and the topic's keys; a partner's carries its `owner`, World Map Plus's carries none |

### Rules

- **Owner, id, order.** Every partner message has an `owner` (the plugin's own id, 1 to 64 characters from `A-Z a-z 0-9 _ . : -`).
  At most 8 owners are known at once. A contribution has a `kind`, an `id` of the same kind and an `order` (-1000 to 1000, default 100);
  the same owner, kind and id sent again replaces it. Contributions sort by order, then owner, then id. At most 64 contributions, 32 per
  owner.
- **Untrusted values.** Everything is checked on arrival by one reader (`bridge/Wire`): types by `instanceof`, texts cleaned of control
  and format characters and cut (120 characters, menu texts 40, hints 60), lists copied before they are read, counts capped. A list
  that is not whole (one item of the wrong type) is refused whole. A message that cannot be read is ignored.
- **Rate.** At most 20 requests and states a second from one owner, and 8 hellos a second from everyone together; the rest are
  ignored. At most 32 questions wait at once.
- **Questions** (`request`) are kept and handled on the next client tick, on the client thread, never inside the sender's own post. An
  answer (`reply`) goes out on that tick or, for work done elsewhere, on the tick after it is ready. Answers and pushes wait at most
  5 seconds to be posted. A question nobody could answer in 5 seconds is dropped. A question without an id is done and not answered.
- **Pushed state** (`state`) is sent once and again only when it changed, at most once a game tick, with a `seq` that goes up from one
  push of a topic to the next. The receiver keeps the newest: a `seq` not higher than the last one taken from that owner for that topic
  is ignored, and so is a push of the wrong shape (the last good one stays). Nothing crosses per frame, per icon or per keystroke.
- **Presence.** A partner that has said nothing for 95 seconds is dropped as if it said goodbye, and everyone is told (push `off`,
  reason `silent`). Whatever a partner pushed is cleared when it is dropped: the judged answer, the layers list, the game map state, the
  undo of an order. A goodbye drops it the same way, without the push.
- **Guarded calls.** Every callback of a partner is called in one place (`Partners.call`), inside a `try`. An exception is logged once,
  and the third in a row switches that contribution off (push `off`, reason `failed`) until its owner registers it again. A painter, hit
  test, card or search provider that takes longer than its budget (painters 4 ms, hit tests 1 ms, cards and buttons 5 ms, search
  300 ms) is skipped for the next 15 calls, then 30, 60 and so on up to 600 while it stays slow; all painters together get 8 ms a frame.
  Clicks, menu choices and panel input are never skipped. A callback that never returns cannot be stopped: nothing in the JVM can, so a
  partner that hangs hangs the thread that called it (see `docs/RULES-AUDIT.md`, round 56A).

### Kinds (`register`)

`call` is the main callback; the other keys are listed with each kind.

| Kind | `call` | Other keys | When and on which thread |
| --- | --- | --- | --- |
| `section` | `JComponent` | `tab` ("places", "routes", "top" or any other name of at most 20 characters, which makes a tab of its own), `title` | Added to the side panel on the interface thread, under a header with the title. The tab "top" puts it above the tabs, under the panel's title and status line, with no header: the partner draws its own (the look is in the `theme` push). A section that takes no room while it has nothing to show leaves the panel as it was. A section that throws while it is painted counts as a failure |
| `action` | `Consumer<Map>` | none; the `id` is the action id | The side panel button bound to this action id sends it, with a payload, on the interface thread: `pins` and `routes` (lists of maps of the selected pins and routes, as in `saved`), and `order` (the pin ids in the order the player ticked them). The first handler by order takes it. Action ids: `share_places`, `share_route`, `import_code`, `order_route`, `undo_order` |
| `painter` | `BiConsumer<Graphics2D, Map>` | `layer`: "over" (the default), "route" (round 63F), "under" or "screen" | "over": every frame, after the other plugins' markers; "route": over the icons and labels, under the route's numbered discs, the saved routes and the pins (the Helper's walking lines; an older version of this plugin draws it as "over"); "under": right after the ground pictures, before the icons, labels, routes and pins. The graphics are a copy. The frame map holds `transform` (an `AffineTransform` from world tiles, x east and y north, to pixels), `bounds`, `mapId`, `floor`, `zoom` (pixels per tile) and `onMap`, a `BiPredicate<Double, Double>` that says whether a place (world x and y) belongs on the map being drawn. The map is read only and reused: read it during the call only. Runs on the thread that draws the map: the client thread for the game's map, the interface thread for the map windows |
| `painter`, layer "screen" | `BiConsumer<Graphics2D, Map>` | `input` `Function<Map, Boolean>` (required) | A panel in screen space, drawn every frame of a map that draws its controls, after the layers list's neighbours and before the search bar. The frame map has `map` (int[4]), `limits` (int[2]: highest start, furthest right), `mouse` (int[2]), `player` (double[3]: map or -1, x, y), `now` (long[1]), `fades` (boolean[1]), `surface` and `box` (int[5]); the arrays change in place, so a frame allocates nothing. The panel writes where it is into `box` (x, y, width, height; width 0 for nothing; the fifth place 1 asks for another frame soon), and the mouse events inside that box go to `input`, with `type` ("click", "press", "drag", "release", "wheel", and "close" when the map closes), `x`, `y`, `amount` and `surface`; it answers true when it took the event (for "press": a scroll bar drag began). Each map window has its own panel, told apart by `surface` |
| `hit` | `Function<Map, Map>` | `click` `Consumer<Map>` | While the pointer is over the map, at most once every 150 ms for a position. The frame has `x`, `y` (pixels), `worldX`, `worldY`, `mapId`, `floor`, `zoom`, `onMap`. Return null or a map: `name`, `detail`, `lines` (up to 8), `rows` (round 62E: a whole card, a List of two-element Lists, label and value, up to 160; the first 14 show at once and the rest when the card is expanded, as the cards of shops did) and `wiki` (what the card is for, such as `shop`, so the wiki button can name the page), `kind`, `id`, `point` (WorldPoint), `clickable`, `hint` (shown even when the thing is not clickable). It becomes a thing under the pointer with a card of its own, ranked with the others by the distance of its `point`. A click on a clickable thing goes to `click` on the client thread, with the thing as a map (`kind`, `name`, `id`, `tile`) |
| `card` | `Function<Map, Object>` | `owns` (List of thing kinds, up to 8) | When a hover card is made, asked with the thing under the pointer (`kind`: "pin", "marker", a category of place, "place"; `name`, `tile`, `id`) and answers up to 4 lines (8 in all from everyone). For a thing of a kind in `owns` it is also asked with `body` true for the whole card: once when the card is built and then at most once a second while the pointer rests. It answers null (the plain card stays) or a map: `title`, `subtitle`, `rows` (a List of two-element Lists, label and value; the first 14 show at once and up to 160 in all, the rest when the card is expanded; round 63F, it said at most 24), `hint` and `wiki` (what the card is for, as in a `hit` answer), drawn in this plugin's card style. The Helper owns `farming` and, since round 63F, `shop`: the card of a game icon of a shop is asked once when it is built, with `kind` "shop", `name` (the icon's), `tile`, `mapId` and `body` true, and the answer replaces the icon's plain card (null keeps it; the shop within 3.5 tiles of the icon). The Helper's own shop hit test (the pointer within 11 pixels of a shop) hides a shop icon's plain card under the same pointer |
| `button` | `Function<Map, List<String>>` | `click` `Function<Map, String>` (required) | A button in the corner of hover cards. When a card is made (not every frame) `call` gets the card's subject: `title`, and `what` (shop, quest, transport, "layer agility" and so on, or the kind of a search result), `name`, `detail` and `wikiSearch` (a name to search the wiki for) when it has them; it answers the button's text and the text of the matching right click entry (also offered on a search result's menu), or null. The first button by order wins. Pressing it sends the subject to `click` on the thread that took the click; the text it answers, if any, shows on the map's notice line. The Helper's wiki button is `worldmapplus-helper` / `wiki`, offered only while `showWikiButtons` is on; it names the wiki page from `what`, `name` and `detail` (round 61C: World Map Plus holds no list of pages) and falls back to searching for `wikiSearch` |
| `menu` | `Consumer<Map>` | `when` `Function<Map, List<String>>` (required), `label` (a name for the contribution) | When this map's right click menu opens, `when` gets the context (`tile`, `mapId`, `floor`, and `thing` and `things` (up to 6, nearest first) when something is under the pointer, each a thing map as above) and answers the texts of the entries for that place, one entry each (at most 8 in all), or null. Choosing one sends the context, with the text as `option`, to `call` on the client thread |
| `search` | `Function<String, List<Map>>` | `select` `Consumer<Map>`, `filtered` `Function<Map, List<Map>>` (round 63F) | When the search text changes. Runs on RuneLite's shared executor, one search at a time, only the newest text kept, so typing never waits. Each result is a map with `name`, `point` (WorldPoint) and optionally `detail`, `mapId`, `id`, and (round 62E) `kind` (one of `KIND`, `MONSTER`, `NPC`, `ITEM`, `MUSIC`, `QUEST`, `SHOP`: the label, the weight and the `type:` filters of that kind of result; any other word is a plain plugin result), `marks` (List of `double[]` {x, y}, up to 400: every place the result stands for, marked when it is chosen; repeated choices go round them nearest first), `aliases` (up to 6 other names it is found by, such as the singular of a kind) and `reveal` (the name of a category of the game's icons to draw at every zoom for a while when it is chosen). They are ranked by this plugin's own scoring; for plain words they are listed among this plugin's own results by that score, and for text with filters too, when only the score orders them (not with `sort:` or a distance, which put them last), at most 20. A partner answers the filters it can judge (the Helper: `type:`, `name:`, `cost:`, `price:`, `free:`, `dist:`, `loc:` and `sort:`). The filters of a place (`region:` and its other names, `near:` a name, `map:`, and their dash forms) are resolved by this plugin: for such a text, a provider that registered `filtered` is called with that instead of `call`, with a map of `text` (what was typed) and `place` (a `Predicate<double[]>` of {map id, x, y}, true when that spot is in the place the text names; it may be called many times on the shared executor and keeps no state), and applies it before it ranks and cuts to 20, so the answer holds what is there. A provider without `filtered` is called as before. Every result is judged against the same filters here whatever the provider did, so a wrong one is dropped. The Helper judges an "every X" result by each of its tiles and marks only those in the place Choosing one goes to `select` on the client thread with `name`, `tile`, `id`, `kind` ("search"), and a plugin with a screen painter is told of it in `chosen` |

Hover cards and menus of the map are drawn by this plugin only; a partner supplies lines, bodies, buttons and entries, never the drawing.

### Questions (`request` and `reply`)

Every reply has `id`, `topic`, `ok` (true when `reason` is "ok") and `reason`. Reasons every topic may give: "bad_request" (a key
missing or of the wrong shape), "not_ready" (the saved places are not loaded yet), "failed".

| Topic | Keys | Answer |
| --- | --- | --- |
| `saved` | `what` "pins" or "routes" (both when missing) | `pins` and `routes`, Lists of Map. A pin has `id` Long, `name`, `kind`, `point` (WorldPoint, with the floor), `mapId` (the map it was saved on), `autoMap` (the map its tile is shown on by itself, -1 for none), `color` (0xRRGGBB, 0 for the usual colour), `hidden`. A route has `id`, `name`, `stops` (List of pin ids), `loop` |
| `save_pins` | `points` (List of WorldPoint, 1 to 64), `maps` (List of Integer, one for each point, -1 for the map the tile is on), `names` (List of String, optional), `kind` (optional, default "pin") | Each point becomes a pin with the player's usual defaults (named "Pin at x, y" unless a name is given), unless the player has a place at that tile, floor and map (it is used again) or no map has that tile (it is left out). `added`, `had`, `skipped` (counts), `full` (true when the list of places filled up), `pinIds` (one for each point taken, null for one left out) |
| `save_route` | `points` (1 to 40), `maps`, `name`, `loop` | A place for every stop as above, then the route over them in order. Nothing is saved unless all of it fits. Reasons: "ok" (with `missing`, the number of new places, `stops` and `routeId`), "too_many_stops" (with `max`, 40), "routes_full", "no_map" (with `stop`, the number of the first stop that is on no map, from 1), "places_full" (with `missing`) or "full" |
| `route_order` | `route` (a route id), `expect` (the stops it must still have), `stops` (the same ids in the new order) | Reasons "ok", "changed" (the route no longer has the stops in `expect`), "bad_stop" (`stops` is not a new order of `expect`) |
| `go` | `point` | Asks Shortest Path, as the panel's Go button does, for the way from where the player stands, and stops a saved route that is being followed. Reasons "ok", "missing" (Shortest Path is not installed), "disabled" (it is off) |
| `coverage` | `points` (up to 16 WorldPoint) or `point` | `tiles`, one map for each point: `covered`, `exact`, `mapName`, `mapKind` (see "Asking whether the map covers a place" at the end of this page). Waits, up to 5 seconds, while the maps are still loading |
| `focus` | `point`; optional `zoom` (pixels per tile), `name`, `exact` | Brings the map to the place, as the v1 `focus` did. Answered once the map has acted, with `mapName`, `mapKind` and reason "ok" or "no_map" |
| `show` | `surface`, `mapId`, `x`, `y`; optional `name`, `detail`, `marks` (List of `double[]` {x, y}, up to 400) | Takes the map window with that surface id to the place, at least as close as a found place is shown, and marks the tiles as a chosen search result is marked. No answer |
| `follow` | `op` ("start", "update", "gone" or "stop"), `name`, `point` (for start and update) | Keeps the map on a point that moves, such as a party member: start takes the map, update moves the point, gone and stop end it. Dragging the map, a search, a focus or Locate me end it too, pushed as `follow_ended`. No answer |

### Pushed state (`state`)

From a partner (cached by World Map Plus, cleared at the partner's goodbye or silence):

| Topic | Keys | Meaning |
| --- | --- | --- |
| `judged` | `quests` (byte[]) and `missing` (String[] of the same length), or `cleared` true | What the Helper made of the player's progress for each need in the table World Map Plus sent (push `needs`, below), one place for each need number (round 61C replaced the `progress` snapshot, which World Map Plus judged itself): `quests` is the state of the quest the need starts (0 unknown or none, 1 not started, 2 started, 3 finished) and `missing` what the player is certainly missing for it as one line of words (`67 Sailing, Monkey Madness I`; null for nothing; cut to 300 characters). World Map Plus keeps the last answer and looks it up by need number (what a player lacks, finished quests, the dot on quest icons, the "Missing" row of a card and of a search result) and never asks. A need made after the answer is not judged. The two arrays must be as long as each other or the push is ignored. The Helper reads and judges only while one of `hideFinishedQuests`, `markUnusable`, `onlyUsable` or `colourQuestsByProgress` is on, and sends on the first game tick after a handshake and then only when the progress or the table changed. Without it every icon has the plain look |
| `layers` | `plugins`, `sent`: Maps of name to count (up to 40 each) | The tick boxes under the layers button: plugins with points on the game's map, and layers other plugins named for what they sent. The tick boxes are the `hiddenMapSources` setting |
| `game_map` | `needs` Boolean, `tip` String | The game's own map should be kept with this one (`needs`, if `syncGameMap` is on), and what the line up button's tag says |
| `status` | `text`, `open` Boolean | The text on the status line at the top of the side panel (on the interface thread), and the side panel opened when `open` is true |
| `order_undo` | `route` (Number), `stops` (List of pin ids) | The route card shows Undo order on that route while it has exactly those stops, and sends the action `undo_order`. An empty `stops` clears it |

From World Map Plus (no `owner`; `seq` goes up across all its topics):

| Topic | Keys | When |
| --- | --- | --- |
| `needs` | `needs`: a List of rows, one for each need number from 0; a row is a List of two, the ways (a List of ways; a way is a List of three Lists of Strings: levels, quests and the wiki's own wording, such as `67 Sailing`, `Monkey Madness I`, `Hard Ardougne Diary`, `32 Quest points`) and the name of the quest the need starts (empty for none) | The table of what icons, cards and search results ask of the player, in the data's own words (round 61C). After every `partner_hello`, and again whenever a need is made that was not in it. The partner judges the rows and answers with `judged` |
| `theme` | `name`, `colors` (Map of role name to `Color`: `ACCENT`, `TEXT`, `TEXT_DIM`, `PAGE`, `CARD`, `EDGE`, `FIELD`, `GOOD`, `BAD`, `BUTTON` and the rest), `edgeWidth` Float (1 or 2) | After every `partner_hello`, and when the player changes the theme |
| `chat_busy` | `busy` Boolean | After every `partner_hello`, and when World Map Plus's own search takes the game's chat area or lets go of it, so a partner that reads the chat line stands down |
| `saved_changed` | `pins`, `routes` Booleans | The saved pins or routes changed (once a client tick at most) |
| `route` | `steps`: a List of `double[8]`, one for each step of the route World Map Plus shows: x, y, toX, toY (tiles), the floor it starts on, the floor it ends on, 1 for a walk and 0 for a jump (a boat, teleport or shortcut), and for a walk the length in tiles it estimates | The route changed (round 62E; at most four times a second, and an empty list when there is none). World Map Plus draws the numbered discs of the jumps and the step list; the Helper draws the walks between them (it works out each way with the walking data, kept, and draws a dashed straight estimate until it is there). The `walker` kind of round 61C is gone: nothing asks another plugin for a walk any more, and the step list says "about" a number of tiles |
| `chosen` | `surface`, `entry` (the result as a map: `kind`, `name`, `detail`, `mapId`, `mapName`, `x`, `y`, `alsoAt`, and the `id` a partner's own search result gave, round 62E) | The player chose a search result and a partner has a screen painter. Such a partner takes over the results panel (the shops, places and drops of a result): it works the entries out itself (the Helper from its own data, since round 62E: the `places` question is gone), draws its panel and takes the map to a row with `show`. Without one no panel is shown. The kind of every place of a kind (round 62E, the Helper's `kinds.json`) is no longer sent: the partner that gave the result knows it by the `id` |
| `follow_ended` | `name` | Following ended here |
| `off` | `partner`, `kind`, `id` (empty for a whole owner), `reason` ("failed" or "silent") | A contribution or an owner was switched off. Register again to get it back |

### How the Helper shows version 1

World Map Plus holds no markers, lines, game map points or census. The Helper does (package `compat`, round 57C): it answers the version 1
messages and shows everything on this map through version 3. Its owner is `worldmapplus-helper`, with one painter (`v1`, order 100:
lines, then markers, then labels), three hit tests (`marker0` to `marker2`, the nearest three markers under the pointer) and one menu
(`markers`: the marker's own entries, **Focus on** for a point that jumps, **Follow** and **Stop following** for a party member). A
version 1 `focus` and `covers` become the questions `focus` and `coverage`, and their replies become `focus_result` and `covers_reply`
under the asker's own id; following a party member is the question `follow`; the layers list and the game map state are the pushes
`layers` and `game_map`. The Helper reads the game's map points and the census on the client thread only while the map was drawn in the
last 1.5 seconds, and pushes only what changed.

### Threads

| Entry point | Thread |
| --- | --- |
| Messages in | Whichever thread the sender posted on. The registry is locked; nothing waits on a partner. Requests are only kept |
| Requests | Handled on the next client tick (the client thread); `search` works on the shared executor |
| `painter`, `hit`, `card`, `button`, `menu` `when` | The thread that draws or opens the menu: the client thread, or the interface thread for a map window. Keep them quick and thread safe |
| `click`, `select`, menu `call` | The client thread |
| screen painter and `input` | The thread that draws the map surface the panel is on, which is also the thread its input arrives on |
| `action`, `section` components, `status` | The interface thread |
| Everything World Map Plus posts | The client tick |

### Settings contract

Part two has no settings of its own. Its settings live in this plugin's `WorldMapPlusConfig`, group `worldmapplus`, in sections whose
description says "needs World Map Plus Helper". A partner reads one with `ConfigManager.getConfiguration("worldmapplus", key)` (the
value as text, which it parses itself), and learns of a change from `ConfigChanged` for the same group. These key names are part
of the API and are checked by `PartnerSettingsContractTest`; they are never renamed, and a rename needs a new key:

| Key | Type | Meaning |
| --- | --- | --- |
| `shareCodesInChat` | boolean | Show what a `!wmp` code holds in place of the code |
| `showOtherPluginPoints` | boolean | Draw the points other plugins put on the game's own map (round 57C) |
| `hiddenMapSources` | string | The plugins and layers hidden from the map, separated by bars (round 57C) |
| `syncGameMap` | boolean | Keep the game's own map with this one (round 57C) |
| `shareReplaceOwnLines` | boolean | The same for the player's own lines |
| `typingGuide` | boolean | The typing guide for share codes |
| `typingGuidePosition` | enum | Where the guide's card sits |
| `theme` | enum | The palette (the `theme` push carries its colours) |
| `animations` | enum | How much the interface moves |
| `sounds` | boolean | Sound cues |
| `showResultsPanel` | boolean | Show the results panel after a search choice (round 57D) |
| `showHotspots` | boolean | Show cards on icons, doorways and shops; the Helper shows the cards of shops (round 62E) |
| `showDropTables` | boolean | Show the drop tabs in the results panel (round 57D) |
| `hideFinishedQuests` | boolean | Leave out the icons of finished quests (round 58E; the Helper reads the progress for it) |
| `markUnusable` | boolean | Dim icons the player cannot use yet (round 58E) |
| `onlyUsable` | boolean | Hide icons the player cannot use yet (round 58E) |
| `colourQuestsByProgress` | boolean | Dot on quest icons by progress (round 58E) |
| `showNpcs` | boolean | Mark the NPCs near the player (round 58F) |
| `npcMinZoom` | integer | Pixels per tile from which NPCs show |
| `npcRadius` | integer | Tiles around the player, at most 18 |
| `npcFilter` | string | Comma separated words, `!word` hides |
| `npcNames` | boolean | Names beside the dots |
| `npcNameZoom` | integer | Pixels per tile from which names show |
| `showRegions` | boolean | Tint, outline and name the regions (round 59G) |
| `showGrid` | boolean | The 64 tile region grid and ids |
| `showWilderness` | boolean | A line at each Wilderness level |
| `showTrail` | boolean | The trail of where the player has walked |
| `trailFade` | decimal | How far back the trail shows, in tens of tiles; 0 never fades it |

Keys that feature moves add later join this table in the same round that adds them. Round 56B moved Sharing and added none: the
Helper reads these through `ConfigManager` and `ConfigChanged` and ignores every other key. A key that is missing has the default
of `WorldMapPlusConfig` (`shareCodesInChat`, `shareReplaceOwnLines` and `typingGuide` on, `typingGuidePosition` over the line, `sounds`
off).

### Without a partner

Everything core works alone. A feature that needs part two shows "This needs the World Map Plus Helper plugin. Install it from the
Plugin Hub for full functionality." where its button or entry would be (a panel button bound to an action nobody handles shows it on
the panel's status line), and the panel says it once in a notice when a feature needs it and part two is not there. A partner alone
does nothing, and says it needs World Map Plus.

## Background: what plugins do to the game's map

This map covers the game's own world map. Other plugins were written for the game's map, not this one, and RuneLite gives
no way to move what they draw onto another map. This page says what a plugin can do to the game's map, what this plugin
does about each, and why that is safe. Everything here follows the Plugin Hub and Jagex rules: nothing is sent to the game server or any other server,
no reflection, no widget is created or changed, no menu entry is added to the map, no other plugin's code is looked up or called (version 3 only calls callbacks that a partner plugin hands over in its own messages, guarded as described above), and nothing a
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
| **A point** | A `WorldMapPoint` given to RuneLite's `WorldMapPointManager`, which RuneLite draws with an icon, a name and a tooltip, and which can jump the map when clicked | Golems Don't Die, Sailing, Bounty Sailing Map, Mahogany Homes, Turael Skipping, the Group Ironmen plugin, Quest Helper (step markers), Shortest Path (target) | Reads the points (the Helper's `Points`) and draws them as markers with their pictures, tooltips and clicks. Says which plugin each came from (the Helper's `Census`), lists the plugins under the layers button with a tick box each, and keeps the game's map with this one so a plugin that only places points near where that map looks (Golems Don't Die) still fills this map |
| **A message** | A `PluginMessage` to Shortest Path, or to this plugin's own `worldmapplus` namespace | Quest Helper and others ask Shortest Path for a route; plugins can send markers, lines and focus requests | Reads the route requests that go by, draws the route, its jumps and steps (`MapActions`, `ExternalOverlays`). The `worldmapplus` messages are "Plugin API v1" above |
| **An overlay hooked to the map** | An overlay drawn after the world map interface or its map layer | Shortest Path's path, RuneLite's own world map points | Shortest Path's path is drawn here from its messages. A hooked overlay of any other plugin is listed by the census, and the line up button makes it fit this map |
| **An overlay of its own on the always-on-top layer** | An overlay that works out where the game's map is looking and draws on it itself, above this map | Quest Helper's lines and arrows, Port Tasks' map overlay, Herbi AFK's lines | Cannot be redrawn here, because nothing says what it drew. The **line up** mode keeps this map exactly where the game's map is, so what they draw falls on the right tiles over this map |
| **A reading** | A plugin reads where the game's map is, its zoom or the area it shows, and adapts | Golems Don't Die (which points to build, how to group them), Port Tasks and Herbi AFK (where to draw), Quest Helper | Nothing to do: the game's map is still there. The census and the keep-with setting make what they read agree with this map |

The map drawn by this plugin and the game's map can be set to the same zoom and place, which is the one thing every way
above depends on. That is the whole design: **make the two agree where an agreement is needed, and draw what can be drawn.**

## The pieces

- **`Census`** (the Helper's package `compat`; since round 57C the census, the points and the version 1 answers are in the Helper, and
  `NativeMapSync`, `LineUp` and the game map moves stay here, told what to do by `game_map`). Reads RuneLite's public, read only lists of plugins and overlays about once a second, while
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
  the session, of every plugin that has had a point on the game's map (`Census.hasHadPoints`, which `Snapshot.needsGameMap`
  reports), including a plugin on `KNOWN_DRAWERS` once it has had one and points that are not shown here because the player
  turned off showing other plugins' points (`Census.remember`). A plugin that is switched off leaves the record. With no
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
- The census knows the plugins in its list by name (`Census.KNOWN_DRAWERS`) and any plugin with an always-on-top overlay
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
draws to `Census.KNOWN_DRAWERS`, which makes the line up button say so. A plugin's author can also ask this map for
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
