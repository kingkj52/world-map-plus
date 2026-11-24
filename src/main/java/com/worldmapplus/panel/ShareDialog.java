package com.worldmapplus.panel;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * The clipboard end of sharing. A code is put on the clipboard when the player presses Share, so pasting it into the
 * game's chat is one keypress, and codes are taken from the clipboard when the player presses Import. Nothing here
 * touches the game: the plugin never types into the chat box, which RuneLite does not allow, and the player pastes
 * and sends each line themselves.
 */
final class ShareDialog
{
	/** The most text read from the clipboard. A code line is under eighty characters, and a paste holds a few. */
	static final int MAX_READ = 20_000;

	private ShareDialog()
	{
	}

	/** Puts text on the system clipboard. Returns false when the clipboard cannot be used. */
	static boolean copy(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
			return true;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	/** The text on the system clipboard, cut to a sensible length, or null when there is none or it cannot be read. */
	@Nullable
	static String read()
	{
		try
		{
			Transferable held = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
			if (held == null || !held.isDataFlavorSupported(DataFlavor.stringFlavor))
			{
				return null;
			}
			String text = (String) held.getTransferData(DataFlavor.stringFlavor);
			return text != null && text.length() > MAX_READ ? text.substring(0, MAX_READ) : text;
		}
		catch (UnsupportedFlavorException | IOException | RuntimeException e)
		{
			return null;
		}
	}
}
