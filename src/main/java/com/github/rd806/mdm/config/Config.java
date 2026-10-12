package com.github.rd806.mdm.config;

import com.github.rd806.mdm.MdmMain;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = MdmMain.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /// 存放导入维度的目录
    public static ForgeConfigSpec.ConfigValue<String> IMPORT_FOLDER;

    /// 是否显示其他维度
    /// 关闭后 GUI 和命令行仅显示 主世界和由 mdm 创建的维度
    public static ForgeConfigSpec.BooleanValue SHOW_OTHER_DIMENSIONS;

    public static ForgeConfigSpec init() {
        IMPORT_FOLDER = BUILDER
                .comment("The import folder.")
                .define("importFolder", "mdm_imports");
        SHOW_OTHER_DIMENSIONS = BUILDER
                .comment("Whether to show other dimensions.")
                .define("showOtherDimensions", true);
        return BUILDER.build();
    }

    /**
     * {@link #SHOW_OTHER_DIMENSIONS} 的读取入口。
     * <p>
     * 配置值在配置加载完成前读会抛 {@code IllegalStateException}，字段本身在 {@link #init()} 之前
     * 也还是 null。调用点（命令、界面）实际都跑在加载之后，但这是条共用路径：读不到就退回默认的
     * true，"多显示几个维度"总好过把整条命令炸掉。
     */
    public static boolean showOtherDimensions() {
        if (SHOW_OTHER_DIMENSIONS == null) {
            return true;
        }
        try {
            return SHOW_OTHER_DIMENSIONS.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
