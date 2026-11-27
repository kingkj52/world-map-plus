package com.worldmapplus.ui;

import static com.worldmapplus.theme.Palette.colour;

import com.worldmapplus.layers.*;
import com.worldmapplus.layers.HoverCard.Row;
import com.worldmapplus.map.*;
import com.worldmapplus.render.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.view.*;
import com.worldmapplus.wiki.*;
import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import javax.annotation.*;

/**
 * Draws a {@link HoverCard} in a box next to the mouse.
 * <p>
 * The box is placed to the lower right of the pointer and flips to the other side when it would
 * run past the edge of the map. If the card has a map preview, the preview is drawn with the same
 * renderer the main view uses, fitted to a small frame inside the card.
 */
public final class HoverCardPainter
{
	static final int PREVIEW_WIDTH = 240;
	static final int PREVIEW_HEIGHT = 150;
	static final int PADDING = 8;
	static final int OFFSET = 16;
	static final int MAX_TEXT_WIDTH = 340;
	private static final int ROW_GAP = 16;

	/** The small Wiki button in the corner of a card about something that has a wiki page. */
	static final int BUTTON_WIDTH = 38;
	static final int BUTTON_HEIGHT = 16;
	private static final int BUTTON_GAP = 10;
	static final String BUTTON_TEXT = "Wiki";
	/** The button of a card with no page of its own, which opens the wiki's search page for its name. */
	static final String SEARCH_BUTTON_TEXT = "Search wiki";
	static final int SEARCH_BUTTON_WIDTH = 72;

	private final MapCatalog catalog;
	private final MapRenderer renderer;

	/** Where the card drawn last was, and where its Wiki button was (null when it had none). */
	private final Rectangle lastBounds = new Rectangle();
	private final Rectangle lastButton = new Rectangle();
	private boolean hasLastButton;

	public HoverCardPainter(MapCatalog catalog, MapRenderer renderer)
	{
		this.catalog = catalog;
		this.renderer = renderer;
	}

	/** True when a card gets a Wiki button: it names a page and the name makes a clean address, or a name to search for. */
	static boolean hasButton(HoverCard card)
	{
		return hasPage(card) || isSearch(card);
	}

	/** True when the card's button opens a page of its own. */
	static boolean hasPage(HoverCard card)
	{
		return card.getWiki() != null && WikiLinks.url(card.getWiki()) != null;
	}

	/** True when the card's button opens the wiki's search page for its name, because it has no page that is certain to exist. */
	static boolean isSearch(HoverCard card)
	{
		return !hasPage(card) && card.getWikiSearch() != null && WikiLinks.searchUrl(card.getWikiSearch()) != null;
	}

	private static int buttonWidth(HoverCard card)
	{
		return isSearch(card) ? SEARCH_BUTTON_WIDTH : BUTTON_WIDTH;
	}

	/** The box the card drawn last was in. */
	Rectangle lastBounds()
	{
		return new Rectangle(lastBounds);
	}

	/** The Wiki button of the card drawn last, or null when it had none. */
	@Nullable
	Rectangle lastButton()
	{
		return hasLastButton ? new Rectangle(lastButton) : null;
	}

	/** Rows per column in an expanded card before it starts another column. */
	static final int MAX_COLUMN_ROWS = 22;
	private static final int COLUMN_GAP = 22;

	/** Splits rows into columns of at most {@link #MAX_COLUMN_ROWS}, as evenly as possible. */
	static List<List<Row>> columns(List<Row> rows)
	{
		List<List<Row>> columns = new ArrayList<>();
		if (rows.isEmpty())
		{
			return columns;
		}
		int count = (rows.size() + MAX_COLUMN_ROWS - 1) / MAX_COLUMN_ROWS;
		int per = (rows.size() + count - 1) / count;
		int start = 0;
		while (start < rows.size())
		{
			int end = Math.min(rows.size(), start + per);
			// An indented row belongs to the one above it, so a column never starts on one.
			while (end < rows.size() && rows.get(end).getLabel().startsWith(" ") && end > start + 1)
			{
				end--;
			}
			columns.add(rows.subList(start, end));
			start = end;
		}
		return columns;
	}

	private static int columnWidth(FontMetrics body, List<Row> column)
	{
		int width = 0;
		for (Row row : column)
		{
			width = Math.max(width, body.stringWidth(row.getLabel()) + ROW_GAP + body.stringWidth(row.getValue()));
		}
		return width;
	}

	/** The size the card needs, in pixels. */
	static Dimension measure(Graphics2D g, HoverCard card, boolean expanded)
	{
		return measure(g, card, card.visibleRows(expanded), card.hasPreview());
	}

