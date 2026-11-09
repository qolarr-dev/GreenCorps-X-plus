package dev.greencorps.mapdupe;

import dev.greencorps.GreenCorps;
import dev.greencorps.util.BundleSelect;
import dev.greencorps.util.ClickBudget;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.List;

/**
 * Sends a {@link CopyPlan} under {@link ClickBudget}, waits for the server's craft results, and at every
 * {@link Step.Checkpoint} lets the server settle and compares the live inventory with the plan. A mismatch
 * (a click the server dropped or changed) ends the run in {@link State#MISMATCH} so the module can put things
 * back and plan the rest again instead of clicking on into the wrong stacks.
 */
public final class CopyRunner {
	public enum State { RUNNING, DONE, MISMATCH, FAILED }

	private static final int RESULT_TIMEOUT_TICKS = 100;
	private static final long MIN_SETTLE_MS = 100;
	private static final long OFF_PLAN_WAIT_MS = 5000;
	private static final long SWALLOWED_RETRY_MS = 1000;
	private static final int MAX_SWALLOWED = 20;

	private final Station station;
	private final int syncId;
	private final List<Step> steps;
	private final int totalClicks;
	private final int perTick;
	private final boolean reverseBundleIndex;
	private final boolean debug;
	private final String label;

	private int index;
	private int clicksDone;
	private int waited;
	private long lastActivity;
	private int lastRevision = Integer.MIN_VALUE;
	private long retryAt;
	private int swallowed;
	private State state = State.RUNNING;
	private String message;

	public CopyRunner(Station station, int syncId, CopyPlan plan, int perTick, boolean reverseBundleIndex, boolean debug, String label) {
		this.station = station;
		this.syncId = syncId;
		this.steps = plan.steps();
		this.totalClicks = plan.clicks();
		this.perTick = Math.max(1, perTick);
		this.reverseBundleIndex = reverseBundleIndex;
		this.debug = debug;
		this.label = label;
		if (debug) GreenCorps.LOG.info("[Map Duper+] {}: {} steps, {} clicks", label, steps.size(), totalClicks);
	}

	public State state() {
		return state;
	}

	public String message() {
		return message;
	}

	public String label() {
		return label;
	}

	public int clicksDone() {
		return clicksDone;
	}

	public int totalClicks() {
		return totalClicks;
	}

	/** Rough seconds left at the current click limit. */
	public int etaSeconds() {
		int left = totalClicks - clicksDone;
		int ticks = Math.max(ClickBudget.etaTicks(left), left / perTick);
		return (ticks + 19) / 20;
	}

