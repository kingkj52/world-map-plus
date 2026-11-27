package com.worldmapplus.ui;

import static org.junit.Assert.assertNotNull;

import com.google.gson.Gson;
import com.worldmapplus.drops.DropTables;
import com.worldmapplus.drops.PriceSource;
import com.worldmapplus.search.BundledSearch;
import com.worldmapplus.search.KindIndex;
import com.worldmapplus.search.SearchEntry;
import com.worldmapplus.search.SearchKind;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;

/**
 * Round 71: the results panel drawn on its own, in every theme, for every kind of view (places of a kind, an item with its shops,
 * ground and Dropped by tabs, a shop's stock reached from an item with Back, a monster's drops with prices, scrolled), with the
 * pointer on rows, tabs, orders, Back, the close button and the scroll bar, and part way through its fade in and a row's warm up.
 * Only runs when the WORLDMAPPLUS_SHOTS environment variable names a folder; the pictures of a theme go in
 * {@code results/<theme>}, so a change of the panel's code can be compared pixel for pixel with an earlier run.
 */
public class ResultsPanelShotTest
{
	private static final Rectangle MAP = new Rectangle(0, 0, 900, 600);

	/** A made up price for every item, so drop rows show their values. */
	private static final PriceSource PRICES = new PriceSource()
	{
		@Override
		public long price(int itemId)
		{
			return itemId % 7 == 0 ? -1 : itemId * 13L % 100_000 + 1;
		}

		@Override
		public void request(int[] itemIds)
		{
		}

		@Override
		public int version()
		{
			return 1;
		}
	};

	private BufferedImage canvas;
	private long now;

	private static SearchEntry find(List<SearchEntry> all, SearchKind kind, String name)
	{
		for (SearchEntry e : all)
		{
			if (e.getKind() == kind && e.getName().equals(name) && e.getKindId() == null)
			{
				return e;
			}
		}
		throw new AssertionError("no " + kind + " " + name);
	}

	private static SearchEntry kind(List<SearchEntry> all, String name)
	{
		for (SearchEntry e : all)
		{
			if (e.getKindId() != null && e.getGroup() < 0 && e.getName().equalsIgnoreCase(name))
			{
				return e;
			}
		}
		throw new AssertionError("no kind " + name);
	}

	private static void measure(ResultView view)
	{
		// The player stands in Lumbridge, on the surface.
		view.measureFrom(true, 0, 3222.5, 3218.5, id -> "In map " + id);
	}

	private void frame(ResultsPanel panel, Point mouse, File folder, String name) throws Exception
	{
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		// A patterned ground, so the panel's see-through fills show.
		for (int y = 0; y < MAP.height; y += 20)
		{
			for (int x = 0; x < MAP.width; x += 20)
			{
				g.setColor(((x + y) / 20) % 2 == 0 ? new Color(70, 96, 60) : new Color(200, 190, 160));
				g.fillRect(x, y, 20, 20);
			}
		}
		panel.layout(MAP, 50, 820);
		panel.paint(g, mouse, now);
		g.dispose();
		if (name != null)
		{
			ImageIO.write(canvas, "png", new File(folder, name + ".png"));
		}
	}

	/** Draws frames until every fade is done, then the picture. */
	private void settled(ResultsPanel panel, Point mouse, File folder, String name) throws Exception
	{
		for (int i = 0; i < 40; i++)
		{
			now += 16;
			frame(panel, mouse, folder, null);
		}
		frame(panel, mouse, folder, name);
	}

	private static Point centre(Rectangle r)
	{
		return new Point(r.x + r.width / 2, r.y + r.height / 2);
	}

