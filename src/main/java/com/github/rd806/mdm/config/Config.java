package com.github.rd806.mdm.config;

import com.github.rd806.mdm.MdmMain;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;

import java.util.List;


@Mod.EventBusSubscriber(modid = MdmMain.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 导入目录的默认值。 */
    public static final String DEFAULT_IMPORT_DIRECTORY = "mdm_imports";

    /** GUI 白名单的默认值：开箱即用的主世界。 */
    public static final List<String> DEFAULT_GUI_WHITELIST = List.of("minecraft:overworld");

    /// 存放导入维度的目录
    public static ForgeConfigSpec.ConfigValue<String> IMPORT_FOLDER;

    /// 是否显示其他维度
    /// 关闭后 GUI 和命令行仅显示 主世界和由 mdm 创建的维度
    public static ForgeConfigSpec.BooleanValue SHOW_OTHER_DIMENSIONS;

    // 可使用 GUI 命令的维度白名单
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS_WHITELISTS;

    public static ForgeConfigSpec init() {
        IMPORT_FOLDER = BUILDER
                .comment("The import folder.")
                .define("importFolder", DEFAULT_IMPORT_DIRECTORY);
        SHOW_OTHER_DIMENSIONS = BUILDER
                .comment("Whether to show other dimensions.")
                .define("showOtherDimensions", true);
        // 用 defineListAllowEmpty 而不是 defineList：后者会把空列表判定成"需要纠正"并改回默认值，
        // 那样"留空 = 谁都不能开"这条语义就表达不出来了。校验器故意宽松（只要求非空字符串），
        // 写错的维度 id 留到使用时警告一次——严格校验会把它静默删掉，用户看不到任何提示。
        DIMENSIONS_WHITELISTS = BUILDER
                .comment("Dimensions in which the dimension selector may be opened. Empty means nobody can.")
                .defineListAllowEmpty("dimensionsWhitelists", DEFAULT_GUI_WHITELIST,
                        o -> o instanceof String s && !s.isBlank());
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

    /**
     * {@link #DIMENSIONS_WHITELISTS} 的读取入口，理由同 {@link #showOtherDimensions()}。
     * <p>
     * 空列表是有意义的值（谁都不能开），所以这里不能把"读不到"和"读到了空列表"混为一谈——
     * 只有真的读不到（字段为 null / 配置尚未加载）才退回默认值。
     */
    public static List<? extends String> dimensionsWhitelists() {
        if (DIMENSIONS_WHITELISTS == null) {
            return DEFAULT_GUI_WHITELIST;
        }
        try {
            return DIMENSIONS_WHITELISTS.get();
        } catch (IllegalStateException e) {
            return DEFAULT_GUI_WHITELIST;
        }
    }
}
