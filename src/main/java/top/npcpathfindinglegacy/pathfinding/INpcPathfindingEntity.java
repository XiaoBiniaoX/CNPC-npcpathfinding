package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.world.entity.ai.navigation.PathNavigation;

public interface INpcPathfindingEntity {
    PathNavigation npcpathfindinglegacy$getVanillaNavigation();

    PathNavigation npcpathfindinglegacy$getEnhancedNavigation();

    void npcpathfindinglegacy$refreshNavigationMode();

    void npcpathfindinglegacy$clearEnhancedState();

    void npcpathfindinglegacy$requestAiRefresh();
}
