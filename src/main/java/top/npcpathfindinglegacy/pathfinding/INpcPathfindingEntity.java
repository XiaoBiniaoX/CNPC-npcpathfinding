package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.pathfinding.PathNavigate;

public interface INpcPathfindingEntity {
    PathNavigate npcpathfindinglegacy$getVanillaNavigation();

    PathNavigate npcpathfindinglegacy$getEnhancedNavigation();

    void npcpathfindinglegacy$refreshNavigationMode();

    void npcpathfindinglegacy$clearEnhancedState();

    void npcpathfindinglegacy$requestAiRefresh();
}
