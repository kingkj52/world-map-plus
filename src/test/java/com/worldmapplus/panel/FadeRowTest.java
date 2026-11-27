package com.worldmapplus.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.worldmapplus.theme.AnimationMode;
import com.worldmapplus.theme.Motion;
import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;

/**
 * Round 71: a side panel row warms with the plugin's one fade, from where it is (the first time too), and settles exactly. Run on the
 * interface thread, so the row's own timer cannot step it between the steps of a test.
 */
public class FadeRowTest
{
	private static final Color IDLE = new Color(43, 39, 33);
	private static final Color LIT = new Color(58, 52, 43);
	private static final Color GLOW = new Color(196, 150, 70);

	@After
	public void animationsOn()
	{
		Motion.use(AnimationMode.ON);
	}

	@Test
	public void warmsUpPartWayThenSettlesOnTheLitColour() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			FadeRow row = new FadeRow(new BorderLayout(), IDLE, LIT, GLOW, null);
			long start = System.currentTimeMillis();
			row.hover(true);
			row.step(start + 60);
			assertNotEquals("the first hover fades too", IDLE, row.getFill());
			assertNotEquals("part way", LIT, row.getFill());
			row.step(start + 1000);
			assertEquals(LIT, row.getFill());
			row.hover(false);
			row.step(start + 2000);
			assertEquals(IDLE, row.getFill());
			row.removeNotify();
		});
	}

	@Test
	public void aGlowFadesAwayAndAnimationsOffJumps() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			FadeRow row = new FadeRow(new BorderLayout(), IDLE, LIT, GLOW, null);
			long start = System.currentTimeMillis();
			row.flash();
			row.step(start + 100);
			assertNotEquals(IDLE, row.getFill());
			row.step(start + 5000);
			assertEquals(IDLE, row.getFill());
			Motion.use(AnimationMode.OFF);
			row.hover(true);
			row.step(start + 5001);
			assertEquals(LIT, row.getFill());
			row.removeNotify();
		});
	}
}
