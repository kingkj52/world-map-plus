package com.worldmapplus.apiexample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * The copy-paste helper of Plugin API version 1 in docs/api is the file this package holds. The Helper plugin's own tests run it
 * against the translator that answers version 1.
 */
public class WorldMapPlusApiTest
{
	@Test
	public void theDocumentCopyIsTheSameFileWithItsOwnPackageLine() throws Exception
	{
		File doc = new File("docs/api/WorldMapPlusApi.java");
		assumeTrue("the docs are left out of the published copy", doc.exists());
		String docText = new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String ours = new String(Files.readAllBytes(new File("src/test/java/com/worldmapplus/apiexample/WorldMapPlusApi.java").toPath()),
			StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertTrue(docText.startsWith("package com.example.yourplugin;\n"));
		assertEquals(ours.replaceFirst("package com\\.worldmapplus\\.apiexample;", "package com.example.yourplugin;"), docText);
	}
}
