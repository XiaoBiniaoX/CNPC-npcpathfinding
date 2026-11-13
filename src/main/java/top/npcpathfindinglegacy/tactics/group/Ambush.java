package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class Ambush {
    public static Decision decide(TacticalContext c) {
        return c.distance() <= 4 || c.recentHurt()
            ? new Decision(c.preferredRange(), c.side() * c.attackLateral(3), 1.3, false, true) : Decision.cover(false);
    }
}
