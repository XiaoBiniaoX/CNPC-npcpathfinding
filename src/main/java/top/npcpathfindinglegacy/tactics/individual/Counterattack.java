package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Counterattack {
    public static Decision decide(TacticalContext c) { return c.recentHurt() || c.distance() < 3 ? new Decision(c.preferredRange(), -c.side() * c.attackLateral(2), 1.3, false, true) : new Decision(Math.min(c.distance(), c.preferredRange() + 2), 0, 0.8, false, false); }
}
