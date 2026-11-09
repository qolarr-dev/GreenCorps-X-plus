package dev.greencorps.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.greencorps.GreenCorps;
import dev.greencorps.mapdupe.LiveInventory;
import dev.greencorps.util.BundleCycle;
import dev.greencorps.util.BundleSelect;
import dev.greencorps.util.ClickBudget;
import dev.greencorps.util.MapPreviewNames;
import dev.greencorps.util.MapPreviewStore;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BundleItem;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Draws cached mapart in inventory and container slots (including nested tooltips).
 * Learn swaps only into empty hotbar (order-safe) and defers container close until restore.
 */
public class MapSlotPreview extends Module {
	public enum LearnMode {
		Off("Off"),
		Hotbar("Hotbar"),
		HotbarAndOffhand("Hotbar+Offhand");

		private final String title;

		LearnMode(String title) {
			this.title = title;
		}

		@Override
		public String toString() {
			return title;
		}
	}

	private final SettingGroup sgGeneral = settings.getDefaultGroup();
	private final SettingGroup sgConflicts = settings.createGroup("Conflicts");

	private final Setting<Boolean> overlayBorder = sgGeneral.add(new BoolSetting.Builder()
		.name("overlay-border")
		.description("Draw slot preview at 18×18 centered on the item rect (over slot chrome). Off = exact 16×16.")
		.defaultValue(true)
		.build()
	);

	private final Setting<LearnMode> learn = sgGeneral.add(new EnumSetting.Builder<LearnMode>()
		.name("learn")
		.description("Fetch missing map pixels via empty-hotbar SWAP (order-safe) or select-flash. Offhand only if empty.")
		.defaultValue(LearnMode.HotbarAndOffhand)
		.build()
	);

	private final Setting<Boolean> debugLog = sgGeneral.add(new BoolSetting.Builder()
		.name("debug-log")
		.description("Write every step of loading the maps in a bundle to the game log (latest.log), to see what the server did.")
		.defaultValue(false)
		.build()
	);

	private final Setting<Boolean> overlappingPreviews = sgConflicts.add(new BoolSetting.Builder()
		.name("overlapping-previews")
		.description("Turn these off for best results: Meteor Better Tooltips → Maps; Rusher ExtraTooltips → Maps → Slot. Map hover lives on Tooltip+.")
		.defaultValue(true)
		.visible(this::hasPreviewConflicts)
		.build()
	);

	private final MapPreviewStore store = MapPreviewStore.get();

	private final Queue<LearnJob> queue = new ArrayDeque<>();
	private final Set<String> queuedKeys = new LinkedHashSet<>();
	private final Set<String> failedOnce = new LinkedHashSet<>();
	/** Maps swapped into empty hotbar, awaiting select/restore to origin slotId. */
	private final List<LearnJob> staged = new ArrayList<>();
	private final Set<Integer> reservedHotbar = new HashSet<>();

	private LearnPhase phase = LearnPhase.IDLE;
	private LearnJob active;
	private int savedSelectedSlot = -1;
	private int holdTicks;
	private int waitVisibleTicks;
	private boolean warnedInventoryEssentials;
	private final Map<String, Boolean> pausedPrior = new LinkedHashMap<>();
	private String lastServerKey = "";
	private final Map<Integer, ItemStack> lastWatchedStacks = new HashMap<>();
	private ItemStack lastCursor = ItemStack.EMPTY;
	private boolean watchedChangedThisTick;
	private boolean pixelsReadyForActive;
	private boolean clickedThisTick;
	private long rusherConflictCacheMs;
	private boolean rusherConflictCached;
	private boolean pendingClose;
	private boolean allowClose;
	private boolean stopStaging;
	/** Bundle in your inventory whose maps are being loaded one by one (see {@link #tickBundle}). */
	private BundleJob bundleJob;
	private boolean warnedNoBundleHotbar;
	/** No bundle job before this (after a server correction or an unfinished cycle). */
	private long bundleRetryAt;
	/** Last change to the player inventory or cursor; a bundle job waits until it is {@link #BUNDLE_QUIET_MS} old. */
	private long lastInventoryActivity;
	private int inventoryFingerprint;
	/** Map id → times it was held in a bundle job without its pixels arriving. */
	private final Map<Integer, Integer> bundleMisses = new HashMap<>();
	/** Bundle jobs stopped by a mismatch since the screen opened; at {@link #BUNDLE_MAX_ABORTS} it stops trying. */
	private int bundleAborts;
	private String lastBundleWait;
	/** Where put-backs landed on the last server a bundle job ran on (see {@link BundleJob#putBackAtEnd}). */
	private Boolean putBackAtEndHint;
	private String putBackHintServer;
	private static final int BUNDLE_MAX_ABORTS = 3;
	private static final long BUNDLE_QUIET_MS = 1000;
	private static final long BUNDLE_RETRY_MS = 5000;
	private static final long BUNDLE_MISMATCH_GRACE_MS = 1500;
	/** A cycle round takes up to 4 clicks; start only with some headroom. */
	private static final int BUNDLE_MIN_BUDGET = 8;

	private enum LearnPhase {
		IDLE,
		STAGE_SWAP,
		STAGE_WAIT,
		FLASH_SELECT,
		FLASH_HOLD,
		FLASH_RESTORE,
		SWAP_OUT,
		WAIT_OUT,
		HOLD,
		SWAP_BACK,
		WAIT_BACK
	}

	private enum LearnPath {
		HOTBAR_FLASH,
		HOTBAR_SWAP,
		OFFHAND
	}

	private static final class LearnJob {
		final String key;
		final int mapId;
		final String name;
		LearnPath path;
		int originSlotId = -1;
		int hotbarIndex = -1;
		boolean didHotbarSwap;
		boolean didOffhandSwap;

		LearnJob(String key, int mapId, String name, LearnPath path) {
			this.key = key;
			this.mapId = mapId;
			this.name = name;
			this.path = path;
		}
	}

	private enum BundleStep {
		/** Take the round's stack onto the cursor (and straight back in when there is nothing to load). */
		SELECT,
		/** Check what came out, park it in the free hotbar slot and select that slot. */
		PLACE,
		/** Hold it until its pixels arrive, then pick it up and put it back. */
		FLASH_HOLD,
		/** Check the bundle is whole again and in the expected order before the next stack. */
		ROUND_DONE
	}

	/**
	 * Takes the bundle's last stack out, parks it in an empty hotbar slot (held there while it is a map with no
	 * preview), and puts it back, where it lands at the front. {@link BundleCycle} repeats that once per stack,
	 * so the bundle ends in its original order.
	 */
	private static final class BundleJob {
		final int syncId;
		final int slotId;
		final int hotbarIndex;
		final int hotbarSlotId;
		BundleCycle cycle;
		/**
		 * Where a stack put back lands in the bundle as the client sees it once the server has answered: the front
		 * (vanilla) or the end (2b2t). Null until the first round shows it. Front: take the last stack each round;
		 * end: take the first. Either way the bundle ends in its order.
		 */
		Boolean putBackAtEnd;
		/** You clicked: stop once the current stack is back in. */
		boolean stop;
		/** The contents' keys at the start, in order: round k takes the stack that was at size-1-k. */
		final List<Long> order;
		BundleStep step = BundleStep.SELECT;
		/** Key of the stack this round takes out. */
		long targetKey;
		/** Stop holding maps and just finish the cycle (screen closing, or you clicked). */
		boolean fast;
		/** The stack taken out this round, while it is on the cursor or parked. */
		ItemStack taken = ItemStack.EMPTY;
		int heldMapId = -1;
		String heldName;
		/** Next step not before this (waiting for the server's answer). */
		long waitUntil;
		long holdUntil;
		/** Last server update to the screen (its revision changed); steps wait until it is a settle time old. */
		int lastRevision = Integer.MIN_VALUE;
		long lastChange;
		/** When the slots first stopped matching what this step expects (0 = they match). */
		long mismatchSince;
		/** The screen's revision when the last click went out; another one means the server has answered since. */
		int clickRevision;
		BundleStep checkedStep;

