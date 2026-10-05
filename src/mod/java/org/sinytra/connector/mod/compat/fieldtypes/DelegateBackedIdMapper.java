package org.sinytra.connector.mod.compat.fieldtypes;

import net.minecraft.core.IdMapper;
import net.minecraftforge.registries.IRegistryDelegate;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * An {@link IdMapper} view over the {@code Map<IRegistryDelegate<T>, V>} shapes that 1.18.2 uses
 * for {@code BlockColors} / {@code ItemColors}.
 *
 * <p>1.20.1 routed these lookups through {@link RedirectingIdMapper}, which could freely store
 * whatever key it was handed because the backing map was keyed by {@code Holder.Reference}. On
 * 1.18.2 that does not work: {@code net.minecraftforge.registries.RegistryDelegate} is package
 * private, so Connector cannot fabricate a delegate for an entry it has not seen. Reads are
 * therefore resolved by scanning the backing map, and {@link #addMapping(Object, int)} is only
 * accepted for values the registry already knows about.
 */
public class DelegateBackedIdMapper<T, V> extends IdMapper<V> {
    private final Map<IRegistryDelegate<T>, V> backing;
    private final IntFunction<T> byId;
    private final Function<T, Integer> idOf;

    public DelegateBackedIdMapper(Map<IRegistryDelegate<T>, V> backing, IntFunction<T> byId, Function<T, Integer> idOf) {
        this.backing = backing;
        this.byId = byId;
        this.idOf = idOf;
    }

    @Override
    public void addMapping(@NotNull V value, int id) {
        IRegistryDelegate<T> key = keyFor(this.byId.apply(id));
        if (key == null) {
            throw new UnsupportedOperationException("Cannot register a value for unknown registry entry id " + id);
        }
        this.backing.put(key, value);
    }

    @Override
    public void add(@NotNull V value) {
        throw new UnsupportedOperationException("Cannot append to a registry delegate backed map");
    }

    @Override
    public int getId(@NotNull V value) {
        for (Map.Entry<IRegistryDelegate<T>, V> entry : this.backing.entrySet()) {
            if (Objects.equals(entry.getValue(), value)) {
                Integer id = this.idOf.apply(entry.getKey().get());
                return id != null ? id : DEFAULT;
            }
        }
        return DEFAULT;
    }

    @Override
    public V byId(int id) {
        IRegistryDelegate<T> key = keyFor(this.byId.apply(id));
        return key != null ? this.backing.get(key) : null;
    }

    @Override
    public Iterator<V> iterator() {
        return this.backing.values().iterator();
    }

    @Override
    public boolean contains(int id) {
        return keyFor(this.byId.apply(id)) != null;
    }

    @Override
    public int size() {
        return this.backing.size();
    }

    private IRegistryDelegate<T> keyFor(T value) {
        if (value == null) {
            return null;
        }
        for (IRegistryDelegate<T> key : this.backing.keySet()) {
            if (key.get() == value) {
                return key;
            }
        }
        return null;
    }
}
