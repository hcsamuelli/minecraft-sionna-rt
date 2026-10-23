package dev.mcsionna;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.math.Transformation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Scans the world around a point, asks the Python Sionna service for paths, and draws them. */
public final class Simulation {
	static final String RAY_TAG = "mc_sionna_ray";
	static final String LABEL_TAG = "mc_sionna_label";
	/** Strongest path is red; every 6 dB weaker moves one color down the list. */
	private static final Block[] COLORS = Stream.of(
		DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.PURPLE
	).map(Blocks.CONCRETE::pick).toArray(Block[]::new);
	private static final double DB_PER_COLOR = 6;
	private static final float RAY_WIDTH = 0.06f;
	/** Live updates re-run at most this often while blocks keep changing (10 ticks = twice a second). */
	private static final int LIVE_INTERVAL_TICKS = 10;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	// everything below is only touched on the server thread
	private static boolean running;
	private static @Nullable Runnable pending;
	private static boolean pendingExport;
	/** The area of the last run that found radios; block changes inside it trigger a live re-run. */
	private static @Nullable ServerLevel liveLevel;
	private static BlockPos liveMin = BlockPos.ZERO, liveMax = BlockPos.ZERO, liveCenter = BlockPos.ZERO;
	private static boolean dirty;
	private static int ticks, lastLiveTick = -LIVE_INTERVAL_TICKS;
	private static @Nullable String lastLiveError;
	/**
	 * The ray and label entities currently drawn, reused in place by the next drawing. Only these exact
	 * objects may load (see McSionna): copies saved in chunks that unloaded are refused when the chunk
	 * comes back, and the next run respawns them, so stale or duplicate drawings never appear.
	 */
	static final List<Display.BlockDisplay> RAYS = new ArrayList<>();
	static final List<Display.TextDisplay> LABELS = new ArrayList<>();

	private Simulation() {}

	private static boolean autoRunOn() {
		return Boolean.parseBoolean(McSionna.SETTINGS.get("auto_run"));
	}

	/** A radio block was placed or removed: re-run around it, even if it's outside the current live area. */
	static void autoRun(ServerLevel level, BlockPos pos) {
		if (autoRunOn()) {
			// schedule() always queues, so we run after the block change that triggered us has finished
			MinecraftServer server = level.getServer();
			server.schedule(server.wrapRunnable(() -> request(level, pos, null, false)));
		}
	}

	/** Called for every block change on the server (ServerLevelMixin), so keep it cheap. */
	public static void onBlockChanged(ServerLevel level, BlockPos pos) {
		if (level == liveLevel && !dirty
			&& pos.getX() >= liveMin.getX() && pos.getY() >= liveMin.getY() && pos.getZ() >= liveMin.getZ()
			&& pos.getX() <= liveMax.getX() && pos.getY() <= liveMax.getY() && pos.getZ() <= liveMax.getZ()) {
			dirty = true;
		}
	}

	/** End of every server tick: start a live re-run if something changed, at most twice a second. */
	static void tick() {
		ticks++;
		if (dirty && !running && liveLevel != null && autoRunOn() && ticks - lastLiveTick >= LIVE_INTERVAL_TICKS) {
			lastLiveTick = ticks;
			request(liveLevel, liveCenter, null, false);
		}
	}

