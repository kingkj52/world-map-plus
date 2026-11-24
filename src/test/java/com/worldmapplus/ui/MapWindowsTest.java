package com.worldmapplus.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.worldmapplus.MapAssets;
import com.worldmapplus.WorldMapPlusConfig;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.bridge.MapActions;
import com.worldmapplus.game.PlayerLocator;
import com.worldmapplus.pins.PanelLauncher;
import com.worldmapplus.pins.PinStore;
import com.worldmapplus.pins.RouteStore;
import com.worldmapplus.prefs.Settings;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.client.eventbus.EventBus;
import org.junit.Assume;
import org.junit.Test;

/** Opens real windows, so it only runs where there is a screen. */
public class MapWindowsTest
{
	private static void settle() throws Exception
	{
		for (int i = 0; i < 3; i++)
		{
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
	}

	private static MapWindows windows() throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) -> method.getReturnType() == boolean.class ? false
				: method.getReturnType().isPrimitive() ? 0 : null);
		Gson gson = new Gson();
		MapAssets assets = new MapAssets(gson, Executors.newSingleThreadScheduledExecutor());
		PinStore pins = new PinStore(PinStore.Storage.inMemory(), gson);
		RouteStore routes = new RouteStore(PinStore.Storage.inMemory(), gson, pins);
		ExternalOverlays external = new ExternalOverlays();
		return new MapWindows(client, assets, new WorldMapPlusConfig()
		{
		}, pins, routes, external, new MapActions(new EventBus(), external), new Settings(null), new PanelLauncher(),
			new PlayerLocator(client), null);
	}

	@Test
	public void windowsOpenDrawCloseAndAreLimited() throws Exception
	{
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
		MapWindows windows = windows();
		try
		{
			windows.open(0, 3222.5, 3218.5, 6);
			settle();
			assertEquals(1, windows.count());

			windows.open(123456, 1, 1, 4);
			windows.open(0, Double.NaN, 1, 4);
			settle();
			assertEquals("a place that is not on a map opens nothing", 1, windows.count());

			Thread.sleep(500);
			settle();
			assertEquals("the window drew without an error", 0, windows.failures());

			for (int i = 0; i < MapWindows.MAX_WINDOWS + 3; i++)
			{
				windows.open(0, 3200 + i, 3200, 4);
			}
			settle();
			assertEquals(MapWindows.MAX_WINDOWS, windows.count());
		}
		finally
		{
			windows.setEnabled(false);
			settle();
			settle();
		}
		windows.open(0, 3222.5, 3218.5, 6);
		settle();
		assertEquals("nothing opens while the plugin is off", 0, windows.count());
		assertTrue("every window closed", windows.count() == 0);
	}
}
