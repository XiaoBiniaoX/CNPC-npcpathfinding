package top.npcpathfindinglegacy.tactics;

public record Decision(double forward, double lateral, double speed, boolean cover, boolean allowAttack) {
    public static Decision advance(double speed) { return new Decision(0, 0, speed, false, true); }
    public static Decision withdraw(double distance) { return new Decision(distance, 0, 1.3, false, true); }
    public static Decision cover(boolean attack) { return new Decision(5, 0, 1.0, true, attack); }
}
