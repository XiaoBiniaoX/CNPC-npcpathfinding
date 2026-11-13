package top.npcpathfindinglegacy;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = NpcPathfindingLegacy.MOD_ID,
    name = NpcPathfindingLegacy.NAME,
    version = NpcPathfindingLegacy.VERSION,
    acceptableRemoteVersions = "[2.0.0,)",
    dependencies = "required-after:customnpcs;required-after:cnpcplus@[4.0.5,);required-after:mixinbooter"
)
public final class NpcPathfindingLegacy {
    public static final String MOD_ID = "npcpathfindinglegacy";
    public static final String NAME = "NPC Pathfinding Legacy";
    public static final String VERSION = "2.0.0";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Only ServerTickEvent uses the Forge bus; stopping is forwarded by the FML handler below.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(top.npcpathfindinglegacy.tactics.flow.FlowFieldService.class);
        NpcPathfindingConfig.load(event.getSuggestedConfigurationFile());
        LOG.info(
            "[NPC Pathfinding Legacy] Loaded {}. CustomNPCs SRParasites compatibility layer detected: {}",
            VERSION,
            classExists("mchhui.compat.SRParasitesCustomNPCCompat")
        );
        if (!classExists("zone.rong.mixinbooter.ILateMixinLoader")) {
            LOG.error("[NPC Pathfinding Legacy] MixinBooter was not found; pathfinding mixins cannot load.");
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOG.info(
            "[NPC Pathfinding Legacy] Ready. Enhanced mode defaults to off; max {} A* searches/tick and {} nodes/search.",
            NpcPathfindingConfig.maxEnhancedSearchesPerTick,
            NpcPathfindingConfig.maxSearchNodes
        );
    }

    @Mod.EventHandler
    public void serverStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent event) {
        // 1.12.2 lifecycle events are delivered by FML, not MinecraftForge.EVENT_BUS.
        top.npcpathfindinglegacy.pathfinding.AsyncPathService.onServerStopping(event);
        top.npcpathfindinglegacy.tactics.flow.FlowFieldService.stopped(event);
        top.npcpathfindinglegacy.tactics.group.SquadService.stopped(event);
        top.npcpathfindinglegacy.tactics.TacticalPositions.stopped(event);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, NpcPathfindingLegacy.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
