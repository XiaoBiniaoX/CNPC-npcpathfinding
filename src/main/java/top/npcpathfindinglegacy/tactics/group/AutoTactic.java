package top.npcpathfindinglegacy.tactics.group;
import top.npcpathfindinglegacy.tactics.*;
public final class AutoTactic {
    public static TacticType choose(TacticalContext c) {
        if (c.health() < 0.25 || c.leaderHealth() < 0.25) return TacticType.RETREAT;
        if (c.recentHurt() && c.health() < 0.55) return TacticType.COVER;
        if (!c.visible() && c.distance() < 12) return TacticType.GROUP_AMBUSH;
        if (c.narrow() || c.rough()) return TacticType.FORMATION;
        if (c.members() >= 4 && c.distance() > 9) return TacticType.PINCER;
        if (c.members() >= 2 && c.enemyFocused()) return TacticType.FLANKING;
        if (c.role().ranged() && c.distance() < c.preferredRange() + 3) return TacticType.SKIRMISH;
        return TacticType.ADVANCE;
    }
}
