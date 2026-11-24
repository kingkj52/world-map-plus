package com.worldmapplus.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class PositionsBoatTest
{
	private interface Answer
	{
		Object to(Object[] args);
	}

	/** An object of an API interface that answers a few calls and nothing else. */
	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, String[] names, Answer[] answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
		{
			for (int i = 0; i < names.length; i++)
			{
				if (names[i].equals(method.getName()))
				{
					return answers[i].to(args);
				}
			}
			Class<?> returns = method.getReturnType();
			if (returns == boolean.class)
			{
				return false;
			}
			if (returns == int.class || returns == long.class || returns == double.class || returns == float.class)
			{
				return 0;
			}
			return null;
		});
	}

	private static WorldView mainView()
	{
		WorldEntity[] boats = new WorldEntity[1];
		return stub(WorldView.class,
			new String[] {"isTopLevel", "getBaseX", "getBaseY", "getId", "worldEntities", "isInstance"},
			new Answer[] {a -> true, a -> 3200, a -> 3200, a -> -1, a -> stub(IndexedObjectSet.class,
				new String[] {"byIndex"}, new Answer[] {b -> (int) b[0] == 7 ? boats[0] : null}), a -> false});
	}

	private static Client client(WorldView main, WorldEntity boat)
	{
		WorldView top = stub(WorldView.class,
			new String[] {"isTopLevel", "getBaseX", "getBaseY", "getId", "worldEntities", "isInstance"},
			new Answer[] {a -> true, a -> 3200, a -> 3200, a -> -1, a -> stub(IndexedObjectSet.class,
				new String[] {"byIndex"}, new Answer[] {b -> (int) b[0] == 7 ? boat : null}), a -> false});
		return stub(Client.class, new String[] {"getTopLevelWorldView", "getWorldView"},
			new Answer[] {a -> top, a -> top});
	}

	private static WorldView boatView()
	{
		return stub(WorldView.class, new String[] {"isTopLevel", "getId", "getPlane"}, new Answer[] {a -> false, a -> 7, a -> 0});
	}

	private static Player player(WorldView view, LocalPoint local, WorldPoint boatWorld)
	{
		return stub(Player.class, new String[] {"getWorldView", "getLocalLocation", "getWorldLocation"},
			new Answer[] {a -> view, a -> local, a -> boatWorld});
	}

	@Test
	public void aPlayerOnABoatIsWhereTheBoatPutsThemInTheMainWorld()
	{
		WorldEntity boat = stub(WorldEntity.class, new String[] {"transformToMainWorld"},
			new Answer[] {a -> new LocalPoint(10 * 128 + 32, 20 * 128 + 96)});
		Client client = client(mainView(), boat);
		// In the boat's own scene the player is at a small coordinate that means nothing on the map.
		Player aboard = player(boatView(), new LocalPoint(3 * 128, 4 * 128), new WorldPoint(3, 4, 0));
		PlayerPosition at = Positions.of(client, aboard);
		assertNotNull(at);
		assertEquals(3210, at.getTile().getX());
		assertEquals(3220, at.getTile().getY());
		assertEquals(3210.25, at.getX(), 1e-9);
		assertEquals(3220.75, at.getY(), 1e-9);
	}

	@Test
	public void aBoatTheClientDoesNotKnowFallsBackToTheTileCentre()
	{
		Client client = client(mainView(), null);
		Player aboard = player(boatView(), new LocalPoint(3 * 128, 4 * 128), new WorldPoint(3, 4, 0));
		PlayerPosition at = Positions.of(client, aboard);
		assertNotNull(at);
		assertEquals(3.5, at.getX(), 1e-9);
		assertEquals(4.5, at.getY(), 1e-9);
	}

	@Test
	public void aPlayerOnTheDeckIsOnTheGroundFloorOfTheMap()
	{
		WorldEntity boat = stub(WorldEntity.class, new String[] {"transformToMainWorld"},
			new Answer[] {a -> new LocalPoint(10 * 128 + 32, 20 * 128 + 96)});
		// The boat's own scene puts its deck on floor 1.
		WorldView deck = stub(WorldView.class, new String[] {"isTopLevel", "getId", "getPlane"}, new Answer[] {a -> false, a -> 7, a -> 1});
		PlayerPosition at = Positions.of(client(mainView(), boat), player(deck, new LocalPoint(3 * 128, 4 * 128), new WorldPoint(3, 4, 1)));
		assertNotNull(at);
		assertEquals(0, at.getTile().getPlane());

		PlayerPosition unknown = Positions.of(client(mainView(), null), player(deck, new LocalPoint(3 * 128, 4 * 128), new WorldPoint(3, 4, 1)));
		assertNotNull(unknown);
		assertEquals(0, unknown.getTile().getPlane());
	}

	@Test
	public void noPlayerIsNoPosition()
	{
		assertNull(Positions.of(client(mainView(), null), null));
	}
}
