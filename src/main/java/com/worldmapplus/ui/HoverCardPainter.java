package com.worldmapplus.ui;

import com.worldmapplus.layers.*;
import com.worldmapplus.map.*;
import com.worldmapplus.render.*;
import com.worldmapplus.theme.*;
import com.worldmapplus.view.*;
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

	/** The small button in the corner of a card, put there by a partner plugin (the Wiki button is the Helper's). */
	static final int BUTTON_WIDTH = 38;
	static final int BUTTON_HEIGHT = 16;
	private static final int BUTTON_GAP = 10;

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

	/** True when a partner put a button on the card. */
	static boolean hasButton(HoverCard card)
	{
		return card.getButtonLabel() != null;
	}

	private static int buttonWidth(HoverCard card)
	{
		return Math.max(BUTTON_WIDTH, 6 * card.getButtonLabel().length() + 6);
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
	static List<List<HoverCard.Row>> columns(List<HoverCard.Row> rows)
	{
		List<List<HoverCard.Row>> columns = new ArrayList<>();
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

	private static int columnWidth(FontMetrics body, List<HoverCard.Row> column)
	{
		int width = 0;
		for (HoverCard.Row row : column)
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
	static List<HoverCard.Row> fitRows(Graphics2D g, HoverCard card, boolean expanded, boolean preview, int maxHeight)
	{
		List<HoverCard.Row> rows = card.visibleRows(expanded);
		int keep = rows.size();
		while (keep > 1 && measure(g, card, rows(rows, keep), preview).height > maxHeight)
		{
			keep--;
		}
		return rows(rows, keep);
	}

	private static List<HoverCard.Row> rows(List<HoverCard.Row> all, int keep)
	{
		if (keep >= all.size())
		{
			return all;
		}
		List<HoverCard.Row> shown = new ArrayList<>(all.subList(0, Math.max(0, keep - 1)));
		shown.add(new HoverCard.Row("and " + (all.size() - shown.size()) + " more", ""));
		return shown;
	}

	private static Dimension measure(Graphics2D g, HoverCard card, List<HoverCard.Row> rows, boolean preview)
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

		List<List<HoverCard.Row>> columns = columns(rows);
		int rowsWidth = 0;
		int tallest = 0;
		for (List<HoverCard.Row> column : columns)
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

	/** Whether the picture is kept, which rows are shown, and the card's size, for a card over a map of a given size. */
	private static final class Fitted
	{
		boolean preview;
		List<HoverCard.Row> rows;
		Dimension size;
	}

	/**
	 * A card is never larger than the map it is over: the picture goes first, then the rows that do not fit, and a text too wide
	 * for the room is cut short with an ellipsis. The full text is what holding Shift shows.
	 */
	private static Fitted fit(Graphics2D g, HoverCard card, boolean expanded, Rectangle limit)
	{
		Fitted fitted = new Fitted();
		fitted.preview = card.hasPreview();
		Dimension size = measure(g, card, card.visibleRows(expanded), fitted.preview);
		if (size.height > limit.height && fitted.preview)
		{
			fitted.preview = false;
		}
		fitted.rows = fitRows(g, card, expanded, fitted.preview, limit.height);
		size = measure(g, card, fitted.rows, fitted.preview);
		fitted.size = new Dimension(Math.min(size.width, limit.width), Math.min(size.height, limit.height));
		return fitted;
	}

	/** Where the card is drawn, for a card over a map of the given size. For tests. */
	static Rectangle boundsOf(Graphics2D g, HoverCard card, boolean expanded, int mouseX, int mouseY, Rectangle limit)
	{
		Fitted fitted = fit(g, card, expanded, limit);
		Point at = place(fitted.size, mouseX, mouseY, limit);
		return new Rectangle(at.x, at.y, fitted.size.width, fitted.size.height);
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

		Theme.panel(g, boxX, boxY, size.width, size.height);
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
		g.setColor(Theme.ACCENT);
		g.drawString(layout.titleText, x, y + title.getAscent());
		if (button)
		{
			paintButton(g, lastButton, pointer != null && lastButton.contains(pointer), card.getButtonLabel());
		}
		y += title.getHeight();

		g.setFont(Palette.BODY);
		FontMetrics body = g.getFontMetrics();
		if (layout.subtitleText != null)
		{
			g.setColor(Theme.TEXT_DIM);
			g.drawString(layout.subtitleText, x, y + body.getAscent());
			y += body.getHeight();
		}

		int columnX = x;
		int tallest = 0;
		int at = 0;
		for (int c = 0; c < layout.columns.size(); c++)
		{
			List<HoverCard.Row> column = layout.columns.get(c);
			int columnWidth = layout.columnWidths[c];
			int rowY = y;
			for (int r = 0; r < column.size(); r++)
			{
				g.setColor(Theme.TEXT_DIM);
				g.drawString(layout.labelTexts[at], columnX, rowY + body.getAscent());
				g.setColor(Theme.TEXT);
				g.drawString(column.get(r).getValue(), columnX + columnWidth - layout.valueWidths[at], rowY + body.getAscent());
				at++;
				rowY += body.getHeight();
			}
			tallest = Math.max(tallest, column.size());
			columnX += columnWidth + COLUMN_GAP;
		}
		y += tallest * body.getHeight();

		if (preview)
		{
			frame.setBounds(x, y + 3, size.width - PADDING * 2, PREVIEW_HEIGHT);
			paintPreview(g, card, frame);
			y += PREVIEW_HEIGHT + 6;
		}

		if (layout.hintText != null)
		{
			g.setFont(Palette.SMALL_ITALIC);
			g.setColor(Theme.HINT);
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
		List<List<HoverCard.Row>> columns;
		int[] columnWidths;
		String titleText;
		String subtitleText;
		String hintText;
		String[] labelTexts;
		int[] valueWidths;
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
		Fitted fitted = fit(g, card, expanded, limit);
		Layout made = new Layout();
		made.card = card;
		made.expanded = expanded;
		made.limitWidth = limit.width;
		made.limitHeight = limit.height;
		made.preview = fitted.preview;
		made.size = fitted.size;
		int innerWidth = fitted.size.width - PADDING * 2;
		boolean button = hasButton(card);
		g.setFont(Palette.TITLE);
		made.titleText = clip(card.getTitle(), g.getFontMetrics(), button ? innerWidth - BUTTON_WIDTH - BUTTON_GAP : innerWidth);
		g.setFont(Palette.BODY);
		FontMetrics body = g.getFontMetrics();
		made.subtitleText = card.getSubtitle() == null ? null : clip(card.getSubtitle(), body, innerWidth);
		made.columns = columns(fitted.rows);
		made.clip = made.columns.size() > 1 || fitted.size.width >= limit.width || fitted.size.height >= limit.height;
		int count = fitted.rows.size();
		made.columnWidths = new int[made.columns.size()];
		made.labelTexts = new String[count];
		made.valueWidths = new int[count];
		int at = 0;
		for (int c = 0; c < made.columns.size(); c++)
		{
			List<HoverCard.Row> column = made.columns.get(c);
			int columnWidth = made.columns.size() == 1 ? innerWidth : columnWidth(body, column);
			made.columnWidths[c] = columnWidth;
			for (HoverCard.Row row : column)
			{
				int valueWidth = body.stringWidth(row.getValue());
				// The label may use all the room the value leaves, so a long one is not cut short for nothing.
				int labelRoom = Math.max(20, columnWidth - valueWidth - (valueWidth > 0 ? ROW_GAP : 0));
				made.labelTexts[at] = clip(row.getLabel(), body, labelRoom);
				made.valueWidths[at] = valueWidth;
				made.clip |= valueWidth > columnWidth;
				at++;
			}
		}
		if (card.getHint() != null)
		{
			g.setFont(Palette.SMALL_ITALIC);
			made.hintText = clip(card.getHint(), g.getFontMetrics(), innerWidth);
		}
		layout = made;
		return made;
	}

	private static void paintButton(Graphics2D g, Rectangle box, boolean lit, String text)
	{
		g.setColor(lit ? Theme.PANEL_ACTIVE : Theme.CARD_BUTTON);
		g.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
		g.setColor(lit ? Theme.ACCENT : Theme.BORDER);
		g.drawRoundRect(box.x, box.y, box.width - 1, box.height - 1, 6, 6);
		g.setFont(Palette.SMALL_BOLD);
		FontMetrics metrics = g.getFontMetrics();
		g.setColor(lit ? Theme.ACCENT : Theme.TEXT);
		g.drawString(text, box.x + (box.width - metrics.stringWidth(text)) / 2,
			box.y + (box.height - metrics.getHeight()) / 2 + metrics.getAscent());
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
					g.setColor(Theme.BORDER);
					g.drawRect(frame.x, frame.y, frame.width - 1, frame.height - 1);
					return;
				}
			}
		}
		ViewTransform view = previewTransform(map, card, frame);
		boolean settled = renderer.paintPreview(g, view, map);
		drawLanding(g, view, card);
		g.setColor(Theme.BORDER);
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

	private static String clip(String text, FontMetrics metrics, int maxWidth)
	{
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String result = text;
		while (result.length() > 1 && metrics.stringWidth(result + "...") > maxWidth)
		{
			result = result.substring(0, result.length() - 1);
		}
		return result + "...";
	}
}
