package top.npcpathfindinglegacy.tactics.group;

import java.util.EnumSet;
import net.minecraft.entity.ai.EntityAIBase;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Reserve idle followers' MOVE flag before CNPC wandering and patrol can issue competing requests. */
public final class FormationFollowGoal extends EntityAIBase {
    private final EntityNPCInterface npc;
    public FormationFollowGoal(EntityNPCInterface npc) { this.npc = npc; setMutexBits(1); }
    @Override public boolean shouldExecute() { return TacticsRuntime.formationFollower(npc) && npc.getAttackTarget() == null; }
    @Override public boolean shouldContinueExecuting() { return shouldExecute(); }
    @Override public void startExecuting() { TacticsRuntime.keepMoving(npc); }
}
