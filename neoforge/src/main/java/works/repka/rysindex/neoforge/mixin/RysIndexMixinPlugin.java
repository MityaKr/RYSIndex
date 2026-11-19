package works.repka.rysindex.neoforge.mixin;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Перехваты классов AE2 и JEI ставятся, только если мод загружен. Без этого набор {@code rysindex.mixins.json} (он
 * необязательный) всё равно не падал, но писал в лог предупреждения Mixin о ненайденных целях. Решает пакет цели:
 * {@code appeng.} нужен мод {@code ae2}, {@code mezz.jei.} нужен {@code jei}; всё остальное (ванильные цели) ставится всегда.
 */
public final class RysIndexMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (targetClassName.startsWith("appeng.")) return loaded("ae2");
        if (targetClassName.startsWith("mezz.jei.")) return loaded("jei");
        return true;
    }

    private static boolean loaded(String modId) {
        LoadingModList list = LoadingModList.get();
        // список ещё не готов: не мешаем, Mixin сам пропустит ненайденную цель
        return list == null || list.getModFileById(modId) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
