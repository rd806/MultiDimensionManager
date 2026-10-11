package com.github.rd806.mdm.dimension.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 运行时新建维度可选的模板。
 * <p>
 * 模板写成与数据包 JSON 完全一致的文本，再走 {@link LevelStem#CODEC} 解析成对象。
 * 这样做而不是手搓 ChunkGenerator 实例，是为了保证和静态维度走同一条码表，不会两边跑偏。
 * <p>
 * 受限于 LevelStem 里 Holder&lt;DimensionType&gt; 编码的是注册表名，模板引用的维度类型
 * 必须已经存在于数据包注册表中——也就是 data/mdm/dimension_type/*.json 提供的那些。
 * 新增模板 = 在这里加一行 JSON + 必要时加一个 dimension_type 文件。
 */
public final class DimensionTemplates {

    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();

    static {
        // 平坦的口袋维度：一块草地平台 + 主世界天空，可以直接站人
        TEMPLATES.put("pocket", """
                {
                  "type": "mdm:pocket",
                  "generator": {
                    "type": "minecraft:flat",
                    "settings": {
                      "biome": "minecraft:plains",
                      "layers": [
                        { "block": "minecraft:bedrock", "height": 1 },
                        { "block": "minecraft:dirt", "height": 2 },
                        { "block": "minecraft:grass_block", "height": 1 }
                      ]
                    }
                  }
                }
                """);

        // 类主世界维度：标准噪声地形
        TEMPLATES.put("overworld_like", """
                {
                  "type": "mdm:overworld_like",
                  "generator": {
                    "type": "minecraft:noise",
                    "settings": "minecraft:overworld",
                    "biome_source": {
                      "type": "minecraft:multi_noise",
                      "preset": "minecraft:overworld"
                    }
                  }
                }
                """);
    }

    private DimensionTemplates() {
    }

    public static Set<String> names() {
        return Collections.unmodifiableSet(TEMPLATES.keySet());
    }

    public static boolean exists(String name) {
        return TEMPLATES.containsKey(name);
    }

    /**
     * 把模板解析成 LevelStem。
     * @return 解析失败（JSON 非法、维度类型不存在、生成器 id 拼错等）时返回 null
     */
    @Nullable
    public static LevelStem build(MinecraftServer server, String name) {
        String json = TEMPLATES.get(name);
        if (json == null) {
            return null;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return LevelStem.CODEC.parse(ops, JsonParser.parseString(json)).result().orElse(null);
    }
}
