package top.npcpathfindinglegacy.mixin;

import net.minecraft.entity.EntityCreature;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.world.World;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.NpcPathfindingConfig;
import top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity;

@Mixin(value = EntityNPCInterface.class, remap = false)
public abstract class MixinEntityNPCInterface extends EntityCreature implements INpcPathfindingEntity {
    @Unique
    private PathNavigate npcpathfindinglegacy$vanillaNavigation;

    @Unique
    private EnhancedPathNavigator npcpathfindinglegacy$enhancedNavigation;

    @Unique
    private int npcpathfindinglegacy$lastMode = -1;

    protected MixinEntityNPCInterface(World world) {
        super(world);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$initialize(World world, CallbackInfo ci) {
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

    @Inject(method = "func_70636_d", at = @At("HEAD"), remap = false)
    private void npcpathfindinglegacy$beforeNpcTick(CallbackInfo ci) {
        this.npcpathfindinglegacy$refreshNavigationMode();
    }

    @Inject(method = "func_70636_d", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$afterNpcTick(CallbackInfo ci) {
        if (this.navigator == this.npcpathfindinglegacy$enhancedNavigation
            && this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.applyPostAiMovementLock();
        }
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
            && mode == 1
            && npc.ais != null
            && npc.ais.movementType == 0
            && this.world != null;

        if (this.npcpathfindinglegacy$lastMode != mode) {
            this.npcpathfindinglegacy$lastMode = mode;
            this.npcpathfindinglegacy$clearEnhancedState();
        }

        if (!useEnhanced) {
            if (this.navigator == this.npcpathfindinglegacy$enhancedNavigation
                && this.npcpathfindinglegacy$vanillaNavigation != null) {
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
    public void npcpathfindinglegacy$clearEnhancedState() {
        if (this.npcpathfindinglegacy$enhancedNavigation != null) {
            this.npcpathfindinglegacy$enhancedNavigation.clearPath();
        }
    }
}
