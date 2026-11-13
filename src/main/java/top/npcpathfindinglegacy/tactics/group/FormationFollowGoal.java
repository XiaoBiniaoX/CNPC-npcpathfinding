package top.npcpathfindinglegacy.tactics.group;

import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.TacticsRuntime;

/** Reserve idle followers' MOVE flag before CNPC wandering and patrol can issue competing requests. */
public final class FormationFollowGoal extends Goal {
    private final EntityNPCInterface npc;
    public FormationFollowGoal(EntityNPCInterface npc) { this.npc = npc; setFlags(EnumSet.of(Flag.MOVE)); }
    @Override public boolean canUse() { return TacticsRuntime.formationFollower(npc) && npc.getTarget() == null; }
    @Override public boolean canContinueToUse() { return canUse(); }
}
