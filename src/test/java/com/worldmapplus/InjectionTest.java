package com.worldmapplus;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.worldmapplus.bridge.ExternalOverlays;
import com.worldmapplus.game.PlayerLocator;
import com.worldmapplus.ui.HudOverlay;
import com.worldmapplus.ui.HudState;
import com.worldmapplus.ui.ViewerOverlay;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;

/**
 * Builds the plugin's object graph with the same kind of injector RuneLite uses, with only the
 * game's own services stubbed. A missing annotation, an unbound type or a cycle fails here rather
 * than when the plugin starts inside the client.
 */
public class InjectionTest
{
	@SuppressWarnings("unchecked")
	private static <T> T withoutConstructor(Class<T> type) throws Exception
	{
		Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		field.setAccessible(true);
		Object unsafe = field.get(null);
		return (T) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
	}

	private static Injector injector() throws Exception
	{
		return injector(withoutConstructor(KeyManager.class));
	}

	/** The same graph with the key manager the caller supplies, so a test can use a real one. */
	static Injector injector(KeyManager keyManager) throws Exception
	{
		return injector(keyManager, Executors.newSingleThreadScheduledExecutor());
	}

	/** The same graph with the executor the caller supplies, so a test can see what is handed to it. */
	static Injector injector(KeyManager keyManager, ScheduledExecutorService executor) throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[] {Client.class},
			(proxy, method, args) -> null);
		ConfigManager configManager = withoutConstructor(ConfigManager.class);
		OverlayManager overlayManager = withoutConstructor(OverlayManager.class);
		MouseManager mouseManager = withoutConstructor(MouseManager.class);
		PluginManager pluginManager = withoutConstructor(PluginManager.class);
		net.runelite.client.ui.ClientToolbar clientToolbar = withoutConstructor(net.runelite.client.ui.ClientToolbar.class);
		net.runelite.client.callback.ClientThread clientThread = withoutConstructor(net.runelite.client.callback.ClientThread.class);
		net.runelite.client.game.ItemManager itemManager = withoutConstructor(net.runelite.client.game.ItemManager.class);
		net.runelite.client.chat.ChatCommandManager chatCommands = withoutConstructor(net.runelite.client.chat.ChatCommandManager.class);

		return Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(Client.class).toInstance(client);
				bind(ConfigManager.class).toInstance(configManager);
				bind(OverlayManager.class).toInstance(overlayManager);
				bind(MouseManager.class).toInstance(mouseManager);
				bind(KeyManager.class).toInstance(keyManager);
				bind(PluginManager.class).toInstance(pluginManager);
				bind(net.runelite.client.ui.ClientToolbar.class).toInstance(clientToolbar);
				bind(net.runelite.client.callback.ClientThread.class).toInstance(clientThread);
				bind(net.runelite.client.game.ItemManager.class).toInstance(itemManager);
				bind(net.runelite.client.chat.ChatCommandManager.class).toInstance(chatCommands);
				bind(EventBus.class).toInstance(new EventBus());
				bind(Gson.class).toInstance(new Gson());
				bind(ScheduledExecutorService.class).toInstance(executor);
			}

			@Provides
			WorldMapPlusConfig config()
			{
				return new WorldMapPlusConfig()
				{
				};
			}
		});
	}

	@Test
	public void everyInjectedClassCanBeBuilt() throws Exception
	{
		Injector injector = injector();
		assertNotNull(injector.getInstance(MapAssets.class));
		assertNotNull(injector.getInstance(PlayerLocator.class));
		assertNotNull(injector.getInstance(ViewerOverlay.class));
		assertNotNull(injector.getInstance(HudOverlay.class));
		assertNotNull(injector.getInstance(HudState.class));
		assertNotNull(injector.getInstance(ExternalOverlays.class));
		assertNotNull(injector.getInstance(com.worldmapplus.pins.RouteRunner.class));
		assertNotNull(injector.getInstance(com.worldmapplus.pins.PanelLauncher.class));
	}

	@Test
	public void sharedServicesAreSingletons() throws Exception
	{
		Injector injector = injector();
		assertSame(injector.getInstance(MapAssets.class), injector.getInstance(MapAssets.class));
		assertSame(injector.getInstance(ExternalOverlays.class), injector.getInstance(ExternalOverlays.class));
		assertSame(injector.getInstance(HudState.class), injector.getInstance(HudState.class));
		assertSame(injector.getInstance(ViewerOverlay.class), injector.getInstance(ViewerOverlay.class));
		assertSame(injector.getInstance(com.worldmapplus.pins.RouteStore.class), injector.getInstance(com.worldmapplus.pins.RouteStore.class));
		assertSame(injector.getInstance(com.worldmapplus.pins.RouteRunner.class), injector.getInstance(com.worldmapplus.pins.RouteRunner.class));
	}

	@Test
	public void thePluginClassCanBeInjected() throws Exception
	{
		Injector injector = injector();
		WorldMapPlusPlugin plugin = injector.getInstance(WorldMapPlusPlugin.class);
		assertNotNull(plugin);
	}

	@Test
	public void theTwoOverlaysHaveTheirOwnInput() throws Exception
	{
		Injector injector = injector();
		ViewerOverlay viewer = injector.getInstance(ViewerOverlay.class);
		HudOverlay hud = injector.getInstance(HudOverlay.class);
		assertNotNull(viewer.getInput());
		assertNotNull(hud.getInput());
		org.junit.Assert.assertNotSame(viewer.getInput(), hud.getInput());
	}

	@Test
	public void theWindowPartsCanBeBuilt() throws Exception
	{
		Injector injector = injector();
		assertNotNull(injector.getInstance(com.worldmapplus.ui.MapWindows.class));
	}
}
