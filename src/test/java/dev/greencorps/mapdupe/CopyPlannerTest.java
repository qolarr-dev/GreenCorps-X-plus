package dev.greencorps.mapdupe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyPlannerTest {
	private static final long MAP_A = 0xA;
	private static final long MAP_B = 0xB;
	private static final long MAP_C = 0xC;
	private static final long EMPTY = 0xE;
	private static final long BUNDLE = 0xB0;
	private static final long DIRT = 0xD;

	private static InvSim inv(Station st) {
		return new InvSim(st);
	}

	private static int count(InvSim sim, long key) {
		int n = 0;
		for (int id = sim.station.invFirst; id <= sim.station.invLast; id++) {
			if (sim.slot(id).key == key && !sim.slot(id).isNone() && sim.slot(id).kind != SimStack.Kind.BUNDLE) n += sim.slot(id).count;
		}
		return n;
	}

	private static void assertClean(InvSim end) {
		assertTrue(end.cursor().isNone(), "cursor");
		assertTrue(end.gridEmpty(), "grid");
	}

	@Test
	void exactCopiesInEachStation() {
		for (Station st : Station.values()) {
			if (st == Station.SHULKER) continue; // sort only, nothing is crafted there
			for (int copies : new int[] { 1, 3, 5, 8, 20, 63 }) {
				InvSim sim = inv(st);
				sim.set(st.invFirst, SimStack.filledMap(MAP_A, 1));
				sim.set(st.invFirst + 1, SimStack.emptyMap(EMPTY, 64));
				CopyPlan plan = CopyPlanner.copies(sim, st.invFirst, copies);
				assertTrue(plan.ok(), st + " " + copies + ": " + plan.error());
				assertClean(plan.end());
				assertEquals(1 + copies, plan.end().slot(st.invFirst).count, st + " " + copies);
				assertEquals(64 - copies, count(plan.end(), EMPTY), st + " " + copies);
				assertEquals(copies, plan.copies());
			}
		}
	}

	@Test
	void craftingTableNeedsFewClicksPerCopy() {
		InvSim sim = inv(Station.CRAFTING);
		sim.set(10, SimStack.filledMap(MAP_A, 1));
		for (int i = 0; i < 8; i++) sim.set(11 + i, SimStack.emptyMap(EMPTY, 64));
		CopyPlan plan = CopyPlanner.copies(sim, 10, 63);
		assertTrue(plan.ok(), plan.error());
		assertEquals(64, plan.end().slot(10).count);
		// Exact counts cost about one right-click per empty map; keep it at or under 1.5 clicks per copy.
		assertTrue(plan.clicks() <= 63 * 3 / 2, "clicks " + plan.clicks());
	}

	@Test
	void overflowGoesToFreeSlotsAndFailsWhenThereAreNone() {
		InvSim sim = inv(Station.INVENTORY);
		sim.set(9, SimStack.filledMap(MAP_A, 60));
		sim.set(10, SimStack.emptyMap(EMPTY, 20));
		CopyPlan plan = CopyPlanner.copies(sim, 9, 10);
		assertTrue(plan.ok(), plan.error());
		assertEquals(64, plan.end().slot(9).count);
		assertEquals(70, count(plan.end(), MAP_A));

		InvSim full = inv(Station.INVENTORY);
		full.set(9, SimStack.filledMap(MAP_A, 60));
		full.set(10, SimStack.emptyMap(EMPTY, 20));
		for (int id = 11; id <= 44; id++) full.set(id, SimStack.other(DIRT, 64, 64));
		CopyPlan none = CopyPlanner.copies(full, 9, 10);
		assertFalse(none.ok());
		assertTrue(none.error().startsWith("No free slot"), none.error());
	}

	@Test
	void notEnoughEmptyMapsOrDirtyGrid() {
		InvSim sim = inv(Station.INVENTORY);
		sim.set(9, SimStack.filledMap(MAP_A, 1));
		sim.set(10, SimStack.emptyMap(EMPTY, 2));
		assertEquals("Need 3 more empty maps", CopyPlanner.copies(sim, 9, 5).error());

		sim.set(1, SimStack.other(DIRT, 1, 64));
		assertEquals("Empty the crafting grid first", CopyPlanner.copies(sim, 9, 1).error());
	}

	@Test
	void copyAllCopiesEveryDistinctMapAndHonoursSkip() {
		InvSim sim = inv(Station.CRAFTING);
		sim.set(10, SimStack.filledMap(MAP_A, 1));
		sim.set(11, SimStack.filledMap(MAP_B, 2));
		sim.set(12, SimStack.filledMap(MAP_C, 1).skipped(true));
		sim.set(13, SimStack.emptyMap(EMPTY, 64));
		CopyPlan plan = CopyPlanner.copyAll(sim, 4);
		assertTrue(plan.ok(), plan.error());
		assertClean(plan.end());
		assertEquals(5, plan.end().slot(10).count);
		assertEquals(6, plan.end().slot(11).count);
		assertEquals(1, plan.end().slot(12).count);
		assertEquals(56, count(plan.end(), EMPTY));
	}

	@Test
	void bundleCopyKeepsOrderInBothBundles() {
		for (Station st : Station.values()) {
			if (st == Station.SHULKER) continue;
			InvSim sim = inv(st);
			List<SimStack> art = List.of(SimStack.filledMap(MAP_A, 1), SimStack.filledMap(MAP_B, 2), SimStack.filledMap(MAP_C, 1));
			sim.set(st.invFirst, SimStack.bundle(BUNDLE, art));
			sim.set(st.invFirst + 1, SimStack.bundle(BUNDLE, List.of()));
			sim.set(st.invFirst + 2, SimStack.emptyMap(EMPTY, 10));
			CopyPlan plan = CopyPlanner.bundle(sim, st.invFirst);
			assertTrue(plan.ok(), st + ": " + plan.error());
			assertClean(plan.end());
			assertEquals(art.toString(), plan.end().slot(st.invFirst).contents.toString(), st + " source");
			assertEquals(art.toString(), plan.end().slot(st.invFirst + 1).contents.toString(), st + " copy");
			assertEquals(6, count(plan.end(), EMPTY), st + " empties");
		}
	}

	@Test
	void bundleCopyKeepsOrderWhereInsertsLandAtTheEnd() {
		for (Station st : Station.values()) {
			if (st == Station.SHULKER) continue;
			InvSim sim = inv(st);
			sim.setBundleInsertsAtEnd(true);
			List<SimStack> art = List.of(SimStack.filledMap(MAP_A, 1), SimStack.filledMap(MAP_B, 2), SimStack.filledMap(MAP_C, 1));
			sim.set(st.invFirst, SimStack.bundle(BUNDLE, art));
			sim.set(st.invFirst + 1, SimStack.bundle(BUNDLE, List.of()));
			sim.set(st.invFirst + 2, SimStack.emptyMap(EMPTY, 10));
			CopyPlan plan = CopyPlanner.bundle(sim, st.invFirst);
			assertTrue(plan.ok(), st + ": " + plan.error());
			assertClean(plan.end());
			assertEquals(art.toString(), plan.end().slot(st.invFirst).contents.toString(), st + " source");
			assertEquals(art.toString(), plan.end().slot(st.invFirst + 1).contents.toString(), st + " copy");
			for (Step step : plan.steps()) {
				if (step instanceof Step.SelectBundle s) assertEquals(0, s.index(), st + " select");
			}
			assertEquals(6, count(plan.end(), EMPTY), st + " empties");
		}
	}

	@Test
	void bundleCopyNeedsAnEmptyBundleAndAFreeSlot() {
		InvSim sim = inv(Station.INVENTORY);
		sim.set(9, SimStack.bundle(BUNDLE, List.of(SimStack.filledMap(MAP_A, 1))));
		sim.set(10, SimStack.emptyMap(EMPTY, 10));
		assertEquals("No empty bundle in your inventory", CopyPlanner.bundle(sim, 9).error());

		sim.set(11, SimStack.bundle(BUNDLE, List.of()));
		for (int id = 12; id <= 44; id++) sim.set(id, SimStack.other(DIRT, 64, 64));
		assertEquals("Needs one free inventory slot", CopyPlanner.bundle(sim, 9).error());
	}

	@Test
	void allBundlesPairsEachSourceWithItsOwnEmptyBundle() {
		InvSim sim = inv(Station.CRAFTING);
		sim.set(10, SimStack.bundle(BUNDLE, List.of(SimStack.filledMap(MAP_A, 1))));
		sim.set(11, SimStack.bundle(BUNDLE, List.of(SimStack.filledMap(MAP_B, 1), SimStack.filledMap(MAP_C, 1))));
		sim.set(12, SimStack.bundle(BUNDLE, List.of()));
		sim.set(13, SimStack.emptyMap(EMPTY, 8));
		assertEquals("Need 2 empty bundles", CopyPlanner.allBundles(sim).error());

		sim.set(14, SimStack.bundle(BUNDLE, List.of()));
		CopyPlan plan = CopyPlanner.allBundles(sim);
		assertTrue(plan.ok(), plan.error());
		assertEquals(sim.slot(10).contents.toString(), plan.end().slot(12).contents.toString());
		assertEquals(sim.slot(11).contents.toString(), plan.end().slot(14).contents.toString());
		assertEquals(5, count(plan.end(), EMPTY));
	}

	@Test
	void cleanupReturnsGridAndCursor() {
		InvSim sim = inv(Station.CRAFTING);
		sim.set(1, SimStack.filledMap(MAP_A, 3));
		sim.set(2, SimStack.emptyMap(EMPTY, 5));
		sim.setCursor(SimStack.filledMap(MAP_A, 4));
		sim.set(10, SimStack.emptyMap(EMPTY, 1));
		CopyPlan plan = CopyPlanner.cleanup(sim);
		assertTrue(plan.ok(), plan.error());
		assertClean(plan.end());
		assertEquals(7, count(plan.end(), MAP_A));
		assertEquals(6, count(plan.end(), EMPTY));
	}

	@Test
	void lastCheckpointMatchesTheEndState() {
		InvSim sim = inv(Station.CRAFTING);
		sim.set(10, SimStack.filledMap(MAP_A, 1));
		sim.set(11, SimStack.filledMap(MAP_B, 1));
		sim.set(12, SimStack.emptyMap(EMPTY, 64));
		CopyPlan plan = CopyPlanner.copyAll(sim, 6);
		assertTrue(plan.ok(), plan.error());
		Step.Checkpoint last = null;
		int checkpoints = 0;
		for (Step step : plan.steps()) {
			if (step instanceof Step.Checkpoint cp) {
				last = cp;
				checkpoints++;
			}
		}
		assertEquals(2, checkpoints);
		assertTrue(last.expected().diff(plan.end()).isEmpty(), last.expected().diff(plan.end()).toString());
		assertTrue(plan.steps().get(plan.steps().size() - 1) instanceof Step.Checkpoint);
	}

	@Test
	void replansTheRestFromAStumbledState() {
		// Copy All of A and B ×4 was planned; the server dropped clicks: A sits in the grid with empties,
		// empties ended up on A's slot and some maps are on the cursor.
		InvSim start = inv(Station.INVENTORY);
		start.set(9, SimStack.filledMap(MAP_A, 1));
		start.set(10, SimStack.filledMap(MAP_B, 1));
		start.set(11, SimStack.emptyMap(EMPTY, 40));
		CopyPlan original = CopyPlanner.copyAll(start, 4);
		assertTrue(original.ok(), original.error());

		InvSim stumbled = inv(Station.INVENTORY);
		stumbled.set(1, SimStack.filledMap(MAP_A, 1));
		stumbled.set(2, SimStack.emptyMap(EMPTY, 2));
		stumbled.set(9, SimStack.emptyMap(EMPTY, 3));
		stumbled.set(10, SimStack.filledMap(MAP_B, 1));
		stumbled.set(11, SimStack.emptyMap(EMPTY, 33));
		stumbled.setCursor(SimStack.filledMap(MAP_A, 2));

		CopyPlan clean = CopyPlanner.cleanup(stumbled);
		assertTrue(clean.ok(), clean.error());
		assertClean(clean.end());
		CopyPlan rest = CopyPlanner.toTargets(clean.end(), original.goal());
		assertTrue(rest.ok(), rest.error());
		assertClean(rest.end());
		assertEquals(5, count(rest.end(), MAP_A));
		assertEquals(5, count(rest.end(), MAP_B));

		CopyPlan nothing = CopyPlanner.toTargets(rest.end(), original.goal());
		assertTrue(nothing.ok());
		assertTrue(nothing.steps().isEmpty());
	}

	@Test
	void cleanupRefusesInsteadOfDropping() {
		InvSim sim = inv(Station.INVENTORY);
		for (int id = 9; id <= 44; id++) sim.set(id, SimStack.other(DIRT, 64, 64));
		sim.setCursor(SimStack.emptyMap(EMPTY, 5));
		CopyPlan plan = CopyPlanner.cleanup(sim);
		assertFalse(plan.ok());
		assertEquals("No room to put 5 items back; make space first", plan.error());
	}

	@Test
	void sortLaysArtOutAsRectangleAndLeavesOtherItems() {
		Station st = Station.INVENTORY;
		InvSim sim = inv(st);
		// 2×2 art scattered over the first row, dirt in the middle of it.
		sim.set(9, SimStack.filledMap(4, 1));
		sim.set(10, SimStack.filledMap(2, 1));
		sim.set(11, SimStack.other(DIRT, 5, 64));
		sim.set(12, SimStack.filledMap(3, 1));
		sim.set(13, SimStack.filledMap(1, 1));
		MapartLayout.Group art = new MapartLayout.Group("art", List.of(1L, 2L, 3L, 4L), 2, 2, "names");
		CopyPlan plan = CopyPlanner.sortMaps(sim, List.of(art));
		assertTrue(plan.ok(), plan.error());
		InvSim end = plan.end();
		assertClean(end);
		assertEquals(DIRT, end.slot(11).key);
		assertEquals(1L, end.slot(9).key);
		assertEquals(2L, end.slot(10).key);
		assertEquals(3L, end.slot(18).key);
		assertEquals(4L, end.slot(19).key);
	}

	@Test
	void sortedMapsNeedNoClicks() {
		Station st = Station.INVENTORY;
		InvSim sim = inv(st);
		sim.set(9, SimStack.filledMap(1, 1));
		sim.set(10, SimStack.filledMap(2, 1));
		MapartLayout.Group art = new MapartLayout.Group("art", List.of(1L, 2L), 2, 1, "names");
		CopyPlan plan = CopyPlanner.sortMaps(sim, List.of(art));
		assertTrue(plan.ok(), plan.error());
		assertEquals(0, plan.clicks());
	}

	@Test
	void bundleSortMovesStacksToFrontInPlace() {
		Station st = Station.INVENTORY;
		InvSim sim = inv(st);
		List<SimStack> shuffled = List.of(SimStack.filledMap(3, 1), SimStack.filledMap(1, 1), SimStack.filledMap(4, 1), SimStack.filledMap(2, 1));
		sim.set(9, SimStack.bundle(BUNDLE, shuffled));
		CopyPlan plan = CopyPlanner.sortBundle(sim, 9, List.of(1L, 2L, 3L, 4L));
		assertTrue(plan.ok(), plan.error());
		assertClean(plan.end());
		List<Long> order = new java.util.ArrayList<>();
		for (SimStack s : plan.end().slot(9).contents) order.add(s.key);
		assertEquals(List.of(1L, 2L, 3L, 4L), order);
		assertTrue(plan.clicks() <= 8, "2 clicks per moved stack: " + plan.clicks());
		CopyPlan again = CopyPlanner.sortBundle(plan.end(), 9, List.of(1L, 2L, 3L, 4L));
		assertEquals(0, again.clicks());
	}

	@Test
	void bundleSortMovesStacksToEndWhereInsertsLandAtTheEnd() {
		InvSim sim = inv(Station.INVENTORY);
		sim.setBundleInsertsAtEnd(true);
		List<SimStack> shuffled = List.of(SimStack.filledMap(3, 1), SimStack.filledMap(1, 1), SimStack.filledMap(4, 1), SimStack.filledMap(2, 1));
		sim.set(9, SimStack.bundle(BUNDLE, shuffled));
		CopyPlan plan = CopyPlanner.sortBundle(sim, 9, List.of(1L, 2L, 3L, 4L));
		assertTrue(plan.ok(), plan.error());
		assertClean(plan.end());
		List<Long> order = new java.util.ArrayList<>();
		for (SimStack s : plan.end().slot(9).contents) order.add(s.key);
		assertEquals(List.of(1L, 2L, 3L, 4L), order);
		assertEquals(4, plan.clicks());
		assertEquals(0, CopyPlanner.sortBundle(plan.end(), 9, List.of(1L, 2L, 3L, 4L)).clicks());
	}

	@Test
	void bundleSortLeavesTheSortedTailAlone() {
		InvSim sim = inv(Station.INVENTORY);
		sim.set(9, SimStack.bundle(BUNDLE, List.of(SimStack.filledMap(9, 1), SimStack.filledMap(1, 1), SimStack.filledMap(2, 1))));
		// Target 1, 2, 9: "9" already sits after nothing it must follow, so only 1 and 2 move (4 clicks).
		CopyPlan plan = CopyPlanner.sortBundle(sim, 9, List.of(1L, 2L, 9L));
		assertTrue(plan.ok(), plan.error());
		assertEquals(4, plan.clicks());
		List<Long> order = new java.util.ArrayList<>();
		for (SimStack s : plan.end().slot(9).contents) order.add(s.key);
		assertEquals(List.of(1L, 2L, 9L), order);
	}

	@Test
	void shulkerSortArrangesTheBoxNotThePlayer() {
		InvSim sim = inv(Station.SHULKER);
		sim.set(0, SimStack.filledMap(2, 1));
		sim.set(1, SimStack.filledMap(1, 1));
		sim.set(30, SimStack.filledMap(7, 1)); // player inventory: untouched
		MapartLayout.Group art = new MapartLayout.Group("art", List.of(1L, 2L), 2, 1, "names");
		CopyPlan plan = CopyPlanner.sortMaps(sim, List.of(art));
		assertTrue(plan.ok(), plan.error());
		assertEquals(1L, plan.end().slot(0).key);
		assertEquals(2L, plan.end().slot(1).key);
		assertEquals(7L, plan.end().slot(30).key);
	}
}
