package dev.greencorps.modules;

import dev.greencorps.GreenCorps;
import dev.greencorps.mapdupe.CopyPlan;
import dev.greencorps.mapdupe.CopyPlanner;
import dev.greencorps.mapdupe.CopyRunner;
import dev.greencorps.mapdupe.InvSim;
import dev.greencorps.mapdupe.LiveInventory;
import dev.greencorps.mapdupe.MapSortDump;
import dev.greencorps.mapdupe.MapartLayout;
import dev.greencorps.mapdupe.SimStack;
import dev.greencorps.mapdupe.Station;
import dev.greencorps.util.ClickBudget;
import dev.greencorps.util.InventoryClickGuard;
import dev.greencorps.util.MapCenterCache;
import dev.greencorps.util.WorldAllowlist;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.MapColor;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Map copying beside the inventory, crafting table and cartography table. Every job is planned on a
 * simulated inventory first (exact counts, results back on the original slot), then sent under the shared
 * click budget. Also copies map bundles into empty bundles, keeping their order.
 */
public class MapDuperPlus extends Module {
	public enum BundleOrder { Auto, On, Off }

	public static final int MAX_COPIES = 512;
	/** Copy All again within this long asks for a second click. */
	private static final long CONFIRM_AFTER_MS = 10_000;
	private static final long CONFIRM_WINDOW_MS = 5_000;
	private static final int MAX_REPLANS = 3;
	/** Bump when the click pace defaults change. */
	private static final int CLICK_PACE = 2;

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgClicks = settings.createGroup("Clicks");
	private final SettingGroup sgFilters = settings.createGroup("Copy All filters");

	private final Setting<Integer> copies = sgGeneral.add(new IntSetting.Builder()
		.name("copies")
		.description("New copies per map (the wing's + / - box).")
		.defaultValue(4)
		.range(1, MAX_COPIES)
		.sliderRange(1, 64)
		.build()
	);

	private final Setting<Boolean> clickToCopy = sgGeneral.add(new BoolSetting.Builder()
		.name("click-to-copy")
		.description("While on, left-clicking a map in your inventory copies it; a map bundle is copied into an empty bundle.")
		.defaultValue(false)
		.build()
	);

	private final Setting<BundleOrder> bundleOrder = sgGeneral.add(new EnumSetting.Builder<BundleOrder>()
		.name("bundle-order")
		.description("Server keeps bundle contents the other way round: reversed select index, put-backs land at the end. Auto = on for 2b2t.")
		.defaultValue(BundleOrder.Auto)
		.build()
	);

