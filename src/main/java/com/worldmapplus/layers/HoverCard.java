package com.worldmapplus.layers;

import java.util.*;
import javax.annotation.*;
import lombok.*;

/**
 * What the box beside the mouse says about the thing under it.
 * <p>
 * A card is plain data: a title, a line under it, rows of label and value, an optional picture of
 * another map, and a line of advice at the bottom. {@code HoverCardPainter} draws it.
 */
public final class HoverCard
{
	/** One row: a label on the left, a value on the right. */
	@Getter
	public static final class Row
	{
		private String label = "";
		private String value = "";

		private Row()
		{
		}

		public Row(String label, String value)
		{
			this.label = label == null ? "" : label;
			this.value = value == null ? "" : value;
		}
	}

	@Getter
	private String title;
	private String subtitle;
	@Getter
	private List<Row> rows;
	/**
	 * Rows that did not fit in the short card. They are shown too when the card is expanded, which
	 * the viewer does while a key is held, so a long list can be read in full.
	 */
	@Getter
	private List<Row> overflow;
	/** The id of a map to show a small picture of, or -1 for none. */
	@Getter
	private int previewMapId;
	@Getter
	private double previewX;
	@Getter
	private double previewY;
	private boolean previewFocus;
	private String hint;
	private String wiki;
	private String wikiSearch;

	/** An empty card, for Gson to fill from {@code things.json}. */
	private HoverCard()
	{
		this(new Builder(""));
	}

	private HoverCard(Builder builder)
	{
		this.wiki = builder.wiki;
		this.wikiSearch = builder.wikiSearch;
		this.title = builder.title;
		this.subtitle = builder.subtitle;
		this.rows = Collections.unmodifiableList(new ArrayList<>(builder.rows));
		this.overflow = Collections.unmodifiableList(new ArrayList<>(builder.overflow));
		this.previewMapId = builder.previewMapId;
		this.previewX = builder.previewX;
		this.previewY = builder.previewY;
		this.previewFocus = builder.previewFocus;
		this.hint = builder.hint;
	}

	/** True when the other card says and shows exactly what this one does. Makes no garbage. */
	public boolean sameContent(@Nullable HoverCard other)
	{
		if (other == this)
		{
			return true;
		}
		return other != null && previewMapId == other.previewMapId && previewFocus == other.previewFocus
			&& previewX == other.previewX && previewY == other.previewY
			&& title.equals(other.title) && Objects.equals(subtitle, other.subtitle)
			&& Objects.equals(hint, other.hint) && Objects.equals(wiki, other.wiki)
			&& Objects.equals(wikiSearch, other.wikiSearch)
			&& sameRows(rows, other.rows) && sameRows(overflow, other.overflow);
	}

	private static boolean sameRows(List<Row> a, List<Row> b)
	{
		int n = a.size();
		if (n != b.size())
		{
			return false;
		}
		for (int i = 0; i < n; i++)
		{
			Row x = a.get(i);
			Row y = b.get(i);
			if (!x.label.equals(y.label) || !x.value.equals(y.value))
			{
				return false;
			}
		}
		return true;
	}

	@Nullable
	public String getSubtitle()
	{
		return subtitle;
	}

	/**
	 * The rows to draw. Collapsed, the normal rows and a note counting what is hidden. Expanded,
	 * the normal rows followed by all of the overflow.
	 */
	public List<Row> visibleRows(boolean expanded)
	{
		if (overflow.isEmpty())
		{
			return rows;
		}
		List<Row> result = new ArrayList<>(rows);
		if (expanded)
		{
			result.addAll(overflow);
		}
		else
		{
			result.add(new Row("and " + overflow.size() + " more (hold Shift)", ""));
		}
		return result;
	}

	/** True when the preview should be centred on {@link #getPreviewX()}, {@link #getPreviewY()} rather than show the whole map. */
	public boolean hasPreviewFocus()
	{
		return previewFocus;
	}

	public boolean hasPreview()
	{
		return previewMapId >= 0;
	}

	@Nullable
	public String getHint()
	{
		return hint;
	}

