package top.npcpathfindinglegacy.tactics;

/** Dispatch only. Each behavior owns its implementation in a separate file. */
public final class TacticDecisions {
    private TacticDecisions() {}
    public static Decision decide(TacticType type, TacticalContext context) {
        switch (type) {
            case FLANKING: return top.npcpathfindinglegacy.tactics.group.Flanking.decide(context);
            case PINCER: return top.npcpathfindinglegacy.tactics.group.PincerMovement.decide(context);
            case COVER: return top.npcpathfindinglegacy.tactics.group.TakeCover.decide(context);
            case GROUP_AMBUSH: return top.npcpathfindinglegacy.tactics.group.Ambush.decide(context);
            case RETREAT: return top.npcpathfindinglegacy.tactics.group.Retreat.decide(context);
            case ADVANCE: return top.npcpathfindinglegacy.tactics.group.Advance.decide(context);
            case SKIRMISH: return top.npcpathfindinglegacy.tactics.group.Skirmish.decide(context);
            case FORMATION: return top.npcpathfindinglegacy.tactics.group.Formation.decide(context);
            case AUTO: return decide(top.npcpathfindinglegacy.tactics.group.AutoTactic.choose(context), context);
            case AGGRESSIVE: return top.npcpathfindinglegacy.tactics.individual.Aggressive.decide(context);
            case EVASIVE: return top.npcpathfindinglegacy.tactics.individual.Evasive.decide(context);
            case KITING: return top.npcpathfindinglegacy.tactics.individual.Kiting.decide(context);
            case HIT_AND_RUN: return top.npcpathfindinglegacy.tactics.individual.HitAndRun.decide(context);
            case BERSERKER: return top.npcpathfindinglegacy.tactics.individual.Berserker.decide(context);
            case OPPORTUNISTIC: return top.npcpathfindinglegacy.tactics.individual.Opportunistic.decide(context);
            case COUNTERATTACK: return top.npcpathfindinglegacy.tactics.individual.Counterattack.decide(context);
            case AMBUSH: return top.npcpathfindinglegacy.tactics.individual.Ambush.decide(context);
            case STRAFING: return top.npcpathfindinglegacy.tactics.individual.Strafing.decide(context);
            case CONDITIONAL_RETREAT: return top.npcpathfindinglegacy.tactics.individual.OpportunisticRetreat.decide(context);
            case ASSAULT: return Decision.incrementPathIndex(1.3);
            default: throw new IllegalArgumentException("Unknown tactic");
}
    }
}
