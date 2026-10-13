package com.github.rd806.mdm.dimension.data;

import com.github.rd806.mdm.dimension.MdmDimensions;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 持久化运行时创建的维度，存放在主世界的存档数据里（world/data/mdm_dimensions.dat）。
 * <p>
 * 为什么必须有这一层：vanilla 只会依据数据包 LEVEL_STEM 注册表来重建 MinecraftServer.levels，
 * 运行时塞进去的维度不在那里面，不记录就会在重启后消失。
 * <p>
 * 文件里是<b>两段互不相干</b>的记录，别把它们混起来：
 * <ul>
 *   <li>{@code dimensions}：模组<b>拥有</b>的维度条目（id + 编码后的 LevelStem）。
 *       它同时就是"哪些维度由本模组管理"的权威定义——{@code restore()} 按它重建维度，
 *       {@code managedLevelKeys()} 按它算 {@code MdmMain.LEVELS}。所以<b>不能</b>往里塞非模组
 *       维度的记录：那会让启动时把它们当成待重建对象。</li>
 *   <li>{@code meta}：<b>任意</b>维度的展示名（alias）、传送落点（spawn）和导入配方（backup），
 *       按维度 id 挂。主世界、数据包维度都能有，和模组拥不拥有那个维度无关。</li>
 * </ul>
 * <p>
 * 这里只存原始 NBT，不做解码——LevelStem 里的 Holder&lt;DimensionType&gt; 需要 registryAccess
 * 才能解开，而 SavedData 的工厂方法拿不到 server。解码交给
 * {@link DynamicDimensionManager#restore(MinecraftServer)}。
 */
public class DimensionSavedData extends SavedData {

    public static final String FILE_ID = "mdm_dimensions";

    // 键名
    private static final String KEY_DIMENSIONS = "dimensions";
    private static final String KEY_META = "meta";
    private static final String KEY_ID = "id";
    private static final String KEY_STEM = "stem";
    private static final String KEY_SPAWN = "spawn";

    // 别名
    private static final String ALIAS = "alias";
    // 对应的备份（即导入的存档路径，为空表示由模版创建）
    private static final String BACKUP = "backup";
    private static final String BACKUP_SOURCE = "source";
    private static final String BACKUP_SOURCE_DIM = "sourceDim";
    private static final String BACKUP_TEMPLATE = "template";
    // 重生点
    private static final String SPAWN_X = "x";
    private static final String SPAWN_Y = "y";
    private static final String SPAWN_Z = "z";

    private final List<CompoundTag> dim = new ArrayList<>();
    private final List<CompoundTag> meta = new ArrayList<>();

    public static DimensionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(DimensionSavedData::fromTag, DimensionSavedData::new, FILE_ID);
    }

    private static DimensionSavedData fromTag(CompoundTag tag) {
        DimensionSavedData data = new DimensionSavedData();
        // 维度列表
        ListTag dimList = tag.getList(KEY_DIMENSIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < dimList.size(); i++) {
            data.dim.add(dimList.getCompound(i));
        }
        // 信息列表
        ListTag metaList = tag.getList(KEY_META, Tag.TAG_COMPOUND);
        for (int i = 0; i < metaList.size(); i++) {
            data.meta.add(metaList.getCompound(i));
        }
        data.migrateLegacyMetadata();
        return data;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag) {
        // 保存维度
        ListTag dimList = new ListTag();
        dimList.addAll(dim);
        tag.put(KEY_DIMENSIONS, dimList);
        // 保存信息
        ListTag metaList = new ListTag();
        metaList.addAll(meta);
        tag.put(KEY_META, metaList);
        return tag;
    }

    /** 所有已记录的维度条目，每个含 "id"（字符串）与 "stem"（编码后的 LevelStem）。 */
    public List<CompoundTag> dims() {
        return List.copyOf(dim);
    }

    /**
     * 记录一个维度；同 id 已存在时覆盖，避免重复条目。
     * <p>
     * 别名和传送出生点存在 {@code meta} 段里，不在这条记录上，所以重录一次不会碰到它们——
     * 这也是把它们拆出去的原因之一。
     */
    public void putDim(CompoundTag entry) {
        String id = entry.getString(KEY_ID);
        dim.removeIf(existing -> id.equals(existing.getString(KEY_ID)));
        dim.add(entry);
        setDirty();
    }

    /**
     * 某个维度配置的传送出生点，只影响 {@code /mdm tp} 不给坐标时的落点，
     * <b>不是</b>世界真正的出生点——刻意不去碰 {@code setDefaultSpawnPos}。
     *
     * @return 没配置过时返回 null
     */
    @Nullable
    public BlockPos spawnOf(String id) {
        CompoundTag target = findMeta(id);
        if (target == null || !target.contains(KEY_SPAWN, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag spawn = target.getCompound(KEY_SPAWN);
        return new BlockPos(spawn.getInt(SPAWN_X), spawn.getInt(SPAWN_Y), spawn.getInt(SPAWN_Z));
    }

    /**
     * 设置或清除某个维度的传送出生点。
     * <p>
     * 不校验维度存不存在——数据层拿不到 server，那层判断在
     * {@link DynamicDimensionManager#setSpawn(MinecraftServer, ResourceKey, BlockPos)}。
     *
     * @param pos 传 null 表示清除
     */
    public void setSpawn(String id, @Nullable BlockPos pos) {
        CompoundTag target = metaEntry(id);
        if (pos == null) {
            target.remove(KEY_SPAWN);
        } else {
            CompoundTag spawn = new CompoundTag();
            spawn.putInt(SPAWN_X, pos.getX());
            spawn.putInt(SPAWN_Y, pos.getY());
            spawn.putInt(SPAWN_Z, pos.getZ());
            target.put(KEY_SPAWN, spawn);
        }
        pruneMeta(id);
        setDirty();
    }

    /**
     * 某个维度的别名——展示用的名字，和传送出生点一样只是模组自己的记录。
     * <p>
     * 记录里没有 "alias" 键、或存的是空白串，都算没设置。
     *
     * @return 没设置过时返回 null
     */
    @Nullable
    public String aliasOf(String id) {
        CompoundTag target = findMeta(id);
        if (target == null || !target.contains(ALIAS, Tag.TAG_STRING)) {
            return null;
        }
        String alias = target.getString(ALIAS);
        return alias.isBlank() ? null : alias;
    }

    /**
     * 设置或清除某个维度的别名。
     *
     * @param alias 传 null 表示清除；空白串同样按清除处理，别往盘里留没意义的空值
     */
    public void setAlias(String id, @Nullable String alias) {
        CompoundTag target = metaEntry(id);
        if (alias == null || alias.isBlank()) {
            target.remove(ALIAS);
        } else {
            target.putString(ALIAS, alias);
        }
        pruneMeta(id);
        setDirty();
    }

    /**
     * 某个维度的导入配方——它当初是从哪份存档的哪个维度导进来的。
     * <p>
     * 三项都要记，不能只记路径：重置时得按当初那次导入的参数精确重来，否则"源维度"会悄悄退回
     * 默认的 overworld，重置出来的跟原来压根不是一个世界。
     *
     * @param template 当初用的兜底模板；没用模板时为 null
     */
    public record Backup(String source, ResourceLocation sourceDim, @Nullable String template) {
    }

    /**
     * 某个维度的导入配方。
     *
     * @return 模板创建的维度（没有备份记录）返回 null
     */
    @Nullable
    public Backup backupOf(String id) {
        CompoundTag target = findMeta(id);
        if (target == null || !target.contains(BACKUP, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag backup = target.getCompound(BACKUP);
        String source = backup.getString(BACKUP_SOURCE);
        ResourceLocation sourceDim = ResourceLocation.tryParse(backup.getString(BACKUP_SOURCE_DIM));
        if (source.isBlank() || sourceDim == null) {
            // 记录残缺（比如存档文件被手改过）。当作没有，别让它晚点炸在 locate 里
            return null;
        }
        String template = backup.contains(BACKUP_TEMPLATE, Tag.TAG_STRING)
                ? backup.getString(BACKUP_TEMPLATE)
                : null;
        return new Backup(source, sourceDim, template);
    }

    /**
     * 设置或清除某个维度的导入配方。
     *
     * @param backup 传 null 表示清除——清掉之后这个维度就被当作"模板创建"，不能再重置
     */
    public void setBackup(String id, @Nullable Backup backup) {
        CompoundTag target = metaEntry(id);
        if (backup == null) {
            target.remove(BACKUP);
        } else {
            CompoundTag tag = new CompoundTag();
            tag.putString(BACKUP_SOURCE, backup.source());
            tag.putString(BACKUP_SOURCE_DIM, backup.sourceDim().toString());
            if (backup.template() != null) {
                tag.putString(BACKUP_TEMPLATE, backup.template());
            }
            target.put(BACKUP, tag);
        }
        pruneMeta(id);
        setDirty();
    }

    /** 删除一个维度条目。同 id 的别名、传送出生点和备份记录一并清掉：删掉再重建就是干净重来。 */
    public boolean remove(String id) {
        boolean changed = dim.removeIf(existing -> id.equals(existing.getString(KEY_ID)));
        boolean metaChanged = meta.removeIf(existing -> id.equals(existing.getString(KEY_ID)));
        if (changed || metaChanged) {
            setDirty();
        }
        return changed;
    }

    public static String idOf(CompoundTag entry) {
        return entry.getString(KEY_ID);
    }

    public static Tag stemOf(CompoundTag entry) {
        return entry.get(KEY_STEM);
    }

    public static CompoundTag entry(String id, Tag stem) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_ID, id);
        tag.put(KEY_STEM, stem);
        return tag;
    }

    /**
     * 已记录的维度 key。这是「本模组管理的维度」的权威定义，
     * {@code MdmMain.LEVELS} 就是它的快照。
     * <p>
     * 只看 {@code dimensions} 段，不看 {@code meta}：给主世界设个别名不代表模组"拥有"主世界。
     */
    public Set<ResourceKey<Level>> managedLevelKeys() {
        Set<ResourceKey<Level>> keys = new HashSet<>();
        for (CompoundTag entry : dim) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getString(KEY_ID));
            if (id != null) {
                keys.add(MdmDimensions.levelKey(id));
            }
        }
        return Set.copyOf(keys);
    }

    // ---------------------------------------------------------------- meta

    /** 某维度的元数据记录，没有就返回 null。 */
    @Nullable
    private CompoundTag findMeta(String id) {
        for (CompoundTag entry : meta) {
            if (id.equals(entry.getString(KEY_ID))) {
                return entry;
            }
        }
        return null;
    }

    /** 某维度的元数据记录，没有就建一条。 */
    private CompoundTag metaEntry(String id) {
        CompoundTag existing = findMeta(id);
        if (existing != null) {
            return existing;
        }
        CompoundTag created = new CompoundTag();
        created.putString(KEY_ID, id);
        meta.add(created);
        return created;
    }

    /**
     * 别名、出生点、备份记录都没了的条目直接删掉。
     * <p>
     * 不清的话，先设后清会在文件里留下一条只有 id 的空壳，越攒越多。反过来说，
     * <b>每加一项 meta 字段都要记得在这里补一个 contains 判断</b>，漏了就会把只设了那一项的记录剪掉。
     */
    private void pruneMeta(String id) {
        meta.removeIf(entry -> id.equals(entry.getString(KEY_ID))
                && !entry.contains(ALIAS, Tag.TAG_STRING)
                && !entry.contains(KEY_SPAWN, Tag.TAG_COMPOUND)
                && !entry.contains(BACKUP, Tag.TAG_COMPOUND));
    }

    /**
     * 把旧版写在维度条目上的 alias / spawn 搬进 meta 段。
     * <p>
     * 早先这两项存在受管维度的条目里，所以只有模组创建的维度能用；拆出 meta 段之后要搬过来，
     * 否则手设过的出生点和别名就白丢了。搬完顺手把条目上的旧键摘掉，免得每次保存又把死数据写回去。
     * meta 里已经有同名值时不覆盖——那才是新的权威来源。
     */
    private void migrateLegacyMetadata() {
        boolean changed = false;
        for (CompoundTag entry : dim) {
            boolean hasAlias = entry.contains(ALIAS, Tag.TAG_STRING);
            boolean hasSpawn = entry.contains(KEY_SPAWN, Tag.TAG_COMPOUND);
            if (!hasAlias && !hasSpawn) {
                continue;
            }
            String id = entry.getString(KEY_ID);
            CompoundTag target = metaEntry(id);
            if (hasAlias && !target.contains(ALIAS, Tag.TAG_STRING)) {
                target.putString(ALIAS, entry.getString(ALIAS));
            }
            if (hasSpawn && !target.contains(KEY_SPAWN, Tag.TAG_COMPOUND)) {
                target.put(KEY_SPAWN, entry.getCompound(KEY_SPAWN).copy());
            }
            entry.remove(ALIAS);
            entry.remove(KEY_SPAWN);
            changed = true;
        }
        if (changed) {
            // 让迁移结果落盘，否则下次进来还得再搬一遍（幂等，但没必要）
            setDirty();
        }
    }
}
