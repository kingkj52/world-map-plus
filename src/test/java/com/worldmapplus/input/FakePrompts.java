package com.worldmapplus.input;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A stand-in for the chat area that keeps its rules: one prompt at a time, opening a second ends the first, Enter and Escape end
 * it, and ending it, however it happens, runs its close callback once. Counts every call made to it, so a test can show that a
 * setting that is off touches nothing. Like the real adapter, {@link #close()} ends only a prompt that was opened through
 * {@link #open}, never one that {@link #foreignPromptOpens()} made.
 */
public class FakePrompts implements ChatSearch.Prompts
{
	private static final class Prompt
	{
		final String title;
		final String initial;
		final Consumer<String> onChanged;
		final Consumer<String> onDone;
		final Runnable onClose;
		final boolean foreign;
		String value;

		Prompt(String title, String initial, Consumer<String> onChanged, Consumer<String> onDone, Runnable onClose, boolean foreign)
		{
			this.foreign = foreign;
			this.title = title;
			this.initial = initial;
			this.value = initial;
			this.onChanged = onChanged;
			this.onDone = onDone;
			this.onClose = onClose;
		}
	}

	private Prompt current;

	/** The areas of the canvas the prompt covers. */
	public final List<Rectangle> covered = new ArrayList<>();

	public int opens;
	public int closes;
	public int coverChecks;
	public final List<String> titles = new ArrayList<>();
	public final List<String> initialTexts = new ArrayList<>();

	/** Every call made, of any kind. Zero means the chat area was never touched. */
	public int calls()
	{
		return opens + closes + coverChecks;
	}

	@Override
	public synchronized void open(String title, String text, Consumer<String> onChanged, Consumer<String> onDone,
		Runnable onClose)
	{
		opens++;
		titles.add(title);
		initialTexts.add(text);
		endCurrent();
		current = new Prompt(title, text, onChanged, onDone, onClose, false);
	}

	@Override
	public synchronized void close()
	{
		closes++;
		if (current != null && !current.foreign)
		{
			endCurrent();
		}
	}

	@Override
	public synchronized boolean covers(int x, int y)
	{
		coverChecks++;
		for (Rectangle area : covered)
		{
			if (area.contains(x, y))
			{
				return true;
			}
		}
		return false;
	}

	private void endCurrent()
	{
		Prompt ended = current;
		current = null;
		if (ended != null)
		{
			ended.onClose.run();
		}
	}

	public synchronized boolean isOpen()
	{
		return current != null;
	}

	public synchronized String title()
	{
		return current == null ? null : current.title;
	}

	public synchronized String value()
	{
		return current == null ? null : current.value;
	}

	/** The player types the whole text, one change at a time. */
	public synchronized void type(String text)
	{
		for (int i = 1; i <= text.length(); i++)
		{
			current.value = text.substring(0, i);
			current.onChanged.accept(current.value);
		}
	}

	/** The player replaces everything with this in one change. */
	public synchronized void set(String text)
	{
		current.value = text;
		current.onChanged.accept(text);
	}

	/** Enter: the prompt reports its text as done and the chat area closes it. */
	public synchronized void enter()
	{
		current.onDone.accept(current.value);
		endCurrent();
	}

	/** Escape: the chat area closes the prompt. */
	public synchronized void escape()
	{
		endCurrent();
	}

	/** Another plugin opens its own prompt, which ends this one. */
	public synchronized void foreignPromptOpens()
	{
		endCurrent();
		current = new Prompt("Someone else", "", v -> { }, v -> { }, () -> { }, true);
	}
}
