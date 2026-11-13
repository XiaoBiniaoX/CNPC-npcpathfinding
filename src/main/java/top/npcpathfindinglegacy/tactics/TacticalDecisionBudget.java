package top.npcpathfindinglegacy.tactics;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Server-thread FIFO; stores identities only, so overdue front-row units cannot starve others. */
public final class TacticalDecisionBudget {
    private final LinkedHashMap<UUID, Long> waiting = new LinkedHashMap<>();
    private java.lang.ref.WeakReference<Object> owner = new java.lang.ref.WeakReference<>(null);
    private long tick = Long.MIN_VALUE;
    private int used;
    public boolean acquire(Object server, long now, UUID npc) {
        if (owner.get() != server || now < tick) { waiting.clear(); owner = new java.lang.ref.WeakReference<>(server); tick = Long.MIN_VALUE; }
        if (tick != now) {
            tick = now; used = 0;
            waiting.entrySet().removeIf(e -> now - e.getValue() > 2);
        }
        waiting.put(npc, now);
        if (used >= 16 || !waiting.keySet().iterator().next().equals(npc)) return false;
        waiting.remove(npc); used++; return true;
    }
}
