package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Berserker {
    public static Decision decide(TacticalContext c) { return new Decision(c.role() == WeaponRole.RANGED ? 2 : 0, 0, c.health() < 0.4 ? 1.5 : 1.3, false, true); }
}
