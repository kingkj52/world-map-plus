package com.worldmapplus.layers;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import org.junit.Assume;
import org.junit.Test;

/** What the drawing calls themselves allocate, to tell the layer's own garbage from Java2D's. Runs only when WORLDMAPPLUS_PERF is set. */
public class Java2dAllocProbeTest
{
	@Test
	public void callsAllocate()
	{
		Assume.assumeTrue(System.getenv("WORLDMAPPLUS_PERF") != null);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		BufferedImage canvas = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		Ellipse2D.Double disc = new Ellipse2D.Double();
		int calls = 20000;
		for (int round = 0; round < 3; round++)
		{
			long a = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
			for (int i = 0; i < calls; i++)
			{
				g.drawImage(icon, i % 1200, i % 700, 12, 12, null);
			}
			long b = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
			for (int i = 0; i < calls; i++)
			{
				g.drawImage(icon, i % 1200, i % 700, null);
			}
			long c = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
			for (int i = 0; i < calls; i++)
			{
				disc.setFrame(i % 1200, i % 700, 12, 12);
				g.fill(disc);
			}
			long d = threads.getThreadAllocatedBytes(Thread.currentThread().getId());
			System.out.printf("a scaled drawImage allocates %d bytes, a plain one %d, a fill of an ellipse %d%n", (b - a) / calls,
				(c - b) / calls, (d - c) / calls);
		}
		g.dispose();
	}
}
