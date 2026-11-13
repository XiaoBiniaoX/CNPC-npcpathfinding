package top.npcpathfindinglegacy.script;

import net.minecraft.world.WorldServer;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity;
import top.npcpathfindinglegacy.tactics.ITacticsData;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Immutable script global; no NPC or world references survive a call. */
public final class NpcPathfindingAPI {
    // Instance fields are visible on the injected object in JSR223 Java interop, including Nashorn.
    public final int PATH_VANILLA = 0, PATH_ENHANCED = 1, PATH_WALL = 2, PATH_OPTIMAL = 3,
        PATH_COMBAT = 4, PATH_THETA = 5, PATH_JPS = 6, PATH_SEGMENTED = 7;
    public final int TACTIC_ASSAULT = 0, TACTIC_FLANKING = 1, TACTIC_PINCER = 2, TACTIC_COVER = 3,
        TACTIC_GROUP_AMBUSH = 4, TACTIC_RETREAT = 5, TACTIC_ADVANCE = 6, TACTIC_SKIRMISH = 7,
        TACTIC_FORMATION = 8, TACTIC_AUTO = 9, TACTIC_AGGRESSIVE = 10, TACTIC_EVASIVE = 11,
        TACTIC_KITING = 12, TACTIC_HIT_AND_RUN = 13, TACTIC_BERSERKER = 14, TACTIC_OPPORTUNISTIC = 15,
        TACTIC_COUNTERATTACK = 16, TACTIC_AMBUSH = 17, TACTIC_STRAFING = 18, TACTIC_CONDITIONAL_RETREAT = 19;

    public int getPathfindingMode(ICustomNpc<?> npc) {
        return ((IDataAIPathfinding)entity(npc).ais).npcpathfindinglegacy$getMode();
    }
    public void setPathfindingMode(ICustomNpc<?> npc, int mode) {
        if (mode < PATH_VANILLA || mode > PATH_SEGMENTED) throw new IllegalArgumentException("Pathfinding mode must be 0..7: " + mode);
        EntityNPCInterface entity = entity(npc);
        IDataAIPathfinding data = (IDataAIPathfinding)entity.ais;
        if (data.npcpathfindinglegacy$getMode() == mode) return;
        entity.getNavigator().clearPath();
        data.npcpathfindinglegacy$setMode(mode);
    }
    public int getTacticVariant(ICustomNpc<?> npc) {
        return ((ITacticsData)entity(npc).ais).npcpathfindinglegacy$getTactic();
    }
    public void setTacticVariant(ICustomNpc<?> npc, int tactic) {
        if (tactic < TACTIC_ASSAULT || tactic > TACTIC_CONDITIONAL_RETREAT) throw new IllegalArgumentException("Tactic variant must be 0..19: " + tactic);
        EntityNPCInterface entity = entity(npc);
        ITacticsData data = (ITacticsData)entity.ais;
        if (data.npcpathfindinglegacy$getTactic() == tactic) return;
        entity.getNavigator().clearPath();
        TacticsRuntime.removed(entity);
        data.npcpathfindinglegacy$setTactic(tactic);
        // Scripts may run inside a EntityAIBase callback. Rebuild before the next AI tick, outside EntityAIBase iteration.
        ((INpcPathfindingEntity)entity).npcpathfindinglegacy$requestAiRefresh();
    }
    private static EntityNPCInterface entity(ICustomNpc<?> npc) {
        if (npc == null || !(npc.getMCEntity() instanceof EntityNPCInterface))
            throw new IllegalArgumentException("Expected a CustomNPC wrapper such as event.npc");
        EntityNPCInterface entity = (EntityNPCInterface)npc.getMCEntity();
        if (!(entity.world instanceof WorldServer) || !entity.world.getMinecraftServer().isCallingFromMinecraftThread())
            throw new IllegalStateException("NpcPathfinding API must run on the server thread");
        if (entity.isDead) throw new IllegalStateException("Cannot access a removed NPC");
        if (!(entity.ais instanceof IDataAIPathfinding) || !(entity.ais instanceof ITacticsData) || !(entity instanceof INpcPathfindingEntity))
            throw new IllegalStateException("NPC Pathfinding mixins are not available for this NPC");
        return entity;
    }
}
