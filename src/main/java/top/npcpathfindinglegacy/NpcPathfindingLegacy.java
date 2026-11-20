package top.npcpathfindinglegacy;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(NpcPathfindingLegacy.MOD_ID)
public final class NpcPathfindingLegacy {
    public static final String MOD_ID = "npcpathfindinglegacy";
    public static final String NAME = "NPC Pathfinding Legacy";
    public static final String VERSION = "2.0.5";
    public static final Logger LOG = LogUtils.getLogger();

    public NpcPathfindingLegacy(IEventBus modEventBus, ModContainer modContainer) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(top.npcpathfindinglegacy.pathfinding.AsyncPathService.class);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(top.npcpathfindinglegacy.tactics.flow.FlowFieldService.class);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(top.npcpathfindinglegacy.tactics.group.SquadService.class);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(top.npcpathfindinglegacy.tactics.TacticalPositions.class);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(top.npcpathfindinglegacy.traffic.TrafficController.class);
        modContainer.registerConfig(ModConfig.Type.COMMON, NpcPathfindingConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        NpcPathfindingConfig.refresh();
        LOG.info(
            "[NPC Pathfinding Legacy] Loaded {}. Enhanced mode defaults to off; max {} A* searches/tick and {} nodes/search.",
            VERSION,
            NpcPathfindingConfig.maxEnhancedSearchesPerTick,
            NpcPathfindingConfig.maxSearchNodes
        );
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == NpcPathfindingConfig.SPEC) {
            NpcPathfindingConfig.refresh();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == NpcPathfindingConfig.SPEC) {
            NpcPathfindingConfig.refresh();
        }
    }
}
