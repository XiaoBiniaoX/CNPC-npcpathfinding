package top.npcpathfindinglegacy.mixin;

import net.minecraft.nbt.NBTTagCompound;
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
import top.npcpathfindinglegacy.tactics.ITacticsData;
import top.npcpathfindinglegacy.tactics.TacticType;

@Mixin(value = DataAI.class, remap = false)
public abstract class MixinDataAI implements IDataAIPathfinding, ITacticsData {
    @Shadow
    private EntityNPCInterface npc;

    @Unique
    private int npcpathfindinglegacy$mode;

    @Unique
    private boolean npcpathfindinglegacy$allowSprint;

    @Unique
    private int npcpathfindinglegacy$walkTendency = 50;

    @Unique
    private int npcpathfindinglegacy$jumpTendency = 50;

    @Unique
    private int npcpathfindinglegacy$parkourMaxDist = 4;

    @Unique
    private int npcpathfindinglegacy$chaseJumpInterval = 40;
    @Unique private int npcpathfindinglegacy$tactic;
    @Unique private int npcpathfindinglegacy$squad;
    @Unique private int npcpathfindinglegacy$tacticRadius = 12;
    @Unique private double npcpathfindinglegacy$comfortDistance = 1.4;

    @Inject(method = "readToNBT", at = @At("TAIL"), remap = false)
    private void npcpathfindinglegacy$readSettings(NBTTagCompound compound, CallbackInfo ci) {
        int oldMode = this.npcpathfindinglegacy$mode;
        boolean oldSprint = this.npcpathfindinglegacy$allowSprint;
        int oldTactic = npcpathfindinglegacy$tactic;
        int oldSquad = npcpathfindinglegacy$squad;
        npcpathfindinglegacy$tactic = TacticType.byId(compound.getInteger("NPC_TacticVariant")).id();
        npcpathfindinglegacy$squad = clamp(compound.getInteger("NPC_TacticSquad"), 0, 9999);
        npcpathfindinglegacy$tacticRadius = compound.hasKey("NPC_TacticRadius")
            ? clamp(compound.getInteger("NPC_TacticRadius"), 3, 24) : 12;
        npcpathfindinglegacy$setComfortDistance(compound.hasKey("NPC_ComfortDistance",99)
            ? compound.getDouble("NPC_ComfortDistance") : 1.4);
        if (compound.hasKey("NPC_PathfindingMode")) {
            this.npcpathfindinglegacy$mode = clamp(compound.getInteger("NPC_PathfindingMode"), 0, 7);
        }
        this.npcpathfindinglegacy$allowSprint = compound.getBoolean("NPC_PathfindingAllowSprint");
        if (compound.hasKey("NPC_WalkTendency")) {
            this.npcpathfindinglegacy$walkTendency = clamp(compound.getInteger("NPC_WalkTendency"), 0, 100);
        }
        if (compound.hasKey("NPC_JumpTendency")) {
            this.npcpathfindinglegacy$jumpTendency = clamp(compound.getInteger("NPC_JumpTendency"), 0, 100);
        }
        if (compound.hasKey("NPC_ParkourMaxDist")) {
            this.npcpathfindinglegacy$parkourMaxDist = clamp(compound.getInteger("NPC_ParkourMaxDist"), 0, 5);
        }
        if (compound.hasKey("NPC_ChaseJumpInterval")) {
            this.npcpathfindinglegacy$chaseJumpInterval = clamp(compound.getInteger("NPC_ChaseJumpInterval"), 0, 200);
        }
        if (oldMode != this.npcpathfindinglegacy$mode || oldSprint != this.npcpathfindinglegacy$allowSprint
            || oldTactic != npcpathfindinglegacy$tactic || oldSquad != npcpathfindinglegacy$squad) {
            this.npcpathfindinglegacy$notifyNavigationChanged();
        }
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = false)
    private void npcpathfindinglegacy$writeSettings(
        NBTTagCompound compound,
        CallbackInfoReturnable<NBTTagCompound> cir
    ) {
        NBTTagCompound result = cir.getReturnValue();
        if (result == null) {
            result = compound;
        }
        result.setInteger("NPC_PathfindingMode", this.npcpathfindinglegacy$mode);
        result.setBoolean("NPC_PathfindingAllowSprint", this.npcpathfindinglegacy$allowSprint);
        result.setInteger("NPC_WalkTendency", this.npcpathfindinglegacy$walkTendency);
        result.setInteger("NPC_JumpTendency", this.npcpathfindinglegacy$jumpTendency);
        result.setInteger("NPC_ParkourMaxDist", this.npcpathfindinglegacy$parkourMaxDist);
        result.setInteger("NPC_ChaseJumpInterval", this.npcpathfindinglegacy$chaseJumpInterval);
        result.setInteger("NPC_TacticVariant", npcpathfindinglegacy$tactic);
        result.setInteger("NPC_TacticSquad", npcpathfindinglegacy$squad);
        result.setInteger("NPC_TacticRadius", npcpathfindinglegacy$tacticRadius);
        result.setDouble("NPC_ComfortDistance",npcpathfindinglegacy$comfortDistance);
    }

    public double npcpathfindinglegacy$getComfortDistance() { return npcpathfindinglegacy$comfortDistance; }
    public void npcpathfindinglegacy$setComfortDistance(double distance) {
        npcpathfindinglegacy$comfortDistance = Double.isFinite(distance) ? Math.max(0,Math.min(8,distance)) : 1.4;
    }

    public int npcpathfindinglegacy$getTactic() { return npcpathfindinglegacy$tactic; }
    public void npcpathfindinglegacy$setTactic(int id) {
        int next = TacticType.byId(id).id();
        if (next == npcpathfindinglegacy$tactic) return;
        npcpathfindinglegacy$tactic = next; npcpathfindinglegacy$notifyNavigationChanged();
    }
    public int npcpathfindinglegacy$getSquad() { return npcpathfindinglegacy$squad; }
    public void npcpathfindinglegacy$setSquad(int squad) {
        npcpathfindinglegacy$squad = clamp(squad, 0, 9999); npcpathfindinglegacy$notifyNavigationChanged();
    }
    public int npcpathfindinglegacy$getTacticRadius() { return npcpathfindinglegacy$tacticRadius; }
    public void npcpathfindinglegacy$setTacticRadius(int radius) { npcpathfindinglegacy$tacticRadius = clamp(radius, 3, 24); }

    @Override
    public int npcpathfindinglegacy$getMode() {
        return this.npcpathfindinglegacy$mode;
    }

    @Override
    public void npcpathfindinglegacy$setMode(int mode) {
        int normalized = clamp(mode, 0, 7);
        if (this.npcpathfindinglegacy$mode == normalized) {
            return;
        }
        this.npcpathfindinglegacy$mode = normalized;
        this.npcpathfindinglegacy$notifyNavigationChanged();
    }

    @Override
    public boolean npcpathfindinglegacy$getAllowSprint() { return this.npcpathfindinglegacy$allowSprint; }

    @Override
    public void npcpathfindinglegacy$setAllowSprint(boolean allowed) {
        if (this.npcpathfindinglegacy$allowSprint == allowed) return;
        this.npcpathfindinglegacy$allowSprint = allowed;
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
