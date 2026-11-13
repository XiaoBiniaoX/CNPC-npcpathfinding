package top.npcpathfindinglegacy.tactics;

public final class Decision {
private final double forward;
private final double lateral;
private final double speed;
private final boolean cover;
private final boolean allowAttack;
public Decision(double forward, double lateral, double speed, boolean cover, boolean allowAttack) {
this.forward=forward;this.lateral=lateral;this.speed=speed;this.cover=cover;this.allowAttack=allowAttack;}
public double forward() { return forward; }
public double lateral() { return lateral; }
public double speed() { return speed; }
public boolean cover() { return cover; }
public boolean allowAttack() { return allowAttack; }
@Override public int hashCode() { return java.util.Objects.hash(forward,lateral,speed,cover,allowAttack); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Decision)) return false; Decision other=(Decision)value; return java.util.Objects.equals(forward,other.forward) && java.util.Objects.equals(lateral,other.lateral) && java.util.Objects.equals(speed,other.speed) && java.util.Objects.equals(cover,other.cover) && java.util.Objects.equals(allowAttack,other.allowAttack); }

    public static Decision incrementPathIndex(double speed) { return new Decision(0, 0, speed, false, true); }
    public static Decision withdraw(double distance) { return new Decision(distance, 0, 1.3, false, true); }
    public static Decision cover(boolean attack) { return new Decision(5, 0, 1.0, true, attack); }
}