	/**
	 * The title of the Old School RuneScape Wiki page about what the card is for, spelt as that page is, or null when the card is for
	 * something that has no page of its own to send a player to. The title is not yet checked for use in an address, see
	 * {@code WikiLinks}.
	 */
	@Nullable
	public String getWiki()
	{
		return wiki;
	}

	/**
	 * A name to look up with the wiki's search page, for a card with no page of its own that is certain to exist, or null. The search
	 * page always answers, so the player lands on a list of results and never on a missing page. Only used when {@link #getWiki()} is
	 * null.
	 */
	@Nullable
	public String getWikiSearch()
	{
		return wikiSearch;
	}

	/** This card with a name to look up on the wiki's search page, when it names no page of its own. The card itself otherwise. */
	public HoverCard withWikiSearch(@Nullable String name)
	{
		return wiki != null || name == null ? this : toBuilder().wikiSearch(name).build();
	}

	public static Builder builder(String title)
	{
		return new Builder(title);
	}

	/** A copy of this card with one more row after the others, such as a note of what else is under the mouse. */
	public HoverCard withRow(String label, String value)
	{
		Builder copy = new Builder(title);
		copy.subtitle = subtitle;
		copy.rows.addAll(rows);
		copy.rows.add(new Row(label, value));
		copy.overflow.addAll(overflow);
		copy.previewMapId = previewMapId;
		copy.previewX = previewX;
		copy.previewY = previewY;
		copy.previewFocus = previewFocus;
		copy.hint = hint;
		copy.wiki = wiki;
		copy.wikiSearch = wikiSearch;
		return new HoverCard(copy);
	}

	/** This card with no wiki page or search named, so it gets no Wiki button. The card itself when it names neither. */
	public HoverCard withoutWiki()
	{
		return wiki == null && wikiSearch == null ? this : toBuilder().wiki(null).wikiSearch(null).build();
	}

	/** A builder holding what this card has, to change a part of it or add to it. */
	public Builder toBuilder()
	{
		Builder copy = new Builder(title);
		copy.subtitle = subtitle;
		copy.rows.addAll(rows);
		copy.overflow.addAll(overflow);
		copy.previewMapId = previewMapId;
		copy.previewX = previewX;
		copy.previewY = previewY;
		copy.previewFocus = previewFocus;
		copy.hint = hint;
		copy.wiki = wiki;
		copy.wikiSearch = wikiSearch;
		return copy;
	}

	public static final class Builder
	{
		private String title;
		private String subtitle;
		private final List<Row> rows = new ArrayList<>();
		private final List<Row> overflow = new ArrayList<>();
		private int previewMapId = -1;
		private double previewX;
		private double previewY;
		private boolean previewFocus;
		private String hint;
		private String wiki;
		private String wikiSearch;

		private Builder(String title)
		{
			this.title = title == null ? "" : title;
		}

		public Builder title(String title)
		{
			this.title = title == null ? "" : title;
			return this;
		}

		public Builder subtitle(String subtitle)
		{
			this.subtitle = subtitle;
			return this;
		}

		public Builder row(String label, String value)
		{
			rows.add(new Row(label, value));
			return this;
		}

		/** A row shown only when the card is expanded. */
		public Builder overflowRow(String label, String value)
		{
			overflow.add(new Row(label, value));
			return this;
		}

		public Builder preview(int mapId)
		{
			this.previewMapId = mapId;
			this.previewFocus = false;
			return this;
		}

		/** A preview of a map centred on a point, zoomed in on it. */
		public Builder preview(int mapId, double x, double y)
		{
			this.previewMapId = mapId;
			this.previewX = x;
			this.previewY = y;
			this.previewFocus = true;
			return this;
		}

		public Builder hint(String hint)
		{
			this.hint = hint;
			return this;
		}

		/** Names the wiki page about what this card is for. Null, or never calling this, means the card has no wiki button. */
		/** Names something to look up on the wiki's search page when the card has no page of its own. */
		public Builder wikiSearch(String name)
		{
			this.wikiSearch = name;
			return this;
		}

		public Builder wiki(String title)
		{
			this.wiki = title;
			return this;
		}

		public HoverCard build()
		{
			return new HoverCard(this);
		}
	}
}
