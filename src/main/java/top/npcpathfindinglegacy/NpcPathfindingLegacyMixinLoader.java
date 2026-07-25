package top.npcpathfindinglegacy;

import java.util.Collections;
import java.util.List;
import zone.rong.mixinbooter.ILateMixinLoader;

public final class NpcPathfindingLegacyMixinLoader implements ILateMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.npcpathfindinglegacy.json");
    }
}
