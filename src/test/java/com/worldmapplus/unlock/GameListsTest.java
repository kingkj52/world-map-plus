package com.worldmapplus.unlock;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.Assume;
import org.junit.Test;

/**
 * Since round 70 the build tools turn the requirement words into checks that name the game's quests and skills
 * ({@code tools/requirement_checks.py}), so they need RuneLite's lists: {@code tools/input/game-lists.json}. This keeps that file equal
 * to the {@link Quest} and {@link Skill} of the RuneLite this plugin is built against. After a RuneLite update that changes them, the
 * list as it is now is in {@code build/game-lists-actual.json}: copy it over the input and run {@code tools/update.py refresh --only
 * cards} (the cards and the search are written again).
 */
public class GameListsTest
{
	static String lists()
	{
		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		List<String> quests = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			quests.add(gson.toJson(new String[] {quest.name(), quest.getName()}));
		}
		List<String> skills = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			skills.add(gson.toJson(new String[] {skill.name(), skill.getName()}));
		}
		return "{\"quests\": [\n" + String.join(",\n", quests) + "\n],\n\"skills\": [\n" + String.join(",\n", skills) + "\n]}\n";
	}

	@Test
	public void theToolsKnowTheQuestsAndSkillsOfThisRuneLite() throws IOException
	{
		String text = lists();
		Path actual = Paths.get("build", "game-lists-actual.json");
		Files.createDirectories(actual.getParent());
		Files.write(actual, text.getBytes(StandardCharsets.UTF_8));
		Path input = Paths.get("tools", "input", "game-lists.json");
		// The published copy leaves the tools and their inputs out.
		Assume.assumeTrue("tools/input is not in this copy", Files.isDirectory(input.getParent()));
		assertEquals("tools/input/game-lists.json is RuneLite's list of quests and skills",
			text, new String(Files.readAllBytes(input), StandardCharsets.UTF_8).replace("\r\n", "\n"));
	}
}
