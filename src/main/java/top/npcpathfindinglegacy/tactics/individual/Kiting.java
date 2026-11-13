package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Kiting {
    public static Decision decide(TacticalContext c) {
        if (c.melee()) return c.attackPhase() ? Decision.advance(1.2) : Decision.withdraw(5);
        return new Decision(Math.min(c.range() * 0.85, c.preferredRange() + 2), c.side() * 2, c.distance() < c.preferredRange() ? 1.35 : 1.0, false, true);
    }
}
