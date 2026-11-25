package com.worldmapplus.tiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.runelite.client.util.Filepath;

/**
 * The one place the plugin touches the disk: a folder named {@code maps} inside the plugin's own RuneLite data folder, holding
 * the map packs that were downloaded and the last manifest. It goes through RuneLite's {@link Filepath} only, which cannot leave
 * that folder. Callers pass names they have checked (see {@link MapManifest}); nothing here is ever run or opened as anything but
 * bytes. A pack is written under a temporary name and moved into place, so a half written file is never read.
 */
final class PackCache
{
	static final String MANIFEST = "manifest.json";
	private static final String PART = ".part";

	private final Filepath dir;

	PackCache(Filepath pluginFolder)
	{
		dir = pluginFolder.join("maps");
	}

	/** A file of the folder. A name is one plain file name: no folder, no way up, nothing hidden. */
	private Filepath file(String name)
	{
		if (name.isEmpty() || name.startsWith(".") || name.indexOf('/') >= 0 || name.indexOf((char) 92) >= 0)
		{
			throw new IllegalArgumentException("not a file name: " + name);
		}
		return dir.join(name);
	}

	/** The files in the folder. Empty when there is no folder yet. */
	Set<String> names()
	{
		Set<String> found = new HashSet<>();
		if (!dir.isDirectory())
		{
			return found;
		}
		try (Stream<Filepath> files = dir.walk(1))
		{
			files.filter(Filepath::isFile).forEach(f -> found.add(f.getFileName()));
		}
		catch (IOException | RuntimeException e)
		{
			// An unreadable folder is an empty one.
		}
		return found;
	}

	/** The bytes of a file, or null when there is none or it is larger than max. */
	@Nullable
	byte[] read(String name, int max) throws IOException
	{
		Filepath file = file(name);
		if (!file.isFile() || file.size() > max)
		{
			return null;
		}
		try (InputStream in = file.openInputStream())
		{
			byte[] bytes = in.readNBytes(max + 1);
			return bytes.length > max ? null : bytes;
		}
	}

	void write(String name, byte[] bytes) throws IOException
	{
		dir.createDirectories();
		Filepath temporary = file(name + PART);
		temporary.write(bytes);
		temporary.moveTo(file(name), StandardCopyOption.REPLACE_EXISTING);
	}

	/** Deletes every file that is not in keep. A file being written has a temporary name and is left alone. */
	void retain(Set<String> keep)
	{
		for (String name : names())
		{
			if (!keep.contains(name) && !name.endsWith(PART))
			{
				delete(name);
			}
		}
	}

	void delete(String name)
	{
		try
		{
			file(name).deleteIfExists();
		}
		catch (IOException | RuntimeException e)
		{
			// Left for the next clean up.
		}
	}
}
