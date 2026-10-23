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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	// only touched on the server thread
	private static boolean running;
	private static @Nullable Runnable pending;

	private Simulation() {}

	static void autoRun(ServerLevel level, BlockPos pos) {
		if (Boolean.parseBoolean(McSionna.SETTINGS.get("auto_run"))) {
			// schedule() always queues, so we run after the block change that triggered us has finished
			MinecraftServer server = level.getServer();
			server.schedule(server.wrapRunnable(() -> request(level, pos, null)));
		}
	}

	/** Simulate the cube of radius {@code radius} around {@code center}. Messages go to {@code source}, or nearby players. */
	static void request(ServerLevel level, BlockPos center, @Nullable CommandSourceStack source) {
		Consumer<Component> tell = msg -> {
			McSionna.LOGGER.info(msg.getString());
			if (source != null) {
				source.sendSystemMessage(msg);
			} else {
				level.players().stream().filter(p -> p.blockPosition().closerThan(center, 160)).forEach(p -> p.sendSystemMessage(msg));
			}
		};
		if (running) {
			pending = () -> request(level, center, source); // latest request wins
			return;
		}
		Scan scan;
		try {
			scan = Scan.of(level, center, Integer.parseInt(McSionna.SETTINGS.get("radius")));
		} catch (IllegalArgumentException e) {
			tell.accept(error(e.getMessage()));
			return;
		}
		if (scan.txs.isEmpty() || scan.rxs.isEmpty()) {
			clear(level);
			tell.accept(Component.literal("Sionna: need at least one transmitter and one receiver within "
				+ McSionna.SETTINGS.get("radius") + " blocks (found " + scan.txs.size() + " tx, " + scan.rxs.size() + " rx)")
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		running = true;
		tell.accept(Component.literal(String.format("Sionna: tracing %d tx / %d rx over %d x %d x %d blocks...",
			scan.txs.size(), scan.rxs.size(), scan.size[0], scan.size[1], scan.size[2])).withStyle(ChatFormatting.GRAY));
		String url = McSionna.SETTINGS.get("url").replaceAll("/+$", "") + "/simulate";
		HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(scan.toJson().toString()))
				.build();
		} catch (IllegalArgumentException e) {
			running = false;
			tell.accept(error("bad url setting " + url + ": " + e.getMessage()));
			return;
		}
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> level.getServer().execute(() -> {
			running = false;
			if (!level.getServer().isSameThread()) {
				// the server stopped while we waited, so execute() ran us on the HTTP thread
				pending = null;
				return;
			}
			try {
				if (err != null) {
					tell.accept(error("could not reach the Sionna service at " + url + " (is `uv run mc-sionna-server` running?): " + err));
				} else {
					JsonObject r = JsonParser.parseString(resp.body()).getAsJsonObject();
					if (r.get("ok").getAsBoolean()) {
						draw(level, scan, r, tell);
					} else {
						tell.accept(error(r.get("error").getAsString()));
					}
				}
			} catch (RuntimeException e) {
				McSionna.LOGGER.error("failed to handle Sionna response", e);
				tell.accept(error(e.toString()));
			}
			if (pending != null) {
				Runnable next = pending;
				pending = null;
				next.run();
			}
		}));
	}

	/**
	 * Ray and label entities from the latest drawing. Older ones still saved in unloaded chunks are
	 * refused when those chunks load (see McSionna), so stale drawings never come back.
	 */
	static final Set<UUID> CURRENT = new HashSet<>();

	/** Remove every ray and label entity in loaded chunks. */
	static int clear(ServerLevel level) {
		CURRENT.clear();
		List<Entity> old = new ArrayList<>();
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains(RAY_TAG) || e.entityTags().contains(LABEL_TAG)) {
				old.add(e);
			}
		}
		old.forEach(Entity::discard);
		return old.size();
	}

	private static void draw(ServerLevel level, Scan scan, JsonObject r, Consumer<Component> tell) {
		clear(level);
		JsonArray paths = r.getAsJsonArray("paths");
		double best = paths.isEmpty() ? 0 : paths.get(0).getAsJsonObject().get("gain_db").getAsDouble();
		for (JsonElement el : paths) {
			JsonObject p = el.getAsJsonObject();
			int color = (int) Math.min((best - p.get("gain_db").getAsDouble()) / DB_PER_COLOR, COLORS.length - 1);
			JsonArray pts = p.getAsJsonArray("points");
			for (int i = 0; i + 1 < pts.size(); i++) {
				segment(level, vec(pts.get(i)), vec(pts.get(i + 1)), COLORS[color]);
			}
		}

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
		for (int i = 0; i < scan.rxs.size(); i++) {
			label(level, scan.rxs.get(i), rxText.getOrDefault(i, new StringBuilder("RX " + (i + 1))).toString());
		}
		for (int i = 0; i < scan.txs.size(); i++) {
			label(level, scan.txs.get(i), "TX " + (i + 1) + "\n" + McSionna.SETTINGS.get("frequency_ghz") + " GHz");
		}

		tell.accept(Component.literal(String.format("Sionna: drew %d paths in %.1fs (%d triangles). Colors: red = strongest, "
			+ "then orange, yellow, lime, light blue, blue, purple, each %.0f dB weaker. Scene + channels saved to %s",
			paths.size(), r.get("elapsed_s").getAsDouble(), r.get("triangles").getAsInt(), DB_PER_COLOR,
			r.get("export_dir").getAsString())).withStyle(ChatFormatting.GREEN));
		for (JsonElement w : r.getAsJsonArray("warnings")) {
			tell.accept(Component.literal("Sionna warning: " + w.getAsString()).withStyle(ChatFormatting.YELLOW));
		}
	}

	/** A thin block_display stretched from a to b. */
	private static void segment(ServerLevel level, Vec3 a, Vec3 b, Block block) {
		Vector3f d = new Vector3f((float) (b.x - a.x), (float) (b.y - a.y), (float) (b.z - a.z));
		float len = d.length();
		if (len < 1e-4f) {
			return;
		}
		// The block model spans 0..1 on each axis: scale it to (w, w, len), center it on the
		// line, then rotate +z onto the ray direction.
		Quaternionf rot = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), d.div(len));
		Vector3f offset = rot.transform(new Vector3f(-RAY_WIDTH / 2, -RAY_WIDTH / 2, 0));
		Display.BlockDisplay e = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		e.setPos(a.x, a.y, a.z);
		e.setBlockState(block.defaultBlockState());
		e.setTransformation(new Transformation(offset, rot, new Vector3f(RAY_WIDTH, RAY_WIDTH, len), null));
		e.setBrightnessOverride(new Brightness(15, 15));
		e.setViewRange(4f);
		e.addTag(RAY_TAG);
		CURRENT.add(e.getUUID());
		level.addFreshEntity(e);
	}

	private static void label(ServerLevel level, BlockPos pos, String text) {
		Display.TextDisplay t = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
		t.setPos(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
		t.setText(Component.literal(text));
		t.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		t.addTag(LABEL_TAG);
		CURRENT.add(t.getUUID());
		level.addFreshEntity(t);
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

		JsonObject toJson() {
			JsonObject o = new JsonObject();
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
