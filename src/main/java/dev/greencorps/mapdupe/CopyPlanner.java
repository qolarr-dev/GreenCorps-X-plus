package dev.greencorps.mapdupe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds exact click plans on an {@link InvSim} (EV mod style: simulate everything first, then send).
 * <p>
 * Copying a map: the whole source stack goes into the filled-map slot, the empty-map slots get exact counts
 * (stacks are halved with right-clicks to keep clicks low), then each result pickup is one craft. Results
 * go back onto the original slot first, overflow to stacks of the same map, then to free slots. No drag clicks.
 * <p>
 * Copying a map bundle: the last stack is selected and pulled out, copied once, then one copy goes into the
 * empty bundle and one back into the source. Inserts land at the front, so after every stack has been round
 * once both bundles are in the original order.
 */
public final class CopyPlanner {
	/** Each empty-map grid slot holds at most one stack. */
	private static final int SLOT_MAX = SimStack.MAX;
	/** A bundle stack is copied on the cursor, so 2 × its size must fit in one stack. */
	public static final int MAX_BUNDLE_ENTRY = SimStack.MAX / 2;

	private final InvSim sim;
	private final List<Step> steps = new ArrayList<>();
	private int clicks;
	private int emptiesUsed;
	private int copies;
	private String error;

	private CopyPlanner(InvSim start) {
		this.sim = start.copy();
	}

	/** {@code copies} more of the map in {@code slot}. */
	public static CopyPlan copies(InvSim start, int slot, int copies) {
		SimStack s = start.slot(slot);
		if (s.kind != SimStack.Kind.FILLED_MAP) return CopyPlan.failed("Not a filled map");
		Map<Long, Integer> goal = new LinkedHashMap<>();
		goal.put(s.key, start.countMap(s.key) + copies);
		return toTargets(start, goal);
	}

	/** {@code copies} more of every map in the inventory (skip filters apply). */
	public static CopyPlan copyAll(InvSim start, int copies) {
		Map<Long, Integer> goal = new LinkedHashMap<>();
		for (int slot : new CopyPlanner(start).distinctMaps()) {
			long key = start.slot(slot).key;
			goal.put(key, start.countMap(key) + copies);
		}
		if (goal.isEmpty()) return CopyPlan.failed("No maps to copy");
		return toTargets(start, goal);
	}

	/**
	 * Bring each map (by key) up to its target count in the inventory. Used for new jobs and to re-plan the rest
	 * of a job from whatever the server actually left. The plan's {@link CopyPlan#goal()} is the same map.
	 */
	public static CopyPlan toTargets(InvSim start, Map<Long, Integer> goal) {
		CopyPlanner p = new CopyPlanner(start);
		int needed = 0;
		for (Map.Entry<Long, Integer> e : goal.entrySet()) needed += Math.max(0, e.getValue() - start.countMap(e.getKey()));
		if (needed == 0) return p.finishWith(goal);
		if (p.ready() && p.checkEmpties(needed)) {
			for (Map.Entry<Long, Integer> e : goal.entrySet()) {
				int missing = e.getValue() - p.sim.countMap(e.getKey());
				if (missing <= 0) continue;
				int slot = p.slotOf(e.getKey());
				if (slot < 0) {
					p.fail("A map to copy is no longer in your inventory");
					break;
				}
				if (!p.copyMap(slot, missing)) break;
			}
		}
		return p.finishWith(goal);
	}

	/** Copy the map bundle in {@code slot} into the first empty bundle. */
	public static CopyPlan bundle(InvSim start, int slot) {
		CopyPlanner p = new CopyPlanner(start);
		if (p.ready()) {
			int dst = p.emptyBundle(Set.of());
			if (dst < 0) p.fail("No empty bundle in your inventory");
			else p.copyBundle(slot, dst);
		}
		return p.finish();
	}

	/** Copy every map bundle into its own empty bundle. */
	public static CopyPlan allBundles(InvSim start) {
		CopyPlanner p = new CopyPlanner(start);
		List<Integer> sources = new ArrayList<>();
		int maps = 0;
		for (int id = p.sim.station.invFirst; id <= p.sim.station.invLast; id++) {
			SimStack s = p.sim.slot(id);
			if (s.isMapBundle()) {
				sources.add(id);
				maps += s.bundleMaps();
			}
		}
		if (sources.isEmpty()) return CopyPlan.failed("No bundles with maps");
		if (p.ready() && p.checkEmpties(maps)) {
			Set<Integer> used = new HashSet<>();
			for (int src : sources) {
				int dst = p.emptyBundle(used);
				if (dst < 0) {
					p.fail("Need " + sources.size() + " empty bundles");
					break;
				}
				used.add(dst);
				if (!p.copyBundle(src, dst)) break;
			}
		}
		return p.finish();
	}

