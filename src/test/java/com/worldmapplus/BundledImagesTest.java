package com.worldmapplus;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.Test;

/**
 * The Plugin Hub bot flags a bundled picture that decodes to more than 4,000,000 bytes ("Oversized image"; width x height x 4).
 * The overviews were 1536 x 1088 (6,684,672 bytes) and are bundled at 768 x 544 now. This reads the size of every picture under
 * src/main/resources, so a future one that is too large fails here and not in the Hub review.
 */
public class BundledImagesTest
{
	private static final long LIMIT = 4_000_000L;
	private static final Path RESOURCES = new File("src/main/resources").toPath();

	private static boolean isPicture(Path file)
	{
		String name = file.getFileName().toString().toLowerCase();
		return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".bmp");
	}

	@Test
	public void everyBundledPictureDecodesToAtMostFourMillionBytes() throws IOException
	{
		List<Path> pictures;
		try (Stream<Path> walk = Files.walk(RESOURCES))
		{
			pictures = walk.filter(Files::isRegularFile).filter(BundledImagesTest::isPicture).collect(Collectors.toList());
		}
		assertFalse("no pictures found, the test looks in the wrong place", pictures.isEmpty());
		List<String> tooBig = new ArrayList<>();
		for (Path file : pictures)
		{
			try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile()))
			{
				Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
				assertTrue(file + " is not a picture ImageIO can read", readers.hasNext());
				ImageReader reader = readers.next();
				try
				{
					reader.setInput(in);
					long bytes = (long) reader.getWidth(0) * reader.getHeight(0) * 4L;
					if (bytes > LIMIT)
					{
						tooBig.add(RESOURCES.relativize(file) + " " + reader.getWidth(0) + "x" + reader.getHeight(0) + " = " + bytes);
					}
				}
				finally
				{
					reader.dispose();
				}
			}
		}
		assertTrue("pictures over " + LIMIT + " bytes decoded: " + tooBig, tooBig.isEmpty());
	}
}
