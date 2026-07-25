package top.npcpathfinding.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import noppes.npcs.ai.NpcGroundPathNavigator;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfinding.pathfinding.IDataAIPathfinding;
import top.npcpathfinding.pathfinding.INpcPathfindingEntity;
import top.npcpathfinding.pathfinding.NpcBaritonePathNavigator;

@Mixin(value = EntityNPCInterface.class, remap = false)
public class MixinEntityNPCInterface implements INpcPathfindingEntity {
    @Unique private PathNavigation npcpathfinding$vanillaNavigation;
    @Unique private PathNavigation npcpathfinding$baritoneNavigation;
    @Unique private int npcpathfinding$lastMode = -1;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void npcpathfinding$initNavigations(EntityType<? extends PathfinderMob> type, Level level, CallbackInfo ci) {
        EntityNPCInterface npc = (EntityNPCInterface) (Object) this;
        Mob mob = (Mob) npc;
        this.npcpathfinding$vanillaNavigation = mob.navigation != null
                ? mob.navigation
                : new NpcGroundPathNavigator((Mob) npc, level);
        this.npcpathfinding$baritoneNavigation = new NpcBaritonePathNavigator((Mob) npc, level);
        this.npcpathfinding$refreshNavigationMode();
    }

    @Inject(method = "m_8107_", at = @At("HEAD"))
    private void npcpathfinding$refreshNavigationBeforeAi(CallbackInfo ci) {
        this.npcpathfinding$refreshNavigationMode();
    }

    /** After goals + moveControl: re-force hop XZ only (no head snap — caused nod/spin). */
    @Inject(method = "m_8107_", at = @At("TAIL"))
    private void npcpathfinding$applyParkourAfterAi(CallbackInfo ci) {
        if (this.npcpathfinding$baritoneNavigation instanceof NpcBaritonePathNavigator nav) {
            nav.applyPostAiParkourLock();
        }
    }

    public PathNavigation m_21573_() {
        this.npcpathfinding$refreshNavigationMode();
        return ((Mob) (Object) this).navigation;
    }

    @Override
    public PathNavigation npcpathfinding$getVanillaNavigation() {
        return this.npcpathfinding$vanillaNavigation;
    }

    @Override
    public PathNavigation npcpathfinding$getBaritoneNavigation() {
        return this.npcpathfinding$baritoneNavigation;
    }

    @Override
    public void npcpathfinding$refreshNavigationMode() {
        EntityNPCInterface npc = (EntityNPCInterface) (Object) this;
        Mob mob = (Mob) npc;
        if (this.npcpathfinding$vanillaNavigation == null) {
            this.npcpathfinding$vanillaNavigation = mob.navigation;
        }
        if (this.npcpathfinding$baritoneNavigation == null && npc.level() != null) {
            this.npcpathfinding$baritoneNavigation = new NpcBaritonePathNavigator((Mob) npc, npc.level());
        }
        int mode = npc.ais instanceof IDataAIPathfinding data ? data.npcpathfinding$getMode() : 0;
        if (this.npcpathfinding$lastMode != mode) {
            this.npcpathfinding$lastMode = mode;
            this.npcpathfinding$clearBaritoneState();
        }
        if (mode == 1 && this.npcpathfinding$baritoneNavigation != null) {
            mob.navigation = this.npcpathfinding$baritoneNavigation;
        } else if (this.npcpathfinding$vanillaNavigation != null) {
            mob.navigation = this.npcpathfinding$vanillaNavigation;
        }
    }

    @Override
    public void npcpathfinding$clearBaritoneState() {
        if (this.npcpathfinding$baritoneNavigation != null) {
            this.npcpathfinding$baritoneNavigation.stop();
        }
    }
}
