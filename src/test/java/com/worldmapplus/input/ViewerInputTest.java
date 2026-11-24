package com.worldmapplus.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import javax.swing.JPanel;
import org.junit.Test;

public class ViewerInputTest
{
	private static final JPanel SOURCE = new JPanel();
	private static final Rectangle AREA = new Rectangle(100, 100, 400, 300);

	private static MouseEvent mouse(int id, int x, int y, int button)
	{
		return new MouseEvent(SOURCE, id, 1000, 0, x, y, 1, false, button);
	}

	private static MouseWheelEvent wheel(int x, int y, int rotation)
	{
		return new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, 1000, 0, x, y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
	}

	private static ViewerInput active()
	{
		ViewerInput input = new ViewerInput();
		input.setActive(AREA);
		return input;
	}

	@Test
	public void nothingIsConsumedWhileInactive()
	{
		ViewerInput input = new ViewerInput();
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertFalse(press.isConsumed());
		MouseWheelEvent wheel = wheel(200, 200, -1);
		input.mouseWheelMoved(wheel);
		assertFalse(wheel.isConsumed());
		assertTrue(input.getQueue().drain().isEmpty());
	}

	@Test
	public void aPressInsideIsConsumedAndQueued()
	{
		ViewerInput input = active();
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertTrue(press.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(1, events.size());
		assertEquals(MapInput.Kind.PRESS, events.get(0).kind);
		assertEquals(200, events.get(0).x);
		assertEquals(MouseEvent.BUTTON1, events.get(0).button());
	}

	@Test
	public void aPressOutsideIsLeftAlone()
	{
		ViewerInput input = active();
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 50, 50, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertFalse(press.isConsumed());
		List<MapInput> noted = input.getQueue().drain();
		assertEquals("only noted, for the map key", 1, noted.size());
		assertEquals(MapInput.Kind.GAME_PRESS, noted.get(0).kind);
	}

	@Test
	public void aDragThatLeavesTheMapIsStillConsumedUntilRelease()
	{
		ViewerInput input = active();
		input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1));
		MouseEvent drag = mouse(MouseEvent.MOUSE_DRAGGED, 5, 5, MouseEvent.NOBUTTON);
		input.mouseDragged(drag);
		assertTrue(drag.isConsumed());
		MouseEvent release = mouse(MouseEvent.MOUSE_RELEASED, 5, 5, MouseEvent.BUTTON1);
		input.mouseReleased(release);
		assertTrue(release.isConsumed());

