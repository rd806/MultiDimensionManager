package com.github.rd806.mdm.config;

import com.github.rd806.mdm.MdmMain;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(modid = MdmMain.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 导入目录的默认值。 */
    public static final String DEFAULT_IMPORT_DIRECTORY = "mdm_imports";

    /// 存放导入维度的目录
    public static ForgeConfigSpec.ConfigValue<String> IMPORT_FOLDER;

    /// 是否显示其他维度
    /// 关闭后 GUI 和命令行仅显示 主世界和由 mdm 创建的维度
    public static ForgeConfigSpec.BooleanValue SHOW_OTHER_DIMENSIONS;

    public static ForgeConfigSpec init() {
        IMPORT_FOLDER = BUILDER
                .comment("The import folder.")
                .define("importFolder", DEFAULT_IMPORT_DIRECTORY);
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

    /**
     * {@link #IMPORT_FOLDER} 的读取入口，理由同 {@link #showOtherDimensions()}。
     * <p>
     * <b>不要在模组构造函数里直接读 {@code IMPORT_FOLDER.get()}</b>：那一刻配置还没加载完
     * （{@code registerConfig} 只是登记，真正的加载在之后的 CONFIG_LOAD 阶段），开发环境下
     * {@code get()} 会抛 {@code IllegalStateException}，生产环境则静默返回默认值——用户改过的
     * 目录会被无视。要用就走这里，读不到就退回默认值。
     */
    public static String importFolder() {
        if (IMPORT_FOLDER == null) {
            return DEFAULT_IMPORT_DIRECTORY;
        }
        try {
            return IMPORT_FOLDER.get();
        } catch (IllegalStateException e) {
            return DEFAULT_IMPORT_DIRECTORY;
        }
    }
}
