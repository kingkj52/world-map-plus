package com.worldmapplus;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * Mirrors the checks the Plugin Hub packager (plugin-hub-tooling, Plugin.java) makes on a plugin's repository, so a
 * mistake shows up here and not in a rejected submission. Each failure says which packager rule it stands for.
 * <p>
 * The Hub's "standard" build copies only {@code src/main/java} and {@code src/main/resources} and ignores this
 * project's build.gradle, so anything that has to be inside the jar must come from those two folders.
 */
public class HubPackagingTest
{
	/** Jagex Fan Content Policy, clause 8.1. The wording is Jagex's and is used as it is given. */
	static final String CREDIT_ONE = "Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy.";
	static final String CREDIT_TWO = "This content is not endorsed by or affiliated with Jagex.";

	private static final Path ROOT = new File(".").toPath();
	private static final Path RESOURCES = ROOT.resolve("src/main/resources");

	/** The packager reads these keys. Any other key makes it fail ("unknown key(s) in runelite-plugin.properties"), except support. */
	private static final Set<String> PROPERTY_KEYS = new HashSet<>(Arrays.asList(
		"displayName", "author", "description", "tags", "plugins", "version", "build", "support"));

	private static String text(Path path) throws IOException
	{
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}

	private static Properties properties() throws IOException
	{
		Path file = ROOT.resolve("runelite-plugin.properties");
		assertTrue("runelite-plugin.properties must exist in the root of the repository", Files.isRegularFile(file));
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(file))
		{
			properties.load(in);
		}
		return properties;
	}

	@Test
	public void licenceExistsAtTheRootOfTheRepository() throws IOException
	{
		Path licence = ROOT.resolve("LICENSE");
		assertTrue("The packager fails a plugin without a LICENSE file in the repository root", Files.isRegularFile(licence));
		String text = text(licence);
		assertTrue("LICENSE must be a licence that lets the Hub distribute the jar; BSD 2-Clause is recommended",
			text.contains("BSD 2-Clause") && text.contains("Redistribution and use in source and binary forms"));
	}

	@Test
	public void noticeAndLicenceTravelInsideTheJarFromSrcMainResources() throws IOException
	{
		// The jar{} block of build.gradle is not run by the Hub's standard build, so the copies the jar needs live in
		// the folder that is. The root files are the ones people read, and the copies must not drift from them.
		for (String name : new String[] {"NOTICE", "LICENSE"})
		{
			Path root = ROOT.resolve(name);
			Path packed = RESOURCES.resolve("META-INF").resolve(name);
			assertTrue(name + " is missing from the repository root", Files.isRegularFile(root));
			assertTrue(name + " must also be at src/main/resources/META-INF/" + name + " so that the Hub build puts it in the jar",
				Files.isRegularFile(packed));
			assertArrayEquals("src/main/resources/META-INF/" + name + " must be a byte for byte copy of ./" + name
				+ ". Copy the root file over it", Files.readAllBytes(root), Files.readAllBytes(packed));
		}
	}

	@Test
	public void theJagexCreditIsWhereItIsRequired() throws IOException
	{
		String[] places = {"NOTICE", "README.md", "src/main/resources/com/worldmapplus/DATA-LICENCE.txt"};
		for (String place : places)
		{
			String text = text(ROOT.resolve(place));
			assertTrue(place + " must carry Jagex's credit line: " + CREDIT_ONE, text.contains(CREDIT_ONE));
			assertTrue(place + " must carry Jagex's credit line: " + CREDIT_TWO, text.contains(CREDIT_TWO));
		}
		String readme = text(ROOT.resolve("README.md")).trim();
		assertTrue("the README ends with the credit lines", readme.endsWith(CREDIT_TWO));
	}

	@Test
	public void noticeDoesNotLicenceTheTilesUnderTheWikisTextLicence() throws IOException
	{
		// The wiki says non-text media must not be assumed to share the licence of its text, and the tiles are drawn from
		// the game's cache. NOTICE must not say they are CC BY-NC-SA, and DATA-LICENCE.txt must agree.
		String notice = text(ROOT.resolve("NOTICE"));
		assertTrue("NOTICE must say that no licence is claimed for the tiles", notice.contains("does not claim any licence for them"));
		String licence = text(RESOURCES.resolve("com/worldmapplus/DATA-LICENCE.txt"));
		assertTrue("DATA-LICENCE.txt must say that no licence is claimed for the tiles", licence.contains("No licence is claimed for them"));
		assertTrue("the data licence file must send readers to NOTICE", licence.contains("NOTICE"));
	}

	@Test
	public void iconIsSmallEnoughAndReadable() throws IOException
	{
		Path icon = ROOT.resolve("icon.png");
		assertTrue("icon.png must be in the root of the repository", Files.isRegularFile(icon));
		assertTrue("icon.png is " + Files.size(icon) + " bytes; the packager's limit is 256 KiB", Files.size(icon) <= 256 * 1024);
		BufferedImage image = ImageIO.read(icon.toFile());
		assertNotNull("icon.png must be a real PNG the packager can read (\"icon is invalid\")", image);
		assertTrue("icon.png is " + image.getWidth() + "x" + image.getHeight()
			+ "; the packager's limit is 5000 pixels (48x72 is suggested)", image.getWidth() * image.getHeight() <= 50 * 100);
		byte[] header = Arrays.copyOf(Files.readAllBytes(icon), 8);
		assertArrayEquals("icon.png must really be a PNG, not a renamed JPEG or ICO",
			new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, header);
	}

	@Test
	public void pluginPropertiesHaveWhatThePackagerRequires() throws IOException
	{
		Properties properties = properties();
		for (String required : new String[] {"displayName", "author", "description", "plugins", "build"})
		{
			String value = properties.getProperty(required);
			assertTrue("runelite-plugin.properties must set " + required, value != null && !value.trim().isEmpty());
		}
		assertTrue("displayName must not be left as the template's Example", !"Example".equals(properties.getProperty("displayName")));
		assertTrue("author must not be left as the template's Nobody", !"Nobody".equals(properties.getProperty("author")));
		String build = properties.getProperty("build");
		assertTrue("build must be standard or gradle, not \"" + build + "\"", "standard".equals(build) || "gradle".equals(build));
		List<String> unknown = new ArrayList<>();
		for (String key : properties.stringPropertyNames())
		{
			if (!PROPERTY_KEYS.contains(key))
			{
				unknown.add(key);
			}
		}
		assertTrue("the packager rejects unknown keys in runelite-plugin.properties: " + unknown, unknown.isEmpty());
	}

	@Test
	public void everyListedPluginClassExistsAndIsAPlugin() throws IOException
	{
		String plugins = properties().getProperty("plugins");
		List<String> names = Arrays.stream(plugins.split("[,:;]")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
		assertTrue("plugins must name at least one class", !names.isEmpty());
		for (String name : names)
		{
			Path source = ROOT.resolve("src/main/java").resolve(name.replace('.', '/') + ".java");
			assertTrue("plugins names " + name + " but " + source + " does not exist", Files.isRegularFile(source));
			String code = text(source);
			assertTrue(name + " must extend Plugin", code.contains("extends Plugin"));
			assertTrue(name + " must have a @PluginDescriptor", code.contains("@PluginDescriptor"));
		}
	}

	@Test
	public void theJavaVersionIsEleven() throws IOException
	{
		String gradle = text(ROOT.resolve("build.gradle"));
		assertTrue("The Hub needs Java 11 compatible plugins; build.gradle must keep options.release.set(11)",
			gradle.contains("options.release.set(11)"));
	}

	@Test
	public void resourcesHoldNoCodeAndNoServiceFile() throws IOException
	{
		List<String> bad = new ArrayList<>();
		try (Stream<Path> files = Files.walk(RESOURCES))
		{
			for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList()))
			{
				String name = file.getFileName().toString();
				String path = RESOURCES.relativize(file).toString().replace(File.separatorChar, '/');
				if (name.endsWith(".class") || name.endsWith(".java") || name.endsWith(".jar") || name.endsWith(".so")
					|| name.endsWith(".dll") || name.endsWith(".dylib"))
				{
					bad.add(path + " (code may not be bundled; the packager fails on .class files in resources)");
				}
				if (path.startsWith("META-INF/services/"))
				{
					bad.add(path + " (do not include a META-INF/services file for plugins)");
				}
			}
		}
		if (!bad.isEmpty())
		{
			fail("src/main/resources holds files the Hub does not allow:\n" + String.join("\n", bad));
		}
	}

	@Test
	public void noBuildOutputIsCommittedUnderSrc() throws IOException
	{
		List<String> bad = new ArrayList<>();
		try (Stream<Path> files = Files.walk(ROOT.resolve("src/main")))
		{
			for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList()))
			{
				if (file.toString().endsWith(".class"))
				{
					bad.add(file.toString());
				}
			}
		}
		assertEquals("class files do not belong in the source tree: " + bad, 0, bad.size());
	}

	@org.junit.Test
	public void thePluginHasTheInternalNameItsDataFolderNeeds()
	{
		// Plugin.getPluginDirectory refuses a plugin without one, and the Plugin Hub requires it to be the hub name of the plugin.
		net.runelite.client.plugins.PluginDescriptor descriptor =
			WorldMapPlusPlugin.class.getAnnotation(net.runelite.client.plugins.PluginDescriptor.class);
		org.junit.Assert.assertEquals("world-map-plus", descriptor.internalName());
	}
}
