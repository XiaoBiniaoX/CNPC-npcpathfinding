package top.npcpathfinding.pathfinding;

import net.minecraft.world.entity.ai.navigation.PathNavigation;

public interface INpcPathfindingEntity {
    PathNavigation npcpathfinding$getVanillaNavigation();
    PathNavigation npcpathfinding$getBaritoneNavigation();
    void npcpathfinding$refreshNavigationMode();
    void npcpathfinding$clearBaritoneState();
}
