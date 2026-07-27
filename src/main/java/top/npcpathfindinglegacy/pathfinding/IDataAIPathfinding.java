package top.npcpathfindinglegacy.pathfinding;

public interface IDataAIPathfinding {
    int npcpathfindinglegacy$getMode();

    void npcpathfindinglegacy$setMode(int mode);

    int npcpathfindinglegacy$getWalkTendency();

    void npcpathfindinglegacy$setWalkTendency(int value);

    int npcpathfindinglegacy$getJumpTendency();

    void npcpathfindinglegacy$setJumpTendency(int value);

    int npcpathfindinglegacy$getParkourMaxDist();

    void npcpathfindinglegacy$setParkourMaxDist(int value);

    int npcpathfindinglegacy$getChaseJumpInterval();

    void npcpathfindinglegacy$setChaseJumpInterval(int value);
}
