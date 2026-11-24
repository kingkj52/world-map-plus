package com.worldmapplus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Every feature can be switched off on its own. Nothing here changes anything about the game;
 * the settings only decide what the plugin draws.
 */
@ConfigGroup(WorldMapPlusConfig.GROUP)
public interface WorldMapPlusConfig extends Config
{
	String GROUP = "worldmapplus";

	/** Where the map looks when it opens. */
	enum StartAt
	{
		PLAYER("Where I am"),
		LAST_VIEW("Where I last looked");

		private final String label;

		StartAt(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** How much of the game view the floating map covers. */
	enum HudSize
	{
		SMALL("Small", 25),
		MEDIUM("Medium", 40),
		LARGE("Large", 60),
		FULL("Whole view", 100);

		private final String label;
		private final int percent;

		HudSize(String label, int percent)
		{
			this.label = label;
			this.percent = percent;
		}

		public int percent()
		{
			return percent;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Where the typing guide for share codes is drawn. */
	enum GuidePosition
	{
		OVER_LINE("Over the typing line"),
		ABOVE_LINE("Just above the typing line");

		private final String label;

		GuidePosition(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** Where the floating map sits in the game view. */
	enum HudCorner
	{
		TOP_LEFT("Top left"),
		TOP_RIGHT("Top right"),
		BOTTOM_LEFT("Bottom left"),
		BOTTOM_RIGHT("Bottom right"),
		CENTRE("Centre");

		private final String label;

		HudCorner(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigSection(
		name = "General",
		description = "What the viewer does when the world map opens",
		position = 0
	)
	String generalSection = "general";

	@ConfigSection(
		name = "Zoom",
		description = "How the mouse wheel zooms the map",
		position = 1
	)
	String zoomSection = "zoom";

	@ConfigSection(
		name = "Map layers",
		description = "What the map draws over the imagery. The layers button on the map changes the same settings",
		position = 2
	)
	String layerSection = "layers";

	@ConfigSection(
		name = "Floating map",
		description = "A see-through map you can keep open over the game while you play",
		position = 3
	)
	String hudSection = "hud";

	@ConfigSection(
		name = "Nearby NPCs",
		description = "Markers for the NPCs around you, like the minimap's dots",
		position = 4
	)
	String npcSection = "npcs";

	@ConfigSection(
		name = "Your progress",
		description = "Let the map respond to your levels and quests. Everything here is off until you turn it on",
		position = 5
	)
	String progressSection = "progress";

	@ConfigSection(
		name = "Look and feel",
		description = "The colours of the map's cards, menus and the side panel, and how much the plugin moves and sounds",
		position = 6
	)
	String lookSection = "look";

	@ConfigSection(
		name = "Sharing",
		description = "Pins and routes sent to other players as !wmp codes, and the guide that helps you type one",
		position = 7
	)
	String sharingSection = "sharing";

	@ConfigItem(
		keyName = "replaceWorldMap",
		name = "Replace the world map",
		description = "Draw this plugin's map over the world map window. Turn off to see the game's own map",
		section = generalSection,
		position = 0
	)
	default boolean replaceWorldMap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "followOtherPlugins",
		name = "Follow other plugins",
		description = "When another plugin centres the game's own map on something, such as a group member or a "
			+ "quest step, bring this map there too",
		section = generalSection,
		position = 5
	)
	default boolean followOtherPlugins()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOtherPluginRoutes",
		name = "Show other plugins' routes",
		description = "When another plugin asks Shortest Path for a route, such as the group list or a quest "
			+ "guide, mark its target and draw its steps on this map as well. This asks Shortest Path once "
			+ "more for the same route with one extra setting, so it sends the route's jumps",
		section = generalSection,
		position = 7
	)
	default boolean showOtherPluginRoutes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareCodesInChat",
		name = "Show share codes in chat",
		description = "When another player sends a !wmp code for a pin or a route, show what it holds in place of the code "
			+ "on your screen (as !lvl shows a level), list it in the side panel and offer Open in World Map Plus on the "
			+ "line's right click menu. Nothing is saved until you press Save. Off leaves chat exactly as it is",
		section = sharingSection,
		position = 0
	)
	default boolean shareCodesInChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareReplaceOwnLines",
		name = "Replace my own share lines too",
		description = "Show what your own !wmp codes hold in place of the code on your screen, as for codes from others. "
			+ "Off leaves your own lines as you typed them. What you send is never changed either way",
		section = sharingSection,
		position = 1
	)
	default boolean shareReplaceOwnLines()
	{
		return true;
	}

	@ConfigItem(
		keyName = "typingGuide",
		name = "Typing guide",
		description = "When you share a pin or a route, show the code as see-through letters on the chat's typing line. "
			+ "Each letter you type turns green when it is right and red when it is wrong, and a long code moves on to its "
			+ "next part after you send one. You still type and send every message yourself",
		section = sharingSection,
		position = 2
	)
	default boolean typingGuide()
	{
		return true;
	}

	@ConfigItem(
		keyName = "typingGuidePosition",
		name = "Typing guide position",
		description = "Over the typing line puts the guide's letters on your own as you type. Just above the typing line "
			+ "shows the whole code in a strip above it instead, if the letters do not line up on your screen",
		section = sharingSection,
		position = 3
	)
	default GuidePosition typingGuidePosition()
	{
		return GuidePosition.OVER_LINE;
	}

	@ConfigItem(
		keyName = "syncGameMap",
		name = "Keep the game map with this one",
		description = "Once you stop moving this map, move the game's own world map, which is still underneath, to the "
			+ "centre of what you are looking at, even though the zoom differs. Plugins such as Golems Don't Die only put "
			+ "their points near where the game's map looks, so without this they show nothing away from where it was. "
			+ "It starts after any other plugin has put a point on the game's map, and does nothing before that. Lines "
			+ "some plugins draw themselves need the zoom to match too, which is what Line up with the game map does",
		section = generalSection,
		position = 8
	)
	default boolean syncGameMap()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lineUpWithGameMap",
		name = "Line up with the game map",
		description = "Keep this map at the zoom and place of the game's own world map, so that lines other plugins "
			+ "draw on the game's map land on the right tiles here. Quest Helper draws the lines of some of its "
			+ "steps that way. While this is on the game's map moves and zooms this one: drag and use the wheel as "
			+ "usual, and Ctrl and right click opens this map's menu in place of the game's. Also a button on the map",
		section = generalSection,
		position = 9
	)
	default boolean lineUpWithGameMap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showOtherPluginPoints",
		name = "Show other plugins' points",
		description = "Draw the points other plugins put on the game's world map, such as group members, on this "
			+ "map as well. The World Map plugin's own points are not repeated, because this map has those as "
			+ "layers",
		section = generalSection,
		position = 6
	)
	default boolean showOtherPluginPoints()
	{
		return true;
	}

	@ConfigItem(
		keyName = "startAt",
		name = "Open at",
		description = "Where the map looks when it opens",
		section = generalSection,
		position = 1
	)
	default StartAt startAt()
	{
		return StartAt.PLAYER;
	}

	@ConfigItem(
		keyName = "followPlayer",
		name = "Follow me",
		description = "Keep the map centred on you when it opens at your position. Dragging the map stops it",
		section = generalSection,
		position = 2
	)
	default boolean followPlayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCoordinates",
		name = "Show coordinates",
		description = "Show the map name and the tile under the mouse in the corner of the map",
		section = generalSection,
		position = 3
	)
	default boolean showCoordinates()
	{
		return true;
	}

