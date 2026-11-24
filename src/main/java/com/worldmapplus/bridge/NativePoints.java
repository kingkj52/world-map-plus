package com.worldmapplus.bridge;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

/**
 * The points other plugins have put on the game's own world map, so this map can show them too.
 * <p>
 * A plugin that wants something on the world map adds a {@link WorldMapPoint} to RuneLite's manager,
 * and RuneLite draws it on the game's map. This plugin draws its own map over that one, so those
 * points would be hidden. The manager does not hand out its list, but it does let a caller look at
 * each point while asking which ones to remove, and answering "none" removes nothing. That is the
 * ordinary public call used here, once every tenth of a second, to read the points and draw them where
 * they belong on this map. Nothing is changed in the manager and nothing is sent anywhere.
 * <p>
 * Other plugins can register any number of points, so the work per reading is bounded: once {@link #MAX_POINTS} markers
 * are made the rest are not looked at, and the copy of a point's picture (up to 16 KB) is made once per picture and then
 * reused for {@link #ICON_REUSE_MILLIS}.
 * <p>
 * The World Map plugin's own points, the teleports, agility shortcuts and so on, are left out. This map
 * already draws those as its own layers, and drawing both would show everything twice.
 * <p>
 * Client thread only.
 */
@Singleton
public class NativePoints
{
	/** Most points shown, so one plugin cannot flood the map. */
	static final int MAX_POINTS = 400;

	/** How long a reading is reused for. */
	static final long REFRESH_MILLIS = 100;

	/**
	 * How long the copy of a picture is reused before the picture is copied again, so a plugin that changes the pixels of
	 * one image in place is still followed, a little late.
	 */
	static final long ICON_REUSE_MILLIS = 2000;

	/** Most pictures whose copies are kept. The list is emptied when it would pass this. */
	static final int MAX_ICONS = 256;

	/** The package of RuneLite's own World Map plugin, whose points are not repeated here. */
	public static final String CORE_PACKAGE = "net.runelite.client.plugins.worldmap.";

	private static final Color DEFAULT = new Color(255, 0, 255);

	/** Where the points are read from. A seam for tests. */
	public interface Source
	{
		/** Calls the sink with every point. */
		void forEach(Consumer<WorldMapPoint> sink);
	}

	private final Source source;
	private final String corePackage;
	private List<ExternalMarker> cache = Collections.emptyList();
	private long cachedAt = Long.MIN_VALUE;

	/** The copies made of the pictures points use, by the picture itself (never its pixels). A picture that is gone drops out. */
	private final Map<BufferedImage, Copy> icons = new WeakHashMap<>();
	private int copies;

	/** A copy of a picture, and when it was made. A null copy means the picture was not usable. */
	private static final class Copy
	{
		final BufferedImage image;
		final long madeAt;

		Copy(BufferedImage image, long madeAt)
		{
			this.image = image;
			this.madeAt = madeAt;
		}
	}

	@Inject
	public NativePoints(WorldMapPointManager manager)
	{
		this(sink -> manager.removeIf(point ->
		{
			sink.accept(point);
			return false;
		}));
	}

	public NativePoints(Source source)
	{
		this(source, CORE_PACKAGE);
	}

	/** For tests, which cannot put a class in RuneLite's own package. */
	public NativePoints(Source source, String corePackage)
	{
		this.source = source;
		this.corePackage = corePackage;
	}

	/** The points as markers, read afresh at most every tenth of a second. */
	public List<ExternalMarker> markers(long nowMillis)
	{
		if (cachedAt != Long.MIN_VALUE && nowMillis >= cachedAt && nowMillis - cachedAt < REFRESH_MILLIS)
		{
			return cache;
		}
		cachedAt = nowMillis;
		List<ExternalMarker> found = new ArrayList<>();
		try
		{
			source.forEach(point ->
			{
				// Past the cap nothing more is converted, however many points there are.
				if (found.size() >= MAX_POINTS)
				{
					return;
				}
				ExternalMarker marker = convert(point, found.size(), corePackage, image -> iconFor(image, nowMillis));
				if (marker != null)
				{
					found.add(marker);
				}
			});
		}
		catch (RuntimeException e)
		{
			// The manager's list is changed by other threads and can be caught mid change. The next
			// reading will be fine, so keep what was shown.
			return cache;
		}
		cache = Collections.unmodifiableList(found);
		return cache;
	}

	/** Longest tooltip taken from another plugin's point, in characters. */
	private static final int MAX_TOOLTIP = 600;

	private static String capped(String text, int max)
	{
		if (text == null)
		{
			return "";
		}
		return text.length() > max ? text.substring(0, max) : text;
	}

	/** True when the point belongs to RuneLite's World Map plugin, which this map draws for itself. */
	static boolean isCore(WorldMapPoint point, String corePackage)
	{
		return point.getClass().getName().startsWith(corePackage);
	}

	/** The copy of a point's picture, made again only when the last one is older than {@link #ICON_REUSE_MILLIS}. */
	@Nullable
	private BufferedImage iconFor(@Nullable Object image, long now)
	{
		if (!(image instanceof BufferedImage))
		{
			return null;
		}
		BufferedImage picture = (BufferedImage) image;
		Copy kept = icons.get(picture);
		if (kept != null && now >= kept.madeAt && now - kept.madeAt < ICON_REUSE_MILLIS)
		{
			return kept.image;
		}
		if (icons.size() >= MAX_ICONS)
		{
			icons.clear();
		}
		BufferedImage copy = ExternalOverlays.icon(picture);
		copies++;
		icons.put(picture, new Copy(copy, now));
		return copy;
	}

	/** How many pictures have been copied so far, for tests. */
	int copiesMade()
	{
		return copies;
	}

	@Nullable
	static ExternalMarker convert(@Nullable WorldMapPoint point, int index, String corePackage)
	{
		return convert(point, index, corePackage, ExternalOverlays::icon);
	}

	@Nullable
	private static ExternalMarker convert(@Nullable WorldMapPoint point, int index, String corePackage,
		Function<Object, BufferedImage> icon)
	{
		if (point == null || point.getWorldPoint() == null || isCore(point, corePackage))
		{
			return null;
		}
		WorldPoint at = point.getWorldPoint();
		Point anchor = point.getImagePoint();
		int[] pin = anchor == null ? null : new int[] {anchor.getX(), anchor.getY()};
		WorldPoint jump = point.isJumpOnClick()
			? (point.getTarget() != null ? point.getTarget() : at) : null;
		String name = capped(point.getName(), ExternalOverlays.MAX_TEXT);
		String tooltip = capped(point.getTooltip(), MAX_TOOLTIP);
		return new ExternalMarker("worldmap", "point" + index, at.getX() + 0.5, at.getY() + 0.5, name, tooltip,
			DEFAULT, icon.apply(point.getImage()), pin, point.isSnapToEdge(), jump, point.getClass().getName());
	}
}
