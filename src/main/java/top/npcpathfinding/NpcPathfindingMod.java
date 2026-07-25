package top.npcpathfinding;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import top.npcpathfinding.config.NpcPathfindingConfig;

@Mod(NpcPathfindingMod.MOD_ID)
public class NpcPathfindingMod {
    public static final String MOD_ID = "npcpathfinding";

    public NpcPathfindingMod() {
        var context = FMLJavaModLoadingContext.get();
        context.registerConfig(ModConfig.Type.COMMON, NpcPathfindingConfig.SPEC, MOD_ID + "-common.toml");
    }
}
