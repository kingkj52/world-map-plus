package com.worldmapplus.layers;

import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.game.PlayerPosition;
import com.worldmapplus.game.Positions;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.view.ViewTransform;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/**
 * Marks the NPCs around the player, the way the minimap's dots do.
 * <p>
 * Held to the minimap's own rules so it shows nothing the game would not already show you:
 * <ul>
 * <li>only NPCs the game itself flags as visible on the minimap;</li>
 * <li>only within {@link #MAX_RADIUS} tiles of the player, about what the minimap reaches;</li>
 * <li>only on the player's own floor.</li>
 * </ul>
 * It draws a dot and, when zoomed in, a name. It shows no combat level, no attack information, no
 * aggression and no timers, and it never draws other players.
 */
@Singleton
public class NpcLayer
{
	/** The most tiles from the player that an NPC may be marked, whatever the setting says. */
	public static final int MAX_RADIUS = 18;

	/** A safety cap on how many markers one frame draws. */
	private static final int MAX_MARKERS = 250;

	private static final Color DOT = new Color(255, 232, 64);
	private static final Color DOT_OUTLINE = new Color(40, 34, 10, 220);
	private static final Color LABEL = new Color(255, 245, 200);
	private static final Color LABEL_SHADOW = new Color(0, 0, 0, 200);
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

	@Inject
	private Client client;

	@Inject
	private WorldMapPlusConfig config;

	private String filterText = "";
	private NpcFilter filter = NpcFilter.parse("");

	/** True when this view should show NPC markers at all. */
	public boolean isActive(ViewTransform view)
	{
		return config.showNpcs() && view.getPixelsPerTile() >= config.npcMinZoom();
	}

	/** Clamps a requested radius to what the minimap would show. */
	static int radius(int requested)
	{
		return Math.max(1, Math.min(MAX_RADIUS, requested));
	}

	/** True when a tile is within the radius of the player, on the same floor. */
	static boolean near(WorldPoint tile, WorldPoint player, int radius)
	{
		return tile.getPlane() == player.getPlane()
			&& Math.max(Math.abs(tile.getX() - player.getX()), Math.abs(tile.getY() - player.getY())) <= radius;
	}

	public void paint(Graphics2D g, ViewTransform view, @Nullable MapInfo viewed, @Nullable PlayerPosition player)
	{
		if (viewed == null || player == null || !isActive(view)
			|| !viewed.contains(player.getTile().getX(), player.getTile().getY()))
		{
			return;
		}

		String text = config.npcFilter();
		if (!text.equals(filterText))
		{
			filterText = text;
			filter = NpcFilter.parse(text);
		}

		int radius = radius(config.npcRadius());
		boolean names = config.npcNames() && view.getPixelsPerTile() >= config.npcNameZoom();
		double dot = Math.max(3.0, Math.min(9.0, view.getPixelsPerTile() * 0.32));

		g.setFont(FONT);
		FontMetrics metrics = g.getFontMetrics();
		int drawn = 0;
		WorldView world = client.getTopLevelWorldView();
		if (world == null)
		{
			return;
		}
		for (NPC npc : world.npcs())
		{
			if (drawn >= MAX_MARKERS)
			{
				break;
			}
			NPCComposition composition = npc.getTransformedComposition();
			if (composition == null || !composition.isMinimapVisible())
			{
				continue;
			}
			// The position is worked out the same way as the player's, so inside an instance both are
			// in the coordinates of the area the instance was copied from and compare correctly.
			PlayerPosition at = Positions.of(client, npc);
			if (at == null || !near(at.getTile(), player.getTile(), radius))
			{
				continue;
			}
			String name = composition.getName();
			name = name == null ? "" : Text.removeTags(name);
			if (name.isEmpty() || "null".equals(name) || !filter.matches(name))
			{
				continue;
			}

			double sx = view.screenX(at.getX());
			double sy = view.screenY(at.getY());
			Ellipse2D shape = new Ellipse2D.Double(sx - dot / 2, sy - dot / 2, dot, dot);
			g.setColor(DOT);
			g.fill(shape);
			g.setColor(DOT_OUTLINE);
			g.draw(shape);

			if (names)
			{
				int tx = (int) (sx + dot / 2 + 3);
				int ty = (int) (sy + metrics.getAscent() / 2.0 - 1);
				g.setColor(LABEL_SHADOW);
				g.drawString(name, tx + 1, ty + 1);
				g.setColor(LABEL);
				g.drawString(name, tx, ty);
			}
			drawn++;
		}
	}
}
