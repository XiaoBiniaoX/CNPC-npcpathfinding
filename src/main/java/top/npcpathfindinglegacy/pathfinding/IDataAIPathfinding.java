package top.npcpathfindinglegacy.pathfinding;

public interface IDataAIPathfinding {
    double npcpathfindinglegacy$getComfortDistance();

    void npcpathfindinglegacy$setComfortDistance(double distance);

    int npcpathfindinglegacy$getMode();

    void npcpathfindinglegacy$setMode(int mode);

    boolean npcpathfindinglegacy$getAllowSprint();

    void npcpathfindinglegacy$setAllowSprint(boolean allowed);

    int npcpathfindinglegacy$getWalkTendency();

    void npcpathfindinglegacy$setWalkTendency(int value);

    int npcpathfindinglegacy$getJumpTendency();

    void npcpathfindinglegacy$setJumpTendency(int value);

    int npcpathfindinglegacy$getParkourMaxDist();

    void npcpathfindinglegacy$setParkourMaxDist(int value);

    int npcpathfindinglegacy$getChaseJumpInterval();

    void npcpathfindinglegacy$setChaseJumpInterval(int value);
}