		long targetKey() {
			return putBackAtEnd == Boolean.TRUE ? order.get(cycle.round()) : order.get(cycle.originalIndex());
		}

		int takeIndex() {
			return putBackAtEnd == Boolean.TRUE ? 0 : cycle.takeIndex();
		}

		BundleJob(int syncId, int slotId, int hotbarIndex, int hotbarSlotId, int size, List<Long> order) {
			this.order = order;
			this.syncId = syncId;
			this.slotId = slotId;
			this.hotbarIndex = hotbarIndex;
			this.hotbarSlotId = hotbarSlotId;
			this.cycle = new BundleCycle(size);
		}
	}

	public MapSlotPreview() {
		super(GreenCorps.CATEGORY, "map-slot-preview",
			"Shows mapart in inventory and container slots. Saves pieces by pixel content under config/greencorps/map-previews.");
	}

	public static MapSlotPreview get() {
		return Modules.get().get(MapSlotPreview.class);
	}

	/**
	 * @return true to cancel the screen close (we still have maps out of place).
	 */
	public boolean onContainerCloseAttempt() {
		if (!isActive() || allowClose) return false;
		if (!hasOutstandingSwaps()) return false;
		pendingClose = true;
		stopStaging = true;
		queue.clear();
		queuedKeys.clear();
		requestImmediateRestore();
		return true;
	}

	/** Screen was removed anyway (server / allowClose). Best-effort restore if handler still valid. */
	public void onContainerForceClosed() {
		if (allowClose) {
			clearLearnState();
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc != null && mc.player != null && hasOutstandingSwaps()) {
			bestEffortRestoreNow(mc);
		}
		clearLearnState();
		pendingClose = false;
		stopStaging = false;
	}

	public boolean hasOutstandingSwaps() {
		if (bundleJob != null) return true;
		if (!staged.isEmpty()) return true;
		if (active == null) return false;
		return active.didHotbarSwap || active.didOffhandSwap
			|| phase == LearnPhase.STAGE_SWAP || phase == LearnPhase.STAGE_WAIT
			|| phase == LearnPhase.FLASH_SELECT || phase == LearnPhase.FLASH_HOLD
			|| phase == LearnPhase.FLASH_RESTORE
			|| phase == LearnPhase.SWAP_OUT || phase == LearnPhase.WAIT_OUT
			|| phase == LearnPhase.HOLD || phase == LearnPhase.SWAP_BACK || phase == LearnPhase.WAIT_BACK;
	}

	private void requestImmediateRestore() {
		if (bundleJob != null) bundleJob.fast = true;
		if (phase == LearnPhase.FLASH_HOLD || phase == LearnPhase.STAGE_WAIT || phase == LearnPhase.HOLD) {
			if (active != null && active.didHotbarSwap) phase = LearnPhase.FLASH_RESTORE;
			else if (active != null && active.didOffhandSwap) phase = LearnPhase.SWAP_BACK;
		} else if (phase == LearnPhase.STAGE_SWAP || phase == LearnPhase.FLASH_SELECT) {
			if (active != null && active.didHotbarSwap) phase = LearnPhase.FLASH_RESTORE;
		}
	}

	/** Vanilla {@code DrawContext.drawItem} draws flat item models at +150 relative to the caller. */
	private static final float ITEM_LAYER_Z = 150f;

	/** Called from DrawContext mixin. Returns true if a preview was drawn. */
	public static boolean tryDrawPreview(DrawContext context, ItemStack stack, int x, int y) {
		MapSlotPreview mod = get();
		if (mod == null || !mod.isActive()) return false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return false;
		if (!stack.isOf(Items.FILLED_MAP)) return false;

		Identifier tex = mod.resolveTexture(stack);
		if (tex == null) return false;

		// Same depth as vanilla's flat item sprite (drawItem translates +150 for non-3D models), so the
		// stack count / durability bar at +200 stays on top and tooltips (z 400) are never covered.
		MatrixStack matrices = context.getMatrices();
		matrices.push();
		matrices.translate(0, 0, ITEM_LAYER_Z);
		if (mod.overlayBorder.get()) {
			context.drawTexture(RenderLayer::getGuiTextured, tex, x - 1, y - 1, 0f, 0f, 18, 18, 128, 128, 128, 128);
		} else {
			context.drawTexture(RenderLayer::getGuiTextured, tex, x, y, 0f, 0f, 16, 16, 128, 128, 128, 128);
		}
		matrices.pop();
		return true;
	}

	private Identifier resolveTexture(ItemStack stack) {
		MinecraftClient mc = MinecraftClient.getInstance();
		String name = MapPreviewNames.keyOf(stack);
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		Integer mapId = mid == null ? null : mid.id();
		MapState state = (mc != null && mc.world != null) ? FilledMapItem.getMapState(stack, mc.world) : null;

		if (state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors)) {
			store.put(state.colors, mapId, name);
			if (mapId != null) {
				Identifier t = store.textureForMapId(mapId);
				if (t != null) return t;
			}
			return store.textureForUniqueName(name);
		}

