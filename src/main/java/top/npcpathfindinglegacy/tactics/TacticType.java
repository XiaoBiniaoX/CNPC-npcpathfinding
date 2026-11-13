package top.npcpathfindinglegacy.tactics;

public enum TacticType {
    ASSAULT(0, false, "assault"),
    FLANKING(1, true, "flanking"), PINCER(2, true, "pincer"), COVER(3, true, "cover"),
    GROUP_AMBUSH(4, true, "group_ambush"), RETREAT(5, true, "retreat"), ADVANCE(6, true, "advance"),
    SKIRMISH(7, true, "skirmish"), FORMATION(8, true, "formation"), AUTO(9, true, "auto"),
    AGGRESSIVE(10, false, "aggressive"), EVASIVE(11, false, "evasive"), KITING(12, false, "kiting"),
    HIT_AND_RUN(13, false, "hit_and_run"), BERSERKER(14, false, "berserker"),
    OPPORTUNISTIC(15, false, "opportunistic"), COUNTERATTACK(16, false, "counterattack"),
    AMBUSH(17, false, "ambush"), STRAFING(18, false, "strafing"), CONDITIONAL_RETREAT(19, false, "conditional_retreat");
    private final int id;
    private final boolean group;
    private final String key;
    TacticType(int id, boolean group, String key) { this.id = id; this.group = group; this.key = key; }
    public int id() { return id; }
    public boolean group() { return group; }
    public String key() { return "npcpathfindinglegacy.tactics." + key; }
    public static TacticType byId(int id) { return id >= 0 && id < values().length ? values()[id] : ASSAULT; }
}
