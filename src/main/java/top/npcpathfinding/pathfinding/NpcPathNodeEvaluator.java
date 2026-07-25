package top.npcpathfinding.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import noppes.npcs.ai.NpcWalkNodeEvaluator;
import net.minecraftforge.registries.ForgeRegistries;
import top.npcpathfinding.config.NpcPathfindingConfig;

public class NpcPathNodeEvaluator extends NpcWalkNodeEvaluator {
    private final PathNavigation navigation;

    public NpcPathNodeEvaluator(PathNavigation navigation) {
        this.navigation = navigation;
    }

    @Override
    public void done() {
        super.done();
        // CNPC sometimes calls setCanFloat immediately after construction.
        if (this.navigation.nodeEvaluator == null) {
            this.navigation.nodeEvaluator = this;
        }
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob) {
        BlockPathTypes override = getConfiguredType(level, x, y, z);
        return override != null ? override : super.getBlockPathType(level, x, y, z, mob);
    }

    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        BlockPathTypes override = getConfiguredType(level, x, y, z);
        return override != null ? override : super.getBlockPathType(level, x, y, z);
    }

    private BlockPathTypes getConfiguredType(BlockGetter level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());

        if (blockId != null && NpcPathfindingConfig.isBlockBlacklisted(blockId)) {
            return BlockPathTypes.BLOCKED;
        }

        if (blockId != null && NpcPathfindingConfig.isBlockWhitelisted(blockId)) {
            return BlockPathTypes.WALKABLE;
        }

        if (state.getBlock() instanceof LadderBlock) {
            return BlockPathTypes.WALKABLE;
        }

        return null;
    }
}
