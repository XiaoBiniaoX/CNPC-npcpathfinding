package top.npcpathfindinglegacy.tactics;

/** Dispatch only. Each behavior owns its implementation in a separate file. */
public final class TacticDecisions {
    private TacticDecisions() {}
    public static Decision decide(TacticType type, TacticalContext context) {
        return switch (type) {
            case FLANKING -> top.npcpathfindinglegacy.tactics.group.Flanking.decide(context);
            case PINCER -> top.npcpathfindinglegacy.tactics.group.PincerMovement.decide(context);
            case COVER -> top.npcpathfindinglegacy.tactics.group.TakeCover.decide(context);
            case GROUP_AMBUSH -> top.npcpathfindinglegacy.tactics.group.Ambush.decide(context);
            case RETREAT -> top.npcpathfindinglegacy.tactics.group.Retreat.decide(context);
            case ADVANCE -> top.npcpathfindinglegacy.tactics.group.Advance.decide(context);
            case SKIRMISH -> top.npcpathfindinglegacy.tactics.group.Skirmish.decide(context);
            case FORMATION -> top.npcpathfindinglegacy.tactics.group.Formation.decide(context);
            case AUTO -> decide(top.npcpathfindinglegacy.tactics.group.AutoTactic.choose(context), context);
            case AGGRESSIVE -> top.npcpathfindinglegacy.tactics.individual.Aggressive.decide(context);
            case EVASIVE -> top.npcpathfindinglegacy.tactics.individual.Evasive.decide(context);
            case KITING -> top.npcpathfindinglegacy.tactics.individual.Kiting.decide(context);
            case HIT_AND_RUN -> top.npcpathfindinglegacy.tactics.individual.HitAndRun.decide(context);
            case BERSERKER -> top.npcpathfindinglegacy.tactics.individual.Berserker.decide(context);
            case OPPORTUNISTIC -> top.npcpathfindinglegacy.tactics.individual.Opportunistic.decide(context);
            case COUNTERATTACK -> top.npcpathfindinglegacy.tactics.individual.Counterattack.decide(context);
            case AMBUSH -> top.npcpathfindinglegacy.tactics.individual.Ambush.decide(context);
            case STRAFING -> top.npcpathfindinglegacy.tactics.individual.Strafing.decide(context);
            case CONDITIONAL_RETREAT -> top.npcpathfindinglegacy.tactics.individual.OpportunisticRetreat.decide(context);
            case ASSAULT -> Decision.advance(1.3);
        };
    }
}
