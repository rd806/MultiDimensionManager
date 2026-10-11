package com.github.rd806.mdm.dimension;

import com.github.rd806.mdm.MdmMain;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

/**
 * 维度相关的 ResourceKey 常量与工厂。
 * <p>
 * 注意：维度类型不能用 DeferredRegister 注册。Registries.DIMENSION_TYPE 是数据包注册表
 * （datapack registry），由 RegistryDataLoader 从 JSON 加载后立即 freeze，
 * 而 Forge 的 RegisterEvent 只对 forge/vanilla 注册表触发。
 * 所以维度类型一律由 data/mdm/dimension_type/*.json 提供，这里只放指向它们的 key。
 */
public final class MdmDimensions {

    private MdmDimensions() {
    }

    /** 虚空口袋维度模板，见 data/mdm/dimension_type/void.json */
    public static final ResourceKey<DimensionType> VOID = dimensionType("void");

    /** 类主世界模板，见 data/mdm/dimension_type/overworld_like.json */
    public static final ResourceKey<DimensionType> OVERWORLD_LIKE = dimensionType("overworld_like");

    public static ResourceKey<DimensionType> dimensionType(String path) {
        return ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.parse(MdmMain.MODID + ":" + path));
    }

    /** 把一个维度 id 转成 MinecraftServer 使用的 Level key。 */
    public static ResourceKey<Level> levelKey(ResourceLocation id) {
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    /**
     * 该维度是否由数据包静态定义（即 data/mdm/dimension/*.json 或其它数据包提供）。
     * 静态维度每次加载存档都会由 vanilla 重建，因此不能在运行时删除。
     */
    public static boolean isStatic(MinecraftServer server, ResourceKey<Level> key) {
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, key.location());
        return server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(stemKey);
    }

    /**
     * 判断维度 id 的 path 是否安全。
     * <p>
     * {@code DimensionType.getStorageFolder} 会把 path 直接当目录名拼进去，而 ResourceLocation
     * 允许 path 为 "." 或 ".."。那样 {@code mdm:..} 会被折叠成 {@code dimensions/mdm} ——
     * 删除这个维度就会连带删掉同一命名空间下别的维度。这类 id 一律拒绝。
     */
    public static boolean isSafePath(ResourceLocation id) {
        String path = id.getPath();
        return !path.equals(".") && !path.equals("..")
                && !path.contains("/") && !path.contains("\\");
    }
}
