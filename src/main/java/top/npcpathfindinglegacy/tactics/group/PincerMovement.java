package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class PincerMovement {
    public static Decision decide(TacticalContext c) {
        // Both wings spread around opposite sides, then converge into weapon range.
        if (c.distance() <= 5) return new Decision(c.preferredRange(), c.side() * c.attackLateral(2), 1.25, false, true);
        return new Decision(-3, c.side() * Math.min(10, 5 + c.slot() / 2), 1.25, false, true);
    }
}
