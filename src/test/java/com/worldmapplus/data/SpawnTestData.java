package com.worldmapplus.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** The bundled spawns.bin as the JSON object the old spawns.json held, for tests that look through its records. */
public final class SpawnTestData
{
	private SpawnTestData()
	{
	}

	public static JsonObject json()
	{
		SpawnLoader.SpawnFile file = SpawnFormat.bundled();
		if (file == null)
		{
			throw new IllegalStateException("spawns.bin is missing or damaged");
		}
		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		return gson.toJsonTree(file).getAsJsonObject();
	}
}
