package top.npcpathfindinglegacy.tactics.individual;
import top.npcpathfindinglegacy.tactics.*;
public final class Opportunistic {
    public static Decision decide(TacticalContext c) { return c.enemyHealth() < 0.4 || !c.enemyFocused() ? new Decision(c.preferredRange(), c.side() * c.attackLateral(3), 1.3, false, true) : Decision.cover(false); }
}
