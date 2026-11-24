package com.worldmapplus.tiles.codec;

/**
 * The one exception the tile codec throws for input it cannot use: a damaged or truncated chunk, a header that
 * lies about sizes, dimensions outside the allowed range, a request for an entry that is not there. The codec
 * never throws anything else for bad input and never runs out of memory because of it.
 */
public final class CodecException extends RuntimeException
{
	public CodecException(String message)
	{
		super(message);
	}

	public CodecException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