	/**
	 * Simulate the cube of radius {@code radius} around {@code center}. With {@code export} (the /sionna run
	 * command) the service saves the scene and channels and progress goes to chat; live runs only trace, and
	 * report in the action bar of nearby players.
	 */
	static void request(ServerLevel level, BlockPos center, @Nullable CommandSourceStack source, boolean export) {
		Consumer<Component> chat = msg -> {
			McSionna.LOGGER.info(msg.getString());
			if (source != null) {
				source.sendSystemMessage(msg);
			} else {
				nearby(level, center).forEach(p -> p.sendSystemMessage(msg));
			}
		};
		Consumer<Component> status = export ? chat : msg -> nearby(level, center).forEach(p -> p.sendSystemMessage(msg, true));
		if (running) {
			if (pending == null || !pendingExport || export) { // latest request wins, but never drop a pending export
				pending = () -> request(level, center, source, export);
				pendingExport = export;
			}
			return;
		}
		Scan scan;
		long t0 = System.nanoTime();
		try {
			scan = Scan.of(level, center, Integer.parseInt(McSionna.SETTINGS.get("radius")));
		} catch (IllegalArgumentException e) {
			stopLive();
			chat.accept(error(e.getMessage()));
			return;
		}
		long scanMs = (System.nanoTime() - t0) / 1_000_000;
		if (level == liveLevel) {
			dirty = false; // this scan already sees every change so far
		}
		if (scan.txs.isEmpty() || scan.rxs.isEmpty()) {
			if (liveLevel == null || level == liveLevel) {
				clear(level);
				stopLive();
			}
			status.accept(Component.literal("Sionna: need at least one transmitter and one receiver within "
				+ McSionna.SETTINGS.get("radius") + " blocks (found " + scan.txs.size() + " tx, " + scan.rxs.size() + " rx)")
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		liveLevel = level;
		liveCenter = center;
		liveMin = new BlockPos(scan.origin[0], scan.origin[1], scan.origin[2]);
		liveMax = liveMin.offset(scan.size[0] - 1, scan.size[1] - 1, scan.size[2] - 1);
		running = true;
		if (export) {
			chat.accept(Component.literal(String.format("Sionna: tracing %d tx / %d rx over %d x %d x %d blocks...",
				scan.txs.size(), scan.rxs.size(), scan.size[0], scan.size[1], scan.size[2])).withStyle(ChatFormatting.GRAY));
		}
		String url = McSionna.SETTINGS.get("url").replaceAll("/+$", "") + "/simulate";
		HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(scan.toJson(export).toString()))
				.build();
		} catch (IllegalArgumentException e) {
			running = false;
			chat.accept(error("bad url setting " + url + ": " + e.getMessage()));
			return;
		}
		long sent = System.nanoTime();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> level.getServer().execute(() -> {
			running = false;
			if (!level.getServer().isSameThread()) {
				// the server stopped while we waited, so execute() ran us on the HTTP thread
				pending = null;
				return;
			}
			String failure = null;
			try {
				if (err != null) {
					failure = "could not reach the Sionna service at " + url + " (is `uv run mc-sionna-server` running?): " + err;
				} else {
					JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
					if (r.get("ok").getAsBoolean()) {
						draw(level, scan, r, chat, status, export, scanMs, (System.nanoTime() - sent) / 1_000_000);
					} else {
						failure = r.get("error").getAsString();
					}
				}
			} catch (RuntimeException e) {
				McSionna.LOGGER.error("failed to handle Sionna response", e);
				failure = e.toString();
			}
			// live runs repeat, so only say the same error once
			if (failure != null && (export || !failure.equals(lastLiveError))) {
				chat.accept(error(failure));
			}
			if (!export) {
				lastLiveError = failure;
			}
			if (pending != null) {
				Runnable next = pending;
				pending = null;
				next.run();
			}
		}));
	}

	private static List<ServerPlayer> nearby(ServerLevel level, BlockPos center) {
		return level.players().stream().filter(p -> p.blockPosition().closerThan(center, 160)).toList();
	}

	/** Stop live re-runs until the next run that finds radios (used by /sionna clear). */
	static void stopLive() {
		liveLevel = null;
		dirty = false;
	}

	/** Forget everything when the server stops, so a single-player world doesn't leak into the next one. */
	static void reset() {
		stopLive();
		RAYS.clear();
		LABELS.clear();
		running = false;
		pending = null;
		lastLiveError = null;
	}

	/** Remove every ray and label entity in loaded chunks. */
	static int clear(ServerLevel level) {
		RAYS.forEach(Entity::discard);
		LABELS.forEach(Entity::discard);
		RAYS.clear();
		LABELS.clear();
		List<Entity> old = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains(RAY_TAG) || e.entityTags().contains(LABEL_TAG)) {
				old.add(e);
			}
		}
		old.forEach(Entity::discard);
		return old.size();
	}

	private static void draw(ServerLevel level, Scan scan, JsonObject r, Consumer<Component> chat, Consumer<Component> status,
		boolean export, long scanMs, long traceMs) {
		JsonArray paths = r.getAsJsonArray("paths");
		double best = paths.isEmpty() ? 0 : paths.get(0).getAsJsonObject().get("gain_db").getAsDouble();
		int n = 0;
		for (JsonElement el : paths) {
			JsonObject p = el.getAsJsonObject();
			int color = (int) Math.min((best - p.get("gain_db").getAsDouble()) / DB_PER_COLOR, COLORS.length - 1);
			JsonArray pts = p.getAsJsonArray("points");
			for (int i = 0; i + 1 < pts.size(); i++) {
				if (segment(level, n, vec(pts.get(i)), vec(pts.get(i + 1)), COLORS[color])) {
					n++;
				}
			}
		}
		trim(RAYS, n);

		Map<Integer, StringBuilder> rxText = new HashMap<>();
		for (JsonElement el : r.getAsJsonArray("links")) {
			JsonObject l = el.getAsJsonObject();
			int rx = l.get("rx").getAsInt();
			StringBuilder sb = rxText.computeIfAbsent(rx, k -> new StringBuilder("RX " + (k + 1)));
			sb.append("\nfrom TX ").append(l.get("tx").getAsInt() + 1).append(": ");
			if (l.get("gain_db").isJsonNull()) {
				sb.append("no paths");
			} else {
				sb.append(String.format("%.1f dB, %d paths, %.1f ns spread", l.get("gain_db").getAsDouble(),
					l.get("num_paths").getAsInt(), l.get("delay_spread_ns").getAsDouble()));
			}
		}
		int k = 0;
		for (int i = 0; i < scan.rxs.size(); i++) {
			label(level, k++, scan.rxs.get(i), rxText.getOrDefault(i, new StringBuilder("RX " + (i + 1))).toString());
		}
		for (int i = 0; i < scan.txs.size(); i++) {
			label(level, k++, scan.txs.get(i), "TX " + (i + 1) + "\n" + McSionna.SETTINGS.get("frequency_ghz") + " GHz");
		}
		trim(LABELS, k);

		if (export) {
			chat.accept(Component.literal(String.format("Sionna: drew %d paths (scan %d ms, trace %d ms, %d triangles). Colors: red = "
				+ "strongest, then orange, yellow, lime, light blue, blue, purple, each %.0f dB weaker. Scene + channels saved to %s",
				paths.size(), scanMs, traceMs, r.get("triangles").getAsInt(), DB_PER_COLOR,
				r.get("export_dir").getAsString())).withStyle(ChatFormatting.GREEN));
			for (JsonElement w : r.getAsJsonArray("warnings")) {
				chat.accept(Component.literal("Sionna warning: " + w.getAsString()).withStyle(ChatFormatting.YELLOW));
			}
		} else {
			status.accept(Component.literal(String.format("Sionna live: %d paths, strongest %.1f dB (scan %d ms, trace %d ms)",
				paths.size(), best, scanMs, traceMs)).withStyle(ChatFormatting.GRAY));
		}
	}

	/** The i-th entity of a pool, reused if it's still alive in this level, otherwise a new one spawned in its place. */
	private static <T extends Display> T pooled(List<T> pool, int i, ServerLevel level, java.util.function.Supplier<T> make, String tag) {
		if (i < pool.size() && !pool.get(i).isRemoved() && pool.get(i).level() == level) {
			return pool.get(i);
		}
		T e = make.get();
		e.addTag(tag);
		if (i < pool.size()) {
			pool.get(i).discard(); // e.g. left behind in another dimension
			pool.set(i, e);
		} else {
			pool.add(e);
		}
		return e;
	}

	/** Drop the pool's entities past the first n. */
	private static void trim(List<? extends Entity> pool, int n) {
		while (pool.size() > n) {
			pool.removeLast().discard();
		}
	}

	/** Make the i-th ray a thin block_display stretched from a to b. Returns false for zero-length segments. */
	private static boolean segment(ServerLevel level, int i, Vec3 a, Vec3 b, Block block) {
		Vector3f d = new Vector3f((float) (b.x - a.x), (float) (b.y - a.y), (float) (b.z - a.z));
		float len = d.length();
		if (len < 1e-4f) {
			return false;
		}
		// The block model spans 0..1 on each axis: scale it to (w, w, len), center it on the
		// line, then rotate +z onto the ray direction.
		Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d.div(len));
		Vector3f offset = rot.transform(new Vector3f(-RAY_WIDTH / 2, -RAY_WIDTH / 2, 0));
		boolean fresh = i >= RAYS.size() || RAYS.get(i).isRemoved() || RAYS.get(i).level() != level;
		Display.BlockDisplay e = pooled(RAYS, i, level, () -> new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level), RAY_TAG);
		e.setPos(a.x, a.y, a.z);
		e.setBlockState(block.defaultBlockState());
		e.setTransformation(new Transformation(offset, rot, new Vector3f(RAY_WIDTH, RAY_WIDTH, len), null));
		if (fresh) {
			e.setBrightnessOverride(new Brightness(15, 15));
			e.setViewRange(4f);
			level.addFreshEntity(e);
		}
		return true;
	}

	private static void label(ServerLevel level, int i, BlockPos pos, String text) {
		boolean fresh = i >= LABELS.size() || LABELS.get(i).isRemoved() || LABELS.get(i).level() != level;
		Display.TextDisplay t = pooled(LABELS, i, level, () -> new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level), LABEL_TAG);
		t.setPos(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
		t.setText(Component.literal(text));
		if (fresh) {
			t.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			level.addFreshEntity(t);
		}
	}

	private static Vec3 vec(JsonElement el) {
		JsonArray a = el.getAsJsonArray();
		return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	private static Component error(String msg) {
		return Component.literal("Sionna error: " + msg).withStyle(ChatFormatting.RED);
	}

	/** A box of blocks around a center, packed as a palette + uint16 indices, plus the radios found in it. */
	record Scan(int[] origin, int[] size, JsonArray palette, byte[] blocks, List<BlockPos> txs, List<BlockPos> rxs) {
		static Scan of(ServerLevel level, BlockPos center, int radius) {
			if (radius < 1 || radius > 128) {
				throw new IllegalArgumentException("radius must be between 1 and 128, got " + radius);
			}
			int x0 = center.getX() - radius, z0 = center.getZ() - radius;
			int y0 = Math.max(level.getMinY(), center.getY() - radius);
			int y1 = Math.min(level.getMaxY(), center.getY() + radius);
			int sx = 2 * radius + 1, sy = y1 - y0 + 1, sz = 2 * radius + 1;
			ByteBuffer buf = ByteBuffer.allocate(sx * sy * sz * 2).order(ByteOrder.LITTLE_ENDIAN);
			Map<BlockState, Integer> ids = new HashMap<>();
			JsonArray palette = new JsonArray();
			List<BlockPos> txs = new ArrayList<>(), rxs = new ArrayList<>();
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			// unloaded chunks count as air instead of being generated on the server thread
			boolean[] loaded = new boolean[sx * sz];
			for (int x = 0; x < sx; x++) {
				for (int z = 0; z < sz; z++) {
					loaded[x * sz + z] = level.getChunkSource().getChunkNow((x0 + x) >> 4, (z0 + z) >> 4) != null;
				}
			}
			for (int x = 0; x < sx; x++) {
				for (int y = 0; y < sy; y++) {
					for (int z = 0; z < sz; z++) {
						p.set(x0 + x, y0 + y, z0 + z);
						BlockState s = loaded[x * sz + z] ? level.getBlockState(p) : Blocks.AIR.defaultBlockState();
						Integer id = ids.get(s);
						if (id == null) {
							id = ids.size();
							ids.put(s, id);
							JsonObject e = new JsonObject();
							e.addProperty("id", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
							e.addProperty("collision", !s.getCollisionShape(level, p).isEmpty());
							e.addProperty("water", s.getFluidState().is(FluidTags.WATER));
							palette.add(e);
						}
						if (s.is(McSionna.TRANSMITTER)) {
							txs.add(p.immutable());
						} else if (s.is(McSionna.RECEIVER)) {
							rxs.add(p.immutable());
						}
						buf.putShort((short) (int) id);
					}
				}
			}
			return new Scan(new int[] {x0, y0, z0}, new int[] {sx, sy, sz}, palette, buf.array(), txs, rxs);
		}

		JsonObject toJson(boolean export) {
			JsonObject o = new JsonObject();
			o.addProperty("export", export);
			o.add("origin", ints(origin));
			o.add("size", ints(size));
			o.add("palette", palette);
			o.addProperty("blocks", Base64.getEncoder().encodeToString(blocks));
			o.add("transmitters", positions(txs));
			o.add("receivers", positions(rxs));
			JsonObject settings = new JsonObject();
			McSionna.SETTINGS.forEach((k, v) -> {
				if (k.equals("url") || k.equals("radius") || k.equals("auto_run")) {
					return;
				}
				if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
					settings.addProperty(k, Boolean.parseBoolean(v));
				} else {
					try {
						settings.addProperty(k, Double.parseDouble(v));
					} catch (NumberFormatException e) {
						settings.addProperty(k, v);
					}
				}
			});
			o.add("settings", settings);
			return o;
		}

		private static JsonArray ints(int... v) {
			JsonArray a = new JsonArray();
			for (int i : v) {
				a.add(i);
			}
			return a;
		}

		private static JsonArray positions(List<BlockPos> list) {
			JsonArray a = new JsonArray();
			for (BlockPos b : list) {
				JsonObject o = new JsonObject();
				o.add("pos", ints(b.getX(), b.getY(), b.getZ()));
				a.add(o);
			}
			return a;
		}
	}
}
