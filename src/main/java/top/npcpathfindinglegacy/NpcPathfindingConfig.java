package top.npcpathfindinglegacy;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

public final class NpcPathfindingConfig {
    private static final String CATEGORY_GENERAL = "general";
    private static final String CATEGORY_BLOCKS = "blocks";

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

    public static void load(File file) {
        Configuration config = new Configuration(file);
        try {
            config.load();
            enabled = config.getBoolean(
                "enabled",
                CATEGORY_GENERAL,
                true,
                "Master switch. Per-NPC enhanced mode still defaults to disabled."
            );
            preventChunkLoads = config.getBoolean(
                "preventChunkLoads",
                CATEGORY_GENERAL,
                true,
                "Never query blocks in unloaded chunks while calculating a path."
            );
            pathReuseTicks = config.getInt(
                "pathReuseTicks",
                CATEGORY_GENERAL,
                8,
                1,
                40,
                "Reuse a moving target path for this many ticks when the target barely moved."
            );
            maxEnhancedSearchesPerTick = config.getInt(
                "maxEnhancedSearchesPerTick",
                CATEGORY_GENERAL,
                3,
                1,
                32,
                "Server-wide enhanced A* calculations allowed per server tick."
            );
            maxSearchNodes = config.getInt(
                "maxSearchNodes",
                CATEGORY_GENERAL,
                1800,
                200,
                10000,
                "Maximum nodes visited by one enhanced A* calculation."
            );
            maxDropHeight = config.getInt(
                "maxDropHeight",
                CATEGORY_GENERAL,
                8,
                1,
                24,
                "Maximum downward transition considered by enhanced pathfinding."
            );
            stuckTicks = config.getInt(
                "stuckTicks",
                CATEGORY_GENERAL,
                24,
                10,
                100,
                "Ticks without meaningful movement before a local detour and repath."
            );

            blacklist = normalize(config.getStringList(
                "blockBlacklist",
                CATEGORY_BLOCKS,
                new String[0],
                "Registry names that enhanced pathfinding must never enter or stand on."
            ));
            whitelist = normalize(config.getStringList(
                "blockWhitelist",
                CATEGORY_BLOCKS,
                new String[]{"minecraft:ladder", "minecraft:vine"},
                "Registry names treated as body-passable by enhanced pathfinding."
            ));
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
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
        ResourceLocation name = ForgeRegistries.BLOCKS.getKey(block);
        return name == null ? null : name.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalize(String[] values) {
        Set<String> result = new HashSet<String>();
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
