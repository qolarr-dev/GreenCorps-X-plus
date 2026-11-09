package dev.greencorps.mapdupe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Screen slots + cursor with vanilla 1.21.4 click semantics for the clicks Map Duper+ plans with: left / right
 * PICKUP, taking a craft result, and bundle insert / extract. The client has no recipes since 1.21.2, so the
 * map cloning recipe (one filled map + N empty-map slots = N + 1 maps) is modelled here.
 * <p>
 * A click the model does not cover (an item the slot refuses, a bundle on the cursor) returns false so the
 * planner gives up instead of guessing.
 */
public final class InvSim {
	public final Station station;
	private final SimStack[] slots;
	private SimStack cursor = SimStack.NONE;
	private boolean bundleInsertsAtEnd;

	public InvSim(Station station) {
		this.station = station;
		this.slots = new SimStack[station.slotCount];
		Arrays.fill(slots, SimStack.NONE);
	}

	private InvSim(InvSim other) {
		this.station = other.station;
		this.slots = other.slots.clone();
		this.cursor = other.cursor;
		this.bundleInsertsAtEnd = other.bundleInsertsAtEnd;
	}

	public InvSim copy() {
		return new InvSim(this);
	}

	public SimStack slot(int id) {
		return slots[id];
	}

	public void set(int id, SimStack stack) {
		slots[id] = stack == null || stack.isNone() ? SimStack.NONE : stack;
	}

	public boolean bundleInsertsAtEnd() {
		return bundleInsertsAtEnd;
	}

	public void setBundleInsertsAtEnd(boolean value) {
		bundleInsertsAtEnd = value;
	}

	public SimStack cursor() {
		return cursor;
	}

	public void setCursor(SimStack stack) {
		cursor = stack == null || stack.isNone() ? SimStack.NONE : stack;
	}

	/** The craft result the server would show for the current grid. */
	public SimStack result() {
		if (station == Station.CARTOGRAPHY) {
			SimStack map = slots[0];
			SimStack extra = slots[1];
			if (!map.isNone() && map.kind == SimStack.Kind.FILLED_MAP && !extra.isNone() && extra.kind == SimStack.Kind.EMPTY_MAP) {
				return SimStack.filledMap(map.key, 2);
			}
			return SimStack.NONE;
		}
		SimStack filled = null;
		int empties = 0;
		for (int id : station.inputs()) {
			SimStack s = slots[id];
			if (s.isNone()) continue;
			if (s.kind == SimStack.Kind.FILLED_MAP) {
				if (filled != null) return SimStack.NONE;
				filled = s;
			} else if (s.kind == SimStack.Kind.EMPTY_MAP) {
				empties++;
			} else {
				return SimStack.NONE;
			}
		}
		if (filled == null || empties == 0) return SimStack.NONE;
		return SimStack.filledMap(filled.key, empties + 1);
	}

	/** Left-click (PICKUP button 0). */
	public boolean left(int id) {
		if (id == station.result) return takeResult();
		SimStack s = slots[id];
		if (s.kind == SimStack.Kind.BUNDLE && !cursor.isNone()) return insertIntoBundle(id);
		if (cursor.isNone()) {
			if (s.isNone()) return true;
			cursor = s;
			slots[id] = SimStack.NONE;
			return true;
		}
		if (!accepts(id, cursor)) return false;
		if (s.isNone()) {
			int n = Math.min(cursor.count, cursor.max);
			slots[id] = cursor.withCount(n);
			cursor = cursor.withCount(cursor.count - n);
			return true;
		}
		if (s.stacksWith(cursor)) {
			int n = Math.min(cursor.count, s.max - s.count);
			slots[id] = s.withCount(s.count + n);
			cursor = cursor.withCount(cursor.count - n);
			return true;
		}
		// Different items: vanilla swaps cursor and slot (only planned for maps; bundles never get here).
		if (cursor.kind == SimStack.Kind.BUNDLE || cursor.count > s.max) return false;
		slots[id] = cursor;
		cursor = s;
		return true;
	}

	/** Right-click (PICKUP button 1). */
	public boolean right(int id) {
		if (id == station.result) return false;
		SimStack s = slots[id];
		if (s.kind == SimStack.Kind.BUNDLE) {
			if (!cursor.isNone() || s.contents.isEmpty()) return false;
			int index = s.selected >= 0 && s.selected < s.contents.size() ? s.selected : 0;
			List<SimStack> contents = new ArrayList<>(s.contents);
			cursor = contents.remove(index);
			slots[id] = s.withContents(contents, -1);
			return true;
		}
		if (cursor.isNone()) {
			if (s.isNone()) return true;
			int take = (s.count + 1) / 2;
			cursor = s.withCount(take);
			slots[id] = s.withCount(s.count - take);
			return true;
		}
		if (!accepts(id, cursor)) return false;
		if (s.isNone()) {
			slots[id] = cursor.withCount(1);
			cursor = cursor.withCount(cursor.count - 1);
			return true;
		}
		if (s.stacksWith(cursor) && s.count < s.max) {
			slots[id] = s.withCount(s.count + 1);
			cursor = cursor.withCount(cursor.count - 1);
			return true;
		}
		return false;
	}

