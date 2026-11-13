package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class Flanking {
    public static Decision decide(TacticalContext c) {
        // A front element pins the enemy; the remaining units all use the same flank.
        if (c.slot() < Math.max(1, c.members() / 3)) return new Decision(c.preferredRange(), 0, 1.0, false, true);
        if (c.distance() <= 5) return new Decision(c.preferredRange(), c.side() * c.attackLateral(2), 1.3, false, true);
        return new Decision(-2, Math.min(10, 4 + c.slot()), 1.2, false, true);
    }
}
