package top.npcpathfindinglegacy;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.ForgeConfigSpec;

public final class NpcPathfindingConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Master switch. Per-NPC enhanced mode still defaults to disabled.")
        .define("enabled", true);

    private static final ForgeConfigSpec.BooleanValue PREVENT_CHUNK_LOADS = BUILDER
        .comment("Compatibility setting. Asynchronous navigation always avoids unloaded chunks.")
        .define("preventChunkLoads", true);

    private static final ForgeConfigSpec.IntValue PATH_REUSE_TICKS = BUILDER
        .comment("Reuse a moving target path for this many ticks when the target barely moved.")
        .defineInRange("pathReuseTicks", 8, 1, 40);

    private static final ForgeConfigSpec.IntValue MAX_ENHANCED_SEARCHES_PER_TICK = BUILDER
        .comment("Server-wide asynchronous path requests started per tick; workers and snapshot capture are bounded.")
        .defineInRange("maxEnhancedSearchesPerTick", 3, 1, 32);

    private static final ForgeConfigSpec.IntValue MAX_SEARCH_NODES = BUILDER
        .comment("Maximum nodes visited by one enhanced A* calculation.")
        .defineInRange("maxSearchNodes", 1800, 200, 10000);

    private static final ForgeConfigSpec.IntValue MAX_DROP_HEIGHT = BUILDER
        .comment("Maximum downward transition considered by enhanced pathfinding.")
        .defineInRange("maxDropHeight", 8, 1, 24);

    private static final ForgeConfigSpec.IntValue STUCK_TICKS = BUILDER
        .comment("Ticks without meaningful movement before a local detour and repath.")
        .defineInRange("stuckTicks", 24, 10, 100);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCK_BLACKLIST = BUILDER
        .comment("Registry names that enhanced pathfinding must never enter or stand on.")
        .defineListAllowEmpty(List.of("blockBlacklist"), List.of(), obj -> obj instanceof String);

    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCK_WHITELIST = BUILDER
        .comment("Registry names treated as body-passable by enhanced pathfinding.")
        .defineListAllowEmpty(
            List.of("blockWhitelist"),
            List.of("minecraft:ladder", "minecraft:vine"),
            obj -> obj instanceof String
        );

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static boolean preventChunkLoads = true;
    public static int pathReuseTicks = 8;
    public static int maxEnhancedSearchesPerTick = 3;
    public static int maxSearchNodes = 1800;
    public static int maxDropHeight = 8;
    public static int stuckTicks = 24;

    private static Set<String> blacklist = Collections.emptySet();
    private static Set<String> whitelist = Collections.emptySet();

    private NpcPathfindingConfig() {
    }

    public static void refresh() {
        enabled = ENABLED.get();
        preventChunkLoads = PREVENT_CHUNK_LOADS.get();
        pathReuseTicks = PATH_REUSE_TICKS.get();
        maxEnhancedSearchesPerTick = MAX_ENHANCED_SEARCHES_PER_TICK.get();
        maxSearchNodes = MAX_SEARCH_NODES.get();
        maxDropHeight = MAX_DROP_HEIGHT.get();
        stuckTicks = STUCK_TICKS.get();
        blacklist = normalize(BLOCK_BLACKLIST.get());
        whitelist = normalize(BLOCK_WHITELIST.get());
    }

    public static boolean isBlacklisted(Block block) {
        String key = registryName(block);
        return key != null && blacklist.contains(key);
    }

    public static boolean isWhitelisted(Block block) {
        String key = registryName(block);
        return key != null && whitelist.contains(key);
    }

    private static String registryName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalize(List<? extends String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
