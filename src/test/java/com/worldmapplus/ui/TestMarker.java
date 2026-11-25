package com.worldmapplus.ui;

import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.PartnerDouble;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.runelite.api.coords.WorldPoint;

/**
 * What the Helper plugin does for a marker another plugin sent, in the smallest form: a painter that draws a dot and a hit test that
 * answers within 12 pixels of it. For surface tests that want something of another plugin on the map without the Helper.
 */
final class TestMarker
{
	private TestMarker()
	{
	}

	static void show(ExternalOverlays external, WorldPoint at, String name, Color color)
	{
		PartnerDouble partner = new PartnerDouble(external, "testmarker");
		partner.register("painter", "dot", 1, (BiConsumer<java.awt.Graphics2D, Map<String, Object>>) (g, frame) ->
		{
			AffineTransform transform = (AffineTransform) frame.get("transform");
			double x = transform.getScaleX() * (at.getX() + 0.5) + transform.getTranslateX();
			double y = transform.getScaleY() * (at.getY() + 0.5) + transform.getTranslateY();
			g.setColor(color);
			g.fill(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
		});
		partner.register("hit", "dot", 1, (Function<Map<String, Object>, Object>) frame ->
		{
			AffineTransform transform = (AffineTransform) frame.get("transform");
			double x = transform.getScaleX() * (at.getX() + 0.5) + transform.getTranslateX();
			double y = transform.getScaleY() * (at.getY() + 0.5) + transform.getTranslateY();
			double px = ((Number) frame.get("x")).doubleValue();
			double py = ((Number) frame.get("y")).doubleValue();
			if (Math.hypot(px - x, py - y) > 12)
			{
				return null;
			}
			Map<String, Object> thing = new HashMap<>();
			thing.put("name", name);
			thing.put("kind", "marker");
			thing.put("id", "t1");
			thing.put("point", at);
			return thing;
		}, "click", (Consumer<Map<String, Object>>) thing -> { });
	}
}
