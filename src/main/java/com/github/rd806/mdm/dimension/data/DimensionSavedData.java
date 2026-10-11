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
 * 这里只存原始 NBT，不做解码——LevelStem 里的 Holder&lt;DimensionType&gt; 需要 registryAccess
 * 才能解开，而 SavedData 的工厂方法拿不到 server。解码交给
 * {@link DynamicDimensionManager#restore(MinecraftServer)}。
 */
public class DimensionSavedData extends SavedData {

    public static final String FILE_ID = "mdm_dimensions";

    private static final String KEY_DIMENSIONS = "dimensions";
    private static final String KEY_ID = "id";
    private static final String KEY_STEM = "stem";
    private static final String KEY_SPAWN = "spawn";

    private static final String SPAWN_X = "x";
    private static final String SPAWN_Y = "y";
    private static final String SPAWN_Z = "z";

    private final List<CompoundTag> entries = new ArrayList<>();

    public static DimensionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(DimensionSavedData::fromTag, DimensionSavedData::new, FILE_ID);
    }

    private static DimensionSavedData fromTag(CompoundTag tag) {
        DimensionSavedData data = new DimensionSavedData();
        ListTag list = tag.getList(KEY_DIMENSIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.entries.add(list.getCompound(i));
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        list.addAll(entries);
        tag.put(KEY_DIMENSIONS, list);
        return tag;
    }

    /** 所有已记录的维度条目，每个含 "id"（字符串）与 "stem"（编码后的 LevelStem）。 */
    public List<CompoundTag> entries() {
        return List.copyOf(entries);
    }

    /** 记录一个维度；同 id 已存在时覆盖，避免重复条目。已设置的传送出生点会被带过去。 */
    public void put(CompoundTag entry) {
        String id = entry.getString(KEY_ID);
        for (CompoundTag existing : entries) {
            if (id.equals(existing.getString(KEY_ID)) && existing.contains(KEY_SPAWN, Tag.TAG_COMPOUND)) {
                // 不这样带过去的话，重新 record 一次就会把手工设置的出生点悄悄抹掉
                entry.put(KEY_SPAWN, existing.getCompound(KEY_SPAWN).copy());
                break;
            }
        }
        entries.removeIf(existing -> id.equals(existing.getString(KEY_ID)));
        entries.add(entry);
        setDirty();
    }

    /**
     * 某个维度配置的传送出生点。
     * <p>
     * 这是模组自己的记录，只影响 {@code /mdm tp} 不给坐标时的落点，
     * <b>不是</b>世界真正的出生点——刻意不去碰 {@code setDefaultSpawnPos}。
     *
     * @return 没配置过、或该维度没有记录时返回 null
     */
    @Nullable
    public BlockPos spawnOf(String id) {
        for (CompoundTag entry : entries) {
            if (!id.equals(entry.getString(KEY_ID))) {
                continue;
            }
            if (!entry.contains(KEY_SPAWN, Tag.TAG_COMPOUND)) {
                return null;
            }
            CompoundTag spawn = entry.getCompound(KEY_SPAWN);
            return new BlockPos(spawn.getInt(SPAWN_X), spawn.getInt(SPAWN_Y), spawn.getInt(SPAWN_Z));
        }
        return null;
    }

    /**
     * 设置或清除某个维度的传送出生点。
     *
     * @param pos 传 null 表示清除
     * @return false 表示该维度不由本模组管理（没有记录），无从设置
     */
    public boolean setSpawn(String id, @Nullable BlockPos pos) {
        for (CompoundTag entry : entries) {
            if (!id.equals(entry.getString(KEY_ID))) {
                continue;
            }
            if (pos == null) {
                entry.remove(KEY_SPAWN);
            } else {
                CompoundTag spawn = new CompoundTag();
                spawn.putInt(SPAWN_X, pos.getX());
                spawn.putInt(SPAWN_Y, pos.getY());
                spawn.putInt(SPAWN_Z, pos.getZ());
                entry.put(KEY_SPAWN, spawn);
            }
            setDirty();
            return true;
        }
        return false;
    }

    public boolean remove(String id) {
        boolean changed = entries.removeIf(existing -> id.equals(existing.getString(KEY_ID)));
        if (changed) {
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
     */
    public Set<ResourceKey<Level>> managedLevelKeys() {
        Set<ResourceKey<Level>> keys = new HashSet<>();
        for (CompoundTag entry : entries) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getString(KEY_ID));
            if (id != null) {
                keys.add(MdmDimensions.levelKey(id));
            }
        }
        return Set.copyOf(keys);
    }
}