	/** The 27 slots Sort arranges (main inventory rows, or a shulker's contents), in reading order. */
	public static int[] mainSlots(Station st) {
		int[] out = new int[27];
		for (int i = 0; i < 27; i++) out[i] = st.sortFirst + i;
		return out;
	}

	/** Target for a hole in an artwork: the cell stays free of maps. */
	private static final long HOLE = Long.MIN_VALUE;

	/**
	 * Sort the maps in the 27 sort slots ({@link #mainSlots}) into {@code groups} (from {@link MapartLayout}). Each
	 * artwork that fits (≤ 9 wide, ≤ 3 tall) is laid out as its own rectangle, holes included, where map or empty
	 * slots allow; the rest follow in reading order. Other items never move, and the hotbar is left alone.
	 */
	public static CopyPlan sortMaps(InvSim start, List<MapartLayout.Group> groups) {
		CopyPlanner p = new CopyPlanner(start);
		if (!p.ready()) return p.finish();
		int[] main = mainSlots(p.sim.station);
		boolean[] usable = new boolean[27];
		Map<Long, List<Integer>> stacksByKey = new LinkedHashMap<>();
		int maps = 0;
		for (int i = 0; i < 27; i++) {
			SimStack s = p.sim.slot(main[i]);
			usable[i] = s.isNone() || s.kind == SimStack.Kind.FILLED_MAP;
			if (s.kind == SimStack.Kind.FILLED_MAP) {
				stacksByKey.computeIfAbsent(s.key, k -> new ArrayList<>()).add(main[i]);
				maps++;
			}
		}
		if (maps < 2) {
			return CopyPlan.failed(p.sim.station == Station.SHULKER ? "Needs 2 or more maps in the shulker"
				: "Need at least 2 maps in the main inventory rows");
		}

		long[] target = new long[27];
		boolean[] taken = new boolean[27];
		List<Long> spill = new ArrayList<>();
		for (MapartLayout.Group g : groups) {
			// Cells row by row; a tile whose map is not here is a hole like a missing one.
			List<Long> cells = new ArrayList<>();
			List<Long> keys = new ArrayList<>();
			for (Long k : g.cells()) {
				boolean here = k != null && stacksByKey.containsKey(k);
				cells.add(here ? k : null);
				if (here) keys.add(k);
			}
			if (keys.isEmpty()) continue;
			int w = g.width(), h = g.height();
			int at = w <= 9 && h <= 3 ? findRect(usable, taken, w, h, cells) : -1;
			if (at < 0) {
				spill.addAll(keys);
				continue;
			}
			for (int i = 0; i < cells.size(); i++) {
				int cell = at + (i / w) * 9 + i % w;
				target[cell] = cells.get(i) == null ? HOLE : cells.get(i);
				taken[cell] = true;
			}
		}
		// Second stacks of a map, then whatever did not fit, in reading order.
		for (Map.Entry<Long, List<Integer>> e : stacksByKey.entrySet()) {
			for (int i = 1; i < e.getValue().size(); i++) spill.add(e.getKey());
		}
		for (MapartLayout.Group g : groups) {
			for (long k : g.order()) if (stacksByKey.containsKey(k) && !spill.contains(k) && !placed(target, taken, k)) spill.add(k);
		}
		int cell = 0;
		for (long k : spill) {
			while (cell < 27 && (!usable[cell] || taken[cell])) cell++;
			if (cell >= 27) return CopyPlan.failed("No room to lay the maps out");
			target[cell] = k;
			taken[cell] = true;
		}
		p.sortInto(main, usable, taken, target);
		if (p.error == null) p.checkpoint("sort");
		return p.finish();
	}

	private static boolean placed(long[] target, boolean[] taken, long key) {
		for (int i = 0; i < 27; i++) if (taken[i] && target[i] == key) return true;
		return false;
	}

	/** Top-left cell of the first free w×h rectangle: untaken, and usable where a tile goes (holes may hold anything). */
	private static int findRect(boolean[] usable, boolean[] taken, int w, int h, List<Long> cells) {
		for (int r = 0; r + h <= 3; r++) {
			for (int c = 0; c + w <= 9; c++) {
				boolean ok = true;
				for (int i = 0; i < cells.size() && ok; i++) {
					int cell = (r + i / w) * 9 + c + i % w;
					ok = !taken[cell] && (cells.get(i) == null || usable[cell]);
				}
				if (ok) return r * 9 + c;
			}
		}
		return -1;
	}