	@ConfigItem(
		keyName = "smoothMagnify",
		name = "Smooth close-up",
		description = "Blend pixels when zoomed in past the map's native detail. Off keeps the crisp pixel look",
		section = generalSection,
		position = 4
	)
	default boolean smoothMagnify()
	{
		return false;
	}

	@ConfigItem(
		keyName = "typeInChatArea",
		name = "Type in the chat area",
		description = "Type searches in the chat area, where every key reaches the search, including keys that other "
			+ "plugins take, such as WASD camera movement. Turn this off to type in the search bar on the map "
			+ "instead. A map in its own window always types straight into its own bar",
		section = generalSection,
		position = 10
	)
	default boolean typeInChatArea()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showWikiButtons",
		name = "Wiki buttons",
		description = "Put a Wiki button on the cards of things that have a page on the Old School RuneScape Wiki, and an Open wiki "
			+ "page entry in the right click menu. The page opens in your own web browser. This plugin never connects to anything",
		section = generalSection,
		position = 11
	)
	default boolean showWikiButtons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showResultsPanel",
		name = "Results panel",
		description = "After choosing a search result with several places, such as every bank, a monster or an item sold in many shops, "
			+ "list them in a panel at the left of the map: how many are at each place, how far it is, tabs, a Back link, and shops in "
			+ "the order of your choice",
		section = generalSection,
		position = 12
	)
	default boolean showResultsPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showDropTables",
		name = "Drop tables",
		description = "In the results panel, show what a monster drops (a Drops tab, by rarity or by value) and what drops an item (a "
			+ "Dropped by tab, with the nearest place of each monster). The tables are bundled from the Old School RuneScape Wiki. "
			+ "Values are RuneLite's own item prices, no request is made",
		section = generalSection,
		position = 13
	)
	default boolean showDropTables()
	{
		return true;
	}

	@ConfigItem(
		keyName = "zoomSpeed",
		name = "Zoom speed",
		description = "How far a single wheel notch zooms, as a percentage of the default",
		section = zoomSection,
		position = 0
	)
	@Range(min = 20, max = 400)
	default int zoomSpeed()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "zoomAcceleration",
		name = "Zoom acceleration",
		description = "How strongly continued scrolling speeds the zoom up. 0 turns acceleration off",
		section = zoomSection,
		position = 1
	)
	@Range(min = 0, max = 400)
	default int zoomAcceleration()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "zoomSmoothing",
		name = "Zoom smoothing",
		description = "How long the zoom takes to settle, in milliseconds. 0 jumps straight to the target",
		section = zoomSection,
		position = 2
	)
	@Range(min = 0, max = 500)
	default int zoomSmoothing()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "showNpcs",
		name = "Show nearby NPCs",
		description = "Mark the NPCs near you on the map, the same ones the minimap would show",
		section = npcSection,
		position = 0
	)
	default boolean showNpcs()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcMinZoom",
		name = "Show from zoom",
		description = "Only show NPCs once zoomed in to at least this many pixels per tile",
		section = npcSection,
		position = 1
	)
	@Range(min = 1, max = 32)
	default int npcMinZoom()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "npcRadius",
		name = "Radius",
		description = "How many tiles around you to show NPCs for. Capped at what the minimap shows",
		section = npcSection,
		position = 2
	)
	@Range(min = 1, max = 18)
	default int npcRadius()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "npcFilter",
		name = "Filter",
		description = "Comma separated words. Shows only names containing one of them. "
			+ "Put ! before a word to hide it. Empty shows all",
		section = npcSection,
		position = 3
	)
	default String npcFilter()
	{
		return "";
	}

	@ConfigItem(
		keyName = "npcNames",
		name = "Show names",
		description = "Write each NPC's name beside its marker when zoomed in far enough",
		section = npcSection,
		position = 4
	)
	default boolean npcNames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcNameZoom",
		name = "Names from zoom",
		description = "Pixels per tile at which names appear",
		section = npcSection,
		position = 5
	)
	@Range(min = 2, max = 32)
	default int npcNameZoom()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "trailFade",
		name = "Trail fades after",
		description = "How far back the trail of where you have walked is shown, in tens of tiles. 0 never fades it. "
			+ "1 fades it out over the last 10 tiles you walked, 2 over 20, and so on. Decimals work: 2.1 is 21 tiles",
		section = layerSection,
		position = 7
	)
	default double trailFade()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "hudEnabled",
		name = "Enable floating map",
		description = "Allow the floating map. It is drawn under the game's interface panels, never over them",
		section = hudSection,
		position = 0
	)
	default boolean hudEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hudToggleKey",
		name = "Show or hide",
		description = "Shows or hides the floating map",
		section = hudSection,
		position = 1
	)
	default Keybind hudToggleKey()
	{
		return new Keybind(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "hudClickThroughKey",
		name = "Click-through hotkey",
		description = "Switches between a map the mouse passes straight through and one you can drag and zoom",
		section = hudSection,
		position = 2
	)
	default Keybind hudClickThroughKey()
	{
		return new Keybind(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
	}

	@ConfigItem(
		keyName = "hudStartsClickThrough",
		name = "Click-through (mouse passes through)",
		description = "On: the mouse passes straight through the floating map, so you can click the game under it. "
			+ "Off: you can drag, zoom and search the floating map. Changing this takes effect at once, and the hotkey "
			+ "above switches it too",
		section = hudSection,
		position = 3
	)
	default boolean hudStartsClickThrough()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hudOpacity",
		name = "Opacity",
		description = "How solid the floating map is, as a percentage",
		section = hudSection,
		position = 4
	)
	@Range(min = 25, max = 100)
	default int hudOpacity()
	{
		return 65;
	}

	@ConfigItem(
		keyName = "hudSize",
		name = "Size",
		description = "How much of the game view the floating map covers",
		section = hudSection,
		position = 5
	)
	default HudSize hudSize()
	{
		return HudSize.MEDIUM;
	}

	@ConfigItem(
		keyName = "hudCorner",
		name = "Position",
		description = "Where in the game view the floating map sits",
		section = hudSection,
		position = 6
	)
	default HudCorner hudCorner()
	{
		return HudCorner.TOP_LEFT;
	}

	@ConfigItem(
		keyName = "hudZoom",
		name = "Opening zoom",
		description = "Pixels per tile the floating map opens at",
		section = hudSection,
		position = 7
	)
	@Range(min = 1, max = 32)
	default int hudZoom()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "hideFinishedQuests",
		name = "Hide quests you have finished",
		description = "Leave out the icon where a quest starts once you have finished that quest, and leave the quest out of search "
			+ "results. Only quests the plugin can tell you have finished are hidden",
		section = progressSection,
		position = 0
	)
	default boolean hideFinishedQuests()
	{
		return false;
	}

	@ConfigItem(
		keyName = "markUnusable",
		name = "Mark what you cannot use yet",
		description = "Draw an icon dimmed, with a small padlock, when you are missing a skill level, a quest, an achievement diary tier "
			+ "or quest points it needs, and say what is missing on its card and in search results. Only icons whose needs the plugin knows are marked, and levels are your real levels, so a boost "
			+ "can get you past one",
		section = progressSection,
		position = 1
	)
	default boolean markUnusable()
	{
		return false;
	}

	@ConfigItem(
		keyName = "onlyUsable",
		name = "Only show what you can use",
		description = "Leave out the icons you cannot use yet, the ones Mark what you cannot use yet would dim. This does not change "
			+ "which icon wins a crowded spot, so icons do not jump about when your levels change",
		section = progressSection,
		position = 2
	)
	default boolean onlyUsable()
	{
		return false;
	}

	@ConfigItem(
		keyName = "colourQuestsByProgress",
		name = "Colour quest icons by progress",
		description = "Put a small dot on the icon where a quest starts: red when you have not started it, yellow when you have, green "
			+ "when it is finished. Quests the plugin cannot tell about keep the plain icon",
		section = progressSection,
		position = 3
	)
	default boolean colourQuestsByProgress()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showHotspots",
		name = "Cards on map icons, doorways, transports and shops",
		description = "Show a card when the mouse is over one of the map's icons, such as a bank or an anvil, a dungeon entrance, "
			+ "a ship, glider, tree or ring, or a shop",
		section = layerSection,
		position = 0
	)
	default boolean showHotspots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showLabels",
		name = "Area names",
		description = "Write the game's own place names on the map: towns, regions, seas and other named areas",
		section = layerSection,
		position = 1
	)
	default boolean showLabels()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPins",
		name = "Pins and saved places",
		description = "Draw your saved places on the map. Turning this off hides them without deleting them, and routes that go through them still work",
		section = layerSection,
		position = 2
	)
	default boolean showPins()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showWilderness",
		name = "Wilderness levels",
		description = "Draw a line at each Wilderness level, with its number",
		section = layerSection,
		position = 4
	)
	default boolean showWilderness()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showGrid",
		name = "Region grid and ids",
		description = "Draw the 64 tile region grid, with each region's id when zoomed in",
		section = layerSection,
		position = 5
	)
	default boolean showGrid()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showTrail",
		name = "Where I have been",
		description = "Draw a trail of where you have walked this session. Kept in memory only",
		section = layerSection,
		position = 6
	)
	default boolean showTrail()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showRegions",
		name = "Region borders",
		description = "Tint and outline the regions of the world, such as Kandarin, Asgarnia and Forinthry, "
			+ "each in its own colour, with their names when zoomed out",
		section = layerSection,
		position = 3
	)
	default boolean showRegions()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hiddenMapSources",
		name = "Plugins hidden from the map",
		description = "The plugins whose points on the game's map are not shown here, separated by bars. "
			+ "The layers button on the map changes this for you",
		section = layerSection,
		position = 9,
		hidden = true
	)
	default String hiddenMapSources()
	{
		return "";
	}

	@ConfigItem(
		keyName = "hiddenGameIcons",
		name = "Game map icons that are hidden",
		description = "The kinds of the game's own map icons that are not drawn, separated by bars, such as Water source or Cooking range. "
			+ "The layers button on the map changes this for you",
		section = layerSection,
		position = 10,
		hidden = true
	)
	default String hiddenGameIcons()
	{
		return "";
	}

	@ConfigItem(
		keyName = "poiLayers",
		name = "Point of interest layers",
		description = "The kinds of point of interest that are shown, as a comma separated list. "
			+ "The layers button on the map changes this for you",
		section = layerSection,
		position = 8,
		hidden = true
	)
	default String poiLayers()
	{
		// The kinds the game draws icons for are on: they add their details to the icons' cards and draw almost nothing of their own.
		// Runecrafting altars and teleport destinations have no game icon, so their markers are left for the player to switch on.
		return "agility,minigame,tree,fishing,hunter,mining,farming,salvage,port";
	}

	@ConfigItem(
		keyName = "theme",
		name = "Theme",
		description = "The colours of everything this plugin draws, on the map and in the side panel. Bronze is the original warm dark look, "
			+ "Slate is cool dark grey with a blue accent, Parchment is light like the game's map scroll, and High contrast has strong "
			+ "borders and solid backgrounds behind all text",
		section = lookSection,
		position = 0
	)
	default com.worldmapplus.theme.ThemeName theme()
	{
		return com.worldmapplus.theme.ThemeName.BRONZE;
	}

	@ConfigItem(
		keyName = "animations",
		name = "Animations",
		description = "Smooth zoom and pan when the map jumps to a place, fades for cards, popups and the results panel, and a soft pulse "
			+ "on the search highlight. Reduced keeps only the short fades. Off draws every change at once. Nothing moves, and nothing "
			+ "is redrawn, while the map is still",
		section = lookSection,
		position = 1
	)
	default com.worldmapplus.theme.AnimationMode animations()
	{
		return com.worldmapplus.theme.AnimationMode.ON;
	}

	@ConfigItem(
		keyName = "sounds",
		name = "Sounds",
		description = "A short click from the game's own sound effects when the map opens or closes, a search result is chosen or a pin "
			+ "is placed, and a sound when a share code is complete. Uses the game's sound effect volume, so it is silent if that is muted",
		section = lookSection,
		position = 2
	)
	default boolean sounds()
	{
		return false;
	}
}
