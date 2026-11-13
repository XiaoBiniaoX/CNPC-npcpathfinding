package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Aggressive {
    public static Decision decide(TacticalContext c) { return new Decision(c.role() == WeaponRole.RANGED ? Math.min(3,c.preferredRange() * 0.5) : 0, 0, 1.35, false, true); }
}
