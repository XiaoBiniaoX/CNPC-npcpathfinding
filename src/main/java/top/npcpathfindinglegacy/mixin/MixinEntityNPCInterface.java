package top.npcpathfindinglegacy.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.NpcPathfindingConfig;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity;

@Mixin(value = EntityNPCInterface.class, remap = false)
public abstract class MixinEntityNPCInterface extends PathfinderMob implements INpcPathfindingEntity {
    @Shadow private int taskCount;
    @Shadow private void updateTasks() { throw new AssertionError("Mixin shadow"); }
    @Unique private boolean npcpathfindinglegacy$refreshAiRequested;
    @Unique
    private PathNavigation npcpathfindinglegacy$vanillaNavigation;

    @Unique
    private EnhancedPathNavigator npcpathfindinglegacy$enhancedNavigation;

    @Unique
    private int npcpathfindinglegacy$lastMode = -1;

    protected MixinEntityNPCInterface(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }
    @Inject(method = "setMoveType", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$addFormationFollow(CallbackInfo ci) {
        EntityNPCInterface npc = (EntityNPCInterface)(Object)this;
        if (npc.ais instanceof top.npcpathfindinglegacy.tactics.ITacticsData data
            && (data.npcpathfindinglegacy$getTactic() == 8 || data.npcpathfindinglegacy$getTactic() == 9)) {
            goalSelector.addGoal(0,new top.npcpathfindinglegacy.tactics.group.FormationFollowGoal(npc));
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$initialize(EntityType<? extends PathfinderMob> type, Level level, CallbackInfo ci) {
        this.npcpathfindinglegacy$vanillaNavigation = this.navigation;
        this.npcpathfindinglegacy$refreshNavigationMode();
    }

    @Inject(method = "updateTasks", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$afterTasksRebuilt(CallbackInfo ci) {
        if (this.navigation != this.npcpathfindinglegacy$enhancedNavigation) {
            this.npcpathfindinglegacy$vanillaNavigation = this.navigation;
        }
        if (this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.stop();
        }
        this.npcpathfindinglegacy$refreshNavigationMode();
    }

    // Entity.aiStep is aiStep in the reobfuscated game/CNPC jar.
    @Inject(method = "aiStep", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$beforeNpcTick(CallbackInfo ci) {
        if (npcpathfindinglegacy$refreshAiRequested && !level().isClientSide) {
            npcpathfindinglegacy$refreshAiRequested = false;
            updateTasks();
            ((EntityNPCInterface)(Object)this).updateAI = false;
        }
        this.npcpathfindinglegacy$refreshNavigationMode();
        top.npcpathfindinglegacy.tactics.TacticsRuntime.tick((EntityNPCInterface)(Object)this);
    }

    @Inject(method = "aiStep", at = @At("RETURN"), remap = false)
    private void npcpathfindinglegacy$afterNpcTick(CallbackInfo ci) {
        top.npcpathfindinglegacy.tactics.TacticsRuntime.keepMoving((EntityNPCInterface)(Object)this);
        if (this.navigation == this.npcpathfindinglegacy$enhancedNavigation
            && this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.applyPostAiMovementLock();
        }
    }

    @Override
    public PathNavigation npcpathfindinglegacy$getVanillaNavigation() {
        return this.npcpathfindinglegacy$vanillaNavigation;
    }

    @Override
    public PathNavigation npcpathfindinglegacy$getEnhancedNavigation() {
        return this.npcpathfindinglegacy$enhancedNavigation;
    }

    @Override
    public void npcpathfindinglegacy$refreshNavigationMode() {
        EntityNPCInterface npc = (EntityNPCInterface) (Object) this;
        int mode = 0;
        if (npc.ais instanceof IDataAIPathfinding) {
            mode = ((IDataAIPathfinding) npc.ais).npcpathfindinglegacy$getMode();
        }

        boolean useEnhanced = NpcPathfindingConfig.enabled
            && mode >= 1 && mode <= 7
            && npc.ais != null
            && npc.ais.movementType == 0
            && this.level() != null
            && !this.level().isClientSide;

        if (this.npcpathfindinglegacy$lastMode != mode) {
            this.npcpathfindinglegacy$lastMode = mode;
            this.npcpathfindinglegacy$clearEnhancedState();
        }

        if (!useEnhanced) {
            if (this.navigation == this.npcpathfindinglegacy$enhancedNavigation
                && this.npcpathfindinglegacy$vanillaNavigation != null) {
                this.npcpathfindinglegacy$enhancedNavigation.stop();
                this.navigation = this.npcpathfindinglegacy$vanillaNavigation;
            }
            return;
        }

        if (this.navigation != this.npcpathfindinglegacy$enhancedNavigation
            && this.navigation instanceof GroundPathNavigation) {
            this.npcpathfindinglegacy$vanillaNavigation = this.navigation;
        }
        if (this.npcpathfindinglegacy$enhancedNavigation == null) {
            this.npcpathfindinglegacy$enhancedNavigation = new EnhancedPathNavigator(npc, this.level());
        }
        this.npcpathfindinglegacy$enhancedNavigation.refreshCapabilities();
        this.navigation = this.npcpathfindinglegacy$enhancedNavigation;
    }

    @Override
    public void npcpathfindinglegacy$requestAiRefresh() { npcpathfindinglegacy$refreshAiRequested = true; }

    @Override
    public void npcpathfindinglegacy$clearEnhancedState() {
        if (this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.stop();
        }
    }
}
