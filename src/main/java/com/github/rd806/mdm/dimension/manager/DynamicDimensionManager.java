package com.github.rd806.mdm.dimension.manager;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.dimension.*;
import com.github.rd806.mdm.dimension.data.DimensionImportSource;
import com.github.rd806.mdm.dimension.data.DimensionSavedData;
import com.github.rd806.mdm.dimension.data.DimensionTemplates;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.DataResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 在运行时创建、查询、删除维度的核心。
 * <p>
 * 创建流程逐行对照 vanilla 的 {@code MinecraftServer.createLevels(ChunkProgressListener)}
 * 里处理非主世界的那一段（MinecraftServer.java:356-380），保持派生存档数据、边界监听、
 * 事件广播的行为一致。注意 1.20.1 <b>没有</b>单数的 createLevel 方法，别照着旧教程找。
 */
public final class DynamicDimensionManager {

    /**
     * 创建时挂在主世界世界边界上的监听器，按维度 key 记录，删除时好摘掉。
     * 这是必要的：监听器持有该维度的 WorldBorder，不摘就会连整个 ServerLevel 一起泄漏。
     */
    private static final Map<ResourceKey<Level>, BorderChangeListener> BORDER_LISTENERS = new ConcurrentHashMap<>();

    private DynamicDimensionManager() {
    }

    // ---------------------------------------------------------------- 查询

    @Nullable
    public static ServerLevel get(MinecraftServer server, ResourceKey<Level> key) {
        return server.getLevel(key);
    }

    /** 当前已加载的所有维度。 */
    public static Set<ResourceKey<Level>> list(MinecraftServer server) {
        return server.levelKeys();
    }

    // ---------------------------------------------------------------- 创建

