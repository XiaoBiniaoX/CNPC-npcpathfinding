package top.npcpathfinding.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NpcPathfindingConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> WHITELIST;

    private static volatile Set<String> cachedBlacklist = Collections.emptySet();
    private static volatile Set<String> cachedWhitelist = Collections.emptySet();
    private static volatile int cacheStamp = Integer.MIN_VALUE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Block blacklist - blocks considered impassable in both pathfinding modes",
                "Fill in block registry names, e.g. minecraft:cactus, minecraft:magma_block");
        BLACKLIST = builder.defineList("blockBlacklist", List.of(),
                obj -> obj instanceof String s && ResourceLocation.isValidResourceLocation(s));

        builder.comment("Block whitelist - blocks considered passable in CNPC vanilla pathfinding (ladder by default)",
                "Fill in block registry names, e.g. minecraft:ladder");
        WHITELIST = builder.defineList("blockWhitelist", List.of("minecraft:ladder"),
                obj -> obj instanceof String s && ResourceLocation.isValidResourceLocation(s));

        SPEC = builder.build();
    }

    private static void refreshCacheIfNeeded() {
        List<? extends String> bl = BLACKLIST.get();
        List<? extends String> wl = WHITELIST.get();
        int stamp = System.identityHashCode(bl) ^ (System.identityHashCode(wl) * 31) ^ (bl.size() << 16) ^ wl.size();
        if (stamp == cacheStamp) return;
        cachedBlacklist = Collections.unmodifiableSet(new HashSet<>(bl));
        cachedWhitelist = Collections.unmodifiableSet(new HashSet<>(wl));
        cacheStamp = stamp;
    }

    public static boolean isBlockBlacklisted(ResourceLocation blockId) {
        refreshCacheIfNeeded();
        return cachedBlacklist.contains(blockId.toString());
    }

    public static boolean isBlockWhitelisted(ResourceLocation blockId) {
        refreshCacheIfNeeded();
        return cachedWhitelist.contains(blockId.toString());
    }
}
