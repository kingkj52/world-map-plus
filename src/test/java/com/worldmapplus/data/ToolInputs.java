package com.worldmapplus.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assume;

/**
 * Opens a data file for a test that looks through it. Since round 69 the files the build tools read but the plugin does not (the
 * entrances, transports, teleports, shops, shortcuts, the game's list, icon details, requirements, kinds and spawns) are kept in
 * {@code tools/input} and are not in the jar; every other file is a resource of the plugin.
 */
public final class ToolInputs
{
	/** The files in tools/input that were once bundled. tools/build_places.py lists the same in INPUTS. */
	public static final Set<String> MOVED = new HashSet<>(Arrays.asList("entrances.json", "transports.json", "teleports.json",
		"shops.json", "shortcuts.json", "map-elements.json", "icon-details.json", "requirements.json", "kinds.json", "spawns.bin"));

	private ToolInputs()
	{
	}

	/** The file's bytes, from tools/input or from the plugin's resources. Null when a resource is missing. */
	public static InputStream stream(String name)
	{
		if (!MOVED.contains(name))
		{
			return ToolInputs.class.getResourceAsStream("/com/worldmapplus/" + name);
		}
		// The published copy (tools/make_release.py) leaves the tools and their inputs out: there these checks of the inputs are skipped.
		Assume.assumeTrue("tools/input is not in this copy", Files.isDirectory(Paths.get("tools", "input")));
		try
		{
			return Files.newInputStream(Paths.get("tools", "input", name));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