	/**
	 * The rows to draw so that the card is no taller than {@code maxHeight}: all of them when they fit, otherwise as many as
	 * do with a last line saying how many were left out. The picture is the first thing given up.
	 */
	static List<Row> fitRows(Graphics2D g, HoverCard card, boolean expanded, boolean preview, int maxHeight)
	{
		List<Row> rows = card.visibleRows(expanded);
		int keep = rows.size();
		while (keep > 1 && measure(g, card, rows(rows, keep), preview).height > maxHeight)
		{
			keep--;
		}
		return rows(rows, keep);
	}

	private static List<Row> rows(List<Row> all, int keep)
	{
		if (keep >= all.size())
		{
			return all;
		}
		List<Row> shown = new ArrayList<>(all.subList(0, Math.max(0, keep - 1)));
		shown.add(new Row("and " + (all.size() - shown.size()) + " more", ""));
		return shown;
	}

	private static Dimension measure(Graphics2D g, HoverCard card, List<Row> rows, boolean preview)
	{
		g.setFont(Palette.TITLE);
		int width = g.getFontMetrics().stringWidth(card.getTitle()) + (hasButton(card) ? buttonWidth(card) + BUTTON_GAP : 0);
		int height = g.getFontMetrics().getHeight();

		g.setFont(Palette.BODY);
		FontMetrics body = g.getFontMetrics();
		if (card.getSubtitle() != null)
		{
			width = Math.max(width, body.stringWidth(card.getSubtitle()));
			height += body.getHeight();
		}

		List<List<Row>> columns = columns(rows);
		int rowsWidth = 0;
		int tallest = 0;
		for (List<Row> column : columns)
		{
			rowsWidth += columnWidth(body, column) + (rowsWidth > 0 ? COLUMN_GAP : 0);
			tallest = Math.max(tallest, column.size());
		}
		width = Math.max(width, rowsWidth);
		height += tallest * body.getHeight();

		if (preview)
		{
			width = Math.max(width, PREVIEW_WIDTH);
			height += PREVIEW_HEIGHT + 6;
		}
		if (card.getHint() != null)
		{
			g.setFont(Palette.SMALL_ITALIC);
			width = Math.max(width, g.getFontMetrics().stringWidth(card.getHint()));
			height += g.getFontMetrics().getHeight() + 2;
		}
		int limit = columns.size() > 1 ? Integer.MAX_VALUE : MAX_TEXT_WIDTH;
		return new Dimension(Math.min(width, limit) + PADDING * 2, height + PADDING * 2);
	}

	/** Where the card's top left goes so it stays inside {@code limit}, beside the pointer. */
	static Point place(Dimension size, int mouseX, int mouseY, Rectangle limit)
	{
		return new Point(placeAxis(size.width, mouseX, limit.x, limit.width),
			placeAxis(size.height, mouseY, limit.y, limit.height));
	}

	/** One coordinate of the card's corner: beside the pointer, flipped to its other side at the edge, kept inside the limit. */
	private static int placeAxis(int extent, int mouse, int limitStart, int limitExtent)
	{
		int at = mouse + OFFSET;
		if (at + extent > limitStart + limitExtent)
		{
			at = mouse - OFFSET - extent;
		}
		return Math.max(limitStart, Math.min(at, limitStart + limitExtent - extent));
	}

	/**
	 * A card is never larger than the map it is over: the picture goes first, then the rows that do not fit, and a text too wide
	 * for the room is cut short with an ellipsis. The full text is what holding Shift shows.
	 */
	private static Layout fit(Graphics2D g, HoverCard card, boolean expanded, Rectangle limit)
	{
		Layout fitted = new Layout();
		fitted.preview = card.hasPreview() && measure(g, card, card.visibleRows(expanded), true).height <= limit.height;
		fitted.rows = fitRows(g, card, expanded, fitted.preview, limit.height);
		Dimension size = measure(g, card, fitted.rows, fitted.preview);
		fitted.size = new Dimension(Math.min(size.width, limit.width), Math.min(size.height, limit.height));
		return fitted;
	}

	/** Where the card is drawn, for a card over a map of the given size. For tests. */
	static Rectangle boundsOf(Graphics2D g, HoverCard card, boolean expanded, int mouseX, int mouseY, Rectangle limit)
	{
		Dimension size = fit(g, card, expanded, limit).size;
		Point at = place(size, mouseX, mouseY, limit);
		return new Rectangle(at, size);
	}

