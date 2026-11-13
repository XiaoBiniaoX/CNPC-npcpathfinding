package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.compat.LegacyMath;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Early vanilla mixins use only this Object boundary, before CNPC is on the mod classpath. */
public final class LegacyVanillaHooks {
    private LegacyVanillaHooks() {}
    public static boolean preventSprint(Object entity) {
        return entity instanceof INpcPathfindingEntity && NpcHooks.preventSprint(entity);
    }
    public static boolean preventLeap(Object entity) {
        return entity instanceof INpcPathfindingEntity && NpcHooks.preventLeap(entity);
    }
    public static boolean preventJump(Object entity) {
        return entity instanceof INpcPathfindingEntity && NpcHooks.preventJump(entity);
    }
    public static void capJump(Object entity) {
        if (entity instanceof INpcPathfindingEntity) NpcHooks.capJump(entity);
    }
    public static void removed(Object entity) {
        if (!(entity instanceof INpcPathfindingEntity)) return;
        NpcHooks.removed(entity);
        ((INpcPathfindingEntity)entity).npcpathfindinglegacy$clearEnhancedState();
    }
    public static boolean redirects(Object entity) {
        return entity instanceof INpcPathfindingEntity && NpcHooks.redirects(entity);
    }
    public static boolean remembers(Object entity, Object target) {
        return entity instanceof INpcPathfindingEntity && NpcHooks.remembers(entity,target);
    }

    // Resolve CNPC classes only after an NPC with our late mixin reaches a vanilla hook.
    private static final class NpcHooks {
        private static EntityNPCInterface npc(Object entity) {
            return entity instanceof EntityNPCInterface ? (EntityNPCInterface)entity : null;
        }
        private static boolean server(EntityNPCInterface npc) {
            return npc != null && npc.world != null && !npc.world.isRemote;
        }
        static boolean preventSprint(Object entity) {
            EntityNPCInterface npc = npc(entity);
            return server(npc) && npc.getNavigator() instanceof EnhancedPathNavigator
                && !((EnhancedPathNavigator)npc.getNavigator()).maySprint();
        }
        static boolean preventLeap(Object entity) {
            EntityNPCInterface npc = npc(entity);
            return npc != null && (npc.getNavigator() instanceof EnhancedPathNavigator || TacticsRuntime.active(npc));
        }
        static boolean preventJump(Object entity) {
            EntityNPCInterface npc = npc(entity);
            return server(npc) && npc.getNavigator() instanceof EnhancedPathNavigator
                && ((EnhancedPathNavigator)npc.getNavigator()).controlsJump();
        }
        static void capJump(Object entity) {
            EntityNPCInterface npc = npc(entity);
            if (server(npc) && npc.getNavigator() instanceof EnhancedPathNavigator
                && ((EnhancedPathNavigator)npc.getNavigator()).controlledMode()) {
                Vec3d motion = LegacyMath.motion(npc);
                LegacyMath.motion(npc,motion.x,Math.min(motion.y,0.50),motion.z);
            }
        }
        static void removed(Object entity) {
            EntityNPCInterface npc = npc(entity); if (server(npc)) TacticsRuntime.removed(npc);
        }
        static boolean redirects(Object entity) {
            EntityNPCInterface npc = npc(entity); return npc != null && TacticsRuntime.redirects(npc);
        }
        static boolean remembers(Object entity, Object target) {
            EntityNPCInterface npc = npc(entity);
            return npc != null && target instanceof Entity && TacticsRuntime.retainHiddenTarget(npc,(Entity)target);
        }
    }
}
