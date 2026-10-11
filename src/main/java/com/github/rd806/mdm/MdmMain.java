package com.github.rd806.mdm;

import com.github.rd806.mdm.config.Config;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.util.Set;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(MdmMain.MODID)
public class MdmMain {

    public static final String MODID = "mdm";

    /**
     * 本模组管理的维度（动态创建与导入的那些）的实时快照。权威来源是存档里的{@code DimensionSavedData}
     *
     * 静态 JSON 维度不在其中，因为它们不由模组管理。
     * <p>
     * 只由 {@code DynamicDimensionManager.refreshManagedLevels} 单向写入：创建/导入、删除、
     * 启动恢复这三条路径最后都会调用它。不要在别处直接赋值，否则会和存档记录不同步。
     */
    public static volatile Set<ResourceKey<Level>> LEVELS = Set.of();

    public static final Logger LOGGER = LogUtils.getLogger();

    public MdmMain(FMLJavaModLoadingContext context) {
        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        context.registerConfig(ModConfig.Type.COMMON, Config.init());
    }
}