		MouseEvent later = mouse(MouseEvent.MOUSE_DRAGGED, 5, 5, MouseEvent.NOBUTTON);
		input.mouseDragged(later);
		assertFalse(later.isConsumed());
	}

	@Test
	public void aDragThatNeverStartedInsideIsIgnored()
	{
		ViewerInput input = active();
		MouseEvent drag = mouse(MouseEvent.MOUSE_DRAGGED, 200, 200, MouseEvent.NOBUTTON);
		input.mouseDragged(drag);
		assertFalse(drag.isConsumed());
		assertTrue(input.getQueue().drain().isEmpty());
	}

	@Test
	public void theWheelIsConsumedOnlyInsideTheMap()
	{
		ViewerInput input = active();
		MouseWheelEvent inside = wheel(300, 300, -1);
		input.mouseWheelMoved(inside);
		assertTrue(inside.isConsumed());
		MouseWheelEvent outside = wheel(10, 10, -1);
		input.mouseWheelMoved(outside);
		assertFalse(outside.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(1, events.size());
		assertEquals(MapInput.Kind.WHEEL, events.get(0).kind);
		assertEquals(-1.0, events.get(0).amount, 1e-9);
	}

	@Test
	public void passThroughRecordsButNeverConsumes()
	{
		ViewerInput input = active();
		input.setPassThrough(true);
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1);
		input.mousePressed(press);
		MouseWheelEvent wheel = wheel(200, 200, 1);
		input.mouseWheelMoved(wheel);
		assertFalse(press.isConsumed());
		assertFalse(wheel.isConsumed());
		assertEquals(2, input.getQueue().drain().size());
	}

	@Test
	public void deactivatingForgetsAPressInProgress()
	{
		ViewerInput input = active();
		input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1));
		input.setActive(null);
		input.setActive(AREA);
		MouseEvent drag = mouse(MouseEvent.MOUSE_DRAGGED, 200, 200, MouseEvent.NOBUTTON);
		input.mouseDragged(drag);
		assertFalse(drag.isConsumed());
	}

	@Test
	public void theQueueIsBounded()
	{
		InputQueue queue = new InputQueue();
		for (int i = 0; i < InputQueue.LIMIT * 3; i++)
		{
			queue.add(MapInput.drag(i, 0, i));
		}
		List<MapInput> events = queue.drain();
		assertTrue(events.size() <= InputQueue.LIMIT + 1);
		assertEquals(InputQueue.LIMIT * 3 - 1, events.get(events.size() - 1).x);
		assertTrue(queue.drain().isEmpty());
	}

	private static KeyEvent key(int id, int code, char c, int modifiers)
	{
		return new KeyEvent(SOURCE, id, 1000, modifiers, code, c);
	}

	@Test
	public void keysPassThroughUnlessTheSearchFieldHasTheKeyboard()
	{
		ViewerInput input = active();
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'a', 0);
		input.keyTyped(typed);
		KeyEvent pressed = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a', 0);
		input.keyPressed(pressed);
		assertFalse(typed.isConsumed());
		assertFalse(pressed.isConsumed());
		assertTrue(input.getQueue().drain().isEmpty());
	}

	@Test
	public void whileCapturingKeysAreQueuedAndConsumed()
	{
		ViewerInput input = active();
		input.setCaptureKeys(true);
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'q', 0);
		input.keyTyped(typed);
		KeyEvent pressed = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_BACK_SPACE, '', 0);
		input.keyPressed(pressed);
		KeyEvent released = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_BACK_SPACE, '', 0);
		input.keyReleased(released);
		assertTrue(typed.isConsumed());
		assertTrue(pressed.isConsumed());
		assertTrue(released.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(2, events.size());
		assertEquals(MapInput.Kind.TYPED, events.get(0).kind);
		assertEquals('q', events.get(0).character());
		assertEquals(MapInput.Kind.KEY, events.get(1).kind);
		assertEquals(KeyEvent.VK_BACK_SPACE, events.get(1).keyCode());
	}

	@Test
	public void controlFStartsASearchOnlyWhileTheMapShows()
	{
		ViewerInput showing = active();
		KeyEvent chord = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		showing.keyPressed(chord);
		assertTrue(chord.isConsumed());
		List<MapInput> events = showing.getQueue().drain();
		assertEquals(1, events.size());
		assertEquals(ViewerInput.KEY_FOCUS_SEARCH, events.get(0).keyCode());

		ViewerInput hidden = new ViewerInput();
		KeyEvent other = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		hidden.keyPressed(other);
		assertFalse(other.isConsumed());
		assertTrue(hidden.getQueue().drain().isEmpty());
	}

	@Test
	public void shiftIsTrackedButNeverTakenWhenNotCapturing()
	{
		ViewerInput input = active();
		KeyEvent down = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.SHIFT_DOWN_MASK);
		input.keyPressed(down);
		assertTrue(input.isExpandHeld());
		assertFalse(down.isConsumed());
		input.keyReleased(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, 0));
		assertFalse(input.isExpandHeld());
	}

	@Test
	public void aClaimLapsesWhenTheOverlayStopsRenewingIt()
	{
		long[] time = {1_000_000_000L};
		ViewerInput input = new ViewerInput();
		input.setClock(() -> time[0]);
		input.setActive(AREA);

		MouseEvent early = mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1);
		input.mousePressed(early);
		assertTrue(early.isConsumed());
		input.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, 200, 200, MouseEvent.BUTTON1));

		// The overlay is no longer drawn, as when its interface closes, and nothing renews the claim.
		time[0] += ViewerInput.FRESH_NANOS + 1;
		MouseEvent late = mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1);
		input.mousePressed(late);
		assertFalse(late.isConsumed());
		MouseWheelEvent wheel = wheel(200, 200, -1);
		input.mouseWheelMoved(wheel);
		assertFalse(wheel.isConsumed());
	}

	@Test
	public void renewingTheClaimKeepsInputTaken()
	{
		long[] time = {1_000_000_000L};
		ViewerInput input = new ViewerInput();
		input.setClock(() -> time[0]);
		for (int frame = 0; frame < 100; frame++)
		{
			time[0] += 16_000_000L;
			input.setActive(AREA);
		}
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 200, 200, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertTrue(press.isConsumed());
	}

	@Test
	public void captureOfTheKeyboardLapsesToo()
	{
		long[] time = {1_000_000_000L};
		ViewerInput input = new ViewerInput();
		input.setClock(() -> time[0]);
		input.setActive(AREA);
		input.setCaptureKeys(true);

		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'a', 0);
		input.keyTyped(typed);
		assertTrue(typed.isConsumed());

		time[0] += ViewerInput.FRESH_NANOS + 1;
		KeyEvent later = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'b', 0);
		input.keyTyped(later);
		assertFalse("a closed map must not keep eating keys", later.isConsumed());
		KeyEvent chord = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		input.keyPressed(chord);
		assertFalse(chord.isConsumed());
	}

	@Test
	public void aPressOutsideEndsTheSearchButStillGoesToTheGame()
	{
		ViewerInput input = active();
		input.setCaptureKeys(true);
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 20, 20, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertFalse("the click is for the game", press.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(2, events.size());
		assertEquals(MapInput.Kind.GAME_PRESS, events.get(0).kind);
		assertEquals(MapInput.Kind.OUTSIDE_PRESS, events.get(1).kind);
	}

	@Test
	public void aPressOutsideWhileNotSearchingOnlyNotesTheKeyClick()
	{
		ViewerInput input = active();
		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 20, 20, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertFalse(press.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(1, events.size());
		assertEquals(MapInput.Kind.GAME_PRESS, events.get(0).kind);
	}

	@Test
	public void whileTheSearchIsTypedInTheChatAreaNoKeyIsTakenButAClickAwayIsReportedWithWhereItWas()
	{
		ViewerInput input = active();
		input.setWatchClicks(true);

		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'w', 0);
		input.keyTyped(typed);
		KeyEvent pressed = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 'w', 0);
		input.keyPressed(pressed);
		KeyEvent released = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_W, 'w', 0);
		input.keyReleased(released);
		assertFalse("the chat area takes the letter itself", typed.isConsumed());
		assertFalse(pressed.isConsumed());
		assertFalse(released.isConsumed());
		assertTrue("nothing is queued for the map's own field", input.getQueue().drain().isEmpty());

		MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, 20, 30, MouseEvent.BUTTON1);
		input.mousePressed(press);
		assertFalse("the click is for the game", press.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(2, events.size());
		assertEquals(MapInput.Kind.OUTSIDE_PRESS, events.get(1).kind);
		assertEquals(20, events.get(1).x);
		assertEquals(30, events.get(1).y);
	}

	@Test
	public void watchingClicksStopsWhenAskedAndLapsesLikeTheOtherClaims()
	{
		long[] time = {0};
		ViewerInput input = active();
		input.setClock(() -> time[0]);
		input.setActive(AREA);
		input.setWatchClicks(true);
		input.setWatchClicks(false);
		input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 20, 20, MouseEvent.BUTTON1));
		assertEquals("only the note for the map key", 1, input.getQueue().drain().size());

		input.setWatchClicks(true);
		time[0] += ViewerInput.FRESH_NANOS + 1;
		input.setActive(AREA);
		input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, 20, 20, MouseEvent.BUTTON1));
		assertEquals("a map that stopped drawing reports nothing more", 1, input.getQueue().drain().size());
	}

	@Test
	public void theGamesOwnControlsKeepTheirClicks()
	{
		ViewerInput input = active();
		input.setKeepClear(java.util.Arrays.asList(new Rectangle(450, 100, 50, 40)));
		MouseEvent onClose = mouse(MouseEvent.MOUSE_PRESSED, 470, 120, MouseEvent.BUTTON1);
		input.mousePressed(onClose);
		assertFalse("the game's close button gets its click", onClose.isConsumed());
		List<MapInput> noted = input.getQueue().drain();
		assertEquals("the click is only noted", 1, noted.size());
		assertEquals(MapInput.Kind.GAME_PRESS, noted.get(0).kind);
		assertEquals(470, noted.get(0).x);
		MouseEvent elsewhere = mouse(MouseEvent.MOUSE_PRESSED, 300, 200, MouseEvent.BUTTON1);
		input.mousePressed(elsewhere);
		assertTrue(elsewhere.isConsumed());
		MouseWheelEvent wheel = wheel(470, 120, -1);
		input.mouseWheelMoved(wheel);
		assertFalse(wheel.isConsumed());
	}

	@Test
	public void controlAWhileTypingSelectsAllTheText()
	{
		ViewerInput input = active();
		input.setCaptureKeys(true);
		KeyEvent chord = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a', KeyEvent.CTRL_DOWN_MASK);
		input.keyPressed(chord);
		assertTrue(chord.isConsumed());
		List<MapInput> events = input.getQueue().drain();
		assertEquals(1, events.size());
		assertEquals(ViewerInput.KEY_SELECT_ALL, events.get(0).keyCode());

		KeyEvent plain = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a', 0);
		input.keyPressed(plain);
		assertEquals(KeyEvent.VK_A, input.getQueue().drain().get(0).keyCode());
	}

	@Test
	public void aReleaseIsTakenOnlyWhenItsPressWas()
	{
		ViewerInput input = active();
		// Held before the search began, so the game saw it go down.
		input.setCaptureKeys(true);
		KeyEvent heldUp = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_W, 'w', 0);
		input.keyReleased(heldUp);
		assertFalse(heldUp.isConsumed());

		// Pressed during the search, so the game never saw it.
		input.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_E, 'e', 0));
		input.setCaptureKeys(false);
		KeyEvent up = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_E, 'e', 0);
		input.keyReleased(up);
		assertTrue(up.isConsumed());
		KeyEvent again = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_E, 'e', 0);
		input.keyReleased(again);
		assertFalse("one press is paired with one release", again.isConsumed());
	}

	@Test
	public void losingFocusForgetsHeldKeysAndShift()
	{
		ViewerInput input = active();
		input.setCaptureKeys(true);
		input.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.SHIFT_DOWN_MASK));
		assertTrue(input.isExpandHeld());
		input.focusLost();
		assertFalse(input.isExpandHeld());
		KeyEvent up = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, 0);
		input.keyReleased(up);
		assertFalse(up.isConsumed());
	}

	@Test
	public void controlFTakesItsOwnKeyUpButNotControls()
	{
		ViewerInput input = active();
		KeyEvent chord = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		input.keyPressed(chord);
		assertTrue(chord.isConsumed());
		KeyEvent controlUp = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED, 0);
		input.keyReleased(controlUp);
		assertFalse(controlUp.isConsumed());
		KeyEvent fUp = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_F, 'f', 0);
		input.keyReleased(fUp);
		assertTrue(fUp.isConsumed());
	}

	@Test
	public void controlFTakesTheControlCharacterItTypesToo()
	{
		ViewerInput input = active();
		KeyEvent chord = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		input.keyPressed(chord);
		assertTrue(chord.isConsumed());
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '', KeyEvent.CTRL_DOWN_MASK);
		input.keyTyped(typed);
		assertTrue("the control F the chord types belongs to the chord the game never saw", typed.isConsumed());
		KeyEvent fUp = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_F, 'f', 0);
		input.keyReleased(fUp);
		assertTrue(fUp.isConsumed());
	}

	@Test
	public void controlFOnTheFloatingMapIsTakenOnlyWhileThePointerIsOverTheMap()
	{
		ViewerInput input = active();
		input.setControlFNeedsPointer(true);
		input.setPointerOver(false);
		KeyEvent away = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		input.keyPressed(away);
		assertFalse("the chord goes on to the game while the pointer is elsewhere", away.isConsumed());
		assertTrue(input.getQueue().drain().isEmpty());
		KeyEvent awayUp = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_F, 'f', 0);
		input.keyReleased(awayUp);
		assertFalse(awayUp.isConsumed());

		input.setPointerOver(true);
		KeyEvent over = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		input.keyPressed(over);
		assertTrue("over the map the chord starts a search", over.isConsumed());

		// Other maps are not affected: without the option the chord is taken wherever the pointer is.
		ViewerInput other = active();
		other.setControlFNeedsPointer(false);
		other.setPointerOver(false);
		KeyEvent anywhere = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK);
		other.keyPressed(anywhere);
		assertTrue(anywhere.isConsumed());
	}

	@Test
	public void keysTypedBeforeTheNextFrameAreTakenAfterControlF()
	{
		ViewerInput input = active();
		input.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK));
		input.getQueue().drain();
		KeyEvent down = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_B, 'b', 0);
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'b', 0);
		KeyEvent up = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_B, 'b', 0);
		input.keyPressed(down);
		input.keyTyped(typed);
		input.keyReleased(up);
		assertTrue(down.isConsumed() && typed.isConsumed() && up.isConsumed());
		boolean sawLetter = false;
		for (MapInput event : input.getQueue().drain())
		{
			sawLetter |= event.kind == MapInput.Kind.TYPED && event.character() == 'b';
		}
		assertTrue("and the search gets the letter when the frame comes", sawLetter);
	}

	@Test
	public void aFrameThatFindsNoSearchGivesTheKeyboardBack()
	{
		ViewerInput input = active();
		input.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK));
		input.setCaptureKeys(false);
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'b', 0);
		input.keyTyped(typed);
		assertFalse(typed.isConsumed());
	}

	@Test
	public void theKeyboardIsGivenBackWhenTheMapStopsDrawingAfterControlF()
	{
		ViewerInput input = new ViewerInput();
		long[] now = {1_000_000_000L};
		input.setClock(() -> now[0]);
		input.setActive(AREA);
		input.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK));
		now[0] += ViewerInput.FRESH_NANOS + 1;
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'b', 0);
		input.keyTyped(typed);
		assertFalse("no frame renewed the claim, so the keys go to the game again", typed.isConsumed());
	}

	@Test
	public void controlFWhenTheMapIsNotShowingTakesNothingAtAll()
	{
		ViewerInput input = new ViewerInput();
		input.keyPressed(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_F, 'f', KeyEvent.CTRL_DOWN_MASK));
		KeyEvent typed = key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '', KeyEvent.CTRL_DOWN_MASK);
		input.keyTyped(typed);
		assertFalse(typed.isConsumed());
		assertTrue(input.getQueue().drain().isEmpty());
	}
}