	public void paint(Graphics2D g, HoverCard card, int mouseX, int mouseY, Rectangle limit, boolean expanded)
	{
		paint(g, card, mouseX, mouseY, limit, expanded, null);
	}

	/**
	 * As above, and says where the pointer is so the Wiki button lights up under it. The card's box and the button's are kept
	 * ({@link #lastBounds()}, {@link #lastButton()}) for whoever wants to take a click on them.
	 */
	public void paint(Graphics2D g, HoverCard card, int mouseX, int mouseY, Rectangle limit, boolean expanded,
		@Nullable Point pointer)
	{
		Layout layout = layoutFor(g, card, expanded, limit);
		boolean preview = layout.preview;
		Dimension size = layout.size;
		int boxX = placeAxis(size.width, mouseX, limit.x, limit.width);
		int boxY = placeAxis(size.height, mouseY, limit.y, limit.height);
		lastBounds.setBounds(boxX, boxY, size.width, size.height);
		boolean button = hasButton(card);
		if (button)
		{
			lastButton.setBounds(boxX + size.width - PADDING - buttonWidth(card), boxY + PADDING - 1, buttonWidth(card), BUTTON_HEIGHT);
		}
		hasLastButton = button;

		Box.panel(g, boxX, boxY, size.width, size.height);
		// Clipping to the box is only done where something could run past it: a card as big as its room, several columns, or a value
		// wider than its column. Cutting a clip out of a complex clip makes garbage every frame.
		Shape outside = null;
		if (layout.clip)
		{
			outside = g.getClip();
			g.clipRect(boxX, boxY, size.width, size.height);
		}

		int x = boxX + PADDING;
		int y = boxY + PADDING;

		g.setFont(Palette.TITLE);
		FontMetrics title = g.getFontMetrics();
		g.setColor(colour("ACCENT"));
		g.drawString(layout.titleText, x, y + title.getAscent());
		if (button)
		{
			paintButton(g, lastButton, pointer != null && lastButton.contains(pointer), isSearch(card) ? SEARCH_BUTTON_TEXT : BUTTON_TEXT);
		}
		y += title.getHeight();

		g.setFont(Palette.BODY);
		FontMetrics body = g.getFontMetrics();
		if (layout.subtitleText != null)
		{
			g.setColor(colour("TEXT_DIM"));
			g.drawString(layout.subtitleText, x, y + body.getAscent());
			y += body.getHeight();
		}

		for (int i = 0; i < layout.labelTexts.length; i++)
		{
			int rowY = y + layout.rowY[i] + body.getAscent();
			g.setColor(colour("TEXT_DIM"));
			g.drawString(layout.labelTexts[i], x + layout.labelX[i], rowY);
			g.setColor(colour("TEXT"));
			g.drawString(layout.rows.get(i).getValue(), x + layout.valueX[i], rowY);
		}
		y += layout.rowsHeight;

		if (preview)
		{
			frame.setBounds(x, y + 3, size.width - PADDING * 2, PREVIEW_HEIGHT);
			paintPreview(g, card, frame);
			y += PREVIEW_HEIGHT + 6;
		}

		if (layout.hintText != null)
		{
			g.setFont(Palette.SMALL_ITALIC);
			g.setColor(colour("HINT"));
			g.drawString(layout.hintText, x, y + 2 + g.getFontMetrics().getAscent());
		}
		if (layout.clip)
		{
			g.setClip(outside);
		}
	}

	/** The frame of the picture, reused so a card drawn every frame makes no new rectangle. */
	private final Rectangle frame = new Rectangle();

	/**
	 * Everything about how a card is laid out that depends only on the card, whether it is expanded and the room: which rows,
	 * the cut-short texts, the widths. Kept for the card drawn last and worked out again only when one of them changes, so a card
	 * the mouse rests on costs no measuring and no new strings each frame.
	 */
	private static final class Layout
	{
		HoverCard card;
		boolean expanded;
		int limitWidth;
		int limitHeight;
		boolean preview;
		boolean clip;
		Dimension size;
		List<Row> rows;
		String titleText;
		String subtitleText;
		String hintText;
		/** Each row's cut label, where its label and value start across the card and how far down it is; how tall the rows are. */
		String[] labelTexts;
		int[] labelX;
		int[] valueX;
		int[] rowY;
		int rowsHeight;
	}

	private Layout layout;

