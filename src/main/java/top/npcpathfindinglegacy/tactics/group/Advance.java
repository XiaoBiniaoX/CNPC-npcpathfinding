package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class Advance {
    public static Decision decide(TacticalContext c) {
        return new Decision(c.preferredRange(), c.side() * c.attackLateral(Math.min(4, c.slot())), 1.05, false, true);
    }
}