		if (mapId != null) {
			Identifier t = store.textureForMapId(mapId);
			if (t != null) return t;
		}
		return store.textureForUniqueName(name);
	}

	/** Reflection-safe: shareable jars may omit MapDuperPlus. */
	private static boolean isMapDuperCloning() {
		try {
			Module m = Modules.get().get("map-duper-plus");
			if (m == null || !m.isActive()) return false;
			Object r = m.getClass().getMethod("isRunning").invoke(m);
			return Boolean.TRUE.equals(r);
		} catch (Throwable t) {
			return false;
		}
	}

	private boolean hasPreviewConflicts() {
		return isMeteorMapsOn() || isRusherMapsSlotOn();
	}

	private boolean isMeteorMapsOn() {
		try {
			Module bt = Modules.get().get("better-tooltips");
			if (bt == null || !bt.isActive()) return false;
			Setting<?> maps = bt.settings.get("maps");
			return maps != null && Boolean.TRUE.equals(maps.get());
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean isRusherMapsSlotOn() {
		long now = System.currentTimeMillis();
		if (now - rusherConflictCacheMs < 1000L) return rusherConflictCached;
		rusherConflictCacheMs = now;
		rusherConflictCached = readRusherMapsSlotFromDisk();
		return rusherConflictCached;
	}

	private static boolean readRusherMapsSlotFromDisk() {
		try {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc == null) return false;
			Path file = mc.runDirectory.toPath().resolve("rusherhack/config/modules/construct.json");
			if (!Files.isRegularFile(file)) return false;
			String raw = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement root = JsonParser.parseString(raw);
			if (!root.isJsonArray()) return false;
			for (JsonElement el : root.getAsJsonArray()) {
				if (!el.isJsonObject()) continue;
				JsonObject mod = el.getAsJsonObject();
				if (!"ExtraTooltips".equals(mod.has("name") ? mod.get("name").getAsString() : "")) continue;
				if (mod.has("toggled") && !mod.get("toggled").getAsBoolean()) return false;
				if (!mod.has("settings") || !mod.get("settings").isJsonArray()) return false;
				for (JsonElement se : mod.getAsJsonArray("settings")) {
					if (!se.isJsonObject()) continue;
					JsonObject s = se.getAsJsonObject();
					if (!"maps".equals(s.has("name") ? s.get("name").getAsString() : "")) continue;
					if (!s.has("value") || !s.get("value").getAsBoolean()) return false;
					if (!s.has("subSettings") || !s.get("subSettings").isJsonArray()) return false;
					for (JsonElement sub : s.getAsJsonArray("subSettings")) {
						if (!sub.isJsonObject()) continue;
						JsonObject ss = sub.getAsJsonObject();
						if ("slot".equals(ss.has("name") ? ss.get("name").getAsString() : "")
							&& ss.has("value") && ss.get("value").getAsBoolean()) {
							return true;
						}
					}
					return false;
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	@Override
	public void onActivate() {
		warnInventoryEssentialsOnce();
		refreshServerKey();
		store.clearSession();
		clearLearnState();
	}

	@Override
	public void onDeactivate() {
		abortLearn();
		restoreConflicts();
		clearLearnState();
	}

	@EventHandler
	private void onTick(TickEvent.Pre event) {
		clickedThisTick = false;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null || mc.world == null) return;

		refreshServerKey();
		ingestLiveColors(mc);
		markWatchedChanges(mc);
		trackInventoryActivity(mc);

		if (pendingClose && !hasOutstandingSwaps()) {
			performDeferredClose(mc);
			snapshotWatched(mc);
			return;
		}

		LearnMode mode = learn.get();
		if (mode == LearnMode.Off) {
			if (phase != LearnPhase.IDLE || !staged.isEmpty()) abortLearn();
			restoreConflicts();
			queue.clear();
			queuedKeys.clear();
			snapshotWatched(mc);
			return;
		}

		if (isMapDuperCloning()) {
			if (phase != LearnPhase.IDLE || !staged.isEmpty()) abortLearn();
			snapshotWatched(mc);
			return;
		}

		if (!stopStaging && !pendingClose) {
			enqueueMissingMaps(mc, mode);
		}
		tickLearn(mc, mode);
		snapshotWatched(mc);
	}

	@EventHandler
	private void onPacket(PacketEvent.Receive event) {
		if (!(event.packet instanceof MapUpdateS2CPacket pkt)) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.world == null) return;
		MapIdComponent mapIdComp = pkt.mapId();
		int id = mapIdComp.id();
		mc.execute(() -> {
			if (mc.world == null) return;
			MapState state = mc.world.getMapState(mapIdComp);
			if (state == null || state.colors == null || MapPreviewStore.isBlank(state.colors)) return;
			String name = findNameForMapId(mc, id);
			store.put(state.colors, id, name);
			if ((active != null && active.mapId == id) || (bundleJob != null && bundleJob.heldMapId == id)) {
				pixelsReadyForActive = true;
			}
		});
	}

	private void performDeferredClose(MinecraftClient mc) {
		if (mc.player == null) {
			pendingClose = false;
			stopStaging = false;
			return;
		}
		allowClose = true;
		pendingClose = false;
		stopStaging = false;
		try {
			mc.player.closeHandledScreen();
		} finally {
			allowClose = false;
		}
	}

	private void refreshServerKey() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) return;
		String key;
		if (mc.isInSingleplayer() && mc.getServer() != null) {
			key = mc.getServer().getSaveProperties().getLevelName();
		} else {
			ServerInfo info = mc.getCurrentServerEntry();
			key = info != null ? info.address : "unknown";
		}
		if (!key.equals(lastServerKey)) {
			lastServerKey = key;
			store.onServerKey(key);
			failedOnce.clear();
		}
	}

	private void ingestLiveColors(MinecraftClient mc) {
		if (!(mc.currentScreen instanceof HandledScreen<?>)) {
			scanStacks(mc, mc.player.getInventory().main);
			scanStacks(mc, List.of(mc.player.getOffHandStack()));
			return;
		}
		ScreenHandler handler = mc.player.currentScreenHandler;
		for (Slot slot : handler.slots) {
			absorbStack(mc, slot.getStack());
		}
		absorbStack(mc, handler.getCursorStack());
	}

	private void scanStacks(MinecraftClient mc, Iterable<ItemStack> stacks) {
		for (ItemStack stack : stacks) absorbStack(mc, stack);
	}

	private void absorbStack(MinecraftClient mc, ItemStack stack) {
		if (stack != null && stack.getItem() instanceof BundleItem) {
			// Maps inside a bundle whose pixels the game already has need no trip through the hotbar.
			BundleContentsComponent contents = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
			if (contents != null) for (ItemStack inner : contents.iterate()) if (needsLoading(inner)) absorbStack(mc, inner);
			return;
		}
		if (stack == null || !stack.isOf(Items.FILLED_MAP)) return;
		MapState state = FilledMapItem.getMapState(stack, mc.world);
		if (state == null || state.colors == null || MapPreviewStore.isBlank(state.colors)) return;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		store.put(state.colors, mid == null ? null : mid.id(), MapPreviewNames.keyOf(stack));
	}

	private void enqueueMissingMaps(MinecraftClient mc, LearnMode mode) {
		ScreenHandler handler = mc.player.currentScreenHandler;
		boolean allowContainer = mode == LearnMode.HotbarAndOffhand && isOpenStorageScreen(mc);

		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP)) continue;

			boolean playerSlot = isPlayerMainOrHotbar(slot, mc);
			boolean containerSlot = allowContainer && isStorageContainerSlot(slot, mc);
			if (!playerSlot && !containerSlot) continue;

			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid == null) continue;
			int mapId = mid.id();
			String name = MapPreviewNames.keyOf(stack);
			String key = "i:" + mapId;

			if (store.hasPreviewFor(mapId, name)) continue;
			if (queuedKeys.contains(key)) continue;
			if (failedOnce.contains(key)) continue;
			if (active != null && key.equals(active.key)) continue;
			if (isStagedKey(key)) continue;
			if (isSelectedHotbarMap(mc, mapId) || isOffhandMap(mc, mapId)) continue;

			LearnPath path;
			int invIndex = slot.getIndex();
			if (playerSlot && invIndex >= 0 && invIndex < 9) {
				path = LearnPath.HOTBAR_FLASH;
			} else if (playerSlot || containerSlot) {
				path = LearnPath.HOTBAR_SWAP;
			} else {
				continue;
			}

			LearnJob job = new LearnJob(key, mapId, name, path);
			job.originSlotId = i;
			queue.offer(job);
			queuedKeys.add(key);
		}
	}

	private boolean isStagedKey(String key) {
		for (LearnJob j : staged) {
			if (j.key.equals(key)) return true;
		}
		return false;
	}

	private void tickLearn(MinecraftClient mc, LearnMode mode) {
		if (mc.interactionManager == null || mc.getNetworkHandler() == null) return;
		if (mc.player.isUsingItem() || mc.player.handSwinging) return;

		ScreenHandler handler = mc.player.currentScreenHandler;
		if (bundleJob != null) {
			tickBundle(mc, handler);
			return;
		}
		if (!handler.getCursorStack().isEmpty()) return;

		boolean chain = true;
		while (chain) {
			chain = false;
			switch (phase) {
				case IDLE -> {
					if (pendingClose) {
						if (!staged.isEmpty()) {
							active = staged.remove(0);
							reservedHotbar.remove(active.hotbarIndex);
							savedSelectedSlot = mc.player.getInventory().selectedSlot;
							phase = LearnPhase.FLASH_RESTORE;
							chain = true;
							break;
						}
						restoreConflicts();
						return;
					}

					// Prefer flashing already-staged maps, then stage more, then flash-native / offhand.
					if (!staged.isEmpty() && findEmptyHotbar(mc) < 0) {
						startFlashStaged(mc);
						chain = true;
						break;
					}
					if (!stopStaging && tryStartStage(mc, handler, mode)) {
						chain = true;
						break;
					}
					if (!staged.isEmpty()) {
						startFlashStaged(mc);
						chain = true;
						break;
					}

					LearnJob next = pollNextJob();
					if (next == null) {
						if (!stopStaging && startBundleJob(mc, handler)) return;
						restoreConflicts();
						return;
					}
					if (store.hasPreviewFor(next.mapId, next.name)) {
						chain = true;
						break;
					}
					int slotId = findOriginSlot(handler, mc, next);
					if (slotId < 0) {
						chain = true;
						break;
					}
					if (jobSlotBusy(handler, slotId)) {
						queue.offer(next);
						queuedKeys.add(next.key);
						return;
					}
					active = next;
					active.originSlotId = slotId;
					if (next.path == LearnPath.HOTBAR_FLASH) {
						Slot slot = handler.getSlot(slotId);
						if (isPlayerHotbar(slot, mc)) {
							active.hotbarIndex = slot.getIndex();
							savedSelectedSlot = mc.player.getInventory().selectedSlot;
							phase = LearnPhase.FLASH_SELECT;
						} else {
							finishActive(false);
							chain = true;
						}
					} else if (next.path == LearnPath.OFFHAND && mode == LearnMode.HotbarAndOffhand
						&& mc.player.getOffHandStack().isEmpty()) {
						pauseConflicts();
						active.didOffhandSwap = true;
						phase = LearnPhase.SWAP_OUT;
					} else {
						finishActive(false);
						chain = true;
					}
				}
				case STAGE_SWAP -> {
					if (active == null || active.hotbarIndex < 0) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					if (jobSlotBusy(handler, active.originSlotId) || clickedThisTick) return;
					if (!mc.player.getInventory().getStack(active.hotbarIndex).isEmpty()) {
						reservedHotbar.remove(active.hotbarIndex);
						finishActive(false);
						chain = true;
						break;
					}
					clickSwapHotbar(handler, active.originSlotId, active.hotbarIndex);
					active.didHotbarSwap = true;
					phase = LearnPhase.STAGE_WAIT;
					waitVisibleTicks = 0;
				}
				case STAGE_WAIT -> {
					waitVisibleTicks++;
					if (isHotbarMatching(mc, active)) {
						staged.add(active);
						active = null;
						phase = LearnPhase.IDLE;
						chain = !pendingClose;
					} else if (waitVisibleTicks > 10) {
						reservedHotbar.remove(active.hotbarIndex);
						finishActive(false);
						chain = true;
					}
				}
				case FLASH_SELECT -> {
					if (active == null || active.hotbarIndex < 0) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					setSelectedSlot(mc, active.hotbarIndex);
					phase = LearnPhase.FLASH_HOLD;
					holdTicks = 0;
					pixelsReadyForActive = false;
				}
				case FLASH_HOLD -> {
					holdTicks++;
					ItemStack held = mc.player.getMainHandStack();
					MapState state = FilledMapItem.getMapState(held, mc.world);
					boolean got = state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors);
					if (got) {
						MapIdComponent mid = held.get(DataComponentTypes.MAP_ID);
						store.put(state.colors, mid == null ? active.mapId : mid.id(), MapPreviewNames.keyOf(held));
					}
					if (pixelsReadyForActive || got || pendingClose) {
						pixelsReadyForActive = false;
						phase = LearnPhase.FLASH_RESTORE;
					} else if (holdTicks >= 40) {
						phase = LearnPhase.FLASH_RESTORE;
					}
				}
				case FLASH_RESTORE -> {
					if (active != null && active.didHotbarSwap && active.hotbarIndex >= 0 && !clickedThisTick) {
						if (isHotbarMatching(mc, active)) {
							clickSwapHotbar(handler, active.originSlotId, active.hotbarIndex);
						}
						reservedHotbar.remove(active.hotbarIndex);
					}
					if (savedSelectedSlot >= 0) setSelectedSlot(mc, savedSelectedSlot);
					finishActive(true);
					chain = hasOutstandingSwaps() || (!queue.isEmpty() && !pendingClose);
				}
				case SWAP_OUT -> {
					if (active == null) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					if (!mc.player.getOffHandStack().isEmpty()) {
						finishActive(false);
						chain = true;
						break;
					}
					if (jobSlotBusy(handler, active.originSlotId) || clickedThisTick) return;
					clickSwapOffhand(handler, active.originSlotId);
					active.didOffhandSwap = true;
					phase = LearnPhase.WAIT_OUT;
					waitVisibleTicks = 0;
				}
				case WAIT_OUT -> {
					waitVisibleTicks++;
					if (isOffhandMatching(mc, active)) {
						phase = LearnPhase.HOLD;
						holdTicks = 0;
						pixelsReadyForActive = false;
					} else if (waitVisibleTicks > 10) {
						finishActive(false);
						chain = true;
					}
				}
				case HOLD -> {
					holdTicks++;
					ItemStack off = mc.player.getOffHandStack();
					MapState state = FilledMapItem.getMapState(off, mc.world);
					boolean got = state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors);
					if (got) {
						MapIdComponent mid = off.get(DataComponentTypes.MAP_ID);
						store.put(state.colors, mid == null ? active.mapId : mid.id(), MapPreviewNames.keyOf(off));
					}
					if (pixelsReadyForActive || got || pendingClose) {
						pixelsReadyForActive = false;
						phase = LearnPhase.SWAP_BACK;
					} else if (holdTicks >= 40) {
						phase = LearnPhase.SWAP_BACK;
					}
				}
				case SWAP_BACK -> {
					if (active == null) {
						phase = LearnPhase.IDLE;
						chain = true;
						break;
					}
					if (!canRestoreOffhand(mc, handler)) {
						finishActive(false);
						chain = true;
						break;
					}
					if (clickedThisTick) return;
					clickSwapOffhand(handler, active.originSlotId);
					phase = LearnPhase.WAIT_BACK;
					waitVisibleTicks = 0;
				}
				case WAIT_BACK -> {
					waitVisibleTicks++;
					if (restored(mc, handler) || waitVisibleTicks > 10) {
						finishActive(true);
						chain = hasOutstandingSwaps() || (!queue.isEmpty() && !pendingClose);
					}
				}
			}
		}
	}

	/**
	 * A bundle in your inventory holding maps with no preview, and an empty hotbar slot to load them through.
	 * Waits until your inventory has been still for a moment (your own clicks and the job's would cross on the
	 * server) and after a server correction, and needs a few clicks left in the click budget.
	 */
	private boolean startBundleJob(MinecraftClient mc, ScreenHandler handler) {
		if (!(mc.currentScreen instanceof HandledScreen<?>) || !staged.isEmpty()) return false;
		long now = System.currentTimeMillis();
		String wait = bundleAborts >= BUNDLE_MAX_ABORTS ? "gave up after " + BUNDLE_MAX_ABORTS + " tries"
			: now < bundleRetryAt ? "retry pause"
			: now - lastInventoryActivity < BUNDLE_QUIET_MS ? "inventory not still yet"
			: !handler.getCursorStack().isEmpty() ? "cursor not empty"
			: !ClickBudget.canSend(BUNDLE_MIN_BUDGET) ? "click budget (" + ClickBudget.remaining() + " left)" : null;
		if (wait != null) {
			if (debugLog.get() && !wait.equals(lastBundleWait) && anyBundleNeedsLoading(mc, handler)) debug("waiting: %s", wait);
			lastBundleWait = wait;
			return false;
		}
		lastBundleWait = null;
		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (!isPlayerMainOrHotbar(slot, mc)) continue;
			ItemStack bundle = slot.getStack();
			if (!(bundle.getItem() instanceof BundleItem) || !bundleNeedsLoading(bundle)) continue;
			int hb = findEmptyHotbar(mc);
			int hbSlot = hb < 0 ? -1 : playerSlotId(handler, mc, hb);
			if (hbSlot < 0) {
				if (!warnedNoBundleHotbar) {
					warnedNoBundleHotbar = true;
					info("Free a hotbar slot to load the maps in your bundle.");
				}
				return false;
			}
			warnedNoBundleHotbar = false;
			List<Long> order = bundleKeys(bundle);
			bundleJob = new BundleJob(handler.syncId, i, hb, hbSlot, order.size(), order);
			if (putBackAtEndHint != null && serverId(mc).equals(putBackHintServer)) bundleJob.putBackAtEnd = putBackAtEndHint;
			debug("start: bundle slot %d, %d stacks %s, park in hotbar %d (slot %d), server index %s",
				i, order.size(), describeContents(bundle), hb, hbSlot, BundleSelect.reversed(mc) ? "mirrored" : "as shown");
			reservedHotbar.add(hb);
			savedSelectedSlot = mc.player.getInventory().selectedSlot;
			pauseConflicts();
			return true;
		}
		return false;
	}

	private boolean anyBundleNeedsLoading(MinecraftClient mc, ScreenHandler handler) {
		for (Slot slot : handler.slots) {
			if (isPlayerMainOrHotbar(slot, mc) && slot.getStack().getItem() instanceof BundleItem && bundleNeedsLoading(slot.getStack())) return true;
		}
		return false;
	}

	private boolean bundleNeedsLoading(ItemStack bundle) {
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (contents == null) return false;
		for (ItemStack stack : contents.iterate()) {
			if (needsLoading(stack)) return true;
		}
		return false;
	}

	private boolean needsLoading(ItemStack stack) {
		if (stack == null || !stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		if (mid == null || failedOnce.contains("i:" + mid.id())) return false;
		return !store.hasPreviewFor(mid.id(), MapPreviewNames.keyOf(stack));
	}

	private static int bundleSize(ItemStack bundle) {
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		return contents == null ? 0 : contents.size();
	}

	private static List<Long> bundleKeys(ItemStack bundle) {
		List<Long> keys = new ArrayList<>();
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (contents != null) for (ItemStack st : contents.iterate()) keys.add(LiveInventory.key(st));
		return keys;
	}

	private static ItemStack bundleStack(ItemStack bundle, int index) {
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		return contents == null || index < 0 || index >= contents.size() ? ItemStack.EMPTY : contents.get(index);
	}

	private static String describe(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "empty";
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		if (mid != null) return "map#" + mid.id();
		if (stack.getItem() instanceof BundleItem) return "bundle(" + bundleSize(stack) + ")";
		return stack.getCount() + "x" + stack.getItem();
	}

	private static String describeContents(ItemStack bundle) {
		List<String> out = new ArrayList<>();
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (contents != null) for (ItemStack st : contents.iterate()) out.add(describe(st));
		return out.toString();
	}

	private void debug(String format, Object... args) {
		if (debugLog.get()) GreenCorps.LOG.info("[Map Slot Preview] bundle " + String.format(format, args));
	}

	/**
	 * One step per tick at most. After a click that the next step depends on, waits ping + 50 ms for the server's
	 * answer and then checks the slots; if they are not as expected (the server undid a click, or you moved
	 * something), the job puts back what it holds and stops, and tries again a few seconds later.
	 */
	private void tickBundle(MinecraftClient mc, ScreenHandler handler) {
		BundleJob job = bundleJob;
		if (handler.syncId != job.syncId || job.slotId >= handler.slots.size() || job.hotbarSlotId >= handler.slots.size()) {
			abortBundle(mc, handler, "the screen changed");
			return;
		}
		long now = System.currentTimeMillis();
		if (handler.getRevision() != job.lastRevision) {
			job.lastRevision = handler.getRevision();
			job.lastChange = now;
		}
		// The server answers a click it disagrees with by resending the slots, sometimes after the next click went
		// out, so the screen briefly shows an older state. Act only once it has been quiet for a settle time.
		if (clickedThisTick || now < job.waitUntil || now - job.lastChange < settleMs(mc)) return;
		if (job.step != job.checkedStep) {
			job.checkedStep = job.step;
			job.mismatchSince = 0;
		}
		ItemStack bundle = handler.getSlot(job.slotId).getStack();
		ItemStack cursor = handler.getCursorStack();
		ItemStack parked = handler.getSlot(job.hotbarSlotId).getStack();
		if (bundle.isEmpty() && cursor.getItem() instanceof BundleItem && job.taken.isEmpty()) {
			// The server picked the whole bundle up instead of taking a stack out: put it straight back.
			if (graceLeft(job, "the bundle is on the cursor")) return;
			clickSlot(handler, job.slotId, 0);
			abortBundle(mc, handler, "the server picked up the bundle instead of taking a stack out");
			return;
		}
		if (!(bundle.getItem() instanceof BundleItem)) {
			mismatch(mc, handler, job, "the bundle left its slot");
			return;
		}
		switch (job.step) {
			case SELECT -> {
				if (!cursor.isEmpty()) {
					// Yours: the take-out click would put it into the bundle. Wait, unless the screen is closing.
					if (pendingClose) finishBundle(mc, false);
					return;
				}
				if (!parked.isEmpty() || bundleSize(bundle) != job.cycle.size()) {
					mismatch(mc, handler, job, "before round " + (job.cycle.round() + 1) + ": parked " + describe(parked) + ", " + bundleSize(bundle) + " stacks");
					return;
				}
				if (job.stop) {
					boolean inOrder = bundleKeys(bundle).equals(job.order);
					debug("stopped for your click after %d rounds, order %s", job.cycle.round(), inOrder ? "kept" : "CHANGED");
					if (!inOrder) info("Stopped loading the bundle's maps because you clicked; its order has shifted.");
					finishBundle(mc, false);
					return;
				}
				if (!ClickBudget.canSend(2)) return;
				job.targetKey = job.targetKey();
				int index = job.takeIndex();
				ItemStack next = bundleStack(bundle, index);
				if (LiveInventory.key(next) != job.targetKey) {
					// Not where this round expects it: the screen has not caught up with the server yet.
					mismatch(mc, handler, job, "round " + (job.cycle.round() + 1) + ": index " + index + " holds "
						+ describe(next) + ", not the next stack");
					return;
				}
				boolean load = !job.fast && needsLoading(next);
				debug("round %d/%d: %s index %d (server %d)", job.cycle.round() + 1, job.cycle.size(),
					load ? "load" : "pass", index, BundleSelect.serverIndex(mc, index, job.cycle.size()));
				job.taken = next.copy();
				BundleSelect.select(mc, job.slotId, bundle, index);
				clickSlot(handler, job.slotId, 1);
				if (load) {
					job.step = BundleStep.PLACE;
				} else {
					// Nothing to load: straight back in with the same tick; ROUND_DONE checks the whole bundle.
					clickSlot(handler, job.slotId, 0);
					job.step = BundleStep.ROUND_DONE;
				}
				settle(mc, job);
			}
			case PLACE -> {
				if (cursor.isEmpty() || bundleSize(bundle) != job.cycle.size() - 1) {
					mismatch(mc, handler, job, "after the take: cursor " + describe(cursor) + ", " + bundleSize(bundle) + " stacks");
					return;
				}
				if (LiveInventory.key(cursor) != job.targetKey) {
					// The server handed out another stack than the one selected: back in, and stop.
					if (graceLeft(job, "took " + describe(cursor))) return;
					clickSlot(handler, job.slotId, 0);
					job.taken = ItemStack.EMPTY;
					abortBundle(mc, handler, "the server took out " + describe(cursor) + " instead of the selected stack");
					return;
				}
				MapIdComponent mid = cursor.get(DataComponentTypes.MAP_ID);
				job.heldMapId = mid != null ? mid.id() : -1;
				job.heldName = MapPreviewNames.keyOf(cursor);
				// Park and hold in one go: the server handles the click before the slot change.
				clickSlot(handler, job.hotbarSlotId, 0);
				setSelectedSlot(mc, job.hotbarIndex);
				pixelsReadyForActive = false;
				job.holdUntil = System.currentTimeMillis() + holdMs(mc);
				debug("holding %s in hotbar %d", describe(job.taken), job.hotbarIndex);
				job.step = BundleStep.FLASH_HOLD;
			}
			case FLASH_HOLD -> {
				if (!cursor.isEmpty() || !ItemStack.areItemsAndComponentsEqual(parked, job.taken)) {
					mismatch(mc, handler, job, "while holding: cursor " + describe(cursor) + ", parked " + describe(parked));
					return;
				}
				MapState state = FilledMapItem.getMapState(parked, mc.world);
				boolean got = state != null && state.colors != null && !MapPreviewStore.isBlank(state.colors);
				if (got) store.put(state.colors, job.heldMapId, job.heldName);
				boolean timedOut = System.currentTimeMillis() >= job.holdUntil;
				if (!got && !pixelsReadyForActive && !job.fast && !timedOut) return;
				if (!ClickBudget.canSend(2)) return;
				debug("%s %s", describe(parked), got || pixelsReadyForActive ? "loaded" : job.fast ? "skipped" : "did not load in time");
				if (!got && !pixelsReadyForActive && timedOut) {
					// Held for the full time and nothing came: give up on it after the second try.
					if (bundleMisses.merge(job.heldMapId, 1, Integer::sum) >= 2) failedOnce.add("i:" + job.heldMapId);
				}
				pixelsReadyForActive = false;
				job.heldMapId = -1;
				// Pick it up and put it back in one go; ROUND_DONE checks the result.
				clickSlot(handler, job.hotbarSlotId, 0);
				clickSlot(handler, job.slotId, 0);
				job.step = BundleStep.ROUND_DONE;
				settle(mc, job);
			}
			case ROUND_DONE -> {
				if (!cursor.isEmpty() || !parked.isEmpty() || bundleSize(bundle) != job.cycle.size()) {
					mismatch(mc, handler, job, "after putting back: cursor " + describe(cursor) + ", parked " + describe(parked) + ", " + bundleSize(bundle) + " stacks");
					return;
				}
				List<Long> keys = bundleKeys(bundle);
				if (job.putBackAtEnd == null && job.cycle.size() > 1) {
					// Round 1 took the last stack: back at the front it rotated the bundle, back at the end nothing moved.
					boolean front = keys.equals(rotated(job.order, false, 1));
					boolean end = keys.equals(job.order);
					if (front == end) {
						mismatch(mc, handler, job, "can't tell where the stack went back in: " + describeContents(bundle));
						return;
					}
					// The front is also what the game guesses before the server answers: on a server that puts it at
					// the end (2b2t) that answer can still be on its way, so believe "front" only after an answer or
					// after the grace time (a vanilla server agrees with the guess and sends nothing).
					if (front && job.lastRevision == job.clickRevision && graceLeft(job, "no answer yet for the put-back")) return;
					job.putBackAtEnd = end;
					putBackAtEndHint = end;
					putBackHintServer = serverId(mc);
					debug("a stack put back lands at the %s", end ? "end (taking the first stack each round)" : "front");
					if (end) {
						job.taken = ItemStack.EMPTY;
						job.cycle = new BundleCycle(job.cycle.size());
						job.step = BundleStep.SELECT;
						return;
					}
				} else if (!keys.equals(rotated(job.order, job.putBackAtEnd == Boolean.TRUE, job.cycle.round() + 1))) {
					mismatch(mc, handler, job, "after round " + (job.cycle.round() + 1) + " the bundle is not in the expected order: " + describeContents(bundle));
					return;
				}
				job.taken = ItemStack.EMPTY;
				job.cycle.roundDone();
				if (job.cycle.done()) {
					boolean inOrder = keys.equals(job.order);
					debug("done, order %s", inOrder ? "kept" : "CHANGED: " + describeContents(bundle));
					if (!inOrder) info("The bundle's order changed while loading its maps; Map Duper+ Bundle Sort puts the maps back in artwork order.");
					finishBundle(mc, true);
				}
				else job.step = BundleStep.SELECT;
			}
		}
	}

	/**
	 * The bundle after {@code rounds} rounds: put-backs at the end move the first stack to the end each round (left
	 * rotation); at the front they move the last stack to the front (right rotation).
	 */
	static List<Long> rotated(List<Long> order, boolean putBackAtEnd, int rounds) {
		int n = order.size();
		List<Long> out = new ArrayList<>(n);
		if (n == 0) return out;
		int shift = Math.floorMod(putBackAtEnd ? rounds : -rounds, n);
		for (int i = 0; i < n; i++) out.add(order.get((i + shift) % n));
		return out;
	}

	private static String serverId(MinecraftClient mc) {
		return mc.getCurrentServerEntry() == null ? "local" : mc.getCurrentServerEntry().address;
	}

	/** Wait for the server's answer to the click just sent. */
	private static void settle(MinecraftClient mc, BundleJob job) {
		job.waitUntil = System.currentTimeMillis() + settleMs(mc);
		job.clickRevision = job.lastRevision;
	}

	/** Ping + two server ticks at the server's tick rate + 50 ms, at least 150 ms. */
	private static long settleMs(MinecraftClient mc) {
		double tps = TickRate.INSTANCE.getTickRate();
		if (tps <= 0) tps = 20.0;
		tps = Math.max(5.0, Math.min(20.0, tps));
		return Math.max(150L, pingMs(mc) + Math.round(2 * 1000.0 / tps) + 50L);
	}

	/**
	 * The slots are not (yet) as this step expects. Late answers from the server can still fix that, so wait up to
	 * {@link #BUNDLE_MISMATCH_GRACE_MS} before treating it as real.
	 *
	 * @return true while still waiting
	 */
	private boolean graceLeft(BundleJob job, String why) {
		long now = System.currentTimeMillis();
		if (job.mismatchSince == 0) {
			job.mismatchSince = now;
			debug("waiting for the server: %s", why);
		}
		return now - job.mismatchSince < BUNDLE_MISMATCH_GRACE_MS;
	}

	private void mismatch(MinecraftClient mc, ScreenHandler handler, BundleJob job, String why) {
		if (!graceLeft(job, why)) abortBundle(mc, handler, why);
	}

	/** How long a map is held for its pixels: 40 server ticks at the server's tick rate (2 s at 20 TPS), plus ping. */
	private static long holdMs(MinecraftClient mc) {
		double tps = TickRate.INSTANCE.getTickRate();
		if (tps <= 0) tps = 20.0;
		tps = Math.max(5.0, Math.min(20.0, tps));
		return Math.round(40 * 1000.0 / tps) + pingMs(mc);
	}

	private static int pingMs(MinecraftClient mc) {
		if (mc.getNetworkHandler() == null || mc.player == null) return 0;
		PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
		return entry == null ? 0 : entry.getLatency();
	}

	private void finishBundle(MinecraftClient mc, boolean complete) {
		BundleJob job = bundleJob;
		if (job == null) return;
		ScreenHandler handler = mc.player.currentScreenHandler;
		ItemStack bundle = job.slotId < handler.slots.size() ? handler.getSlot(job.slotId).getStack() : ItemStack.EMPTY;
		// Maps still without a preview (or a cycle cut short): wait a little before the next round.
		if (!complete || (bundle.getItem() instanceof BundleItem && bundleNeedsLoading(bundle))) {
			bundleRetryAt = System.currentTimeMillis() + BUNDLE_RETRY_MS;
		}
		reservedHotbar.remove(job.hotbarIndex);
		if (savedSelectedSlot >= 0 && mc.getNetworkHandler() != null) setSelectedSlot(mc, savedSelectedSlot);
		savedSelectedSlot = -1;
		bundleJob = null;
		if (queue.isEmpty() && staged.isEmpty()) restoreConflicts();
	}

	/** The slots are not as the job expects: the server corrected us, or you moved something. */
	private void abortBundle(MinecraftClient mc, ScreenHandler handler, String why) {
		debug("stopped: %s", why);
		// The remembered put-back side may be what was wrong: find it out again next time.
		putBackAtEndHint = null;
		ClickBudget.backOff();
		restoreBundleNow(mc, handler);
		if (++bundleAborts == BUNDLE_MAX_ABORTS) {
			info("Stopped loading the maps in your bundle after " + BUNDLE_MAX_ABORTS
				+ " tries (" + why + "). Reopen the inventory to try again; turn on debug-log to see every step in latest.log.");
		}
	}

	/**
	 * Puts the stack the job took back into the bundle right away (screen closing hard, module off, mismatch).
	 * Only touches the parked slot and the cursor while they still hold exactly that stack, so it never moves
	 * anything of yours.
	 */
	private void restoreBundleNow(MinecraftClient mc, ScreenHandler handler) {
		BundleJob job = bundleJob;
		if (job == null) return;
		boolean sameScreen = handler.syncId == job.syncId && job.slotId < handler.slots.size()
			&& job.hotbarSlotId < handler.slots.size();
		if (sameScreen && !job.taken.isEmpty()
			&& handler.getSlot(job.slotId).getStack().getItem() instanceof BundleItem) {
			if (handler.getCursorStack().isEmpty()
				&& ItemStack.areItemsAndComponentsEqual(handler.getSlot(job.hotbarSlotId).getStack(), job.taken)) {
				clickSlot(handler, job.hotbarSlotId, 0);
			}
			if (ItemStack.areItemsAndComponentsEqual(handler.getCursorStack(), job.taken)) {
				clickSlot(handler, job.slotId, 0);
			}
		}
		finishBundle(mc, false);
	}

	/**
	 * You clicked in the screen while a bundle job runs: it stops once the current stack is back in the bundle, so
	 * it never works against your own moves (the bundle's order may then have shifted). While the job's own stack is
	 * on the cursor the click is swallowed, so it can't drop that stack somewhere else.
	 *
	 * @return true to swallow the click
	 */
	public boolean onUserScreenClick() {
		lastInventoryActivity = System.currentTimeMillis();
		BundleJob job = bundleJob;
		if (job == null) return false;
		job.fast = true;
		job.stop = true;
		return job.step == BundleStep.PLACE;
	}

	/** Remembers when the player inventory or cursor last changed, so a bundle job starts only once things are still. */
	private void trackInventoryActivity(MinecraftClient mc) {
		if (bundleJob != null) return;
		ScreenHandler handler = mc.player.currentScreenHandler;
		int fp = ItemStack.hashCode(handler.getCursorStack());
		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (isPlayerMainOrHotbar(slot, mc)) fp = fp * 31 + ItemStack.hashCode(slot.getStack()) * 7 + slot.getStack().getCount();
		}
		if (fp != inventoryFingerprint) {
			inventoryFingerprint = fp;
			lastInventoryActivity = System.currentTimeMillis();
		}
	}

	private int playerSlotId(ScreenHandler handler, MinecraftClient mc, int invIndex) {
		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (slot.inventory == mc.player.getInventory() && slot.getIndex() == invIndex) return i;
		}
		return -1;
	}

	private void clickSlot(ScreenHandler handler, int slotId, int button) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.interactionManager == null || mc.player == null) return;
		mc.interactionManager.clickSlot(handler.syncId, slotId, button, SlotActionType.PICKUP, mc.player);
		clickedThisTick = true;
	}

	private LearnJob pollNextJob() {
		LearnJob next = queue.poll();
		if (next == null) return null;
		queuedKeys.remove(next.key);
		return next;
	}

	private boolean tryStartStage(MinecraftClient mc, ScreenHandler handler, LearnMode mode) {
		int hb = findEmptyHotbar(mc);
		if (hb < 0) {
			// No empty hotbar: maybe empty offhand for one job
			if (mode == LearnMode.HotbarAndOffhand && mc.player.getOffHandStack().isEmpty()) {
				LearnJob off = pollSwapJob();
				if (off == null) return false;
				int slotId = findOriginSlot(handler, mc, off);
				if (slotId < 0 || jobSlotBusy(handler, slotId)) {
					queue.offer(off);
					queuedKeys.add(off.key);
					return false;
				}
				active = off;
				active.path = LearnPath.OFFHAND;
				active.originSlotId = slotId;
				pauseConflicts();
				phase = LearnPhase.SWAP_OUT;
				return true;
			}
			return false;
		}

		LearnJob job = pollSwapJob();
		if (job == null) return false;
		int slotId = findOriginSlot(handler, mc, job);
		if (slotId < 0 || jobSlotBusy(handler, slotId)) {
			queue.offer(job);
			queuedKeys.add(job.key);
			return false;
		}
		active = job;
		active.path = LearnPath.HOTBAR_SWAP;
		active.originSlotId = slotId;
		active.hotbarIndex = hb;
		reservedHotbar.add(hb);
		pauseConflicts();
		phase = LearnPhase.STAGE_SWAP;
		return true;
	}

	private LearnJob pollSwapJob() {
		Iterator<LearnJob> it = queue.iterator();
		while (it.hasNext()) {
			LearnJob j = it.next();
			if (j.path == LearnPath.HOTBAR_SWAP) {
				it.remove();
				queuedKeys.remove(j.key);
				return j;
			}
		}
		return null;
	}

	private void startFlashStaged(MinecraftClient mc) {
		if (staged.isEmpty()) return;
		active = staged.remove(0);
		reservedHotbar.remove(active.hotbarIndex);
		savedSelectedSlot = mc.player.getInventory().selectedSlot;
		phase = LearnPhase.FLASH_SELECT;
	}

	/** Empty hotbar only — never displace occupied slots (would dump items into storage). */
	private int findEmptyHotbar(MinecraftClient mc) {
		PlayerInventory inv = mc.player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (reservedHotbar.contains(i)) continue;
			if (inv.getStack(i).isEmpty()) return i;
		}
		return -1;
	}

	private void setSelectedSlot(MinecraftClient mc, int slot) {
		if (slot < 0 || slot > 8) return;
		mc.player.getInventory().selectedSlot = slot;
		mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
		clickedThisTick = true;
	}

	private void finishActive(boolean mayRestoreModules) {
		if (active != null) {
			reservedHotbar.remove(active.hotbarIndex);
		}
		active = null;
		savedSelectedSlot = -1;
		phase = LearnPhase.IDLE;
		holdTicks = 0;
		waitVisibleTicks = 0;
		pixelsReadyForActive = false;
		if (mayRestoreModules && !hasOutstandingSwaps() && queue.isEmpty()) restoreConflicts();
	}

	private void abortLearn() {
		MinecraftClient mc = MinecraftClient.getInstance();
		bestEffortRestoreNow(mc);
		clearLearnState();
	}

	private void bestEffortRestoreNow(MinecraftClient mc) {
		if (mc == null || mc.player == null || mc.interactionManager == null) return;
		ScreenHandler handler = mc.player.currentScreenHandler;
		restoreBundleNow(mc, handler);
		List<LearnJob> toRestore = new ArrayList<>(staged);
		staged.clear();
		if (active != null && (active.didHotbarSwap || active.didOffhandSwap)) {
			toRestore.add(0, active);
		}
		active = null;
		for (LearnJob job : toRestore) {
			if (job.didHotbarSwap && job.hotbarIndex >= 0 && isHotbarMatching(mc, job)) {
				clickSwapHotbar(handler, job.originSlotId, job.hotbarIndex);
			} else if (job.didOffhandSwap && isOffhandMatching(mc, job)) {
				ItemStack atOrigin = handler.getSlot(job.originSlotId).getStack();
				if (atOrigin.isEmpty()) {
					clickSwapOffhand(handler, job.originSlotId);
				}
			}
		}
		if (savedSelectedSlot >= 0 && mc.getNetworkHandler() != null) {
			setSelectedSlot(mc, savedSelectedSlot);
		}
	}

	private void clearLearnState() {
		phase = LearnPhase.IDLE;
		active = null;
		bundleJob = null;
		bundleAborts = 0;
		queue.clear();
		queuedKeys.clear();
		staged.clear();
		reservedHotbar.clear();
		savedSelectedSlot = -1;
		holdTicks = 0;
		waitVisibleTicks = 0;
		pixelsReadyForActive = false;
		stopStaging = false;
	}

	private void clickSwapOffhand(ScreenHandler handler, int slotId) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.interactionManager == null || mc.player == null) return;
		mc.interactionManager.clickSlot(handler.syncId, slotId, 40, SlotActionType.SWAP, mc.player);
		clickedThisTick = true;
	}

	private void clickSwapHotbar(ScreenHandler handler, int slotId, int hotbarIndex) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.interactionManager == null || mc.player == null) return;
		if (hotbarIndex < 0 || hotbarIndex > 8) return;
		mc.interactionManager.clickSlot(handler.syncId, slotId, hotbarIndex, SlotActionType.SWAP, mc.player);
		clickedThisTick = true;
	}

	private boolean canRestoreOffhand(MinecraftClient mc, ScreenHandler handler) {
		if (active == null) return false;
		if (active.originSlotId < 0 || active.originSlotId >= handler.slots.size()) return false;
		ItemStack off = mc.player.getOffHandStack();
		if (!off.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = off.get(DataComponentTypes.MAP_ID);
		if (mid == null || mid.id() != active.mapId) return false;
		return handler.getSlot(active.originSlotId).getStack().isEmpty();
	}

	private boolean restored(MinecraftClient mc, ScreenHandler handler) {
		if (active == null) return true;
		ItemStack inv = handler.getSlot(active.originSlotId).getStack();
		if (!inv.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = inv.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == active.mapId;
	}

	private boolean isOffhandMatching(MinecraftClient mc, LearnJob job) {
		ItemStack off = mc.player.getOffHandStack();
		if (!off.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = off.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == job.mapId;
	}

	private boolean isHotbarMatching(MinecraftClient mc, LearnJob job) {
		if (job.hotbarIndex < 0 || job.hotbarIndex > 8) return false;
		ItemStack stack = mc.player.getInventory().getStack(job.hotbarIndex);
		if (!stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == job.mapId;
	}

	private int findOriginSlot(ScreenHandler handler, MinecraftClient mc, LearnJob job) {
		if (job.originSlotId >= 0 && job.originSlotId < handler.slots.size()) {
			ItemStack stack = handler.getSlot(job.originSlotId).getStack();
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid != null && mid.id() == job.mapId) return job.originSlotId;
		}
		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			ItemStack stack = slot.getStack();
			if (!stack.isOf(Items.FILLED_MAP)) continue;
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid == null || mid.id() != job.mapId) continue;
			if (job.path == LearnPath.HOTBAR_FLASH && !isPlayerHotbar(slot, mc)) continue;
			if (job.path == LearnPath.HOTBAR_SWAP || job.path == LearnPath.OFFHAND) {
				boolean ok = isPlayerMainOrHotbar(slot, mc) || isStorageContainerSlot(slot, mc);
				if (!ok) continue;
			}
			return i;
		}
		return -1;
	}

	private boolean jobSlotBusy(ScreenHandler handler, int slotId) {
		if (!handler.getCursorStack().isEmpty()) return true;
		if (watchedChangedThisTick) return true;
		ItemStack now = handler.getSlot(slotId).getStack();
		ItemStack prev = lastWatchedStacks.get(slotId);
		return prev != null && !ItemStack.areItemsAndComponentsEqual(prev, now);
	}

	private void markWatchedChanges(MinecraftClient mc) {
		watchedChangedThisTick = false;
		ScreenHandler handler = mc.player.currentScreenHandler;
		ItemStack cursor = handler.getCursorStack();
		if (!ItemStack.areItemsAndComponentsEqual(lastCursor, cursor)) {
			watchedChangedThisTick = true;
			return;
		}
		for (int slotId : watchedSlotIds(handler, mc)) {
			ItemStack stack = handler.getSlot(slotId).getStack();
			ItemStack prev = lastWatchedStacks.get(slotId);
			if (prev != null && !ItemStack.areItemsAndComponentsEqual(prev, stack)) {
				watchedChangedThisTick = true;
				return;
			}
		}
	}

	private void snapshotWatched(MinecraftClient mc) {
		ScreenHandler handler = mc.player.currentScreenHandler;
		lastCursor = handler.getCursorStack().copy();
		Map<Integer, ItemStack> next = new HashMap<>();
		for (int slotId : watchedSlotIds(handler, mc)) {
			next.put(slotId, handler.getSlot(slotId).getStack().copy());
		}
		lastWatchedStacks.clear();
		lastWatchedStacks.putAll(next);
	}

	private Set<Integer> watchedSlotIds(ScreenHandler handler, MinecraftClient mc) {
		Set<Integer> ids = new LinkedHashSet<>();
		for (int i = 0; i < handler.slots.size(); i++) {
			if (isOffhandSlot(handler.getSlot(i), mc)) ids.add(i);
		}
		if (active != null && active.originSlotId >= 0 && active.originSlotId < handler.slots.size()) {
			ids.add(active.originSlotId);
		}
		for (LearnJob j : staged) {
			if (j.originSlotId >= 0 && j.originSlotId < handler.slots.size()) ids.add(j.originSlotId);
		}
		return ids;
	}

	private static boolean isOpenStorageScreen(MinecraftClient mc) {
		return mc.currentScreen instanceof ShulkerBoxScreen
			|| mc.currentScreen instanceof GenericContainerScreen;
	}

	private static boolean isStorageContainerSlot(Slot slot, MinecraftClient mc) {
		if (slot.inventory instanceof PlayerInventory) return false;
		if (mc.currentScreen instanceof ShulkerBoxScreen) return true;
		if (mc.currentScreen instanceof GenericContainerScreen) return true;
		return false;
	}

	private static boolean isPlayerMainOrHotbar(Slot slot, MinecraftClient mc) {
		if (slot.inventory != mc.player.getInventory()) return false;
		int idx = slot.getIndex();
		return idx >= 0 && idx < 36;
	}

	private static boolean isPlayerHotbar(Slot slot, MinecraftClient mc) {
		if (slot.inventory != mc.player.getInventory()) return false;
		int idx = slot.getIndex();
		return idx >= 0 && idx < 9;
	}

	private static boolean isOffhandSlot(Slot slot, MinecraftClient mc) {
		return slot.inventory == mc.player.getInventory() && slot.getIndex() == 40;
	}

	private static boolean isSelectedHotbarMap(MinecraftClient mc, int mapId) {
		ItemStack stack = mc.player.getMainHandStack();
		if (!stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == mapId;
	}

	private static boolean isOffhandMap(MinecraftClient mc, int mapId) {
		ItemStack stack = mc.player.getOffHandStack();
		if (!stack.isOf(Items.FILLED_MAP)) return false;
		MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
		return mid != null && mid.id() == mapId;
	}

	private static String findNameForMapId(MinecraftClient mc, int mapId) {
		if (mc.player == null) return null;
		ScreenHandler handler = mc.player.currentScreenHandler;
		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid != null && mid.id() == mapId) {
				String n = MapPreviewNames.keyOf(stack);
				if (n != null) return n;
			}
		}
		for (int i = 0; i < mc.player.getInventory().size(); i++) {
			ItemStack stack = mc.player.getInventory().getStack(i);
			MapIdComponent mid = stack.get(DataComponentTypes.MAP_ID);
			if (mid != null && mid.id() == mapId) {
				String n = MapPreviewNames.keyOf(stack);
				if (n != null) return n;
			}
		}
		return null;
	}

	private void pauseConflicts() {
		if (!pausedPrior.isEmpty()) return;
		pauseIfActive("meteordevelopment.meteorclient.systems.modules.player.AutoReplenish");
		pauseIfActive("meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks");
		pauseIfActive("bep.hax.modules.Replenish");
		pauseIfActive("bep.hax.modules.MapDuplicator");
	}

	private void pauseIfActive(String className) {
		Module mod = findByClassName(className);
		if (mod == null) return;
		pausedPrior.put(className, mod.isActive());
		if (mod.isActive()) mod.toggle();
	}

	private void restoreConflicts() {
		for (Map.Entry<String, Boolean> e : pausedPrior.entrySet()) {
			Module mod = findByClassName(e.getKey());
			if (mod == null) continue;
			if (e.getValue() && !mod.isActive()) mod.toggle();
			if (!e.getValue() && mod.isActive()) mod.toggle();
		}
		pausedPrior.clear();
	}

	private static Module findByClassName(String className) {
		try {
			for (Module mod : Modules.get().getAll()) {
				if (mod.getClass().getName().equals(className)) return mod;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private void warnInventoryEssentialsOnce() {
		if (warnedInventoryEssentials) return;
		warnedInventoryEssentials = true;
		try {
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("inventoryessentials")) {
				warning("inventoryessentials is installed and cannot be paused — learn waits one tick if the cursor or active map slot is busy.");
			}
		} catch (Throwable ignored) {
		}
	}
}
