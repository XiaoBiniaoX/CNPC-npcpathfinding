package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class HitAndRun {
    public static Decision decide(TacticalContext c) { return c.attackPhase() ? new Decision(c.preferredRange(), 0, 1.35, false, true) : new Decision(c.preferredRange() + 7, c.side() * 3, 1.4, false, !c.melee()); }
}
