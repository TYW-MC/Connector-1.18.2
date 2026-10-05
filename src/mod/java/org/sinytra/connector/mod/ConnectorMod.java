package org.sinytra.connector.mod;

import com.electronwill.nightconfig.core.file.FileConfigBuilder;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.file.GenericBuilder;
import com.google.gson.JsonElement;
import org.sinytra.connector.ConnectorUtil;
import org.sinytra.connector.mod.compat.FluidHandlerCompat;
import org.sinytra.connector.mod.compat.LateRenderTypesInit;
import org.sinytra.connector.mod.compat.LateSheetsInit;
import org.sinytra.connector.mod.compat.LazyEntityAttributes;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.Deserializers;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.Optional;

@Mod(ConnectorUtil.CONNECTOR_MODID)
public class ConnectorMod {
    public static final Logger LOG = LoggerFactory.getLogger(ConnectorMod.class);

    private static boolean clientLoadComplete;
    private static boolean preventFreeze;

    public static boolean clientLoadComplete() {
        return clientLoadComplete;
    }

    public ConnectorMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(EventPriority.HIGHEST, ConnectorMod::onClientSetup);
        FluidHandlerCompat.init(bus);
        if (FMLLoader.getDist().isClient()) {
            bus.addListener(ConnectorMod::onLoadComplete);
        }

        ModList modList = ModList.get();
        if (modList.isLoaded("fabric_object_builder_api_v1")) {
            bus.addListener(EventPriority.HIGHEST, LazyEntityAttributes::initializeLazyAttributes);
        }
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        LateRenderTypesInit.regenerateRenderTypeIds();
        clientLoadComplete = true;
    }

    private static void onLoadComplete(FMLLoadCompleteEvent event) {
        LateSheetsInit.completeSheetsInit();
    }

    // Injected into mod code by ClassAnalysingTransformer
    @SuppressWarnings("unused")
    public static InputStream getModResourceAsStream(Class<?> clazz, String name) {
        InputStream classRes = clazz.getResourceAsStream(name);
        return classRes != null ? classRes : clazz.getClassLoader().getResourceAsStream(name);
    }

    // Injected into mod code by ClassAnalysingTransformer
    //
    // 1.18.2 note: 1.20.1 used `LootDataType.deserialize(location, json, resourceManager)`.
    // LootDataType was introduced by the 1.19.3 loot-data refactor and does not exist on
    // 1.18.2, so the helper takes the raw JSON and runs the 1.18.2 loot table serializer
    // instead. The ResourceLocation is kept in the signature because the calling Fabric code
    // always has it and it documents which table is being deserialized.
    @SuppressWarnings("unused")
    public static LootTable deserializeLootTable(ResourceLocation location, JsonElement json) {
        return Deserializers.createLootTableSerializer().create().fromJson(json, LootTable.class);
    }

    // Injected into mod code by ClassAnalysingTransformer
    @SuppressWarnings("unused")
    public static GenericBuilder<?, ?> useModConfigResource(FileConfigBuilder builder, String resource) {
        URL url = ConnectorMod.class.getClassLoader().getResource(resource);
        return builder.onFileNotFound(FileNotFoundAction.copyData(url));
    }

    @SuppressWarnings("unused")
    public static InteractionHand itemToHand(Player player, ItemStack stack) {
        return ItemStack.isSameItemSameTags(stack, player.getItemInHand(InteractionHand.MAIN_HAND)) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    @SuppressWarnings("deprecation")
    public static void unfreezeRegistries() {
        // 1.18.2 note: 1.19.3+ exposes a single `BuiltInRegistries.REGISTRY` root that has to be
        // unfrozen, plus each child registry. On 1.18.2 the root registry is `Registry.REGISTRY`
        // and it is not `Iterable`, so children are walked through `stream()`.
        ((MappedRegistry<?>) Registry.REGISTRY).unfreeze();
        Registry.REGISTRY.stream().forEach(registry -> ((MappedRegistry<?>) registry).unfreeze());
        preventFreeze = true;
    }

    public static boolean preventFreeze() {
        return preventFreeze;
    }
}
