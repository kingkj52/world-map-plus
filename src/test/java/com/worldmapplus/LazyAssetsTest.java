package com.worldmapplus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.worldmapplus.map.MapCatalog;
import com.worldmapplus.map.MapInfo;
import com.worldmapplus.render.MapRenderer;
import com.worldmapplus.ui.HudOverlay;
import com.worldmapplus.ui.MapWindows;
import com.worldmapplus.ui.ViewerOverlay;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.KeyManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The bundled data is read when the map is first needed, not when the plugin object is built. These tests build the object
 * graph the way RuneLite does for a plugin that is installed but not started, and look at when the data is read, at what the
 * plugin hands to the executor when it starts, and at a stop followed by another start.
 * <p>
 * The plugin's own {@code startUp} is not run here: it needs the client's overlay manager, mouse manager and toolbar, none of
 * which can be built outside the client. {@link #theStartRequestsTheLoadOnTheExecutor()} reads its source instead, and the
 * calls it makes are exercised one by one below.
 */
public class LazyAssetsTest
{
	/** Keeps what is handed to {@code execute}, so the test decides when, and on which thread, it runs. */
	static final class Held extends ScheduledThreadPoolExecutor
	{
		final List<Runnable> held = Collections.synchronizedList(new ArrayList<>());

		Held()
		{
			super(1);
		}

		@Override
		public void execute(Runnable command)
		{
			held.add(command);
		}
	}

	private Held executor;
	private Injector injector;

	@Before
	public void setUp() throws Exception
	{
		executor = new Held();
		Client client = (Client) java.lang.reflect.Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[] {Client.class}, (proxy, method, args) -> null);
		KeyManager keys = Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(Client.class).toInstance(client);
				bind(EventBus.class).toInstance(new EventBus());
			}
		}).getInstance(KeyManager.class);
		injector = InjectionTest.injector(keys, executor);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	@Test
	public void buildingTheObjectGraphReadsNoData() throws Exception
	{
		assertNotNull(injector.getInstance(WorldMapPlusPlugin.class));
		assertNotNull(injector.getInstance(ViewerOverlay.class));
		assertNotNull(injector.getInstance(HudOverlay.class));
		assertNotNull(injector.getInstance(MapWindows.class));
		assertNotNull(injector.getInstance(com.worldmapplus.input.ChatSearch.class));
		MapAssets assets = injector.getInstance(MapAssets.class);
		assertFalse("nothing is parsed while the plugin is only built", assets.isLoaded());
		assertTrue("nothing was handed to the executor either", executor.held.isEmpty());
	}

	@Test
	public void theFirstUseReadsTheDataOnceAndOnlyThen() throws Exception
	{
		MapAssets assets = injector.getInstance(MapAssets.class);
		assertFalse(assets.isLoaded());
		MapCatalog catalog = assets.getCatalog();
		assertTrue(assets.isLoaded());
		assertTrue(catalog.getMaps().size() > 100);
		assertNotNull(catalog.byId(MapInfo.SURFACE));
		assertSame(catalog, assets.getCatalog());
		assertTrue("reading the data needs no executor task", executor.held.isEmpty());
	}

	@Test
	public void preloadAsksTheExecutorAndTheTaskReadsTheData() throws Exception
	{
		MapAssets assets = injector.getInstance(MapAssets.class);
		assets.preload();
		assertFalse("preload does not read on the calling thread", assets.isLoaded());
		assertEquals(1, executor.held.size());
		executor.held.get(0).run();
		assertTrue(assets.isLoaded());
		assets.preload();
		assertEquals("a second preload finds the data read and asks for nothing", 1, executor.held.size());
	}

	@Test
	public void startStopStartKeepsTheDataAndLetsGoOfTheTiles() throws Exception
	{
		MapAssets assets = injector.getInstance(MapAssets.class);

		// start
		assets.preload();
		executor.held.get(0).run();
		MapCatalog catalog = assets.getCatalog();
		MapRenderer renderer = assets.getRenderer();
		assertTrue(assets.getPoiCategories().size() > 0);
		assertTrue(assets.getSearchEntries().size() > 1000);

		// stop
		assets.release();
		assertEquals("stopping lets go of the decoded tiles", 0, assets.getTiles().pendingCount());
		assertTrue("the parsed data stays, so another start costs nothing", assets.isLoaded());

		// start again
		assets.preload();
		assertEquals("the second start asks for nothing more", 1, executor.held.size());
		assertSame(catalog, assets.getCatalog());
		assertSame(renderer, assets.getRenderer());
		assertNotNull(assets.getTiles().getSource());

		// stop and start once more
		assets.release();
		assets.preload();
		assertSame(catalog, assets.getCatalog());
	}

	@Test
	public void stoppingBeforeTheDataWasEverReadIsHarmless() throws Exception
	{
		MapAssets assets = new MapAssets(new Gson(), executor);
		assets.release();
		assets.release();
		assertFalse(assets.isLoaded());
		assertTrue(executor.held.isEmpty());
	}

	@Test
	public void threadsThatNeedTheDataTogetherShareOneLoad() throws Exception
	{
		MapAssets assets = new MapAssets(new Gson(), executor);
		int threads = 4;
		CountDownLatch go = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		Object[] seen = new Object[threads];
		for (int i = 0; i < threads; i++)
		{
			int at = i;
			Thread thread = new Thread(() ->
			{
				try
				{
					go.await();
					seen[at] = assets.getCatalog();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
				finally
				{
					done.countDown();
				}
			});
			thread.setDaemon(true);
			thread.start();
		}
		go.countDown();
		assertTrue(done.await(120, TimeUnit.SECONDS));
		assertNotNull(seen[0]);
		for (int i = 1; i < threads; i++)
		{
			assertSame(seen[0], seen[i]);
		}
	}

	@Test
	public void theStartRequestsTheLoadOnTheExecutorAndNothingInItReadsTheMaps() throws IOException
	{
		String source = new String(Files.readAllBytes(Paths.get("src/main/java/com/worldmapplus/WorldMapPlusPlugin.java")),
			StandardCharsets.UTF_8);
		int from = source.indexOf("	private void start()");
		int to = source.indexOf("	void registerKeys()");
		assertTrue(from > 0 && to > from);
		String start = source.substring(from, to);
		assertTrue("start asks for the load to be run on the executor", start.contains("assets.preload();"));
		assertNull("start must not touch a getter that loads the data", firstGetter(start));
		String tick = source.substring(source.indexOf("public void onGameTick"), source.indexOf("public void onGameStateChanged"));
		assertTrue("a game tick does not wait for the data", tick.contains("assets.isLoaded()"));
	}

	/** The first call on the assets that would read the data, or null. A method reference is not a call. */
	private static String firstGetter(String code)
	{
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("assets(\\.|::)(\\w+)").matcher(code);
		while (m.find())
		{
			String name = m.group(2);
			boolean reference = m.group(1).equals("::");
			if (!reference && !name.equals("preload") && !name.equals("isLoaded") && !name.equals("release")
				&& !name.equals("setUnlocks") && !name.equals("setPrices"))
			{
				return name;
			}
		}
		return null;
	}
}
