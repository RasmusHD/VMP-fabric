package com.ishland.vmp.common.playerwatching;

import com.ishland.vmp.common.maps.AreaMap;
import com.ishland.vmp.common.util.SimpleObjectPool;
import com.ishland.vmp.mixins.access.IThreadedAnvilChunkStorageEntityTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.objects.Object2ObjectFunction;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.Set;

public class NearbyEntityTracking {

    private final SimpleObjectPool<ReferenceLinkedOpenHashSet<?>> pooledHashSets =
            new SimpleObjectPool<>(unused -> new ReferenceLinkedOpenHashSet<>(),
                    ReferenceLinkedOpenHashSet::clear,
                    ts -> {
                        ts.clear();
                        ts.trim(4);
                    },
                    8192
            );

    private final AreaMap<ThreadedAnvilChunkStorage.EntityTracker> areaMap = new AreaMap<>(
            (object, x, z) -> {
            },
            (object, x, z) -> {
            },
            false
    );

    private final Reference2ReferenceLinkedOpenHashMap<ServerPlayerEntity, ReferenceLinkedOpenHashSet<ThreadedAnvilChunkStorage.EntityTracker>> playerTrackers = new Reference2ReferenceLinkedOpenHashMap<>();
    private final Reference2LongOpenHashMap<ThreadedAnvilChunkStorage.EntityTracker> tracker2ChunkPos = new Reference2LongOpenHashMap<>();

    public void addEntityTracker(ThreadedAnvilChunkStorage.EntityTracker tracker) {
        final ChunkSectionPos trackedSection = ((IThreadedAnvilChunkStorageEntityTracker) tracker).getTrackedSection();
        this.areaMap.add(
                tracker,
                trackedSection.getX(),
                trackedSection.getZ(),
                getChunkViewDistance(tracker)
        );
        this.tracker2ChunkPos.put(tracker, ((IThreadedAnvilChunkStorageEntityTracker) tracker).getEntity().getChunkPos().toLong());
    }

    public void removeEntityTracker(ThreadedAnvilChunkStorage.EntityTracker tracker) {
        this.areaMap.remove(tracker);
        this.tracker2ChunkPos.removeLong(tracker);
    }

    public void addPlayer(ServerPlayerEntity player) {
        this.playerTrackers.put(player, (ReferenceLinkedOpenHashSet<ThreadedAnvilChunkStorage.EntityTracker>) this.pooledHashSets.alloc());
    }

    public void removePlayer(ServerPlayerEntity player) {
        final ReferenceLinkedOpenHashSet<ThreadedAnvilChunkStorage.EntityTracker> originalTrackers = this.playerTrackers.remove(player);
        if (originalTrackers != null) {
            for (ThreadedAnvilChunkStorage.EntityTracker tracker : originalTrackers) {
                tracker.stopTracking(player);
            }
            this.pooledHashSets.release(originalTrackers);
        }
    }

    public void tick() {
        for (Reference2LongMap.Entry<ThreadedAnvilChunkStorage.EntityTracker> entry : this.tracker2ChunkPos.reference2LongEntrySet()) {
            final ChunkPos pos = ((IThreadedAnvilChunkStorageEntityTracker) entry.getKey()).getEntity().getChunkPos();
            if (pos.toLong() != entry.getLongValue()) {
                this.areaMap.update(entry.getKey(), pos.x, pos.z, getChunkViewDistance(entry.getKey()));
                entry.setValue(pos.toLong());
            }
        }

        var trackerTickList = new ReferenceLinkedOpenHashSet<ThreadedAnvilChunkStorage.EntityTracker>(this.areaMap.uniqueObjects());
        for (var entry : this.playerTrackers.entrySet()) {
            final Set<ThreadedAnvilChunkStorage.EntityTracker> currentTrackers = this.areaMap.getObjectsInRange(entry.getKey().getChunkPos().toLong());

            boolean isPlayerPositionUpdated = ((ServerPlayerEntityExtension) entry.getKey()).vmpTracking$isPositionUpdated();
            ((ServerPlayerEntityExtension) entry.getKey()).vmpTracking$updatePosition();

            // update original trackers
            final ReferenceLinkedOpenHashSet<ThreadedAnvilChunkStorage.EntityTracker> trackers = entry.getValue();
            for (ObjectListIterator<ThreadedAnvilChunkStorage.EntityTracker> iterator = trackers.iterator(); iterator.hasNext(); ) {
                ThreadedAnvilChunkStorage.EntityTracker entityTracker = iterator.next();
                if (currentTrackers.contains(entityTracker)) {
                    if (isPlayerPositionUpdated || ((EntityTrackerExtension) entityTracker).isPositionUpdated()) {
                        tryUpdateTracker(entityTracker, entry.getKey());
                    }
                    trackerTickList.add(entityTracker);
                } else {
                    entityTracker.stopTracking(entry.getKey());
                    iterator.remove();
                }
            }

            // update new trackers
            for (ThreadedAnvilChunkStorage.EntityTracker entityTracker : currentTrackers) {
                if (!trackers.contains(entityTracker)) {
                    if (isPlayerPositionUpdated || ((EntityTrackerExtension) entityTracker).isPositionUpdated()) {
                        tryUpdateTracker(entityTracker, entry.getKey());
                    }
                    trackerTickList.add(entityTracker);
                    trackers.add(entityTracker);
                }
            }
        }
        for (ThreadedAnvilChunkStorage.EntityTracker entityTracker : trackerTickList) {
            ((EntityTrackerExtension) entityTracker).tryTick();
            ((EntityTrackerExtension) entityTracker).updatePosition();
        }
    }

    private static void tryUpdateTracker(ThreadedAnvilChunkStorage.EntityTracker entityTracker, ServerPlayerEntity player) {
        entityTracker.updateTrackedStatus(player);
    }

    private int getChunkViewDistance(ThreadedAnvilChunkStorage.EntityTracker tracker) {
        return (int) Math.ceil(((IThreadedAnvilChunkStorageEntityTracker) tracker).invokeGetMaxTrackDistance() / 16.0) + 1;
    }

}