	/**
	 * Bundle select as the runner sends it ({@code BundleSelect}: clear, then select), so it always lands on
	 * {@code index} (vanilla's toggle never comes into play); out of range clears.
	 */
	public void select(int id, int index) {
		SimStack s = slots[id];
		if (s.kind != SimStack.Kind.BUNDLE) return;
		int sel = index < 0 || index >= s.contents.size() ? -1 : index;
		slots[id] = s.withContents(s.contents, sel);
	}

	private boolean takeResult() {
		SimStack r = result();
		if (r.isNone()) return false;
		if (cursor.isNone()) {
			cursor = r;
		} else if (cursor.stacksWith(r) && cursor.count + r.count <= cursor.max) {
			cursor = cursor.withCount(cursor.count + r.count);
		} else {
			return false;
		}
		for (int id : station.inputs()) {
			SimStack s = slots[id];
			if (!s.isNone()) slots[id] = s.withCount(s.count - 1);
		}
		return true;
	}

	/**
	 * Vanilla BundleItem.onClicked LEFT with a cursor stack: insert as much as fits (each item weighs 64 / its stack
	 * size, a bundle holds 64), merged stacks move to the front. Bundles inside bundles are not modelled.
	 */
	private boolean insertIntoBundle(int id) {
		SimStack bundle = slots[id];
		if (cursor.kind == SimStack.Kind.BUNDLE) return false;
		int used = 0;
		for (SimStack s : bundle.contents) {
			if (s.kind == SimStack.Kind.BUNDLE) return false;
			used += s.count * weight(s);
		}
		int n = Math.min((SimStack.MAX - used) / weight(cursor), cursor.count);
		if (n <= 0) return false;
		List<SimStack> contents = new ArrayList<>(bundle.contents);
		int merge = -1;
		for (int i = 0; i < contents.size(); i++) {
			if (contents.get(i).stacksWith(cursor)) {
				merge = i;
				break;
			}
		}
		SimStack added = cursor.withCount(n);
		if (merge >= 0) {
			SimStack old = contents.remove(merge);
			added = old.withCount(old.count + n);
		}
		contents.add(bundleInsertsAtEnd ? contents.size() : 0, added);
		slots[id] = bundle.withContents(contents, bundle.selected);
		cursor = cursor.withCount(cursor.count - n);
		return true;
	}

	/** Bundle weight of one item: 64 / max stack size (a map 1, an ender pearl 4, a sword 64). */
	private static int weight(SimStack s) {
		return SimStack.MAX / Math.max(1, Math.min(SimStack.MAX, s.max));
	}

	/** Cartography slots only take maps; crafting inputs take anything. */
	private boolean accepts(int id, SimStack stack) {
		if (id == station.result) return false;
		if (station == Station.CARTOGRAPHY) {
			if (id == 0) return stack.kind == SimStack.Kind.FILLED_MAP;
			if (id == 1) return stack.kind == SimStack.Kind.EMPTY_MAP;
		}
		return true;
	}

	public int countEmpties() {
		int n = 0;
		for (int id = station.invFirst; id <= station.invLast; id++) {
			if (slots[id].kind == SimStack.Kind.EMPTY_MAP) n += slots[id].count;
		}
		return n;
	}

	/** Slots (and the cursor) that differ from {@code other}; the result slot is ignored. */
	public List<String> diff(InvSim other) {
		List<String> out = new ArrayList<>();
		for (int id = 0; id < slots.length; id++) {
			if (id == station.result) continue;
			if (!slots[id].sameAs(other.slots[id])) out.add(id + ": " + slots[id] + " vs " + other.slots[id]);
		}
		if (!cursor.sameAs(other.cursor)) out.add("cursor: " + cursor + " vs " + other.cursor);
		return out;
	}

	/** Filled maps of {@code key} in the inventory part. */
	public int countMap(long key) {
		int n = 0;
		for (int id = station.invFirst; id <= station.invLast; id++) {
			SimStack s = slots[id];
			if (s.kind == SimStack.Kind.FILLED_MAP && s.key == key) n += s.count;
		}
		return n;
	}

	public boolean gridEmpty() {
		for (int id : station.inputs()) {
			if (!slots[id].isNone()) return false;
		}
		return true;
	}
}
