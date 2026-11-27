package com.worldmapplus.share;

/** A share code that cannot be made or read. The message says why, in words a player can read. */
public final class ShareException extends Exception
{
	public ShareException(String message)
	{
		super(message);
	}
}
