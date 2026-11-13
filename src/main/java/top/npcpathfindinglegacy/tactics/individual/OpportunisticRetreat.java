package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class OpportunisticRetreat {
    public static Decision decide(TacticalContext c) { return c.health() < 0.35 || c.recentHurt() && c.health() < 0.55 ? Decision.withdraw(12) : new Decision(c.preferredRange(), 0, 1.1, false, true); }
}