	/** Move every map stack to a cell planned for its map: pick one up, drop / swap it into place, repeat. */
	private void sortInto(int[] main, boolean[] usable, boolean[] taken, long[] target) {
		int guard = 0;
		while (true) {
			int from = -1;
			for (int i = 0; i < 27 && from < 0; i++) {
				SimStack s = sim.slot(main[i]);
				if (!usable[i] || s.isNone()) continue;
				if (!taken[i] || target[i] != s.key) from = i;
			}
			if (from < 0) return;
			click(main[from], Step.LEFT);
			while (!sim.cursor().isNone()) {
				if (guard++ > 200) {
					fail("Could not sort the maps");
					return;
				}
				long key = sim.cursor().key;
				int to = -1;
				for (int i = 0; i < 27 && to < 0; i++) {
					if (!usable[i] || !taken[i] || target[i] != key) continue;
					SimStack here = sim.slot(main[i]);
					if (here.isNone() || here.key != key) to = i;
				}
				if (to < 0 || !click(main[to], Step.LEFT)) {
					fail("Could not sort the maps");
					return;
				}
			}
		}
	}

	/**
	 * Bundle Sort, in place ("move to front"): for k = n-1 … 0 select the stack that belongs at k, take it out
	 * and put it straight back into the same bundle, where it lands at index 0. Two clicks per stack, no free
	 * slot or spare bundle. The largest tail of {@code target} already in order is left where it is.
	 *
	 * @param target contents keys in the wanted order (index 0 first)
	 */
	public static CopyPlan sortBundle(InvSim start, int slot, List<Long> target) {
		CopyPlanner p = new CopyPlanner(start);
		if (!p.ready()) return p.finish();
		SimStack bundle = p.sim.slot(slot);
		if (bundle.kind != SimStack.Kind.BUNDLE || bundle.contents.size() < 2) return CopyPlan.failed("Nothing to sort in that bundle");
		List<Long> current = new ArrayList<>();
		for (SimStack s : bundle.contents) current.add(s.key);
		List<Long> a = new ArrayList<>(current), b = new ArrayList<>(target);
		a.sort(Long::compare);
		b.sort(Long::compare);
		if (!a.equals(b)) return CopyPlan.failed("The bundle changed; try again");
		boolean atEnd = p.sim.bundleInsertsAtEnd();
		List<Long> want = atEnd ? target.reversed() : target;
		List<Long> have = atEnd ? current.reversed() : current;
		int n = want.size();
		// Longest tail target[n-m … n-1] that already appears in this order: those stay put.
		int keep = 0;
		int pos = have.size();
		for (int k = n - 1; k >= 0; k--) {
			int at = have.subList(0, pos).lastIndexOf(want.get(k));
			if (at < 0) break;
			pos = at;
			keep++;
		}
		for (int k = n - keep - 1; k >= 0; k--) {
			List<SimStack> contents = p.sim.slot(slot).contents;
			int index = -1;
			for (int i = 0; i < contents.size(); i++) {
				if (contents.get(i).key == want.get(k)) {
					index = i;
					break;
				}
			}
			if (index < 0) return CopyPlan.failed("Lost track of the bundle contents");
			p.steps.add(new Step.SelectBundle(slot, index, contents.size()));
			p.sim.select(slot, index);
			if (!p.click(slot, Step.RIGHT)) return CopyPlan.failed("Could not take a stack out of the bundle");
			if (!p.click(slot, Step.LEFT)) return CopyPlan.failed("Could not put the stack back into the bundle");
		}
		p.checkpoint("bundle sort");
		return p.finish();
	}

	/** Put the cursor and grid back into the inventory (after a stop or an error). Never drops anything. */
	public static CopyPlan cleanup(InvSim start) {
		CopyPlanner p = new CopyPlanner(start);
		if (!p.sim.cursor().isNone()) p.deposit(-1);
		for (int id : p.sim.station.inputs()) {
			if (p.error != null) break;
			if (p.sim.slot(id).isNone()) continue;
			p.click(id, Step.LEFT);
			p.deposit(-1);
		}
		return p.finish();
	}

	private CopyPlan finish() {
		return finishWith(null);
	}

