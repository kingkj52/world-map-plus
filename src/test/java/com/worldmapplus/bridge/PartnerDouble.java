package com.worldmapplus.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.events.PluginMessage;

/**
 * A test double for a partner plugin: it talks to the main plugin only through plugin messages, as a real partner would, so no
 * class of either side is shared. It registers one of each kind of contribution.
 */
public final class PartnerDouble
{
	private final ExternalOverlays main;
	private final String owner;

	public PartnerDouble(ExternalOverlays main, String owner)
	{
		this.main = main;
		this.owner = owner;
	}

	public void send(String name, Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("owner", owner);
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		main.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, name, data));
	}

	public void hello(String role)
	{
		send("partner_hello", "role", role, "apiVersion", 3);
	}

	/** Asks a question of a topic (an id among the key values gets a reply). */
	public void request(String topic, Object... keyValues)
	{
		send("request", with("topic", topic, keyValues));
	}

	private int seq;

	/** Asks a question and lets a client tick handle it at once, for tests of the map that do not tick. */
	public void ask(String topic, Object... keyValues)
	{
		request(topic, keyValues);
		main.partners().answerAsks(System.currentTimeMillis());
	}

	private static int posted;

	/** Posts a request or a state of a topic from a partner named "test", with its data, and lets a client tick handle it. */
	public static void post(ExternalOverlays main, String name, String topic, Map<String, Object> data)
	{
		Map<String, Object> all = new HashMap<>(data);
		// A few owners in turn, so a test that asks many times is not held by the rate of one partner.
		all.put("owner", "test" + posted % 4);
		all.put("topic", topic);
		all.put("seq", ++posted);
		main.onPluginMessage(new PluginMessage(ExternalOverlays.NAMESPACE, name, all));
		main.partners().answerAsks(System.currentTimeMillis());
	}

	/** Pushes a state of a topic with the next sequence number. */
	public void state(String topic, Object... keyValues)
	{
		send("state", with("topic", topic, with("seq", ++seq, keyValues)));
	}

	private static Object[] with(String key, Object value, Object[] more)
	{
		Object[] all = new Object[more.length + 2];
		all[0] = key;
		all[1] = value;
		System.arraycopy(more, 0, all, 2, more.length);
		return all;
	}

	public void register(String kind, String id, int order, Object call, Object... more)
	{
		Object[] all = new Object[more.length + 8];
		all[0] = "kind";
		all[1] = kind;
		all[2] = "id";
		all[3] = id;
		all[4] = "order";
		all[5] = order;
		all[6] = "call";
		all[7] = call;
		System.arraycopy(more, 0, all, 8, more.length);
		send("register", all);
	}

	public void goodbye()
	{
		send("partner_goodbye");
	}

	/** What the main plugin has queued to say, taken out, after it answered the questions that wait (as on a client tick). */
	public List<ExternalOverlays.Reply> heard()
	{
		main.partners().answerAsks(System.currentTimeMillis());
		return main.drainReplies(System.currentTimeMillis());
	}

	/** The replies heard, by request id. */
	public static Map<String, Object> reply(List<ExternalOverlays.Reply> heard, String id)
	{
		for (ExternalOverlays.Reply reply : heard)
		{
			if ("reply".equals(reply.getName()) && id.equals(reply.getData().get("id")))
			{
				return reply.getData();
			}
		}
		return null;
	}

	/** The last state pushed of a topic among those heard, or null. */
	public static Map<String, Object> pushed(List<ExternalOverlays.Reply> heard, String topic)
	{
		Map<String, Object> last = null;
		for (ExternalOverlays.Reply reply : heard)
		{
			if ("state".equals(reply.getName()) && topic.equals(reply.getData().get("topic")))
			{
				last = reply.getData();
			}
		}
		return last;
	}

	public static Map<String, Object> data(Object... keyValues)
	{
		Map<String, Object> data = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2)
		{
			data.put((String) keyValues[i], keyValues[i + 1]);
		}
		return data;
	}
}
