package com.github.rd806.mdm.dimension.data;

import com.github.rd806.mdm.config.Config;
import com.github.rd806.mdm.dimension.MdmDimensions;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 一个待导入的外部存档，来自约定目录 {@code <gamedir>/mdm_imports/<name>/}。
 * <p>
 * 只读 level.dat 与 region 文件，不打开存档、不加锁。刻意不用
 * {@code LevelStorageSource.LevelStorageAccess}：它的构造器会 {@code DirectoryLock.create(...)}
 * 锁住源目录，而且子类构造器是包私有的。
 */
public final class DimensionImportSource {

    private static final String LEVEL_DAT = "level.dat";
    private static final String TAG_DATA = "Data";
    private static final String TAG_WORLD_GEN = "WorldGenSettings";
    private static final String TAG_DIMENSIONS = "dimensions";
    private static final String TAG_TYPE = "type";
    private static final String TAG_GENERATOR = "generator";

    private final String name;
    private final Path root;
    /** level.dat 里的 Data 复合标签。 */
    private final CompoundTag levelData;

    private DimensionImportSource(String name, Path root, CompoundTag levelData) {
        this.name = name;
        this.root = root;
        this.levelData = levelData;
    }

    // ---------------------------------------------------------------- 定位

    /** 约定目录本身，缺失时创建。目录名来自配置，所以只能在这里按需读——不能提前到构造函数。 */
    public static Path directory() {
        return FMLPaths.getOrCreateGameRelativePath(Path.of(Config.importFolder()));
    }

