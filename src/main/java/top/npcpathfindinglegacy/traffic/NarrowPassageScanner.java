package top.npcpathfindinglegacy.traffic;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;
import top.npcpathfindinglegacy.pathfinding.GridPos;

/** Incremental connected-component scan; 0=blocked, 1=open, 2=narrow. */
public final class NarrowPassageScanner {
    private static final int[] DX = {1,-1,0,0}, DZ = {0,0,1,-1}, DY = {0,1,-1};
    private final ArrayDeque<GridPos> pending = new ArrayDeque<>();
    private final Set<GridPos> seen = new HashSet<>(), cells = new HashSet<>();

    public NarrowPassageScanner(GridPos seed) { pending.add(seed); seen.add(seed); }

    /** A single diagonal at a bend must not split a one-body-wide passage. */
    public static int classify(int exits, int diagonals) {
        return diagonals == 0 || exits <= 2 && diagonals <= 1 ? 2 : 1;
    }

    public boolean advance(ToIntFunction<GridPos> sample, int budget) {
        while (budget-- > 0 && !pending.isEmpty()) {
            GridPos pos = pending.removeFirst();
            if (sample.applyAsInt(pos) != 2) continue;
            cells.add(pos);
            for (int d = 0; d < DX.length; d++) {
                for (int dy : DY) {
                    GridPos next = pos.offset(DX[d], dy, DZ[d]);
                    if (seen.add(next)) pending.addLast(next);
                }
            }
        }
        return pending.isEmpty();
    }

    public Set<GridPos> cells() { return Set.copyOf(cells); }
}
