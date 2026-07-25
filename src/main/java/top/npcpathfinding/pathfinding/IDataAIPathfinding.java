package top.npcpathfinding.pathfinding;

public interface IDataAIPathfinding {
    int npcpathfinding$getMode();
    void npcpathfinding$setMode(int mode);
    int npcpathfinding$getWalkTendency();
    void npcpathfinding$setWalkTendency(int val);
    int npcpathfinding$getJumpTendency();
    void npcpathfinding$setJumpTendency(int val);
    /** Max parkour gap in blocks (0 = off, 1-5). */
    int npcpathfinding$getParkourMaxDist();
    void npcpathfinding$setParkourMaxDist(int val);
    /** Ticks between flat chase run-jumps; 0 = never. Default 40. */
    int npcpathfinding$getChaseJumpInterval();
    void npcpathfinding$setChaseJumpInterval(int val);
}
