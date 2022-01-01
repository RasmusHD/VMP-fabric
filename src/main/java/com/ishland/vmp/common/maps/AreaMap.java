package com.ishland.vmp.common.maps;

import com.ishland.vmp.common.util.SimpleObjectPool;
import io.papermc.paper.util.MCUtil;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenCustomHashSet;

import java.util.Collections;
import java.util.Set;

public class AreaMap<T> {

    private static <E> Hash.Strategy<E> makeIdentityHashCodeStrategy() {
        return new Hash.Strategy<>() {
            @Override
            public int hashCode(E o) {
                return System.identityHashCode(o);
            }

            @Override
            public boolean equals(E a, E b) {
                return a == b;
            }
        };
    }

    private static final Object[] EMPTY = new Object[0];

    private final SimpleObjectPool<RawObjectLinkedOpenIdentityHashSet<T>> pooledHashSets = new SimpleObjectPool<>(unused -> new RawObjectLinkedOpenIdentityHashSet<>(), RawObjectLinkedOpenIdentityHashSet::clear, 8192);
    private final Long2ObjectFunction<RawObjectLinkedOpenIdentityHashSet<T>> allocHashSet = unused -> pooledHashSets.alloc();
    private final Long2ObjectOpenHashMap<RawObjectLinkedOpenIdentityHashSet<T>> map = new Long2ObjectOpenHashMap<>();
    private final Object2IntOpenCustomHashMap<T> viewDistances = new Object2IntOpenCustomHashMap<>(makeIdentityHashCodeStrategy());
    private final Object2LongOpenCustomHashMap<T> lastCenters = new Object2LongOpenCustomHashMap<>(makeIdentityHashCodeStrategy());

    private Listener<T> addListener = null;
    private Listener<T> removeListener = null;

    public AreaMap() {
        this(null, null);
    }

    public AreaMap(Listener<T> addListener, Listener<T> removeListener) {
        this.addListener = addListener;
        this.removeListener = removeListener;
    }

    public Set<T> getObjectsInRange(long coordinateKey) {
        final RawObjectLinkedOpenIdentityHashSet<T> set = map.get(coordinateKey);
        return set != null ? set : Collections.emptySet();
    }

    public Object[] getObjectsInRangeArray(long coordinateKey) {
        final RawObjectLinkedOpenIdentityHashSet<T> set = map.get(coordinateKey);
        return set != null ? set.getRawSet() : EMPTY;
    }

    public void add(T object, int x, int z, int rawViewDistance) {
        int viewDistance = rawViewDistance - 1;
        viewDistances.put(object, viewDistance);
        lastCenters.put(object, MCUtil.getCoordinateKey(x, z));
        for (int xx = x - viewDistance; xx <= x + viewDistance; xx++) {
            for (int zz = z - viewDistance; zz <= z + viewDistance; zz++) {
                add0(xx, zz, object);
            }
        }

        validate(object, x, z, viewDistance);
    }

    public void remove(T object) {
        if (!viewDistances.containsKey(object)) return;
        final int viewDistance = viewDistances.removeInt(object);
        final long lastCenter = lastCenters.removeLong(object);
        final int x = MCUtil.getCoordinateX(lastCenter);
        final int z = MCUtil.getCoordinateZ(lastCenter);
        for (int xx = x - viewDistance; xx <= x + viewDistance; xx++) {
            for (int zz = z - viewDistance; zz <= z + viewDistance; zz++) {
                remove0(xx, zz, object);
            }
        }
        validate(object, -1, -1, -1);
    }

    public void update(T object, int x, int z, int rawViewDistance) {
        if (!viewDistances.containsKey(object))
            throw new IllegalArgumentException("Tried to update %s when not in map".formatted(object));
        final int viewDistance = rawViewDistance - 1;
        final int oldViewDistance = viewDistances.replace(object, viewDistance);
        final long oldCenter = lastCenters.replace(object, MCUtil.getCoordinateKey(x, z));
        final int oldX = MCUtil.getCoordinateX(oldCenter);
        final int oldZ = MCUtil.getCoordinateZ(oldCenter);

        updateAdds(object, oldX, oldZ, oldViewDistance, x, z, viewDistance);
        updateRemovals(object, oldX, oldZ, oldViewDistance, x, z, viewDistance);

        validate(object, x, z, viewDistance);
    }