	private CopyPlan finishWith(Map<Long, Integer> goal) {
		if (error != null) return CopyPlan.failed(error);
		return new CopyPlan(List.copyOf(steps), sim, clicks, emptiesUsed, copies, null, goal);
	}

	private void checkpoint(String unit) {
		steps.add(new Step.Checkpoint(sim.copy(), unit));
	}

	private int slotOf(long key) {
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			SimStack s = sim.slot(id);
			if (s.kind == SimStack.Kind.FILLED_MAP && s.key == key) return id;
		}
		return -1;
	}

	// ------------------------------------------------------------ checks --

	private boolean ready() {
		if (!sim.cursor().isNone()) return fail("Empty your cursor first");
		if (!sim.gridEmpty()) return fail("Empty the " + (sim.station == Station.CARTOGRAPHY ? "table" : "crafting grid") + " first");
		return true;
	}

	private boolean checkEmpties(int needed) {
		int have = sim.countEmpties();
		if (have < needed) return fail("Need " + (needed - have) + " more empty map" + (needed - have == 1 ? "" : "s"));
		return true;
	}

	private boolean fail(String reason) {
		if (error == null) error = reason;
		return false;
	}

	private List<Integer> distinctMaps() {
		List<Integer> out = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			SimStack s = sim.slot(id);
			if (s.kind != SimStack.Kind.FILLED_MAP || s.skip) continue;
			if (seen.add(s.key)) out.add(id);
		}
		return out;
	}

	private int emptyBundle(Set<Integer> exclude) {
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			if (sim.slot(id).isEmptyBundle() && !exclude.contains(id)) return id;
		}
		return -1;
	}

	// -------------------------------------------------------------- maps --

	private boolean copyMap(int slot, int wanted) {
		if (sim.slot(slot).kind != SimStack.Kind.FILLED_MAP) return fail("Not a filled map");
		int perCraft = sim.station.emptiesPerCraft();
		int left = wanted;
		while (left > 0) {
			int chunk = Math.min(left, SLOT_MAX * perCraft);
			if (!copyChunk(slot, chunk)) return false;
			left -= chunk;
		}
		return true;
	}

	private boolean copyChunk(int home, int wanted) {
		Station st = sim.station;
		int a = st.filledSlot();
		int[] bs = st.emptySlots();
		long key = sim.slot(home).key;
		int used = Math.min(bs.length, wanted);
		int base = wanted / used;
		int extra = wanted % used;

		// Whole source stack into the filled-map slot; it only loses one map per craft.
		if (!click(home, Step.LEFT) || !click(a, Step.LEFT)) return fail("Could not move the map into the grid");
		for (int i = 0; i < used; i++) {
			if (!loadEmpties(bs[i], base + (i < extra ? 1 : 0))) return false;
		}
		int crafts = base + (extra > 0 ? 1 : 0);
		for (int c = 0; c < crafts; c++) {
			if (sim.slot(a).isNone() && !click(a, Step.RIGHT)) return fail("Lost the map while copying");
			SimStack result = sim.result();
			if (result.isNone()) return fail("No craft result to plan with");
			SimStack cur = sim.cursor();
			if (!cur.isNone() && cur.count + result.count > cur.max && !deposit(home)) return false;
			steps.add(new Step.AwaitResult(result.count, key));
			if (!click(st.result, Step.LEFT)) return fail("Could not take the result");
		}
		if (!deposit(home)) return false;
		if (!sim.slot(a).isNone()) {
			click(a, Step.LEFT);
			if (!deposit(home)) return false;
		}
		copies += wanted;
		emptiesUsed += wanted;
		checkpoint("map x" + wanted);
		return true;
	}

	/** Put exactly {@code need} empty maps into grid slot {@code b}. */
	private boolean loadEmpties(int b, int need) {
		while (need > 0) {
			int q = emptiesFor(b);
			if (q < 0) return fail("Not enough empty maps");
			int have = sim.slot(q).count;
			int half = (have + 1) / 2;
			if (have <= need) {
				click(q, Step.LEFT);
				click(b, Step.LEFT);
				need -= have;
			} else if (half <= need) {
				click(q, Step.RIGHT);
				click(b, Step.LEFT);
				need -= half;
			} else {
				click(q, Step.RIGHT);
				for (int i = 0; i < need; i++) click(b, Step.RIGHT);
				click(q, Step.LEFT);
				need = 0;
			}
			if (!sim.cursor().isNone()) return fail("Empty maps would not fit the grid");
		}
		return true;
	}

	/** An inventory stack of empty maps that can join grid slot {@code b}. */
	private int emptiesFor(int b) {
		SimStack in = sim.slot(b);
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			SimStack s = sim.slot(id);
			if (s.kind != SimStack.Kind.EMPTY_MAP) continue;
			if (in.isNone() || s.stacksWith(in)) return id;
		}
		return -1;
	}

	/** Cursor back into the inventory: {@code home} first, then stacks it joins, then free slots. */
	private boolean deposit(int home) {
		int guard = 0;
		while (!sim.cursor().isNone()) {
			if (guard++ > 64) return fail("Could not put the maps back");
			int target = -1;
			SimStack cur = sim.cursor();
			if (home >= 0 && (sim.slot(home).isNone() || (sim.slot(home).stacksWith(cur) && sim.slot(home).count < cur.max))) {
				target = home;
			}
			for (int id = sim.station.invFirst; target < 0 && id <= sim.station.invLast; id++) {
				SimStack s = sim.slot(id);
				if (s.stacksWith(cur) && s.count < s.max) target = id;
			}
			for (int id = sim.station.invFirst; target < 0 && id <= sim.station.invLast; id++) {
				if (sim.slot(id).isNone()) target = id;
			}
			if (target < 0) {
				return fail(home < 0
					? "No room to put " + cur.count + " item" + (cur.count == 1 ? "" : "s") + " back; make space first"
					: "No free slot for " + cur.count + " more map" + (cur.count == 1 ? "" : "s"));
			}
			click(target, Step.LEFT);
		}
		return true;
	}

	// ----------------------------------------------------------- bundles --

	private boolean copyBundle(int src, int dst) {
		SimStack bundle = sim.slot(src);
		if (!bundle.isMapBundle()) return fail("That bundle holds more than maps");
		for (SimStack s : bundle.contents) {
			if (s.count > MAX_BUNDLE_ENTRY) return fail("Bundle stacks above " + MAX_BUNDLE_ENTRY + " maps can't be copied");
		}
		int free = freeSlot();
		if (free < 0) return fail("Needs one free inventory slot");
		int total = bundle.bundleMaps();
		if (!checkEmpties(total)) return false;

		Station st = sim.station;
		int a = st.filledSlot();
		int b = st.emptySlots()[0];
		int n = bundle.contents.size();
		int left = total;
		int take = sim.bundleInsertsAtEnd() ? 0 : n - 1;
		for (int j = 0; j < n; j++) {
			SimStack entry = sim.slot(src).contents.get(take);
			int c = entry.count;
			if (sim.slot(b).count < c) {
				int room = SLOT_MAX - sim.slot(b).count;
				if (!loadEmpties(b, Math.min(room, left - sim.slot(b).count))) return false;
			}
			steps.add(new Step.SelectBundle(src, take, n));
			sim.select(src, take);
			if (!click(src, Step.RIGHT)) return fail("Could not take a map out of the bundle");
			click(a, Step.LEFT);
			for (int k = 0; k < c; k++) {
				steps.add(new Step.AwaitResult(2, entry.key));
				if (!click(st.result, Step.LEFT)) return fail("Could not take the result");
			}
			// 2c on the cursor: park, take half for the copy, the rest goes back home.
			click(free, Step.LEFT);
			click(free, Step.RIGHT);
			if (!click(dst, Step.LEFT)) return fail("The empty bundle is full");
			click(free, Step.LEFT);
			if (!click(src, Step.LEFT)) return fail("The bundle is full");
			left -= c;
			copies += c;
			emptiesUsed += c;
			// Every click is checked against the plan before it is sent, so a settle-and-compare every 8 stacks
			// (and at the end) is enough; waiting after each stack made big bundles crawl.
			if ((j + 1) % 8 == 0 || j == n - 1) checkpoint("bundle stack " + (j + 1) + "/" + n);
		}
		if (!sim.slot(b).isNone()) {
			click(b, Step.LEFT);
			if (!deposit(-1)) return false;
		}
		return true;
	}

	private int freeSlot() {
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			if (sim.slot(id).isNone()) return id;
		}
		return -1;
	}

	private boolean click(int slot, int button) {
		SimStack before = slot == sim.station.result ? null : sim.slot(slot);
		SimStack cursor = sim.cursor();
		boolean ok = button == Step.LEFT ? sim.left(slot) : sim.right(slot);
		if (!ok) return false;
		steps.add(new Step.Click(slot, button, before, cursor));
		clicks++;
		return true;
	}
}
