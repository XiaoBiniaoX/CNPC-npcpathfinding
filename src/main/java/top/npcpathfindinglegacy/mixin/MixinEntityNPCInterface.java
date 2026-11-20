package top.npcpathfindinglegacy.mixin;

import net.minecraft.entity.EntityCreature;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.world.World;
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
import top.npcpathfindinglegacy.traffic.TrafficController;

@Mixin(value = EntityNPCInterface.class, remap = false)
public abstract class MixinEntityNPCInterface extends EntityCreature implements INpcPathfindingEntity {
    @Shadow private int taskCount;
    @Shadow private void updateTasks() { throw new AssertionError("Mixin shadow"); }
    @Unique private boolean npcpathfindinglegacy$refreshAiRequested;
    @Unique
    private PathNavigate npcpathfindinglegacy$vanillaNavigation;

    @Unique
    private EnhancedPathNavigator npcpathfindinglegacy$enhancedNavigation;

    @Unique
    private int npcpathfindinglegacy$lastMode = -1;

    protected MixinEntityNPCInterface(World level) {
        super(level);
    }
    @Inject(method = "setMoveType", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$addFormationFollow(CallbackInfo ci) {
        EntityNPCInterface npc = (EntityNPCInterface)(Object)this;
        if (npc.ais instanceof top.npcpathfindinglegacy.tactics.ITacticsData
            && (((top.npcpathfindinglegacy.tactics.ITacticsData)(npc.ais)).npcpathfindinglegacy$getTactic() == 8 || ((top.npcpathfindinglegacy.tactics.ITacticsData)(npc.ais)).npcpathfindinglegacy$getTactic() == 9)) {
            tasks.addTask(0,new top.npcpathfindinglegacy.tactics.group.FormationFollowGoal(npc));
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$initialize(World level, CallbackInfo ci) {
        this.npcpathfindinglegacy$vanillaNavigation = this.navigator;
        this.npcpathfindinglegacy$refreshNavigationMode();
    }

    @Inject(method = "updateTasks", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$afterTasksRebuilt(CallbackInfo ci) {
        if (this.navigator != this.npcpathfindinglegacy$enhancedNavigation) {
            this.npcpathfindinglegacy$vanillaNavigation = this.navigator;
        }
        if (this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.clearPath();
        }
        this.npcpathfindinglegacy$refreshNavigationMode();
    }

    // Entity.aiStep is func_70636_d in the reobfuscated game/CNPC jar.
    @Inject(method = "func_70636_d", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$beforeNpcTick(CallbackInfo ci) {
        if (npcpathfindinglegacy$refreshAiRequested && !world.isRemote) {
            npcpathfindinglegacy$refreshAiRequested = false;
            updateTasks();
            ((EntityNPCInterface)(Object)this).updateAI = false;
        }
        this.npcpathfindinglegacy$refreshNavigationMode();
        top.npcpathfindinglegacy.tactics.TacticsRuntime.tick((EntityNPCInterface)(Object)this);
    }

    @Inject(method = "func_70636_d", at = @At("RETURN"), remap = false)
    private void npcpathfindinglegacy$afterNpcTick(CallbackInfo ci) {
        top.npcpathfindinglegacy.tactics.TacticsRuntime.keepMoving((EntityNPCInterface)(Object)this);
        if (this.navigator == this.npcpathfindinglegacy$enhancedNavigation
            && this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.applyPostAiMovementLock();
        }
        TrafficController.afterAi((EntityNPCInterface)(Object)this);
    }

    @Override
    public PathNavigate npcpathfindinglegacy$getVanillaNavigation() {
        return this.npcpathfindinglegacy$vanillaNavigation;
    }

    @Override
    public PathNavigate npcpathfindinglegacy$getEnhancedNavigation() {
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
            && this.world != null
            && !this.world.isRemote;

        if (this.npcpathfindinglegacy$lastMode != mode) {
            this.npcpathfindinglegacy$lastMode = mode;
            this.npcpathfindinglegacy$clearEnhancedState();
        }

        if (!useEnhanced) {
            if (this.navigator == this.npcpathfindinglegacy$enhancedNavigation
                && this.npcpathfindinglegacy$vanillaNavigation != null) {
                this.npcpathfindinglegacy$enhancedNavigation.clearPath();
                this.navigator = this.npcpathfindinglegacy$vanillaNavigation;
            }
            return;
        }

        if (this.navigator != this.npcpathfindinglegacy$enhancedNavigation
            && this.navigator instanceof PathNavigateGround) {
            this.npcpathfindinglegacy$vanillaNavigation = this.navigator;
        }
        if (this.npcpathfindinglegacy$enhancedNavigation == null) {
            this.npcpathfindinglegacy$enhancedNavigation = new EnhancedPathNavigator(npc, this.world);
        }
        this.npcpathfindinglegacy$enhancedNavigation.refreshCapabilities();
        this.navigator = this.npcpathfindinglegacy$enhancedNavigation;
    }

    @Override
    public void npcpathfindinglegacy$requestAiRefresh() { npcpathfindinglegacy$refreshAiRequested = true; }

    @Override
    public void npcpathfindinglegacy$clearEnhancedState() {
        if (this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.clearPath();
        }
    }
}