	private final Setting<Boolean> noDrag = sgGeneral.add(new BoolSetting.Builder()
		.name("no-drag-maps")
		.description("Drag-splitting maps, bundles and banners leaves ghost items on 2b2t; ignore those drags while on.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> pauseReplenish = sgGeneral.add(new BoolSetting.Builder()
		.name("pause-replenish")
		.description("Pause AutoReplenish / BepHax Replenish / InventoryTweaks / Map Copier while copying.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> chatStatus = sgGeneral.add(new BoolSetting.Builder()
		.name("chat-status")
		.description("Chat when a copy job finishes or stops.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> clickLimit = sgClicks.add(new IntSetting.Builder()
		.name("clicks-per-window")
		.description("Most inventory clicks (all of them, yours included) per window. 2b2t kicks above 80 per 80 server ticks; it halves by itself for 30 s whenever the server corrects a job.")
		.defaultValue(ClickBudget.DEFAULT_LIMIT)
		.range(ClickBudget.MIN_LIMIT, 400)
		.sliderRange(20, 120)
		.build()
	);

	private final Setting<Integer> clickWindow = sgClicks.add(new IntSetting.Builder()
		.name("click-window")
		.description("Window for clicks-per-window, in server ticks.")
		.defaultValue(ClickBudget.DEFAULT_WINDOW_TICKS)
		.range(20, 400)
		.sliderRange(20, 200)
		.build()
	);

	private final Setting<Boolean> adaptToTps = sgClicks.add(new BoolSetting.Builder()
		.name("adapt-to-tps")
		.description("Stretch the window when the server runs under 20 TPS (its ticks take longer, so its limit lasts longer).")
		.defaultValue(true)
		.build()
	);

	private final Setting<Integer> clicksPerTick = sgClicks.add(new IntSetting.Builder()
		.name("max-clicks-per-tick")
		.description("Most clicks in one tick, so the budget is spread out instead of sent in one burst.")
		.defaultValue(2)
		.range(1, 10)
		.sliderRange(1, 4)
		.build()
	);

	private final Setting<Boolean> debugLog = sgClicks.add(new BoolSetting.Builder()
		.name("debug-log")
		.description("Write every click and checkpoint of a job to the game log (latest.log).")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> skipUnlocked = sgFilters.add(new BoolSetting.Builder()
		.name("skip-unlocked")
		.description("Copy All leaves maps that are not locked in a cartography table.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> skipBlank = sgFilters.add(new BoolSetting.Builder()
		.name("skip-blank")
		.description("Copy All leaves blank (fully transparent) maps.")
		.defaultValue(true)
		.build()
	);

	private final Setting<Boolean> skipUnnamed = sgFilters.add(new BoolSetting.Builder()
		.name("skip-unnamed")
		.description("Copy All leaves maps without a name.")
		.defaultValue(false)
		.build()
	);

	private final MapCenterCache centers = new MapCenterCache();
	private final Map<String, Boolean> pausedPrior = new LinkedHashMap<>();
	private final List<String> pausedNames = new ArrayList<>();
	private boolean warnedInventoryEssentials;

	private CopyRunner runner;
	private CopyRunner cleanup;
	private Station runStation;
	private String status = "";
	private boolean bundleSortMode;
	/** Kept for the next time the screen opens (errors, stopped or unfinished jobs); other messages end with the screen. */
	private boolean statusKeep;
	/** Map key → target count of the running job, for re-planning after a mismatch (null for bundle jobs). */
	private Map<Long, Integer> goal;
	private String jobLabel;
	private int replans;
	private boolean replanAfterCleanup;
	private long lastCopyAllAt = Long.MIN_VALUE / 2;
	private long confirmUntil;

	public MapDuperPlus() {
		super(GreenCorps.CATEGORY, "map-duper-plus",
			"Copies maps and map bundles beside the inventory, crafting table and cartography table, under a 2b2t-safe click budget.");
	}

	public static MapDuperPlus get() {
		return Modules.get().get(MapDuperPlus.class);
	}

	/** Learned map centers; Tooltip+ and Map Slot Preview read them by reflection. */
	public MapCenterCache centers() {
		return centers;
	}

	public int copies() {
		return copies.get();
	}

	public void setCopies(int n) {
		copies.set(Math.max(1, Math.min(MAX_COPIES, n)));
	}

	public boolean clickToCopy() {
		return clickToCopy.get();
	}

	public void toggleClickToCopy() {
		clickToCopy.set(!clickToCopy.get());
		if (clickToCopy.get()) bundleSortMode = false;
	}

	/** Bundle Sort mode: the next bundle clicked is sorted. Off when the screen closes or something else is clicked. */
	public boolean bundleSort() {
		return bundleSortMode;
	}

	public void toggleBundleSort() {
		bundleSortMode = !bundleSortMode;
		if (bundleSortMode) clickToCopy.set(false);
	}

	public void endBundleSort() {
		if (!bundleSortMode) return;
		bundleSortMode = false;
		setStatus("Bundle Sort off", false);
	}

	/**
	 * Sort the bundle in {@code slotId}: its maps in artwork reading order (names, then edges; index 0 = top-left,
	 * so the rows-of-8 tooltip reads like the picture), then other stacks in their current order.
	 *
	 * @return true when the slot held a bundle with something in it
	 */
	public boolean startBundleSort(HandledScreen<?> screen, int slotId) {
		InvSim sim = snapshotForSort(screen);
		if (sim == null || mc.world == null || slotId < 0 || slotId >= sim.station.slotCount) return false;
		SimStack s = sim.slot(slotId);
		if (s.kind != SimStack.Kind.BUNDLE || s.contents.isEmpty()) return false;
		BundleContentsComponent contents = screen.getScreenHandler().getSlot(slotId).getStack().get(DataComponentTypes.BUNDLE_CONTENTS);
		if (contents == null) return false;
		List<ItemStack> stacks = new ArrayList<>();
		for (ItemStack st : contents.iterate()) stacks.add(st);
		List<Long> target = new ArrayList<>();
		List<MapartLayout.Group> groups = layout(stacks);
		for (MapartLayout.Group g : groups) target.addAll(g.order());
		for (ItemStack st : stacks) {
			long key = LiveInventory.key(st);
			if (!target.contains(key)) target.add(key);
		}
		CopyPlan plan = CopyPlanner.sortBundle(sim, slotId, target);
		if (plan.ok() && plan.clicks() == 0) {
			setStatus("Bundle is already sorted", false);
			return true;
		}
		start(screen, plan, "Bundle Sort");
		if (plan.ok() && isRunning()) setStatus("Bundle Sort: " + describe(groups) + ", " + plan.clicks() + " clicks", false);
		return true;
	}

	/** A click on anything but a map ends Click To Copy. */
	public void endClickToCopy() {
		if (!clickToCopy.get()) return;
		clickToCopy.set(false);
		setStatus("Click To Copy off", false);
	}

	/** Closing the screen ends Click To Copy, any pending confirm, and the session's passing messages. */
	public void onScreenClosed() {
		clickToCopy.set(false);
		bundleSortMode = false;
		confirmUntil = 0;
		if (!statusKeep) setStatus("", false);
	}

	private void setStatus(String text, boolean keep) {
		status = text == null ? "" : text;
		statusKeep = keep && !status.isEmpty();
	}

	/** True while Copy All waits for its confirming second click. */
	public boolean copyAllNeedsConfirm() {
		return System.currentTimeMillis() < confirmUntil;
	}

	public void cancelConfirm() {
		confirmUntil = 0;
	}

	public String status() {
		return status;
	}

	public List<String> pausedNames() {
		return pausedNames;
	}

	public boolean isRunning() {
		return runner != null || cleanup != null;
	}

	public CopyRunner runner() {
		return runner != null ? runner : cleanup;
	}

	public LiveInventory.Filters filters() {
		return new LiveInventory.Filters(skipUnlocked.get(), skipBlank.get(), skipUnnamed.get());
	}

	@Override
	public void onActivate() {
		status = "";
		warnInventoryEssentialsOnce();
	}

	@Override
	public void onDeactivate() {
		stop("Map Duper+ turned off");
		runner = null;
		cleanup = null;
		restoreConflicts();
		InventoryClickGuard.setNoDragMapsBundlesBanners(false);
	}

	@EventHandler
	private void onTick(TickEvent.Post event) {
		InventoryClickGuard.setNoDragMapsBundlesBanners(noDrag.get());
		ClickBudget.configure(clickLimit.get(), clickWindow.get(), adaptToTps.get());
		if (mc.player == null || mc.world == null) return;
		learnCenters();

		if (runner != null) {
			runner.tick(mc);
			if (runner.state() != CopyRunner.State.RUNNING) finishJob();
		} else if (cleanup != null) {
			cleanup.tick(mc);
			if (cleanup.state() != CopyRunner.State.RUNNING) finishCleanup();
		}
	}

	// -------------------------------------------------------------- jobs --

	/** Current inventory as a simulation, or null outside a supported screen. */
	public InvSim snapshot(HandledScreen<?> screen) {
		return snapshotAt(screen, LiveInventory.stationFor(screen));
	}

	/** Like {@link #snapshot}, for the screens Sort Maps also works in (open shulkers). */
	public InvSim snapshotForSort(HandledScreen<?> screen) {
		return snapshotAt(screen, LiveInventory.sortStationFor(screen));
	}

	private InvSim snapshotAt(HandledScreen<?> screen, Station st) {
		if (st == null || mc.player == null) return null;
		InvSim sim = LiveInventory.read(screen.getScreenHandler(), st, filters(), mc.world);
		if (sim != null) sim.setBundleInsertsAtEnd(reverseBundleIndex());
		return sim;
	}

	/** Copy All; a second Copy All within 10 s needs a confirming click. */
	public void startCopyAll(HandledScreen<?> screen) {
		long now = System.currentTimeMillis();
		if (now - lastCopyAllAt < CONFIRM_AFTER_MS && now >= confirmUntil) {
			confirmUntil = now + CONFIRM_WINDOW_MS;
			setStatus("Copy All again? Click Confirm", false);
			return;
		}
		confirmUntil = 0;
		InvSim sim = snapshot(screen);
		if (sim == null) return;
		lastCopyAllAt = now;
		start(screen, CopyPlanner.copyAll(sim, copies()), "Copy All");
	}

	/** Map stacks in the 27 slots Sort Maps arranges (main inventory rows, or the open shulker). */
	public int sortableMaps(InvSim sim) {
		if (sim == null) return 0;
		int n = 0;
		for (int id : CopyPlanner.mainSlots(sim.station)) {
			if (sim.slot(id).kind == SimStack.Kind.FILLED_MAP) n++;
		}
		return n;
	}

	/** Sort Maps: lay each artwork out tile by tile, from its names or, failing that, its matching edges. */
	public void startSort(HandledScreen<?> screen) {
		InvSim sim = snapshotForSort(screen);
		if (sim == null || mc.world == null) return;
		List<ItemStack> stacks = new ArrayList<>();
		for (int id : CopyPlanner.mainSlots(sim.station)) stacks.add(screen.getScreenHandler().getSlot(id).getStack());
		List<MapartLayout.Group> groups = layout(stacks);
		CopyPlan plan = CopyPlanner.sortMaps(sim, groups);
		if (plan.ok() && plan.clicks() == 0) {
			setStatus("Maps are already sorted", false);
			return;
		}
		start(screen, plan, "Sort Maps");
		if (plan.ok() && isRunning()) setStatus("Sort Maps: " + describe(groups) + ", " + plan.clicks() + " clicks", false);
	}

	/** Artworks among {@code stacks} (one tile per distinct map); with debug-log on, the tiles are dumped for tests. */
	private List<MapartLayout.Group> layout(List<ItemStack> stacks) {
		Map<Long, MapartLayout.Tile> tiles = new LinkedHashMap<>();
		for (ItemStack stack : stacks) {
			if (!stack.isOf(Items.FILLED_MAP)) continue;
			tiles.computeIfAbsent(LiveInventory.key(stack), key -> tile(key, stack));
		}
		List<MapartLayout.Tile> list = new ArrayList<>(tiles.values());
		List<MapartLayout.Group> groups = MapartLayout.arrange(list, MapDuperPlus::rgb);
		if (debugLog.get()) MapSortDump.write(list, groups, MapDuperPlus::rgb);
		return groups;
	}

	private MapartLayout.Tile tile(long key, ItemStack stack) {
		String name = stack.contains(DataComponentTypes.CUSTOM_NAME) ? stack.getName().getString() : null;
		MapState state = FilledMapItem.getMapState(stack, mc.world);
		byte[] colors = state == null ? null : state.colors;
		return new MapartLayout.Tile(key, name, colors == null ? null : colors.clone());
	}

	private static int rgb(int colorByte) {
		return MapColor.getRenderColor(colorByte & 0xFF) & 0xFFFFFF;
	}

	private static String describe(List<MapartLayout.Group> groups) {
		List<String> parts = new ArrayList<>();
		for (MapartLayout.Group g : groups) {
			String title = g.title().isEmpty() ? "unnamed" : g.title();
			parts.add(title + " " + g.width() + "×" + g.height() + " (" + g.method() + ")");
		}
		String joined = String.join(", ", parts);
		return joined.length() > 120 ? groups.size() + " artworks" : joined;
	}

	public void startAllBundles(HandledScreen<?> screen) {
		InvSim sim = snapshot(screen);
		if (sim != null) start(screen, CopyPlanner.allBundles(sim), "Copy Bundles");
	}

	/** Click To Copy on a slot. @return true when the slot held something it copies */
	public boolean startSlot(HandledScreen<?> screen, int slotId) {
		InvSim sim = snapshot(screen);
		if (sim == null || !sim.station.isInventory(slotId)) return false;
		SimStack s = sim.slot(slotId);
		if (s.kind == SimStack.Kind.FILLED_MAP) {
			start(screen, CopyPlanner.copies(sim, slotId, copies()), "Copy map");
			return true;
		}
		if (s.isMapBundle()) {
			start(screen, CopyPlanner.bundle(sim, slotId), "Copy bundle");
			return true;
		}
		return false;
	}

	private void start(HandledScreen<?> screen, CopyPlan plan, String label) {
		if (isRunning()) return;
		if (!plan.ok()) {
			setStatus(plan.error(), true);
			return;
		}
		pauseConflicts();
		runStation = plan.end().station;
		goal = plan.goal();
		jobLabel = label;
		replans = 0;
		launch(screen, plan);
		setStatus(label + ": " + plan.copies() + " cop" + (plan.copies() == 1 ? "y" : "ies") + ", " + plan.clicks() + " clicks", false);
	}

	private void launch(HandledScreen<?> screen, CopyPlan plan) {
		runner = new CopyRunner(runStation, screen.getScreenHandler().syncId, plan, clicksPerTick.get(),
			reverseBundleIndex(), debugLog.get(), jobLabel);
	}

	public void stop(String reason) {
		if (runner != null) runner.stop(reason);
	}

	private void finishJob() {
		CopyRunner done = runner;
		runner = null;
		switch (done.state()) {
			case DONE -> {
				setStatus(jobLabel + ": done", false);
				if (chatStatus.get()) info("%s finished.", jobLabel);
				endJob();
			}
			case MISMATCH -> {
				boolean retry = goal != null && replans < MAX_REPLANS;
				setStatus(jobLabel + ": " + done.message() + (retry ? "; re-planning" : "; stopped"), true);
				if (chatStatus.get()) warning("%s: %s; %s.", jobLabel, done.message(),
					retry ? "putting things back and planning the rest again (slower)" : "stopped");
				replanAfterCleanup = retry;
				startCleanup();
			}
			default -> {
				setStatus(done.message(), true);
				if (chatStatus.get()) warning("%s stopped: %s", jobLabel, done.message());
				replanAfterCleanup = false;
				startCleanup();
			}
		}
	}

	/** Put the cursor and grid back into the inventory; never drops anything. */
	private void startCleanup() {
		if (!(mc.currentScreen instanceof HandledScreen<?> screen) || runStation == null) {
			endJob();
			return;
		}
		InvSim sim = snapshotAt(screen, runStation);
		if (sim == null) {
			endJob();
			return;
		}
		if (sim.cursor().isNone() && sim.gridEmpty()) {
			afterCleanup(screen);
			return;
		}
		CopyPlan plan = CopyPlanner.cleanup(sim);
		if (!plan.ok()) {
			setStatus("Stopped: " + plan.error(), true);
			if (chatStatus.get()) warning("%s: %s", jobLabel, plan.error());
			endJob();
			return;
		}
		cleanup = new CopyRunner(runStation, screen.getScreenHandler().syncId, plan, clicksPerTick.get(), false, debugLog.get(), "Clean up");
	}

	private void finishCleanup() {
		CopyRunner done = cleanup;
		cleanup = null;
		if (done.state() != CopyRunner.State.DONE || !(mc.currentScreen instanceof HandledScreen<?> screen)) {
			setStatus("Stopped: could not put everything back (" + done.message() + ")", true);
			endJob();
			return;
		}
		afterCleanup(screen);
	}

	/** Cursor and grid are empty again: re-plan the rest of the job toward its goal, or end it. */
	private void afterCleanup(HandledScreen<?> screen) {
		if (!replanAfterCleanup || goal == null) {
			endJob();
			return;
		}
		replanAfterCleanup = false;
		InvSim sim = snapshotAt(screen, runStation);
		CopyPlan plan = sim == null ? CopyPlan.failed("screen changed") : CopyPlanner.toTargets(sim, goal);
		if (!plan.ok()) {
			setStatus(jobLabel + " stopped: " + plan.error(), true);
			endJob();
			return;
		}
		if (plan.steps().isEmpty()) {
			setStatus(jobLabel + ": done", false);
			if (chatStatus.get()) info("%s finished.", jobLabel);
			endJob();
			return;
		}
		replans++;
		setStatus(jobLabel + ": re-planned (" + replans + "/" + MAX_REPLANS + "), " + plan.copies() + " to go", true);
		launch(screen, plan);
	}

	private void endJob() {
		goal = null;
		replanAfterCleanup = false;
		restoreConflicts();
	}

	/** Whether the server counts bundle select indexes from the other end (bundle-order; Auto = 2b2t). */
	public boolean reverseBundleIndex() {
		return switch (bundleOrder.get()) {
			case On -> true;
			case Off -> false;
			case Auto -> WorldAllowlist.allowsJoin(WorldAllowlist.joinedHost(mc), WorldAllowlist.proxyAddresses());
		};
	}

	// ----------------------------------------------------------- centers --

	private void learnCenters() {
		for (int i = 0; i < mc.player.getInventory().size(); i++) learnCenter(mc.player.getInventory().getStack(i));
	}

	private void learnCenter(ItemStack stack) {
		if (!stack.isOf(Items.FILLED_MAP)) return;
		MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
		MapState state = FilledMapItem.getMapState(stack, mc.world);
		if (id != null && state != null) centers.learnFrom(id.id(), state);
	}

	// --------------------------------------------------------- conflicts --

	private void pauseConflicts() {
		pausedPrior.clear();
		pausedNames.clear();
		if (!pauseReplenish.get()) return;
		pauseIfActive("meteordevelopment.meteorclient.systems.modules.player.AutoReplenish", "AutoReplenish");
		pauseIfActive("meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks", "InventoryTweaks");
		pauseIfActive("bep.hax.modules.Replenish", "Replenish");
		pauseIfActive("bep.hax.modules.MapDuplicator", "Map Copier");
	}

	private void pauseIfActive(String className, String label) {
		Module mod = findByClassName(className);
		if (mod == null) return;
		pausedPrior.put(className, mod.isActive());
		if (mod.isActive()) {
			mod.toggle();
			pausedNames.add(label);
		}
	}

	private void restoreConflicts() {
		for (Map.Entry<String, Boolean> e : pausedPrior.entrySet()) {
			Module mod = findByClassName(e.getKey());
			if (mod != null && e.getValue() && !mod.isActive()) mod.toggle();
		}
		pausedPrior.clear();
		pausedNames.clear();
	}

	private static Module findByClassName(String className) {
		for (Module mod : Modules.get().getAll()) {
			if (mod.getClass().getName().equals(className)) return mod;
		}
		return null;
	}

	private void warnInventoryEssentialsOnce() {
		if (warnedInventoryEssentials) return;
		warnedInventoryEssentials = true;
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("inventoryessentials")) {
			warning("inventoryessentials is installed and cannot be paused by Map Duper+; turn its auto-refill off while copying.");
		}
	}

	@Override
	public NbtCompound toTag() {
		NbtCompound tag = super.toTag();
		if (tag == null) tag = new NbtCompound();
		tag.put("mapCenters", centers.toNbt());
		tag.putInt("clickPace", CLICK_PACE);
		return tag;
	}

	@Override
	public Module fromTag(NbtCompound tag) {
		super.fromTag(tag);
		if (tag.contains("mapCenters", NbtElement.LIST_TYPE)) {
			centers.fromNbt(tag.getList("mapCenters", NbtElement.COMPOUND_TYPE));
		}
		// Click pace defaults were retuned; saved values from an older pace go back to the new defaults once.
		if (tag.getInt("clickPace") < CLICK_PACE) {
			clickLimit.reset();
			clicksPerTick.reset();
		}
		return this;
	}

	@Override
	public String getInfoString() {
		CopyRunner r = runner();
		if (r == null) return clickToCopy.get() ? "click" : null;
		return r.clicksDone() + "/" + r.totalClicks();
	}
}
