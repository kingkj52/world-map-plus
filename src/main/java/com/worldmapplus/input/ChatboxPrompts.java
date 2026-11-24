package com.worldmapplus.input;

import java.awt.Rectangle;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxInput;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

/**
 * The chat area, through RuneLite's own {@link ChatboxPanelManager}. That class builds the prompt and runs the game's script for
 * it. This plugin adds no widget of its own and changes none.
 * <p>
 * {@link ChatboxPanelManager#close()} only schedules a close of whatever prompt is current when it runs. So the prompt that
 * {@code build()} returned is kept, and a close is asked for only from a task on the client thread that first checks that
 * the manager's current input is that very prompt. A prompt another plugin opened in the meantime is left alone.
 */
@Singleton
public class ChatboxPrompts implements ChatSearch.Prompts
{
	private final ChatboxPanelManager manager;
	private final ClientThread clientThread;

	/** The prompt this plugin opened last, or null. Only compared with the manager's current input, never used otherwise. */
	private volatile ChatboxInput opened;

	@Inject
	public ChatboxPrompts(ChatboxPanelManager manager, ClientThread clientThread)
	{
		this.manager = manager;
		this.clientThread = clientThread;
	}

	@Override
	public void open(String title, String text, Consumer<String> onChanged, Consumer<String> onDone, Runnable onClose)
	{
		opened = manager.openTextInput(title)
			.value(text)
			.onChanged(onChanged)
			.onDone(onDone)
			.onClose(onClose)
			.build();
	}

	@Override
	public void close()
	{
		ChatboxInput ours = opened;
		if (ours == null)
		{
			return;
		}
		// Queued behind the open, which the manager also queues, so the prompt is current by the time this runs.
		clientThread.invokeLater(() ->
		{
			if (manager.getCurrentInput() == ours)
			{
				manager.close();
			}
			if (opened == ours)
			{
				opened = null;
			}
		});
	}

	@Override
	public boolean covers(int x, int y)
	{
		Widget container = manager.getContainerWidget();
		if (container == null || container.isHidden())
		{
			return false;
		}
		Rectangle bounds = container.getBounds();
		return bounds != null && bounds.contains(x, y);
	}
}