    private void updateAdds(T object, int oldX, int oldZ, int oldViewDistance, int newX, int newZ, int newViewDistance) {
        int xLower = oldX - oldViewDistance;
        int xHigher = oldX + oldViewDistance;
        int zLower = oldZ - oldViewDistance;
        int zHigher = oldZ + oldViewDistance;

        for (int xx = newX - newViewDistance; xx <= newX + newViewDistance; xx ++) {
            for (int zz = newZ - newViewDistance; zz <= newZ + newViewDistance; zz ++) {
                if (!isInRange(xLower, xHigher, zLower, zHigher, xx, zz)) {
                    add0(xx, zz, object);
                }
            }
        }
    }

    private void updateRemovals(T object, int oldX, int oldZ, int oldViewDistance, int newX, int newZ, int newViewDistance) {
        int xLower = newX - newViewDistance;
        int xHigher = newX + newViewDistance;
        int zLower = newZ - newViewDistance;
        int zHigher = newZ + newViewDistance;

        for (int xx = oldX - oldViewDistance; xx <= oldX + oldViewDistance; xx ++) {
            for (int zz = oldZ - oldViewDistance; zz <= oldZ + oldViewDistance; zz ++) {
                if (!isInRange(xLower, xHigher, zLower, zHigher, xx, zz)) {
                    remove0(xx, zz, object);
                }
            }
        }
    }

    private void add0(int xx, int zz, T object) {
        final RawObjectLinkedOpenIdentityHashSet<T> set = map.computeIfAbsent(MCUtil.getCoordinateKey(xx, zz), allocHashSet);
        set.add(object);
        if (this.addListener != null) this.addListener.accept(object, xx, zz);
    }

    private void remove0(int xx, int zz, T object) {
        final long coordinateKey = MCUtil.getCoordinateKey(xx, zz);
        final RawObjectLinkedOpenIdentityHashSet<T> set = map.get(coordinateKey);
        if (set == null)
            throw new IllegalStateException("Expect non-null set in [%d,%d]".formatted(xx, zz));
        if (!set.remove(object))
            throw new IllegalStateException("Expect %s in %s ([%d,%d])".formatted(object, set, xx, zz));
        if (set.isEmpty()) {
            map.remove(coordinateKey);
            pooledHashSets.release(set);
        }
        if (this.removeListener != null) this.removeListener.accept(object, xx, zz);
    }

    private boolean isInRange(int xLower, int xHigher, int zLower, int zHigher, int x, int z) {
        return x >= xLower && x <= xHigher && z >= zLower && z <= zHigher;
    }

    // only for debugging
    private void validate(T object, int x, int z, int viewDistance) {
        if (viewDistance < 0) {
            for (Long2ObjectMap.Entry<RawObjectLinkedOpenIdentityHashSet<T>> entry : map.long2ObjectEntrySet()) {
                if (entry.getValue().contains(object))
                    throw new IllegalStateException("Unexpected %s in %s ([%d,%d])".formatted(object, entry.getValue(), MCUtil.getCoordinateX(entry.getLongKey()), MCUtil.getCoordinateZ(entry.getLongKey())));
            }
        } else {
            for (int xx = x - viewDistance; xx <= x + viewDistance; xx++) {
                for (int zz = z - viewDistance; zz <= z + viewDistance; zz++) {
                    final long coordinateKey = MCUtil.getCoordinateKey(xx, zz);
                    final RawObjectLinkedOpenIdentityHashSet<T> set = map.get(coordinateKey);
                    if (set == null)
                        throw new IllegalStateException("Expect non-null set in [%d,%d]".formatted(xx, zz));
                    if (!set.contains(object))
                        throw new IllegalStateException("Expect %s in %s ([%d,%d])".formatted(object, set, xx, zz));
                }
            }
        }
    }

    private static class RawObjectLinkedOpenIdentityHashSet<E> extends ObjectLinkedOpenCustomHashSet<E> {

        public RawObjectLinkedOpenIdentityHashSet() {
            super(makeIdentityHashCodeStrategy());
        }

        public Object[] getRawSet() {
            return this.key;
        }

    }

    public interface Listener<T> {
        void accept(T object, int x, int z);
    }

}