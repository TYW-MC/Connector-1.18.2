package org.sinytra.connector.transformer.patch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraftforge.coremod.api.ASMAPI;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.sinytra.adapter.patch.api.Patch;
import org.sinytra.adapter.patch.util.MethodQualifier;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClassAnalysingTransformer implements ClassNodeTransformer.ClassProcessor {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 1.18.2 note on the loot pair below: `LootDataType` was introduced by the 1.19.3 loot-data
    // refactor and does not exist on 1.18.2, so this source qualifier can never match here. On
    // 1.18.2 loot deserialization is already ResourceManager-free through
    // `Deserializers.createLootTableSerializer()`, so `ConnectorMod.deserializeLootTable` was
    // reshaped to `(ResourceLocation, JsonElement) -> LootTable`. The pair is kept dormant until
    // the ForgifiedFabricAPI 1.18.2 loot call-site is known, at which point the source qualifier
    // and the replacement descriptor have to be re-derived together.
    //
    // 注意：这一组的目标全是 static 方法，所以固定用 INVOKESTATIC。
    private static final Map<MethodQualifier, MethodQualifier> REPLACEMENTS = Map.of(
        new MethodQualifier("Ljava/lang/Class;", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "getModResourceAsStream", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;"),

        new MethodQualifier("Lnet/minecraft/world/level/storage/loot/LootDataType;", ASMAPI.mapMethod("m_278763_"), "(Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonElement;)Ljava/util/Optional;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "deserializeLootTable", "(Lnet/minecraft/world/level/storage/loot/LootDataType;Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonElement;)Ljava/util/Optional;"),

        new MethodQualifier("Lcom/electronwill/nightconfig/core/file/FileConfigBuilder;", "defaultResource", "(Ljava/lang/String;)Lcom/electronwill/nightconfig/core/file/GenericBuilder;"),
        new MethodQualifier("org/sinytra/connector/mod/ConnectorMod", "useModConfigResource", "(Lcom/electronwill/nightconfig/core/file/FileConfigBuilder;Ljava/lang/String;)Lcom/electronwill/nightconfig/core/file/GenericBuilder;")
    );

    private static final String FORGE_PATCH_RESOURCE = "/connector_forge_member_patches.json";

    /**
     * Forge 用 binpatch 改过的成员会丢掉 SRG 名、改用 Mojang 名（SRG 名只对未被改动的成员成立）。
     * 例如 1.18.2 的同名方法：
     * <pre>
     *   原版 : private static void m_174570_(Item, ResourceLocation, ClampedItemPropertyFunction)
     *   Forge: public  static void register  (Item, ResourceLocation, ItemPropertyFunction)
     * </pre>
     * Fabric 模组按 intermediary 编译、被 Connector remap 到 SRG 后调用的仍是 {@code m_174570_}，
     * 而 Forge 运行时没有这个成员，于是 NoSuchMethodError。
     * 该表由 {@code tools/gen_forge_member_patches.py} 比对「原版 SRG jar」与
     * 「Forge 补丁 jar」自动生成。
     */
    private record MemberPatch(String owner, String name, String desc, String newName, String newDesc) {
    }

    private static final Map<String, List<MemberPatch>> FORGE_METHOD_PATCHES;
    private static final Map<String, List<MemberPatch>> FORGE_FIELD_PATCHES;

    static {
        Map<String, List<MemberPatch>> methods = new HashMap<>();
        Map<String, List<MemberPatch>> fields = new HashMap<>();
        try (InputStream in = ClassAnalysingTransformer.class.getResourceAsStream(FORGE_PATCH_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Missing {} - Forge-patched members will not be remapped", FORGE_PATCH_RESOURCE);
            } else {
                JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                readPatches(root.getAsJsonArray("methods"), methods);
                readPatches(root.getAsJsonArray("fields"), fields);
                LOGGER.debug("Loaded {} Forge method patches and {} field patches",
                    methods.values().stream().mapToInt(List::size).sum(),
                    fields.values().stream().mapToInt(List::size).sum());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load " + FORGE_PATCH_RESOURCE, e);
        }
        FORGE_METHOD_PATCHES = methods;
        FORGE_FIELD_PATCHES = fields;
    }

    private static void readPatches(JsonArray array, Map<String, List<MemberPatch>> target) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            MemberPatch patch = new MemberPatch(
                obj.get("owner").getAsString(),
                obj.get("name").getAsString(),
                obj.get("desc").getAsString(),
                obj.get("newName").getAsString(),
                obj.get("newDesc").getAsString());
            target.computeIfAbsent(patch.owner(), k -> new ArrayList<>()).add(patch);
        }
    }

    @Override
    public Patch.Result process(ClassNode node) {
        boolean applied = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode minsn) {
                    for (Map.Entry<MethodQualifier, MethodQualifier> entry : REPLACEMENTS.entrySet()) {
                        if (entry.getKey().matches(minsn)) {
                            MethodQualifier replacement = entry.getValue();
                            method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, replacement.owner(), replacement.name(), replacement.desc(), false));
                            applied = true;
                        }
                    }
                    List<MemberPatch> forgePatches = FORGE_METHOD_PATCHES.get(minsn.owner);
                    if (forgePatches != null) {
                        for (MemberPatch patch : forgePatches) {
                            if (patch.name().equals(minsn.name) && patch.desc().equals(minsn.desc)) {
                                // 保留原 opcode —— Forge 通常只是改名/放宽签名，调用形态不变
                                method.instructions.set(insn, new MethodInsnNode(minsn.getOpcode(), minsn.owner, patch.newName(), patch.newDesc(), minsn.itf));
                                applied = true;
                                break;
                            }
                        }
                    }
                } else if (insn instanceof FieldInsnNode finsn) {
                    List<MemberPatch> forgePatches = FORGE_FIELD_PATCHES.get(finsn.owner);
                    if (forgePatches != null) {
                        for (MemberPatch patch : forgePatches) {
                            if (patch.name().equals(finsn.name) && patch.desc().equals(finsn.desc)) {
                                method.instructions.set(insn, new FieldInsnNode(finsn.getOpcode(), finsn.owner, patch.newName(), patch.newDesc()));
                                applied = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
        return applied ? Patch.Result.APPLY : Patch.Result.PASS;
    }
}
