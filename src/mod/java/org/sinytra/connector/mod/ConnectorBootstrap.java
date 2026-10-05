package org.sinytra.connector.mod;

import org.sinytra.connector.loader.ConnectorEarlyLoader;
import net.minecraftforge.coremod.api.ASMAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Prepare Fabric Loader before game classes and mixins are loaded
 */
public class ConnectorBootstrap implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger(ConnectorBootstrap.class);

    /**
     * Mixin targets that are interfaces and carry injectors.
     *
     * <p>1.18.2 note: Forge 1.18.2 ships the plain upstream SpongePowered Mixin 0.8.5 build, whose
     * {@code MixinApplicatorInterface#prepareInjections} unconditionally rejects <em>every</em>
     * injector declared in a mixin that targets an interface. Connector mixes into a few Forge
     * interfaces, so applying them aborts the whole launch with a fatal MixinTransformerError.
     * These mixins are skipped instead of crashing; the resulting behavioural gap is documented in
     * the port notes. Resolving the target reflectively is not an option because loading the class
     * that Mixin is currently preparing would recurse back into mixin application.
     */
    private static final Set<String> INTERFACE_TARGETS = Set.of(
        "net.minecraftforge.common.extensions.IForgeItem"
    );

    static {
        CrashReportUpgrade.registerCrashLogInfo();
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (INTERFACE_TARGETS.contains(targetClassName)) {
            LOGGER.warn("Skipping mixin {} - Mixin 0.8.5 cannot inject into the interface {} on 1.18.2",
                mixinClassName, targetClassName);
            return false;
        }
        return !ConnectorEarlyLoader.hasEncounteredException();
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.equals("dev.su5ed.sinytra.connector.mod.mixin.item.LateItemStackMixin")) {
            String methodName = ASMAPI.mapMethod("m_41638_");
            targetClass.methods.stream()
                .filter(m -> m.name.equals(methodName) && m.desc.equals("(Lnet/minecraft/world/entity/EquipmentSlot;)Lcom/google/common/collect/Multimap;"))
                .findFirst()
                .ifPresent(method -> {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getOpcode() == Opcodes.ARETURN) {
                            method.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/collect/HashMultimap", "create", "(Lcom/google/common/collect/Multimap;)Lcom/google/common/collect/HashMultimap;", false));
                        }
                    }
                });

        }
    }

    // We don't need any of the mixin stuff
    //@formatter:off
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() {return null;}
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() {return null;}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    //@formatter:on
}