    /**
     * 把一个 LevelStem 实例化成 ServerLevel 并挂到服务器上。幂等：已加载则原样返回。
     */
    public static ServerLevel create(MinecraftServer server, ResourceKey<Level> key, LevelStem stem) {
        ServerLevel existing = server.getLevel(key);
        if (existing != null) {
            return existing;
        }

        ServerLevelData overworldData = server.getWorldData().overworldData();
        DerivedLevelData derived = new DerivedLevelData(server.getWorldData(), overworldData);
        long seed = server.getWorldData().worldGenOptions().seed();

        ServerLevel level = new ServerLevel(
                server,
                server.executor,       // 通过 accesstransformer 打开：f_129738_
                server.storageSource,  // 通过 accesstransformer 打开：f_129744_
                derived,
                key,
                stem,
                NoopChunkProgressListener.INSTANCE,
                server.getWorldData().isDebugWorld(),
                BiomeManager.obfuscateSeed(seed),
                ImmutableList.of(),    // 非主世界不带自定义刷怪器，与 vanilla 一致
                false,                 // tickTime
                null);                 // RandomSequences：构造器内部 requireNonNullElseGet，传 null 安全

        BorderChangeListener borderListener =
                new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
        server.overworld().getWorldBorder().addListener(borderListener);
        BORDER_LISTENERS.put(key, borderListener);

        server.forgeGetWorldMap().put(key, level);
        // 必须调用：服务器 tick 遍历的是缓存的 worldArray，不标记新维度不会被 tick。
        server.markWorldsDirty();

        MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(level));
        MdmMain.LOGGER.info("Dynamic dimension created: {}", key.location());
        return level;
    }

    /** 创建维度并把它的定义写进存档，使其在重启后仍然存在。 */
    public static ServerLevel createAndPersist(MinecraftServer server, ResourceKey<Level> key, LevelStem stem) {
        ServerLevel level = create(server, key, stem);
        record(server, key, stem);
        refreshManagedLevels(server);
        return level;
    }

    // ---------------------------------------------------------------- 导入

    /** 一次导入的结果。 */
    public record ImportResult(ResourceLocation dimensionId, String source, long chunkFiles) {
    }

    /**
     * 把一个外部存档的地形导入成一个新维度。
     * <p>
     * 顺序是刻意的：<b>先拷贝 region 文件，再创建 ServerLevel</b>。因为
     * {@code RegionFileStorage} 会缓存已打开的 RegionFile 句柄，而 {@code RegionFile}
     * 在构造时一次性读取 8 KiB 头——文件被打开过之后再覆盖，改动不会被看到。
     * 反过来，只要文件在首次请求该区块前就位，FULL 状态的区块会直接走 load 分支，
     * ChunkGenerator 完全不会被调用。
     */
    public static ImportResult importDimension(MinecraftServer server, ResourceKey<Level> key,
                                               DimensionImportSource source, ResourceLocation sourceDim,
                                               @Nullable String fallbackTemplate) {
        CompoundTag stem = source.declaredStem(sourceDim);
        if (stem == null) {
            throw new DimensionImportSource.ImportException(Component.translatable(
                    "message.mdm.import.undeclared_dimension",
                    source.name(), sourceDim, String.join(", ", source.declaredDimensions())));
        }

        Holder<DimensionType> type = DimensionImportSource.resolveType(server, stem);
        ChunkGenerator generator = DimensionImportSource.resolveGenerator(server, stem);

        // 源声明的类型/生成器解析不出来时，退到模板
        if ((type == null || generator == null) && fallbackTemplate != null) {
            LevelStem fallback = DimensionTemplates.build(server, fallbackTemplate);
            if (fallback == null) {
                throw new DimensionImportSource.ImportException(Component.translatable(
                        "message.mdm.import.unknown_template",
                        fallbackTemplate, String.join(", ", DimensionTemplates.names())));
            }
            if (type == null) {
                type = fallback.type();
            }
            if (generator == null) {
                generator = fallback.generator();
            }
        }
        if (type == null) {
            throw new DimensionImportSource.ImportException(
                    Component.translatable("message.mdm.import.unknown_type", sourceDim));
        }
        if (generator == null) {
            throw new DimensionImportSource.ImportException(
                    Component.translatable("message.mdm.import.unknown_generator", sourceDim));
        }

        DimensionType target = type.value();

        // 安全阀：高度不匹配时 ChunkSerializer.read 会静默丢弃越界的区块段，必须在这里拦下
        int[] extent = DimensionImportSource.declaredExtent(server, stem);
        if (extent == null) {
            throw new DimensionImportSource.ImportException(
                    Component.translatable("message.mdm.import.unknown_extent", sourceDim));
        }
        if (extent[0] != target.minY() || extent[1] != target.height()) {
            throw new DimensionImportSource.ImportException(Component.translatable(
                    "message.mdm.import.extent_mismatch",
                    extent[0], extent[1], target.minY(), target.height()));
        }

        // 先拷贝文件，再创建维度
        Path targetFolder = DimensionType.getStorageFolder(key, server.getWorldPath(LevelResource.ROOT));
        Path sourceFolder = source.storageFolder(sourceDim);

        long chunkFiles = copyTree(sourceFolder.resolve("region"), targetFolder.resolve("region"), true);
        if (chunkFiles == 0L) {
            throw new DimensionImportSource.ImportException(Component.translatable(
                    "message.mdm.import.no_chunks", sourceDim, sourceFolder.resolve("region")));
        }
        // entities/ 与 poi/ 缺失是正常的；data/ 刻意不拷，那是维度级 SavedData，可能与目标世界冲突
        copyTree(sourceFolder.resolve("entities"), targetFolder.resolve("entities"), false);
        copyTree(sourceFolder.resolve("poi"), targetFolder.resolve("poi"), false);

        createAndPersist(server, key, new LevelStem(type, generator));

        // 记下这次导入的配方：之后 /mdm modify backup 查得到，/mdm dimension reset 能照它重来。
        // 写在 meta 段（不在这条维度记录里），所以后续 re-record 不会把它抹掉
        DimensionSavedData.get(server).setBackup(key.location().toString(),
                new DimensionSavedData.Backup(source.name(), sourceDim, fallbackTemplate));

        MdmMain.LOGGER.info("Imported {} chunk file(s) from '{}' into {}", chunkFiles, source.name(), key.location());
        return new ImportResult(key.location(), source.name(), chunkFiles);
    }

    /**
     * 递归拷贝目录。
     * @param required 源目录不存在时是否报错（region 必须，entities/poi 可缺）
     * @return 拷贝的文件数
     */
    private static long copyTree(Path from, Path to, boolean required) {
        if (!Files.isDirectory(from)) {
            if (required) {
                throw new DimensionImportSource.ImportException(
                        Component.translatable("message.mdm.import.missing_folder", from.getFileName()));
            }
            return 0L;
        }
        long copied = 0L;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path destination = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
        } catch (IOException e) {
            throw new DimensionImportSource.ImportException(
                    Component.translatable("message.mdm.import.copy_failed", from, to, e.getMessage()), e);
        }
        return copied;
    }

    // ---------------------------------------------------------------- 删除 / 重置

    /**
     * 卸载并移除一个运行时创建的维度，连同它的存档目录。
     * <p>
     * 对静态维度（数据包 JSON 定义的）无效——那些每次加载存档都会被 vanilla 重建，
     * 调用方应当先用 {@link MdmDimensions#isStatic} 拦下来。
     */
    public static boolean delete(MinecraftServer server, ResourceKey<Level> key) {
        if (server.getLevel(key) == null) {
            return false;
        }
        unloadAndWipe(server, key);

        // 从持久化记录里删除，避免重启复活。meta 段（别名/出生点/备份）也一并清掉
        DimensionSavedData.get(server).remove(key.location().toString());
        refreshManagedLevels(server);

        MdmMain.LOGGER.info("Dynamic dimension deleted: {}", key.location());
        return true;
    }

    /**
     * 把人送走、把 level 从服务器摘下来关闭、再抹掉它的存档目录。
     * <p>
     * 抹掉目录之后这个维度的地形就没了（region 文件不可恢复），调用方决定后续怎么办：删除到此为止，
     * 重置则接着从备份重建。level 没加载时跳过卸载，直接抹目录。
     * <p>
     * <b>步骤顺序是有讲究的，别调换</b>：
     * <ol>
     *   <li>先把人送走，否则卸载后玩家会挂在死掉的 level 上。</li>
     *   <li>再摘服务器映射。这一步是「卸载生效」的关键，必须排在任何可能阻塞的清理之前：
     *       IOWorker 的写入队列一旦被关闭，之后任何 synchronize().join() 都永远等不到结果
     *       （ProcessorMailbox 关闭后会静默丢弃入队任务），会把服务器主线程卡死在这里，
     *       连带 stopServer 也走不完。先摘映射就能保证即使关闭出问题，卸载也已经生效。</li>
     *   <li>只调一次 close()。Level.close() 内部就是 chunkSource.close()，再单独调一次
     *       getChunkSource().close() 会走到第二次 save(true) → flushWorker() → synchronize().join()，
     *       于是正好撞上上面那条死锁。这里不需要另外调 save()：数据马上要整目录删掉，
     *       close() 自己会 flush。事件与关闭的先后顺序对齐 vanilla 的 stopServer。</li>
     *   <li>最后抹目录，必须在 close 之后——Windows 上文件句柄没释放会删不掉。</li>
     * </ol>
     */
    private static void unloadAndWipe(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            deleteStorageFolder(server, key);
            return;
        }

        evictPlayers(server, level);

        server.forgeGetWorldMap().remove(key);
        server.markWorldsDirty();
        removeBorderListener(server, key);

        MinecraftForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        try {
            level.close();
        } catch (Exception e) {
            // 关不干净也继续：维度已经从 levels 里摘掉，卸载本身已经生效
            MdmMain.LOGGER.error("Failed to close dimension {} cleanly", key.location(), e);
        }

        deleteStorageFolder(server, key);
    }

    // ---------------------------------------------------------------- 备份 / 重置

    /**
     * 某个维度的导入配方——记着它当初是从哪份存档的哪个维度导进来的。
     *
     * @return 不是导入来的（模板创建）返回 null
     */
    @Nullable
    public static DimensionSavedData.Backup backupOf(MinecraftServer server, ResourceKey<Level> key) {
        return DimensionSavedData.get(server).backupOf(key.location().toString());
    }

    /**
     * 改写或清除某个维度的导入配方。
     * <p>
     * 改配方只动这份记录，<b>不会</b>去动已经导入的地形——要按新配方重来，得再敲一次重置。
     *
     * @param backup 传 null 表示清除，此后该维度被当作"模板创建"，不能再重置
     * @return false 表示该维度没加载，无处可设
     */
    public static boolean setBackup(MinecraftServer server, ResourceKey<Level> key, @Nullable DimensionSavedData.Backup backup) {
        if (server.getLevel(key) == null) {
            return false;
        }
        DimensionSavedData.get(server).setBackup(key.location().toString(), backup);
        return true;
    }

    /**
     * 按备份原地重置一个维度：卸载 → 抹掉地形 → 照原配方重导一次。
     * <p>
     * 维度 id、别名、传送出生点、备份记录本身都保留——它们在 meta 段里，重导只写 {@code dim} 段。
     * 连带把 LevelStem 刷成与备份一致（重导本来就会重新解析类型/生成器，这是对的）。
     *
     * @throws DimensionImportSource.ImportException 没有备份记录、备份目录不存在，或重导过程中任何
     *                                               可预期的失败——消息已 i18n，交给命令层投递
     */
    public static ImportResult resetFromBackup(MinecraftServer server, ResourceKey<Level> key) {
        DimensionSavedData.Backup backup = backupOf(server, key);
        if (backup == null) {
            throw new DimensionImportSource.ImportException(
                    Component.translatable("message.mdm.import.no_backup", key.location()));
        }
        DimensionImportSource source = DimensionImportSource.locate(backup.source());

        // 预检源里到底有没有区块文件。重置是「先破坏再重建」，抹掉之后拷贝才失败的话，会留下一个
        // 被抹空的维度——所以宁可在这里就先失败，一个字节都别动
        Path sourceRegion = source.storageFolder(backup.sourceDim()).resolve("region");
        if (!Files.isDirectory(sourceRegion)) {
            throw new DimensionImportSource.ImportException(
                    Component.translatable("message.mdm.import.missing_folder", sourceRegion.getFileName()));
        }
        try (Stream<Path> walk = Files.list(sourceRegion)) {
            if (walk.noneMatch(path -> path.getFileName().toString().endsWith(".mca"))) {
                throw new DimensionImportSource.ImportException(Component.translatable(
                        "message.mdm.import.no_chunks", backup.sourceDim(), sourceRegion));
            }
        } catch (IOException e) {
            throw new DimensionImportSource.ImportException(Component.translatable(
                    "message.mdm.import.unreadable", sourceRegion, e.getMessage()), e);
        }

        // 必须真的卸载并抹干净，不是保险：importDimension 的注释写明 region 文件要在 level 创建前
        // 就位，因为 RegionFileStorage 会缓存已打开的句柄——level 还开着就覆盖，改动不会被看到
        unloadAndWipe(server, key);

        // importDimension 不检查"维度是否已存在"，正好直接复用：重新解析类型/生成器、
        // 重拷 region/entities/poi、createAndPersist 重建 level 并重写 stem。
        // 它也会把同一份配方再写一遍，幂等
        ImportResult result = importDimension(server, key, source, backup.sourceDim(), backup.template());
        MdmMain.LOGGER.info("Reset dimension {} from backup '{}'", key.location(), backup.source());
        return result;
    }

    /**
     * 摘掉创建时挂在主世界世界边界上的监听器。
     * 不摘的话它会一直持有被删维度的 WorldBorder，进而持有整个 ServerLevel——每删一个维度泄漏一份。
     */
    private static void removeBorderListener(MinecraftServer server, ResourceKey<Level> key) {
        BorderChangeListener listener = BORDER_LISTENERS.remove(key);
        if (listener != null) {
            server.overworld().getWorldBorder().removeListener(listener);
        }
    }

    private static void evictPlayers(MinecraftServer server, ServerLevel level) {
        ServerLevel overworld = server.overworld();
        if (level == overworld) {
            return;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        for (ServerPlayer player : List.copyOf(level.players())) {
            player.teleportTo(overworld,
                    spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                    player.getYRot(), player.getXRot());
        }
    }

    private static void deleteStorageFolder(MinecraftServer server, ResourceKey<Level> key) {
        if (key == Level.OVERWORLD || key == Level.NETHER || key == Level.END) {
            return;
        }
        Path folder = DimensionType.getStorageFolder(key, server.getWorldPath(LevelResource.ROOT));
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    MdmMain.LOGGER.warn("Failed to delete {} while removing dimension {}", path, key.location(), e);
                }
            });
        } catch (IOException e) {
            MdmMain.LOGGER.error("Failed to wipe storage folder {} of dimension {}", folder, key.location(), e);
        }
    }

    // ---------------------------------------------------------------- 传送

    /**
     * 把玩家在维度之间传送。
     * <p>
     * 用 {@code ServerPlayer#teleportTo(ServerLevel, x, y, z, yRot, xRot)} 就够了——
     * 它内部已经处理了跨维度的全部流程（重发 respawn 包、从旧 level 摘除、挂到新 level），
     * 并且会触发 ForgeHooks.onTravelToDimension。
     *
     * @param requested 目标坐标；传 null 时用玩家当前 X/Z 对应的地表高度
     */
    public static boolean teleport(ServerPlayer player, ResourceKey<Level> key, @Nullable BlockPos requested) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            return false;
        }

        BlockPos target;
        if (requested != null) {
            // 显式坐标优先，忽略配置的出生点
            target = requested;
        } else {
            // 没给坐标时先用该维度配置的传送出生点；没配过才退到地表计算
            BlockPos spawn = spawnOf(server, key);
            target = spawn != null ? spawn : surface(level, player.getBlockX(), player.getBlockZ());
        }

        // 强制加载目标区块，否则玩家会在未加载的区块里往下掉
        level.getChunkAt(target);

        player.teleportTo(level,
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        return true;
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        // 空世界（没有地表）时退到世界中层，至少不会立刻掉出世界底部
        if (y <= level.getMinBuildHeight()) {
            y = level.getMinBuildHeight() + level.getHeight() / 2;
        }
        return new BlockPos(x, y, z);
    }

    // ---------------------------------------------------------------- 持久化

    /** 把维度的 LevelStem 编码进存档数据。 */
    public static void record(MinecraftServer server, ResourceKey<Level> key, LevelStem stem) {
        Tag encoded = encode(server, stem);
        if (encoded == null) {
            MdmMain.LOGGER.error("Failed to encode LevelStem for {}, dimension will not survive restart",
                    key.location());
            return;
        }
        DimensionSavedData.get(server).putDim(DimensionSavedData.entry(key.location().toString(), encoded));
    }

    /** 服务器启动时重建所有被记录的运行时维度。 */
    public static int restore(MinecraftServer server) {
        DimensionSavedData data = DimensionSavedData.get(server);
        int restored = 0;
        for (CompoundTag entry : data.dims()) {
            ResourceLocation id = ResourceLocation.tryParse(DimensionSavedData.idOf(entry));
            if (id == null) {
                MdmMain.LOGGER.warn("Skipping persisted dimension with unparsable id");
                continue;
            }
            ResourceKey<Level> key = MdmDimensions.levelKey(id);
            if (server.getLevel(key) != null) {
                // 静态维度（或已恢复的）已经由 vanilla / 上一轮建好了
                continue;
            }
            LevelStem stem = decode(server, DimensionSavedData.stemOf(entry));
            if (stem == null) {
                MdmMain.LOGGER.warn("Skipping persisted dimension {}: its LevelStem no longer decodes", id);
                continue;
            }
            create(server, key, stem);
            restored++;
        }
        refreshManagedLevels(server);
        return restored;
    }

    /**
     * 刷新 {@link MdmMain#LEVELS}，让它与存档里记录的维度和集合保持一致。
     * <p>
     * 刷新逻辑只在这一处实现，所有会改动维度集合的路径（创建/导入、删除、启动恢复）最后都调用它。
     * 「镜像字段」最大的风险就是漏掉某个改动点导致与实际不同步，收敛到单个实现能把这个风险降到最低。
     */
    public static void refreshManagedLevels(MinecraftServer server) {
        MdmMain.LEVELS = DimensionSavedData.get(server).managedLevelKeys();
    }

    // ---------------------------------------------------------------- 传送出生点 / 别名

    /**
     * 某个维度配置的传送出生点。
     * <p>
     * 只影响 {@link #teleport} 在不给坐标时的落点，<b>不是</b>世界真正的出生点——
     * 刻意不去动 {@code setDefaultSpawnPos}。
     *
     * @return 没配置过时返回 null
     */
    @Nullable
    public static BlockPos spawnOf(MinecraftServer server, ResourceKey<Level> key) {
        return DimensionSavedData.get(server).spawnOf(key.location().toString());
    }

    /**
     * 设置或清除某个维度的传送出生点。
     * <p>
     * 任何<b>已加载</b>的维度都能设，不管是不是本模组创建的——静态维度（数据包 JSON 定义的、
     * 以及 vanilla 那三个）同样适用。
     *
     * @param pos 传 null 表示清除
     * @return false 表示该维度没加载，无处可设
     */
    public static boolean setSpawn(MinecraftServer server, ResourceKey<Level> key, @Nullable BlockPos pos) {
        if (server.getLevel(key) == null) {
            return false;
        }
        DimensionSavedData.get(server).setSpawn(key.location().toString(), pos);
        return true;
    }

    /**
     * 某个维度的别名——界面上拿来展示的名字。
     * <p>
     * 与传送出生点同一套语义：任何已加载的维度都可能有。
     *
     * @return 没设置过时返回 null
     */
    @Nullable
    public static String aliasOf(MinecraftServer server, ResourceKey<Level> key) {
        return DimensionSavedData.get(server).aliasOf(key.location().toString());
    }

    /**
     * 设置或清除某个维度的别名。
     * <p>
     * 和 {@link #setSpawn} 一样，任何<b>已加载</b>的维度都能设，不限于本模组创建的。
     *
     * @param alias 传 null 表示清除
     * @return false 表示该维度没加载，无处可设
     */
    public static boolean setAlias(MinecraftServer server, ResourceKey<Level> key, @Nullable String alias) {
        if (server.getLevel(key) == null) {
            return false;
        }
        DimensionSavedData.get(server).setAlias(key.location().toString(), alias);
        return true;
    }

    @Nullable
    private static Tag encode(MinecraftServer server, LevelStem stem) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        return LevelStem.CODEC.encodeStart(ops, stem).result().orElse(null);
    }

    @Nullable
    private static LevelStem decode(MinecraftServer server, Tag tag) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        DataResult<LevelStem> result = LevelStem.CODEC.parse(ops, tag);
        if (result.error().isPresent()) {
            MdmMain.LOGGER.warn("LevelStem decode error: {}", result.error().get().message());
            return null;
        }
        return result.result().orElse(null);
    }
}
