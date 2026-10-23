package dev.mcsionna;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McSionna implements ModInitializer {
	public static final String MOD_ID = "mc_sionna";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Block TRANSMITTER = registerBlock("transmitter");
	public static final Block RECEIVER = registerBlock("receiver");

	/** Settings sent along with every run; edited with /sionna set, saved to config/mc-sionna.properties. */
	public static final Map<String, String> SETTINGS = new LinkedHashMap<>();
	private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("mc-sionna.properties");

	static {
		SETTINGS.put("url", "http://127.0.0.1:8765");
		SETTINGS.put("radius", "48");
		SETTINGS.put("auto_run", "true");
		SETTINGS.put("frequency_ghz", "3.5");
		SETTINGS.put("max_depth", "4");
		SETTINGS.put("max_drawn_paths", "100");
		SETTINGS.put("diffraction", "false");
		SETTINGS.put("thickness_per_block_m", "0.2");
	}

	@Override
	public void onInitialize() {
		loadSettings();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(out -> {
			out.accept(TRANSMITTER);
			out.accept(RECEIVER);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("sionna")
				.then(Commands.literal("run").executes(c -> {
					Simulation.request(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()), c.getSource());
					return 1;
				}))
				.then(Commands.literal("clear").executes(c -> {
					int n = Simulation.clear(c.getSource().getLevel());
					c.getSource().sendSuccess(() -> Component.literal("Removed " + n + " ray/label entities"), false);
					return n;
				}))
				.then(Commands.literal("settings").executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("Sionna settings: " + SETTINGS), false);
					return 1;
				}))
				.then(Commands.literal("set")
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.argument("key", StringArgumentType.word())
						.suggests((c, b) -> {
							SETTINGS.keySet().forEach(b::suggest);
							return b.buildFuture();
						})
						.then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> {
							String key = StringArgumentType.getString(c, "key");
							if (!SETTINGS.containsKey(key)) {
								c.getSource().sendFailure(Component.literal("Unknown setting " + key + ", one of " + SETTINGS.keySet()));
								return 0;
							}
							SETTINGS.put(key, StringArgumentType.getString(c, "value").trim());
							saveSettings();
							c.getSource().sendSuccess(() -> Component.literal(key + " = " + SETTINGS.get(key)), true);
							return 1;
						}))))));
		LOGGER.info("mc-sionna ready, Sionna service at {}", SETTINGS.get("url"));
	}

	private static Block registerBlock(String name) {
		Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, name);
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		Block block = Blocks.register(blockKey, RadioBlock::new, BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		BlockItem item = new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
		item.registerBlocks(Item.BY_BLOCK, item);
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);
		return block;
	}

	private static void loadSettings() {
		if (!Files.exists(CONFIG)) {
			saveSettings();
			return;
		}
		try (Reader r = Files.newBufferedReader(CONFIG)) {
			Properties p = new Properties();
			p.load(r);
			p.forEach((k, v) -> SETTINGS.put((String) k, (String) v));
		} catch (IOException e) {
			LOGGER.warn("could not read {}", CONFIG, e);
		}
	}

	private static void saveSettings() {
		try (Writer w = Files.newBufferedWriter(CONFIG)) {
			Properties p = new Properties();
			p.putAll(SETTINGS);
			p.store(w, "mc-sionna settings (also editable in game with /sionna set <key> <value>)");
		} catch (IOException e) {
			LOGGER.warn("could not write {}", CONFIG, e);
		}
	}
}