	private Layout layoutFor(Graphics2D g, HoverCard card, boolean expanded, Rectangle limit)
	{
		Layout kept = layout;
		if (kept != null && kept.expanded == expanded && kept.limitWidth == limit.width && kept.limitHeight == limit.height
			&& kept.card.sameContent(card))
		{
			kept.card = card;
			return kept;
		}
		Layout made = fit(g, card, expanded, limit);
		made.card = card;
		made.expanded = expanded;
		made.limitWidth = limit.width;
		made.limitHeight = limit.height;
		int innerWidth = made.size.width - PADDING * 2;
		boolean button = hasButton(card);
		g.setFont(Palette.TITLE);
		made.titleText = Box.dots(card.getTitle(), g.getFontMetrics(), button ? innerWidth - BUTTON_WIDTH - BUTTON_GAP : innerWidth);
		g.setFont(Palette.BODY);
		FontMetrics body = g.getFontMetrics();
		made.subtitleText = card.getSubtitle() == null ? null : Box.dots(card.getSubtitle(), body, innerWidth);
		List<List<Row>> columns = columns(made.rows);
		made.clip = columns.size() > 1 || made.size.width >= limit.width || made.size.height >= limit.height;
		int count = made.rows.size();
		made.labelTexts = new String[count];
		made.labelX = new int[count];
		made.valueX = new int[count];
		made.rowY = new int[count];
		int at = 0;
		int columnX = 0;
		for (List<Row> column : columns)
		{
			int columnWidth = columns.size() == 1 ? innerWidth : columnWidth(body, column);
			for (int r = 0; r < column.size(); r++)
			{
				Row row = column.get(r);
				int valueWidth = body.stringWidth(row.getValue());
				// The label may use all the room the value leaves, so a long one is not cut short for nothing.
				int labelRoom = Math.max(20, columnWidth - valueWidth - (valueWidth > 0 ? ROW_GAP : 0));
				made.labelTexts[at] = Box.dots(row.getLabel(), body, labelRoom);
				made.labelX[at] = columnX;
				made.valueX[at] = columnX + columnWidth - valueWidth;
				made.rowY[at] = r * body.getHeight();
				made.clip |= valueWidth > columnWidth;
				at++;
			}
			made.rowsHeight = Math.max(made.rowsHeight, column.size() * body.getHeight());
			columnX += columnWidth + COLUMN_GAP;
		}
		if (card.getHint() != null)
		{
			g.setFont(Palette.SMALL_ITALIC);
			made.hintText = Box.dots(card.getHint(), g.getFontMetrics(), innerWidth);
		}
		layout = made;
		return made;
	}

	private static void paintButton(Graphics2D g, Rectangle box, boolean lit, String text)
	{
		Box.pill(g, box, lit ? "PANEL_ACTIVE" : "CARD_BUTTON", lit ? "ACCENT" : "BORDER");
		g.setFont(Palette.SMALL_BOLD);
		FontMetrics metrics = g.getFontMetrics();
		g.setColor(colour(lit ? "ACCENT" : "TEXT"));
		g.drawString(text, box.x + (box.width - metrics.stringWidth(text)) / 2, Box.middle(box.y, box.height, metrics));
	}

	/** Pixels per tile a focused preview opens at: a frame this size then shows about 80 by 50 tiles. */
	static final double FOCUS_SCALE = 3.0;

	/**
	 * The transform for a preview. With a focus it is centred on that point and zoomed in, never less
	 * than the scale that would fit the whole map. Without one it fits the whole map.
	 */
	static ViewTransform previewTransform(MapInfo map, HoverCard card, Rectangle frame)
	{
		double fitX = frame.width / (double) (map.getMaxX() - map.getMinX());
		double fitY = frame.height / (double) (map.getMaxY() - map.getMinY());
		double fit = Math.min(fitX, fitY);
		if (card.hasPreviewFocus())
		{
			double scale = Math.max(fit, FOCUS_SCALE);
			return new ViewTransform(card.getPreviewX(), card.getPreviewY(), scale,
				frame.x, frame.y, frame.width, frame.height);
		}
		double cx = (map.getMinX() + map.getMaxX()) / 2.0;
		double cy = (map.getMinY() + map.getMaxY()) / 2.0;
		return new ViewTransform(cx, cy, fit, frame.x, frame.y, frame.width, frame.height);
	}

	/** Pictures kept, each 240 by 150 pixels at most, so the card of a doorway does not draw the map again every frame. */
	private static final int PICTURES = 4;

	/** A finished picture of a map for a card, and what it is a picture of. */
	private static final class Picture
	{
		int mapId;
		boolean focus;
		double x;
		double y;
		int width;
		int height;
		boolean smooth;
		BufferedImage image;

