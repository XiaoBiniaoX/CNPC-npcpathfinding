package top.npcpathfindinglegacy.mixin;

import net.minecraft.nbt.CompoundTag;
import noppes.npcs.entity.EntityNPCInterface;
import noppes.npcs.entity.data.DataAI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.npcpathfindinglegacy.pathfinding.IDataAIPathfinding;
import top.npcpathfindinglegacy.pathfinding.INpcPathfindingEntity;

@Mixin(value = DataAI.class, remap = false)
public abstract class MixinDataAI implements IDataAIPathfinding {
    @Shadow
    private EntityNPCInterface npc;

    @Unique
    private int npcpathfindinglegacy$mode;

    @Unique
    private int npcpathfindinglegacy$walkTendency = 50;

    @Unique
    private int npcpathfindinglegacy$jumpTendency = 50;

    @Unique
    private int npcpathfindinglegacy$parkourMaxDist = 4;

    @Unique
    private int npcpathfindinglegacy$chaseJumpInterval = 40;

    @Inject(method = "readToNBT", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$readSettings(CompoundTag compound, CallbackInfo ci) {
        int oldMode = this.npcpathfindinglegacy$mode;
        if (compound.contains("NPC_PathfindingMode")) {
            this.npcpathfindinglegacy$mode = clamp(compound.getInt("NPC_PathfindingMode"), 0, 1);
        }
        if (compound.contains("NPC_WalkTendency")) {
            this.npcpathfindinglegacy$walkTendency = clamp(compound.getInt("NPC_WalkTendency"), 0, 100);
        }
        if (compound.contains("NPC_JumpTendency")) {
            this.npcpathfindinglegacy$jumpTendency = clamp(compound.getInt("NPC_JumpTendency"), 0, 100);
        }
        if (compound.contains("NPC_ParkourMaxDist")) {
            this.npcpathfindinglegacy$parkourMaxDist = clamp(compound.getInt("NPC_ParkourMaxDist"), 0, 5);
        }
        if (compound.contains("NPC_ChaseJumpInterval")) {
            this.npcpathfindinglegacy$chaseJumpInterval = clamp(compound.getInt("NPC_ChaseJumpInterval"), 0, 200);
        }
        if (oldMode != this.npcpathfindinglegacy$mode) {
            this.npcpathfindinglegacy$notifyNavigationChanged();
        }
    }

    @Inject(method = "save", at = @At("RETURN"), remap = false)
    private void npcpathfindinglegacy$writeSettings(
        CompoundTag compound,
        CallbackInfoReturnable<CompoundTag> cir
    ) {
        CompoundTag result = cir.getReturnValue();
        if (result == null) {
            result = compound;
        }
        result.putInt("NPC_PathfindingMode", this.npcpathfindinglegacy$mode);
        result.putInt("NPC_WalkTendency", this.npcpathfindinglegacy$walkTendency);
        result.putInt("NPC_JumpTendency", this.npcpathfindinglegacy$jumpTendency);
        result.putInt("NPC_ParkourMaxDist", this.npcpathfindinglegacy$parkourMaxDist);
        result.putInt("NPC_ChaseJumpInterval", this.npcpathfindinglegacy$chaseJumpInterval);
    }

    @Override
    public int npcpathfindinglegacy$getMode() {
        return this.npcpathfindinglegacy$mode;
    }

    @Override
    public void npcpathfindinglegacy$setMode(int mode) {
        int normalized = clamp(mode, 0, 1);
        if (this.npcpathfindinglegacy$mode == normalized) {
            return;
        }
        this.npcpathfindinglegacy$mode = normalized;
        this.npcpathfindinglegacy$notifyNavigationChanged();
    }

    @Override
    public int npcpathfindinglegacy$getWalkTendency() {
        return this.npcpathfindinglegacy$walkTendency;
    }

    @Override
    public void npcpathfindinglegacy$setWalkTendency(int value) {
        this.npcpathfindinglegacy$walkTendency = clamp(value, 0, 100);
    }

    @Override
    public int npcpathfindinglegacy$getJumpTendency() {
        return this.npcpathfindinglegacy$jumpTendency;
    }

    @Override
    public void npcpathfindinglegacy$setJumpTendency(int value) {
        this.npcpathfindinglegacy$jumpTendency = clamp(value, 0, 100);
    }

    @Override
    public int npcpathfindinglegacy$getParkourMaxDist() {
        return this.npcpathfindinglegacy$parkourMaxDist;
    }

    @Override
    public void npcpathfindinglegacy$setParkourMaxDist(int value) {
        this.npcpathfindinglegacy$parkourMaxDist = clamp(value, 0, 5);
    }

    @Override
    public int npcpathfindinglegacy$getChaseJumpInterval() {
        return this.npcpathfindinglegacy$chaseJumpInterval;
    }

    @Override
    public void npcpathfindinglegacy$setChaseJumpInterval(int value) {
        this.npcpathfindinglegacy$chaseJumpInterval = clamp(value, 0, 200);
    }

    @Unique
    private void npcpathfindinglegacy$notifyNavigationChanged() {
        if (this.npc == null) {
            return;
        }
        if (this.npc instanceof INpcPathfindingEntity) {
            INpcPathfindingEntity pathing = (INpcPathfindingEntity) this.npc;
            pathing.npcpathfindinglegacy$clearEnhancedState();
            pathing.npcpathfindinglegacy$refreshNavigationMode();
        }
        this.npc.updateAI = true;
    }

    @Unique
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
