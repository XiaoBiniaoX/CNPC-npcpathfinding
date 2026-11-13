package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class Retreat {
    public static Decision decide(TacticalContext c) {
        return new Decision(Math.max(10, c.preferredRange() + 5), c.side() * (c.slot() / 2 + 1), 1.3, false, c.visible());
    }
}