	void scenes(File folder) throws Exception
	{
		folder.mkdirs();
		canvas = new BufferedImage(MAP.width, MAP.height, BufferedImage.TYPE_INT_RGB);
		now = 5_000_000;
		List<SearchEntry> all = BundledSearch.entries();
		KindIndex kinds = BundledSearch.kinds();
		DropTables drops = DropTables.load(new Gson());
		ResultsPanel panel = new ResultsPanel();

		// Every bank: the fade in part way, then settled, then the pointer on a row part way and settled.
		ResultView banks = ResultViews.forEntry(kind(all, "banks"), kinds, all, drops);
		assertNotNull(banks);
		measure(banks);
		panel.replace(banks);
		frame(panel, null, folder, "banks-fade-0");
		now += 40;
		frame(panel, null, folder, "banks-fade-40");
		now += 40;
		frame(panel, null, folder, "banks-fade-80");
		settled(panel, null, folder, "banks");
		Rectangle row = panel.rowRect(2);
		assertNotNull(row);
		now += 16;
		frame(panel, centre(row), folder, null);
		now += 50;
		frame(panel, centre(row), folder, "banks-row-warming");
		settled(panel, centre(row), folder, "banks-row");
		settled(panel, centre(panel.closeRect()), folder, "banks-close");
		settled(panel, centre(panel.sortRect(1)), folder, "banks-sort-hover");
		ResultView.sort(banks.current(), ResultView.Sort.NAME);
		settled(panel, null, folder, "banks-by-name");
		panel.scroll(4);
		settled(panel, null, folder, "banks-scrolled");
		Rectangle bar = panel.getBounds();
		panel.dragBarTo(bar.y + bar.height - 30);
		settled(panel, null, folder, "banks-dragged");

		// An item with shops, ground and Dropped by tabs, before and after its prices.
		ResultView feather = ResultViews.forEntry(find(all, SearchKind.ITEM, "Feather"), kinds, all, drops);
		assertNotNull(feather);
		measure(feather);
		panel.replace(feather);
		settled(panel, null, folder, "feather-shops");
		settled(panel, centre(panel.tabRect(1)), folder, "feather-tab-hover");
		feather.selectTab(1);
		settled(panel, null, folder, "feather-ground");
		feather.selectTab(2);
		settled(panel, null, folder, "feather-dropped-by");
		feather.refreshPrices(PRICES);
		measure(feather);
		settled(panel, null, folder, "feather-dropped-by-priced");
		ResultView.sort(feather.current(), ResultView.Sort.NEAREST);
		settled(panel, null, folder, "feather-dropped-by-nearest");

		// A shop reached from a row of the item's shops: Back shows.
		feather.selectTab(0);
		ResultView.Row first = feather.current().rows.get(0);
		ResultView shop = ResultViews.forEntry(first.follow, kinds, all, drops);
		assertNotNull(shop);
		measure(shop);
		panel.open(shop);
		settled(panel, null, folder, "shop");
		settled(panel, centre(panel.backRect()), folder, "shop-back-hover");
		ResultView.sort(shop.current(), ResultView.Sort.PRICE);
		settled(panel, null, folder, "shop-by-price");
		panel.back();
		settled(panel, null, folder, "shop-back");

		// A monster with its drops and prices, scrolled, with the pointer on the scroll bar.
		ResultView goblin = ResultViews.forEntry(find(all, SearchKind.MONSTER, "Goblin"), kinds, all, drops);
		assertNotNull(goblin);
		measure(goblin);
		panel.replace(goblin);
		settled(panel, null, folder, "goblin");
		goblin.selectTab(goblin.tabs.size() - 1);
		settled(panel, null, folder, "goblin-drops");
		goblin.refreshPrices(PRICES);
		measure(goblin);
		ResultView.sort(goblin.current(), ResultView.Sort.VALUE);
		settled(panel, null, folder, "goblin-drops-value");
		panel.scroll(3);
		Rectangle bounds = panel.getBounds();
		settled(panel, new Point(bounds.x + bounds.width - 7, bounds.y + bounds.height - 40), folder, "goblin-scrolled");

		// An item that is only sold, and a monster found in many places.
		ResultView lobster = ResultViews.forEntry(find(all, SearchKind.ITEM, "Lobster"), kinds, all, drops);
		assertNotNull(lobster);
		measure(lobster);
		panel.replace(lobster);
		settled(panel, null, folder, "lobster");
		ResultView cow = ResultViews.forEntry(find(all, SearchKind.MONSTER, "Cow"), kinds, all, drops);
		assertNotNull(cow);
		measure(cow);
		panel.replace(cow);
		settled(panel, null, folder, "cow");

		// Closed and opened again: the fade in starts again.
		panel.close();
		frame(panel, null, folder, "closed");
		panel.replace(cow);
		now += 16;
		frame(panel, null, folder, "cow-fade-0");
		now += 60;
		frame(panel, null, folder, "cow-fade-60");
	}

	@Test
	public void draw() throws Exception
	{
		String folder = System.getenv("WORLDMAPPLUS_SHOTS");
		Assume.assumeTrue(folder != null && !folder.isEmpty());
		try
		{
			for (com.worldmapplus.theme.Palette palette : com.worldmapplus.theme.Palette.all())
			{
				com.worldmapplus.theme.Palette.use(palette);
				scenes(new File(folder, "results/" + palette.name().toLowerCase().replace(' ', '-')));
			}
			// With animations off nothing fades.
			com.worldmapplus.theme.Palette.use(com.worldmapplus.theme.Palette.all()[0]);
			com.worldmapplus.theme.Motion.use(com.worldmapplus.theme.AnimationMode.OFF);
			scenes(new File(folder, "results/bronze-still"));
		}
		finally
		{
			com.worldmapplus.theme.Motion.use(com.worldmapplus.theme.AnimationMode.ON);
			com.worldmapplus.theme.Palette.use(com.worldmapplus.theme.Palette.all()[0]);
		}
	}
}
