package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class Skirmish {
    public static Decision decide(TacticalContext c) {
        return new Decision(c.attackPhase() ? c.preferredRange() : c.preferredRange() + 5,
            c.side() * (c.attackPhase() ? c.attackLateral(4) : 4), c.attackPhase() ? 1.2 : 1.3, !c.attackPhase() && c.recentHurt(), c.attackPhase() || !c.melee());
    }
}
