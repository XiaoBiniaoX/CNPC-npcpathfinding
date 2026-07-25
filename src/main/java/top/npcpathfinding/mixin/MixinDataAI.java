package top.npcpathfinding.mixin;

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
import top.npcpathfinding.pathfinding.IDataAIPathfinding;
import top.npcpathfinding.pathfinding.INpcPathfindingEntity;

@Mixin(value = DataAI.class, remap = false)
public abstract class MixinDataAI implements IDataAIPathfinding {
    @Shadow private EntityNPCInterface npc;

    @Unique private int npcpathfinding$mode = 0;
    @Unique private int npcpathfinding$walkTendency = 50;
    @Unique private int npcpathfinding$jumpTendency = 50;
    @Unique private int npcpathfinding$parkourMaxDist = 4;
    @Unique private int npcpathfinding$chaseJumpInterval = 40;

    @Inject(method = "readToNBT", at = @At("TAIL"))
    private void onReadToNBT(CompoundTag compound, CallbackInfo ci) {
        if (compound.contains("NPC_PathfindingMode")) {
            this.npcpathfinding$mode = compound.getInt("NPC_PathfindingMode");
        }
        if (compound.contains("NPC_WalkTendency")) {
            this.npcpathfinding$walkTendency = compound.getInt("NPC_WalkTendency");
        }
        if (compound.contains("NPC_JumpTendency")) {
            this.npcpathfinding$jumpTendency = compound.getInt("NPC_JumpTendency");
        }
        if (compound.contains("NPC_ParkourMaxDist")) {
            this.npcpathfinding$parkourMaxDist = compound.getInt("NPC_ParkourMaxDist");
        }
        if (compound.contains("NPC_ChaseJumpInterval")) {
            this.npcpathfinding$chaseJumpInterval = compound.getInt("NPC_ChaseJumpInterval");
        }
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void onSave(CompoundTag compound, CallbackInfoReturnable<CompoundTag> cir) {
        var tag = cir.getReturnValue();
        tag.putInt("NPC_PathfindingMode", this.npcpathfinding$mode);
        tag.putInt("NPC_WalkTendency", this.npcpathfinding$walkTendency);
        tag.putInt("NPC_JumpTendency", this.npcpathfinding$jumpTendency);
        tag.putInt("NPC_ParkourMaxDist", this.npcpathfinding$parkourMaxDist);
        tag.putInt("NPC_ChaseJumpInterval", this.npcpathfinding$chaseJumpInterval);
    }

    @Override
    public int npcpathfinding$getMode() { return this.npcpathfinding$mode; }

    @Override
    public void npcpathfinding$setMode(int mode) {
        this.npcpathfinding$mode = mode;
        if (this.npc != null) {
            if (this.npc instanceof INpcPathfindingEntity pathing) {
                pathing.npcpathfinding$clearBaritoneState();
                pathing.npcpathfinding$refreshNavigationMode();
            }
            this.npc.updateAI = true;
        }
    }

    @Override
    public int npcpathfinding$getWalkTendency() { return this.npcpathfinding$walkTendency; }

    @Override
    public void npcpathfinding$setWalkTendency(int val) {
        this.npcpathfinding$walkTendency = Math.max(0, Math.min(100, val));
    }

    @Override
    public int npcpathfinding$getJumpTendency() { return this.npcpathfinding$jumpTendency; }

    @Override
    public void npcpathfinding$setJumpTendency(int val) {
        this.npcpathfinding$jumpTendency = Math.max(0, Math.min(100, val));
    }

    @Override
    public int npcpathfinding$getParkourMaxDist() { return this.npcpathfinding$parkourMaxDist; }

    @Override
    public void npcpathfinding$setParkourMaxDist(int val) {
        this.npcpathfinding$parkourMaxDist = Math.max(0, Math.min(5, val));
    }

    @Override
    public int npcpathfinding$getChaseJumpInterval() { return this.npcpathfinding$chaseJumpInterval; }

    @Override
    public void npcpathfinding$setChaseJumpInterval(int val) {
        this.npcpathfinding$chaseJumpInterval = Math.max(0, Math.min(200, val));
    }
}
