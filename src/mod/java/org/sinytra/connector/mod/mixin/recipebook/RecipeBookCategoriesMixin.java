package org.sinytra.connector.mod.mixin.recipebook;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.minecraft.client.RecipeBookCategories;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.Map;

import static net.minecraft.client.RecipeBookCategories.*;

@Mixin(RecipeBookCategories.class)
public class RecipeBookCategoriesMixin {
}
