package com.worldmapplus.apiexample;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Talks to the World Map Plus plugin through RuneLite plugin messages, Plugin API version 1 (see its docs/COMPAT.md).
 * <p>
 * Copy this one file into your plugin and change the package line. It needs nothing from World Map Plus: plugins on the Hub cannot
 * depend on each other, and these are plain messages. If World Map Plus is not installed nobody receives them and nothing happens.
 * Post from the client thread. Answers arrive as {@link PluginMessage} events in the namespace {@link #NAMESPACE}, on a later client
 * tick; read them with the methods at the end.
 */
public final class WorldMapPlusApi
{
	public static final String NAMESPACE = "worldmapplus";

	/** The newest API version this copy knows. A {@code hello_reply} with a higher number still supports everything here. */
	public static final int KNOWN_VERSION = 1;

	private WorldMapPlusApi()
	{
	}

	/** Asks what World Map Plus supports. The answer is a {@code hello_reply} with this id; no answer within 2 seconds means absent. */
	public static void hello(EventBus bus, String id)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("id", id);
		send(bus, "hello", data);
	}

	/**
	 * The data of a marker, ready for the optional keys ("name", "tooltip", "color", "image", "clickable", "hint", "menu", "layer").
	 * Source and id: letters, digits and _ - . : only, at most 64, if clicks or menu entries are to be reported back.
	 */
	public static Map<String, Object> marker(String source, String id, WorldPoint point)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("source", source);
		data.put("id", id);
		data.put("point", point);
		return data;
	}

	/** Shows or moves a marker with a name, a tooltip and a colour. */
	public static void showMarker(EventBus bus, String source, String id, WorldPoint point, String name, String tooltip, Color color)
	{
		Map<String, Object> data = marker(source, id, point);
		data.put("name", name);
		data.put("tooltip", tooltip);
		data.put("color", color);
		send(bus, "marker", data);
	}

	/** Removes one marker of a source, or every marker of it when the id is null. */
	public static void clearMarkers(EventBus bus, String source, String id)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("source", source);
		if (id != null)
		{
			data.put("id", id);
		}
		send(bus, "marker_clear", data);
	}

	/** Draws or moves a line through the points, at most 5000 of them. */
	public static void showLine(EventBus bus, String source, String id, List<WorldPoint> points, Color color, String label)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("source", source);
		data.put("id", id);
		data.put("points", points);
		data.put("color", color);
		data.put("label", label);
		send(bus, "line", data);
	}

	/** Removes one line of a source, or every line of it when the id is null. */
	public static void clearLines(EventBus bus, String source, String id)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("source", source);
		if (id != null)
		{
			data.put("id", id);
		}
		send(bus, "line_clear", data);
	}

	/** Brings the map to a place. Zoom is pixels per tile, 0 for the map's own choice. */
	public static void focus(EventBus bus, WorldPoint point, double zoom, String name)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("point", point);
		data.put("zoom", zoom);
		data.put("name", name);
		send(bus, "focus", data);
	}

	/** Posts any message of the API with data made by the methods above or by hand. */
	public static void send(EventBus bus, String name, Map<String, Object> data)
	{
		bus.post(new PluginMessage(NAMESPACE, name, data));
	}

	// Reading answers. Every value is checked, so a reply of an unexpected shape reads as nothing.

	/** True when the event is an answer from World Map Plus with this name. */
	public static boolean isReply(PluginMessage message, String name)
	{
		return message != null && NAMESPACE.equals(message.getNamespace()) && name.equals(message.getName())
			&& message.getData() != null;
	}

	/** The API version a {@code hello_reply} or {@code ready} names, or -1 when the event is neither. */
	public static int apiVersion(PluginMessage message)
	{
		if (!isReply(message, "hello_reply") && !isReply(message, "ready"))
		{
			return -1;
		}
		Object version = message.getData().get("apiVersion");
		return version instanceof Integer ? (Integer) version : -1;
	}

	/** True when a {@code hello_reply} or {@code ready} lists a message name, so a feature can be tested for without a version. */
	public static boolean supports(PluginMessage message, String messageName)
	{
		if (apiVersion(message) < 0)
		{
			return false;
		}
		Object messages = message.getData().get("messages");
		return messages instanceof List && ((List<?>) messages).contains(messageName);
	}

	/** A text value of an answer, or an empty text. */
	public static String text(PluginMessage message, String key)
	{
		Object value = message == null || message.getData() == null ? null : message.getData().get(key);
		return value instanceof String ? (String) value : "";
	}

	/** True for a {@code marker_clicked} or {@code marker_menu} about a marker of this source. The id is {@code text(m, "id")}. */
	public static boolean isAboutMine(PluginMessage message, String source)
	{
		return (isReply(message, "marker_clicked") || isReply(message, "marker_menu")) && source.equals(text(message, "source"));
	}

	/** The menu entry chosen in a {@code marker_menu}, or an empty text. */
	public static String menuOption(PluginMessage message)
	{
		return isReply(message, "marker_menu") ? text(message, "option") : "";
	}

	/** A list of menu entries for {@link #marker}, as the "menu" key wants it. */
	public static List<String> menu(String... entries)
	{
		return Collections.unmodifiableList(java.util.Arrays.asList(entries));
	}
}
