package org.sinytra.connector.mod.compat;

import com.google.common.base.Stopwatch;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.Tag;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites Fabric's {@code c:} convention tags into Forge's tag naming so that Forge mods
 * looking for {@code forge:ingots/copper} also see Fabric's {@code c:copper_ingots}.
 *
 * <p><b>1.18.2 port note — the data model changed.</b> 1.20.1's {@code TagLoader.load}
 * returns {@code Map<ResourceLocation, List<TagLoader.EntryWithSource>>}, i.e. a flat list
 * of entries per tag which this class freely cleared and re-populated. 1.18.2 returns
 * {@code Map<ResourceLocation, Tag.Builder>} instead, so every mutation has to go through
 * the {@code Tag.Builder} API:
 * <ul>
 *   <li>{@code TagLoader.EntryWithSource} → {@code Tag.BuilderEntry} (same record shape:
 *       {@code entry()} / {@code source()}), obtained from {@code Tag.Builder#getEntries()}.</li>
 *   <li>{@code TagEntry} (one record with {@code getId()/isTag()/isRequired()}) → four
 *       classes: {@code Tag.TagEntry}, {@code Tag.ElementEntry}, {@code Tag.OptionalTagEntry},
 *       {@code Tag.OptionalElementEntry}. Only {@code Tag.TagEntry} kept a public
 *       {@code getId()}; the other three are read through fields widened by
 *       {@code accesstransformer.cfg}. See {@link #entryId(Tag.Entry)}.</li>
 *   <li>{@code new TagEntry(name, isTag, isRequired)} → the matching concrete class.</li>
 *   <li>{@code Tag.Builder} has no {@code clear()}, so the Fabric tag's body is dropped by
 *       publishing a fresh builder under the same key rather than emptying a list.</li>
 * </ul>
 */
public final class TagConverter {
    private static final String FABRIC_NAMESPACE = "c";
    private static final Pattern RAW_ORES_PATTERN = Pattern.compile("^raw_(.+?)_ores$");
    private static final String TAG_ENTRY_SPLITTER = "_(?!.*_)";
    private static final Collection<String> COMMON_TYPES = Set.of("small_dusts");
    private static final Collection<String> COMMON_GROUP_PREFIXES = Set.of("tools");
    private static final Collection<String> COMMON_GROUP_PREFIXES_NO_ENTRYPATH = Set.of("gems");
    private static final Map<String, String> ALIASES = Map.of(
        "blocks", "storage_blocks",
        "raw_ores", "raw_materials"
    );
    private static final Map<String, Pair<String, @Nullable String>> FORGE_TAG_CACHE = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void postProcessTags(Map<ResourceLocation, Tag.Builder> tags) {
        int counter = 0;
        Stopwatch stopwatch = Stopwatch.createStarted();
        Collection<ResourceLocation> existing = tags.keySet();
        // Snapshot first: we keep mutating `tags` (adding the generated forge tags, replacing
        // the fabric tags) while iterating, and Tag.Builder bodies must be read as originally
        // loaded, not as we leave them behind.
        Map<ResourceLocation, Tag.Builder> copy = new HashMap<>(tags);
        for (Map.Entry<ResourceLocation, Tag.Builder> entry : copy.entrySet()) {
            ResourceLocation name = entry.getKey();
            if (!isFabricTag(name)) {
                continue;
            }
            ResourceLocation newName = getNormalizedTagName(name.getPath(), existing);
            LOGGER.trace("Converting tag {} to tag {}", name, newName);
            Tag.Builder newEntries = tags.computeIfAbsent(newName, loc -> Tag.Builder.tag());
            entry.getValue().getEntries().forEach(tagEntry -> {
                Tag.Entry rawEntry = tagEntry.entry();
                ResourceLocation entryName = entryId(rawEntry);
                if (entryName == null) {
                    // Unknown entry shape - keep it verbatim rather than dropping content.
                    newEntries.add(rawEntry, tagEntry.source());
                }
                else if (isFabricTag(entryName)) {
                    ResourceLocation newEntryName = getNormalizedTagName(entryName.getPath(), existing);
                    // Don't make the generated tag reference itself.
                    if (!newName.equals(newEntryName)) {
                        newEntries.add(retargetEntry(rawEntry, newEntryName), tagEntry.source());
                    }
                }
                else if (!newName.equals(entryName)) {
                    newEntries.add(rawEntry, tagEntry.source());
                }
            });
            // Replace the Fabric tag's body with a single pointer at the generated Forge tag:
            // `c:copper_ingots` becomes an alias of `forge:ingots/copper`.
            //
            // Tag.Builder exposes no clear(), so we publish a brand new builder. The original
            // body (and with it any `replace: true` flag / `remove` list) is intentionally
            // discarded - that mirrors 1.20.1, where the entry list was cleared outright.
            Tag.Builder alias = Tag.Builder.tag();
            alias.addTag(newName, "connector");
            tags.put(name, alias);
            counter++;
        }
        stopwatch.stop();
        if (counter > 0) {
            LOGGER.debug("Converted {} tags in {} ms", counter, stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    /**
     * 1.18.2 replacement for 1.20.1's {@code TagEntry#getId()}.
     *
     * <p>{@code Tag.TagEntry} still has a getter. The other three subclasses do not, and
     * because they are the ones that make {@code c:} → {@code forge:} conversion work (the
     * element entries are the payload being moved into the generated tag), we cannot simply
     * skip them. Their {@code id} fields are widened in {@code accesstransformer.cfg}.
     *
     * @return the referenced id, or {@code null} if the entry is of an unrecognised shape.
     */
    @Nullable
    private static ResourceLocation entryId(Tag.Entry entry) {
        if (entry instanceof Tag.TagEntry tagEntry) {
            return tagEntry.getId();
        }
        if (entry instanceof Tag.ElementEntry elementEntry) {
            return elementEntry.id;
        }
        if (entry instanceof Tag.OptionalElementEntry optionalElementEntry) {
            return optionalElementEntry.id;
        }
        if (entry instanceof Tag.OptionalTagEntry optionalTagEntry) {
            return optionalTagEntry.id;
        }
        return null;
    }

    /**
     * Rebuilds a tag entry of the same kind (required/optional, element/tag) pointing at a
     * new location. 1.20.1 did this with {@code new TagEntry(name, isTag, isRequired)};
     * 1.18.2 needs the concrete class picked explicitly.
     */
    private static Tag.Entry retargetEntry(Tag.Entry original, ResourceLocation newName) {
        if (original instanceof Tag.TagEntry) {
            return new Tag.TagEntry(newName);
        }
        if (original instanceof Tag.OptionalTagEntry) {
            return new Tag.OptionalTagEntry(newName);
        }
        if (original instanceof Tag.ElementEntry) {
            return new Tag.ElementEntry(newName);
        }
        if (original instanceof Tag.OptionalElementEntry) {
            return new Tag.OptionalElementEntry(newName);
        }
        return original;
    }

    public static ResourceLocation getNormalizedTagName(String path, Collection<ResourceLocation> existing) {
        Pair<String, @Nullable String> newPath = getForgeTagName(path);
        String group = newPath.getFirst();
        String entryPath = newPath.getSecond();
        // Check for common groups (c:bows -> forge:tools/bows)
        if (entryPath == null) {
            for (String prefix : COMMON_GROUP_PREFIXES) {
                ResourceLocation tag = new ResourceLocation("forge", prefix + "/" + group);
                if (existing.contains(tag)) {
                    LOGGER.debug("Found existing prefixed forge tag {}", tag);
                    return tag;
                }
            }
            for (String prefix : COMMON_GROUP_PREFIXES_NO_ENTRYPATH) {
                // Handle plural to singular tag names (c:diamonds -> forge:gems/diamond)
                // This will handle existing tags, but won't be able to detect nonexisted tag names like c:rubies -> forge:gems/ruby
                // Well, it's better than nothing I guess
                if (group.endsWith("s")) {
                    ResourceLocation singularTag = new ResourceLocation("forge", prefix + "/" + group.substring(0, group.length() - 1));
                    if (existing.contains(singularTag)) {
                        LOGGER.debug("Found existing singular prefixed forge tag {}", singularTag);
                        return singularTag;
                    }
                }
            }
        }
        String tagPath = group + (entryPath != null ? "/" + entryPath : "");
        // Prefer vanilla tags if they exist (c:axes -> vanilla:axes)
        ResourceLocation vanillaTag = new ResourceLocation(tagPath);
        if (existing.contains(vanillaTag)) {
            LOGGER.debug("Found existing vanilla tag {}", vanillaTag);
            return vanillaTag;
        }
        // Fallback
        ResourceLocation forgeTag = new ResourceLocation("forge", tagPath);
        if (!existing.contains(forgeTag)) {
            LOGGER.debug("Creating new forge tag {}", forgeTag);
        }
        return forgeTag;
    }

    public static Pair<String, @Nullable String> getForgeTagName(String path) {
        return FORGE_TAG_CACHE.computeIfAbsent(path, TagConverter::computeForgeTagName);
    }

    private static Pair<String, @Nullable String> computeForgeTagName(String path) {
        // Group aliases
        for (Map.Entry<String, String> entry : ALIASES.entrySet()) {
            if (entry.getKey().equals(path)) {
                return Pair.of(entry.getValue(), null);
            }
        }
        // Special cases
        Matcher matcher = RAW_ORES_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Pair.of("raw_materials", matcher.group(1));
        }
        // Generic conversion
        else if (path.contains("_")) {
            // Find common types that consist of multiple words
            for (String common : COMMON_TYPES) {
                if (path.endsWith(common)) {
                    return Pair.of(common, path.replace("_" + common, ""));
                }
            }
            // Split on last occurence of '_'
            String[] parts = path.split(TAG_ENTRY_SPLITTER);
            // Find alias for group
            String group = ALIASES.getOrDefault(parts[1], parts[1]);
            // Convert to forge naming (raw
            return Pair.of(group, parts[0]);
        }
        // Group tag (c:ingots -> forge:ingots)
        return Pair.of(ALIASES.getOrDefault(path, path), null);
    }

    private static boolean isFabricTag(ResourceLocation location) {
        return location.getNamespace().equals(FABRIC_NAMESPACE);
    }

    private TagConverter() {}
}
