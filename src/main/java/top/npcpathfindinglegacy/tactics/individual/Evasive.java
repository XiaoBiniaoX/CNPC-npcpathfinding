package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Evasive {
    public static Decision decide(TacticalContext c) { return new Decision(c.preferredRange() + (c.recentHurt() ? 4 : 0), c.side() * (c.attackPhase() ? 1 : -1) * c.attackLateral(4), 1.3, c.health() < 0.3, true); }
}