	public void tick(MinecraftClient mc) {
		if (state != State.RUNNING) return;
		if (mc.player == null || mc.interactionManager == null || !(mc.currentScreen instanceof HandledScreen<?>)) {
			fail("Screen closed; stopped");
			return;
		}
		ScreenHandler handler = mc.player.currentScreenHandler;
		if (handler.syncId != syncId) {
			fail("Screen changed; stopped");
			return;
		}
		long now = System.currentTimeMillis();
		if (handler.getRevision() != lastRevision) {
			lastRevision = handler.getRevision();
			lastActivity = now;
		}
		int sent = 0;
		while (index < steps.size()) {
			Step step = steps.get(index);
			if (step instanceof Step.Click c) {
				if (sent >= perTick || now < retryAt || !ClickBudget.canSend(1)) return;
				String off = offPlan(mc, handler, c);
				if (off != null) {
					// Give late server corrections time to land, then stop instead of clicking into the wrong stack.
					if (sent > 0 || now - lastActivity < offPlanWaitMs(mc)) return;
					mismatch(mc, handler, off);
					return;
				}
				ItemStack cursorBefore = handler.getCursorStack().copy();
				mc.interactionManager.clickSlot(syncId, c.slot(), c.button(), SlotActionType.PICKUP, mc.player);
				if (ItemStack.areEqual(cursorBefore, handler.getCursorStack())) {
					if (debug) GreenCorps.LOG.info("[Map Duper+] click {} was blocked by another mod; retrying", c.slot());
					if (++swallowed > MAX_SWALLOWED) {
						fail("Another mod keeps blocking the clicks");
						return;
					}
					ClickBudget.backOff();
					retryAt = now + SWALLOWED_RETRY_MS;
					return;
				}
				swallowed = 0;
				if (debug) GreenCorps.LOG.info("[Map Duper+] click {} {}", c.slot(), c.button() == Step.LEFT ? "left" : "right");
				sent++;
				clicksDone++;
				lastActivity = now;
				index++;
			} else if (step instanceof Step.SelectBundle s) {
				select(mc, handler, s);
				lastActivity = now;
				index++;
			} else if (step instanceof Step.AwaitResult r) {
				ItemStack result = handler.getSlot(station.result).getStack();
				if (!result.isOf(Items.FILLED_MAP) || result.getCount() != r.count()) {
					if (++waited > RESULT_TIMEOUT_TICKS) mismatch(mc, handler, "the server showed no craft result");
					return;
				}
				ItemStack map = handler.getSlot(station.filledSlot()).getStack();
				if (!map.isEmpty() && LiveInventory.key(map) != r.mapKey()) {
					mismatch(mc, handler, "a different map came out than planned"
						+ (reverseBundleIndex ? " (try bundle-order Off)" : " (try bundle-order On)"));
					return;
				}
				waited = 0;
				index++;
			} else if (step instanceof Step.Checkpoint cp) {
				if (now - lastActivity < settleMs(mc)) return;
				InvSim live = LiveInventory.read(handler, station, null, mc.world);
				List<String> diff = live == null ? List.of("screen layout changed") : live.diff(cp.expected());
				if (!diff.isEmpty() && now - lastActivity < offPlanWaitMs(mc)) return;
				if (debug) GreenCorps.LOG.info("[Map Duper+] checkpoint {}: {}", cp.unit(), diff.isEmpty() ? "ok" : diff);
				if (!diff.isEmpty()) {
					mismatch(mc, handler, diff.size() + " slot" + (diff.size() == 1 ? "" : "s") + " differ after " + cp.unit());
					return;
				}
				index++;
			}
		}
		state = State.DONE;
	}

	/** Long enough for the server's corrections to arrive: ping + 50 ms, at least 100 ms. */
	private static long settleMs(MinecraftClient mc) {
		int ping = 0;
		if (mc.getNetworkHandler() != null && mc.player != null) {
			PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
			if (entry != null) ping = entry.getLatency();
		}
		return Math.max(MIN_SETTLE_MS, ping + 50L);
	}

	private static long offPlanWaitMs(MinecraftClient mc) {
		return Math.max(settleMs(mc), OFF_PLAN_WAIT_MS);
	}

	/** Clear, then select: lands on the planned stack whatever the server had selected. */
	private void select(MinecraftClient mc, ScreenHandler handler, Step.SelectBundle s) {
		BundleSelect.select(mc, s.slot(), handler.getSlot(s.slot()).getStack(), s.index());
	}

	/** Why the live slot or cursor differs from what the plan expects before this click, or null. */
	private static String offPlan(MinecraftClient mc, ScreenHandler handler, Step.Click c) {
		if (c.cursor() != null) {
			SimStack cursor = LiveInventory.toSim(handler.getCursorStack(), null, mc.world);
			if (!cursor.sameAs(c.cursor())) return "the cursor is not as planned (" + cursor + ", expected " + c.cursor() + ")";
		}
		if (c.before() != null) {
			SimStack live = LiveInventory.toSim(handler.getSlot(c.slot()).getStack(), null, mc.world);
			if (!live.sameAs(c.before())) return "slot " + c.slot() + " is not as planned (" + live + ", expected " + c.before() + ")";
		}
		return null;
	}

	private void mismatch(MinecraftClient mc, ScreenHandler handler, String why) {
		ClickBudget.backOff();
		state = State.MISMATCH;
		// Status and chat get the short reason; the stack details go to the debug log.
		int detail = why.indexOf(" (");
		message = detail > 0 && why.endsWith(")") && why.contains("expected") ? why.substring(0, detail) : why;
		if (debug) GreenCorps.LOG.info("[Map Duper+] {}: {} at step {}/{}", label, why, index, steps.size());
	}

	public void stop(String reason) {
		if (state == State.RUNNING) fail(reason);
	}

	private void fail(String reason) {
		state = State.FAILED;
		message = reason;
	}
}