    /** 约定目录下所有看起来像存档的子目录名。 */
    public static List<String> list() {
        Path dir = directory();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve(LEVEL_DAT)))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 打开并粗校验一个源。
     * @throws ImportException 目录不存在、缺 level.dat、或 level.dat 读不出/结构不对
     */
    public static DimensionImportSource locate(String name) {
        Path dir = directory().normalize();
        Path root = dir.resolve(name).normalize();
        // 命令用的 StringArgumentType.word() 允许 "." 和 "-"，所以 ".." 是可匹配的。
        // 要求解析结果仍是约定目录的直接子目录，挡住路径穿越。
        if (!dir.equals(root.getParent())) {
            throw new ImportException(Component.translatable("message.mdm.import.illegal_name", name, Config.importFolder()));
        }
        if (!Files.isDirectory(root)) {
            throw new ImportException(Component.translatable("message.mdm.import.not_found", name, Config.importFolder()));
        }
        Path levelDat = root.resolve(LEVEL_DAT);
        if (!Files.isRegularFile(levelDat)) {
            throw new ImportException(Component.translatable("message.mdm.import.no_level_dat", root, LEVEL_DAT));
        }

        CompoundTag tag;
        try {
            tag = NbtIo.readCompressed(levelDat.toFile());
        } catch (IOException e) {
            throw new ImportException(Component.translatable(
                    "message.mdm.import.unreadable", levelDat, e.getMessage()), e);
        }

        CompoundTag data = tag.getCompound(TAG_DATA);
        if (data.isEmpty()) {
            throw new ImportException(Component.translatable("message.mdm.import.no_data_tag", levelDat));
        }
        return new DimensionImportSource(name, root, data);
    }

    public String name() {
        return name;
    }

    public Path root() {
        return root;
    }

    // ---------------------------------------------------------------- 源维度信息

    /**
     * 源存档声明的某个维度的 level-stem（含 type / generator）。
     * @return 源存档没有声明该维度时返回 null
     */
    @Nullable
    public CompoundTag declaredStem(ResourceLocation sourceDim) {
        CompoundTag dims = levelData.getCompound(TAG_WORLD_GEN).getCompound(TAG_DIMENSIONS);
        String key = sourceDim.toString();
        return dims.contains(key, Tag.TAG_COMPOUND) ? dims.getCompound(key) : null;
    }

    /** 源存档里某个维度的区块存储目录。复刻 DimensionType.getStorageFolder 的四类布局。 */
    public Path storageFolder(ResourceLocation sourceDim) {
        ResourceKey<Level> key = MdmDimensions.levelKey(sourceDim);
        return DimensionType.getStorageFolder(key, root);
    }

    /** 源存档在 level.dat 里声明的所有维度 id，用于报错时给出可选值。 */
    public List<String> declaredDimensions() {
        CompoundTag dims = levelData.getCompound(TAG_WORLD_GEN).getCompound(TAG_DIMENSIONS);
        return dims.getAllKeys().stream().sorted().toList();
    }

    // ---------------------------------------------------------------- 解析源声明的类型与生成器

    /**
     * 源声明的维度类型在<b>目标</b>注册表里的 Holder。
     * <p>
     * level.dat 里 {@code type} 有字符串（注册表引用）与内联 compound 两种形态。只有字符串形态、
     * 且该 id 在目标注册表里存在时才能直接复用——这时高度天然与源区块一致。
     * 内联形态无法在运行时注册成新的 DimensionType（数据包注册表已 freeze），返回 null 交给兜底。
     */
    @Nullable
    public static Holder<DimensionType> resolveType(MinecraftServer server, CompoundTag stem) {
        ResourceLocation id = declaredTypeId(stem);
        if (id == null) {
            return null;
        }
        Registry<DimensionType> types = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        return types.getHolder(ResourceKey.create(Registries.DIMENSION_TYPE, id)).orElse(null);
    }

    /** {@code type} 是字符串形态时的 id；内联形态或缺失时返回 null。 */
    @Nullable
    private static ResourceLocation declaredTypeId(CompoundTag stem) {
        Tag typeTag = stem.get(TAG_TYPE);
        if (typeTag == null || typeTag.getId() != Tag.TAG_STRING) {
            return null;
        }
        return ResourceLocation.tryParse(typeTag.getAsString());
    }

    /**
     * 源声明的垂直范围 {@code [minY, height]}，两种 type 形态都能算出来。
     * <p>
     * 这是导入前的安全阀依据：区块段按绝对 Y 寻址，高度不匹配时
     * {@code ChunkSerializer.read} 会<b>静默丢弃</b>越界的段，所以必须自己比对。
     *
     * @return 无法确定时返回 null
     */
    @Nullable
    public static int[] declaredExtent(MinecraftServer server, CompoundTag stem) {
        ResourceLocation id = declaredTypeId(stem);
        if (id != null) {
            Registry<DimensionType> types = server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
            DimensionType resolved = types.get(id);
            return resolved == null ? null : new int[]{resolved.minY(), resolved.height()};
        }
        // 内联 compound：直接用同一套码表解，避免手读 min_y/height 读错
        Tag typeTag = stem.get(TAG_TYPE);
        if (typeTag == null) {
            return null;
        }
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        return DimensionType.DIRECT_CODEC.parse(ops, typeTag)
                .result()
                .map(type -> new int[]{type.minY(), type.height()})
                .orElse(null);
    }

    /**
     * 解析源声明的生成器。导入区域内地形由 region 文件决定，生成器只负责导入范围之外的新区块，
     * 所以解析失败是可接受的降级。
     * @return 解析失败时返回 null
     */
    @Nullable
    public static ChunkGenerator resolveGenerator(MinecraftServer server, CompoundTag stem) {
        Tag generatorTag = stem.get(TAG_GENERATOR);
        if (generatorTag == null) {
            return null;
        }
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        return ChunkGenerator.CODEC.parse(ops, generatorTag).result().orElse(null);
    }

    /**
     * 导入过程中可预期的失败。
     * <p>
     * 携带的是 {@link Component} 而不是拼好的字符串：命令执行者看到的必须是<b>按他自己语言</b>
     * 渲染的文字。{@code getMessage()} 退化成给日志看的兜底文本——Forge 会在服务端也加载模组的
     * en_us（{@code LanguageHook}），所以日志里同样是完整的一句话，不是裸键名。
     * <p>
     * 展示时用 {@link #component()}，不要再 {@code Component.literal(e.getMessage())}。
     */
    public static class ImportException extends RuntimeException {
        private final Component component;

        public ImportException(Component component) {
            this(component, null);
        }

        public ImportException(Component component, @Nullable Throwable cause) {
            super(component.getString(), cause);
            this.component = component;
        }

        /** 面向玩家的那一份，客户端会按自己的语言渲染。 */
        public Component component() {
            return component;
        }
    }
}
