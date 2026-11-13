package top.npcpathfindinglegacy.pathfinding;

import java.util.ArrayList;
import java.util.List;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Step;

/** A* followed by any-angle shortcuts; jumps, drops and ladders are never merged. */
public final class ThetaPathSmoother {
    private ThetaPathSmoother() {}

    public static PlannedRoute smooth(TerrainSnapshot terrain, PlannedRoute route) {
        if (route == null || route.steps().size() < 3) return route;
        List<Step> original = route.steps();
        ArrayList<Step> result = new ArrayList<>();
        int current = 0;
        result.add(original.get(0));
        while (current < original.size() - 1) {
            int farthest = current + 1;
            for (int next = current + 2; next < original.size(); next++) {
                if (original.get(next - 1).action() != Action.WALK || original.get(next).action() != Action.WALK) break;
                if (terrain.groundLineClear(original.get(current).position(), original.get(next).position())) farthest = next;
            }
            result.add(original.get(farthest));
            current = farthest;
        }
        return new PlannedRoute(result, route.target(), route.reached(), route.cost());
    }
}
