package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Strafing {
    public static Decision decide(TacticalContext c) { return new Decision(c.preferredRange(), c.side() * c.attackLateral(4), 1.15, false, true); }
}
