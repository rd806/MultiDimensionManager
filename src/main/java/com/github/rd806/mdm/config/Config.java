package com.github.rd806.mdm.config;

import com.github.rd806.mdm.MdmMain;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Forge's config APIs
@Mod.EventBusSubscriber(modid = MdmMain.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /// 存放导入维度的目录
    public static ForgeConfigSpec.ConfigValue<String> IMPORT_FOLDER;

    public static ForgeConfigSpec init() {
        IMPORT_FOLDER = BUILDER
                .comment("The import folder.")
                .define("importFolder", "mdm_imports");
        return BUILDER.build();
    }
}
