package top.npcpathfindinglegacy.mixin;

import java.util.HashMap;
import noppes.npcs.controllers.ScriptContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.npcpathfindinglegacy.script.NpcPathfindingAPI;

/** CNPC copies Data into each executor's globals before evaluating the script. */
@Mixin(value = ScriptContainer.class, remap = false)
public abstract class MixinScriptContainer {
    @Shadow @Final public static HashMap<String,Object> Data;
    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void npcpathfindinglegacy$registerScriptApi(CallbackInfo ci) {
        Data.put("NpcPathfinding",new NpcPathfindingAPI());
    }
}
