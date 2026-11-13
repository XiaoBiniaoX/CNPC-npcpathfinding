package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class TakeCover {
    public static Decision decide(TacticalContext c) {
        if (!c.role().ranged() && c.health() > 0.65 && c.distance() < 3) return Decision.advance(1.1);
        return Decision.cover(false);
    }
}