		boolean is(HoverCard card, Rectangle frame, boolean smoothNow)
		{
			return image != null && mapId == card.getPreviewMapId() && focus == card.hasPreviewFocus()
				&& x == card.getPreviewX() && y == card.getPreviewY()
				&& width == frame.width && height == frame.height && smooth == smoothNow;
		}
	}

	private final Picture[] pictures = new Picture[PICTURES];
	private int nextPicture;

	/** How many finished pictures are kept, for tests. */
	int picturesKept()
	{
		int count = 0;
		for (Picture kept : pictures)
		{
			if (kept != null && kept.image != null)
			{
				count++;
			}
		}
		return count;
	}

	/** True when the picture can be drawn as one image: nothing see-through or scaled about the way it is drawn. */
	private static boolean plainTarget(Graphics2D g)
	{
		Composite composite = g.getComposite();
		return composite instanceof AlphaComposite && ((AlphaComposite) composite).getAlpha() == 1f
			&& g.getDeviceConfiguration().getDefaultTransform().equals(g.getTransform());
	}

	private void paintPreview(Graphics2D g, HoverCard card, Rectangle frame)
	{
		MapInfo map = catalog.byId(card.getPreviewMapId());
		if (map == null)
		{
			g.setColor(Color.BLACK);
			g.fillRect(frame.x, frame.y, frame.width, frame.height);
			return;
		}
		boolean smooth = renderer.isSmoothMagnify();
		boolean plain = plainTarget(g);
		if (plain)
		{
			for (Picture kept : pictures)
			{
				if (kept != null && kept.is(card, frame, smooth))
				{
					g.drawImage(kept.image, frame.x, frame.y, null);
					if (card.hasPreviewFocus())
					{
						// The border is drawn with the stroke the landing ring leaves behind, as when the picture is drawn live.
						g.setStroke(RING_INNER);
					}
					g.setColor(colour("BORDER"));
					g.drawRect(frame.x, frame.y, frame.width - 1, frame.height - 1);
					return;
				}
			}
		}
		ViewTransform view = previewTransform(map, card, frame);
		boolean settled = renderer.paintPreview(g, view, map);
		drawLanding(g, view, card);
		g.setColor(colour("BORDER"));
		g.drawRect(frame.x, frame.y, frame.width - 1, frame.height - 1);
		if (settled && plain)
		{
			keep(g, card, map, frame, smooth);
		}
	}

	/** Where the doorway lands, so the picture says "you would arrive here". */
	private static void drawLanding(Graphics2D g, ViewTransform view, HoverCard card)
	{
		if (!card.hasPreviewFocus())
		{
			return;
		}
		double sx = view.screenX(card.getPreviewX());
		double sy = view.screenY(card.getPreviewY());
		g.setColor(Color.WHITE);
		g.setStroke(RING_OUTER);
		g.drawOval((int) sx - 8, (int) sy - 8, 16, 16);
		g.setColor(RING_COLOR);
		g.setStroke(RING_INNER);
		g.drawOval((int) sx - 8, (int) sy - 8, 16, 16);
	}

	private static final BasicStroke RING_OUTER = new BasicStroke(3f);
	private static final BasicStroke RING_INNER = new BasicStroke(1.5f);
	private static final Color RING_COLOR = new Color(255, 214, 0);

	/** Draws the picture once more into an image of its own, and keeps the image only if that came out final too. */
	private void keep(Graphics2D live, HoverCard card, MapInfo map, Rectangle frame, boolean smooth)
	{
		if (frame.width <= 0 || frame.height <= 0 || frame.width > PREVIEW_WIDTH * 2 || frame.height > PREVIEW_HEIGHT * 2)
		{
			return;
		}
		BufferedImage image = new BufferedImage(frame.width, frame.height,
			BufferedImage.TYPE_INT_RGB);
		Graphics2D own = image.createGraphics();
		try
		{
			own.setRenderingHints(live.getRenderingHints());
			ViewTransform view = previewTransform(map, card, new Rectangle(0, 0, frame.width, frame.height));
			boolean settled = renderer.paintPreview(own, view, map);
			drawLanding(own, view, card);
			if (!settled)
			{
				return;
			}
		}
		finally
		{
			own.dispose();
		}
		Picture slot = pictures[nextPicture];
		if (slot == null)
		{
			slot = new Picture();
			pictures[nextPicture] = slot;
		}
		nextPicture = (nextPicture + 1) % PICTURES;
		slot.mapId = card.getPreviewMapId();
		slot.focus = card.hasPreviewFocus();
		slot.x = card.getPreviewX();
		slot.y = card.getPreviewY();
		slot.width = frame.width;
		slot.height = frame.height;
		slot.smooth = smooth;
		slot.image = image;
	}
}
