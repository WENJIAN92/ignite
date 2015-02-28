/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.eviction.*;
import org.apache.ignite.internal.managers.deployment.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.extras.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.processors.dr.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.offheap.unsafe.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.expiry.*;
import javax.cache.processor.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.internal.processors.cache.CacheFlag.*;
import static org.apache.ignite.transactions.TransactionState.*;

/**
 * Adapter for cache entry.
 */
@SuppressWarnings({
    "NonPrivateFieldAccessedInSynchronizedContext", "TooBroadScope", "FieldAccessedSynchronizedAndUnsynchronized"})
public abstract class GridCacheMapEntry implements GridCacheEntryEx {
    /** */
    private static final sun.misc.Unsafe UNSAFE = GridUnsafe.unsafe();

    /** */
    private static final byte IS_DELETED_MASK = 0x01;

    /** */
    private static final byte IS_UNSWAPPED_MASK = 0x02;

    /** */
    public static final Comparator<GridCacheVersion> ATOMIC_VER_COMPARATOR = new GridCacheAtomicVersionComparator();

    /**
     * NOTE
     * ====
     * Make sure to recalculate this value any time when adding or removing fields from entry.
     * The size should be count as follows:
     * <ul>
     * <li>Primitives: byte/boolean = 1, short = 2, int/float = 4, long/double = 8</li>
     * <li>References: 8 each</li>
     * <li>Each nested object should be analyzed in the same way as above.</li>
     * </ul>
     */
    // TODO IGNITE-51.
    private static final int SIZE_OVERHEAD = 87 /*entry*/ + 32 /* version */;

    /** Static logger to avoid re-creation. Made static for test purpose. */
    protected static final AtomicReference<IgniteLogger> logRef = new AtomicReference<>();

    /** Logger. */
    protected static volatile IgniteLogger log;

    /** Cache registry. */
    @GridToStringExclude
    protected final GridCacheContext<?, ?> cctx;

    /** Key. */
    @GridToStringInclude
    protected final KeyCacheObject key;

    /** Value. */
    @GridToStringInclude
    protected CacheObject val;

    /** Start version. */
    @GridToStringInclude
    protected final long startVer;

    /** Version. */
    @GridToStringInclude
    protected GridCacheVersion ver;

    /** Next entry in the linked list. */
    @GridToStringExclude
    private volatile GridCacheMapEntry next0;

    /** Next entry in the linked list. */
    @GridToStringExclude
    private volatile GridCacheMapEntry next1;

    /** Key hash code. */
    @GridToStringInclude
    private final int hash;

    /** Off-heap value pointer. */
    private long valPtr;

    /** Extras */
    @GridToStringInclude
    private GridCacheEntryExtras extras;

    /**
     * Flags:
     * <ul>
     *     <li>Deleted flag - mask {@link #IS_DELETED_MASK}</li>
     *     <li>Unswapped flag - mask {@link #IS_UNSWAPPED_MASK}</li>
     * </ul>
     */
    @GridToStringInclude
    protected byte flags;

    /**
     * @param cctx Cache context.
     * @param key Cache key.
     * @param hash Key hash value.
     * @param val Entry value.
     * @param next Next entry in the linked list.
     * @param ttl Time to live.
     * @param hdrId Header id.
     */
    protected GridCacheMapEntry(GridCacheContext<?, ?> cctx, KeyCacheObject key, int hash, CacheObject val,
        GridCacheMapEntry next, long ttl, int hdrId) {
        log = U.logger(cctx.kernalContext(), logRef, GridCacheMapEntry.class);

        key = (KeyCacheObject)cctx.kernalContext().portable().detachPortable(key, cctx);

        assert key != null;

        this.key = key;
        this.hash = hash;
        this.cctx = cctx;

        ttlAndExpireTimeExtras(ttl, CU.toExpireTime(ttl));

        val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

        synchronized (this) {
            value(val, null);
        }

        next(hdrId, next);

        ver = cctx.versions().next();

        startVer = ver.order();
    }

    /** {@inheritDoc} */
    @Override public long startVersion() {
        return startVer;
    }

    /**
     * Sets entry value. If off-heap value storage is enabled, will serialize value to off-heap.
     *
     * @param val Value to store.
     * @param valBytes Value bytes to store.
     */
    protected void value(@Nullable CacheObject val, @Nullable byte[] valBytes) {
        assert Thread.holdsLock(this);

        // In case we deal with IGFS cache, count updated data
        if (cctx.cache().isIgfsDataCache() && cctx.kernalContext().igfsHelper().isIgfsBlockKey(key.value(cctx, false))) {
            int newSize = valueLength0(val, null);
            int oldSize = valueLength0(this.val, this.val == null ? valueBytes0() : null);

            int delta = newSize - oldSize;

            if (delta != 0 && !cctx.isNear())
                cctx.cache().onIgfsDataSizeChanged(delta);
        }

        if (!isOffHeapValuesOnly()) {
            this.val = val;

            valPtr = 0;
        }
        else {
            try {
                if (cctx.kernalContext().config().isPeerClassLoadingEnabled()) {
                    if (val != null || valBytes != null) {
                        if (val == null)
                            val = cctx.marshaller().unmarshal(valBytes, cctx.deploy().globalLoader());

                        if (val != null)
                            cctx.gridDeploy().deploy(val.getClass(), val.getClass().getClassLoader());
                    }

                    if (U.p2pLoader(val)) {
                        cctx.deploy().addDeploymentContext(
                            new GridDeploymentInfoBean((GridDeploymentInfo)val.getClass().getClassLoader()));
                    }
                }

                GridUnsafeMemory mem = cctx.unsafeMemory();

                assert mem != null;

                if (val != null) {
                    boolean valIsByteArr = val.byteArray();

                    valPtr = mem.putOffHeap(valPtr, val.valueBytes(cctx), valIsByteArr);
                }
                else {
                    mem.removeOffHeap(valPtr);

                    valPtr = 0;
                }
            }
            catch (IgniteCheckedException e) {
                U.error(log, "Failed to deserialize value [entry=" + this + ", val=" + val + ']');

                throw new IgniteException(e);
            }
        }
    }

    /**
     * Isolated method to get length of IGFS block.
     *
     * @param val Value.
     * @param valBytes Value bytes.
     * @return Length of value.
     */
    private int valueLength(@Nullable byte[] val, GridCacheValueBytes valBytes) {
        assert valBytes != null;

        return val != null ? val.length : valBytes.isNull() ? 0 : valBytes.get().length - (valBytes.isPlain() ? 0 : 6);
    }

    /**
     * Isolated method to get length of IGFS block.
     *
     * @param val Value.
     * @param valBytes Value bytes.
     * @return Length of value.
     */
    private int valueLength0(@Nullable CacheObject val, @Nullable IgniteBiTuple<byte[], Boolean> valBytes) {
        byte[] bytes = val != null ? (byte[])val.value(cctx, false) : null;

        return bytes != null ? bytes.length :
            (valBytes == null) ? 0 : valBytes.get1().length - (valBytes.get2() ? 0 : 6);
    }

    /**
     * @return Value bytes.
     */
    protected GridCacheValueBytes valueBytesUnlocked() {
        assert Thread.holdsLock(this);

        if (!isOffHeapValuesOnly()) {
// TODO IGNITE-51.
//            if (valBytes != null)
//                return GridCacheValueBytes.marshaled(valBytes);

            try {
                if (valPtr != 0 && cctx.offheapTiered())
                    return offheapValueBytes();
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }
        else {
            if (valPtr != 0) {
                GridUnsafeMemory mem = cctx.unsafeMemory();

                assert mem != null;

                return mem.getOffHeap(valPtr);
            }
        }

        return GridCacheValueBytes.nil();
    }

    /** {@inheritDoc} */
    @Override public int memorySize() throws IgniteCheckedException {
// TODO IGNITE-51
//        byte[] kb;
//        GridCacheValueBytes vb;
//
//        CacheObject v;
//
//        int extrasSize;
//
//        synchronized (this) {
//            kb = keyBytes;
//            vb = valueBytesUnlocked();
//
//            v = val;
//
//            extrasSize = extrasSize();
//        }
//
//        if (kb == null || (vb.isNull() && v != null)) {
//            if (kb == null)
//                kb = CU.marshal(cctx.shared(), key);
//
//            if (vb.isNull())
//                vb = (v != null && v instanceof byte[]) ? GridCacheValueBytes.plain(v) :
//                    GridCacheValueBytes.marshaled(CU.marshal(cctx.shared(), v));
//
//            synchronized (this) {
//                if (keyBytes == null)
//                    keyBytes = kb;
//
//                // If value didn't change.
//                if (!isOffHeapValuesOnly() && valBytes == null && val == v && cctx.config().isStoreValueBytes())
//                    valBytes = vb.isPlain() ? null : vb.get();
//            }
//        }
//
//        return SIZE_OVERHEAD + extrasSize + kb.length + (vb.isNull() ? 0 : vb.get().length);
        return 0;
    }

    /** {@inheritDoc} */
    @Override public boolean isInternal() {
        return key.internal();
    }

    /** {@inheritDoc} */
    @Override public boolean isDht() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isLocal() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isNear() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isReplicated() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean detached() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public <K, V> GridCacheContext<K, V> context() {
        return (GridCacheContext<K, V>)cctx;
    }

    /** {@inheritDoc} */
    @Override public boolean isNew() throws GridCacheEntryRemovedException {
        assert Thread.holdsLock(this);

        checkObsolete();

        return isStartVersion();
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean isNewLocked() throws GridCacheEntryRemovedException {
        checkObsolete();

        return isStartVersion();
    }

    /**
     * @return {@code True} if start version.
     */
    public boolean isStartVersion() {
        return ver.nodeOrder() == cctx.localNode().order() && ver.order() == startVer;
    }

    /** {@inheritDoc} */
    @Override public boolean valid(long topVer) {
        return true;
    }

    /** {@inheritDoc} */
    @Override public int partition() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override public boolean partitionValid() {
        return true;
    }

    /** {@inheritDoc} */
    @Nullable @Override public GridCacheEntryInfo info() {
        GridCacheEntryInfo info = null;

        long time = U.currentTimeMillis();

        try {
            synchronized (this) {
                if (!obsolete()) {
                    info = new GridCacheEntryInfo();

                    info.key(key);
                    info.cacheId(cctx.cacheId());

                    long expireTime = expireTimeExtras();

                    boolean expired = expireTime != 0 && expireTime <= time;

                    info.ttl(ttlExtras());
                    info.expireTime(expireTime);
                    info.version(ver);
                    info.setNew(isStartVersion());
                    info.setDeleted(deletedUnlocked());

                    if (!expired) {
                        info.value(val);
// TODO IGNITE-51.
//                        info.value(cctx.kernalContext().config().isPeerClassLoadingEnabled() ?
//                            rawGetOrUnmarshalUnlocked(false) : val);
//
//                        GridCacheValueBytes valBytes = valueBytesUnlocked();
//
//                        if (!valBytes.isNull()) {
//                            if (valBytes.isPlain())
//                                info.value((V)valBytes.get());
//                            else
//                                info.valueBytes(valBytes.get());
//                        }
                   }
                }
            }
        }
        catch (Exception e) {
            throw new IgniteException("Failed to unmarshal object while creating entry info: " + this, e);
        }

        return info;
    }

    /** {@inheritDoc} */
    @Override public CacheObject unswap() throws IgniteCheckedException {
        return unswap(false, true);
    }

    /**
     * Unswaps an entry.
     *
     * @param ignoreFlags Whether to ignore swap flags.
     * @param needVal If {@code false} then do not to deserialize value during unswap.
     * @return Value.
     * @throws IgniteCheckedException If failed.
     */
    @Nullable @Override public CacheObject unswap(boolean ignoreFlags, boolean needVal) throws IgniteCheckedException {
        boolean swapEnabled = cctx.swap().swapEnabled() && (ignoreFlags || !cctx.hasFlag(SKIP_SWAP));

        if (!swapEnabled && !cctx.isOffHeapEnabled())
            return null;

        synchronized (this) {
            if (isStartVersion() && ((flags & IS_UNSWAPPED_MASK) == 0)) {
                GridCacheSwapEntry e;

                if (cctx.offheapTiered()) {
                    e = cctx.swap().readOffheapPointer(this);

                    if (e != null) {
                        if (e.offheapPointer() > 0) {
                            valPtr = e.offheapPointer();

                            if (needVal) {
                                CacheObject val = cctx.fromOffheap(valPtr, false);

                                e.value(val);
                            }
                        }
                        else { // Read from swap.
                            valPtr = 0;

                            // TODO IGNITE-51.
                            if (cctx.portableEnabled() && !e.valueIsByteArray())
                                e.valueBytes(null); // Clear bytes marshalled with portable marshaller.
                        }
                    }
                }
                else
                    e = detached() ? cctx.swap().read(this, true, true, true) : cctx.swap().readAndRemove(this);

                if (log.isDebugEnabled())
                    log.debug("Read swap entry [swapEntry=" + e + ", cacheEntry=" + this + ']');

                flags |= IS_UNSWAPPED_MASK;

                // If there is a value.
                if (e != null) {
                    long delta = e.expireTime() == 0 ? 0 : e.expireTime() - U.currentTimeMillis();

                    if (delta >= 0) {
                        CacheObject val = e.value();

                        val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

                        // Set unswapped value.
                        update(val, e.valueBytes(), e.expireTime(), e.ttl(), e.version());

                        // Must update valPtr again since update() will reset it.
                        if (cctx.offheapTiered() && e.offheapPointer() > 0)
                            valPtr = e.offheapPointer();

                        return val;
                    }
                    else
                        clearIndex(e.value());
                }
            }
        }

        return null;
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    private void swap() throws IgniteCheckedException {
        if (cctx.isSwapOrOffheapEnabled() && !deletedUnlocked() && hasValueUnlocked() && !detached()) {
            assert Thread.holdsLock(this);

            long expireTime = expireTimeExtras();

            if (expireTime > 0 && U.currentTimeMillis() >= expireTime) { // Don't swap entry if it's expired.
                // Entry might have been updated.
                if (cctx.offheapTiered()) {
                    cctx.swap().removeOffheap(key);

                    valPtr = 0;
                }

                return;
            }

            if (val == null && cctx.offheapTiered() && valPtr != 0) {
                if (log.isDebugEnabled())
                    log.debug("Value did not change, skip write swap entry: " + this);

                if (cctx.swap().offheapEvictionEnabled())
                    cctx.swap().enableOffheapEviction(key());

                return;
            }

            IgniteUuid valClsLdrId = null;

            if (val != null)
                valClsLdrId = cctx.deploy().getClassLoaderId(val.value(cctx, false).getClass().getClassLoader());

            IgniteBiTuple<byte[], Boolean> valBytes = valueBytes0();

            cctx.swap().write(key(),
                ByteBuffer.wrap(valBytes.get1()),
                valBytes.get2(),
                ver,
                ttlExtras(),
                expireTime,
                cctx.deploy().getClassLoaderId(U.detectObjectClassLoader(key.value(cctx, false))),
                valClsLdrId);

            if (log.isDebugEnabled())
                log.debug("Wrote swap entry: " + this);
        }
    }

    /**
     * @return Value bytes and flag indicating whether value is byte array.
     */
    private IgniteBiTuple<byte[], Boolean> valueBytes0() {
        if (valPtr != 0) {
            assert isOffHeapValuesOnly() || cctx.offheapTiered();

            return cctx.unsafeMemory().get(valPtr);
        }
        else {
            assert val != null;

            try {
                byte[] bytes = val.valueBytes(cctx);

                boolean plain = val.byteArray();

                return new IgniteBiTuple<>(bytes, plain);
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException(e);
            }
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    protected final void releaseSwap() throws IgniteCheckedException {
        if (cctx.isSwapOrOffheapEnabled()) {
            synchronized (this){
                cctx.swap().remove(key());
            }

            if (log.isDebugEnabled())
                log.debug("Removed swap entry [entry=" + this + ']');
        }
    }

    /**
     * @param tx Transaction.
     * @param key Key.
     * @param reload flag.
     * @param subjId Subject ID.
     * @param taskName Task name.
     * @return Read value.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable protected Object readThrough(@Nullable IgniteInternalTx tx, KeyCacheObject key, boolean reload, UUID subjId,
        String taskName) throws IgniteCheckedException {
        return cctx.store().loadFromStore(tx, key);
    }

    /** {@inheritDoc} */
    @Nullable @Override public final CacheObject innerGet(@Nullable IgniteInternalTx tx,
        boolean readSwap,
        boolean readThrough,
        boolean failFast,
        boolean unmarshal,
        boolean updateMetrics,
        boolean evt,
        boolean tmp,
        UUID subjId,
        Object transformClo,
        String taskName,
        @Nullable IgniteCacheExpiryPolicy expirePlc)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        cctx.denyOnFlag(LOCAL);

        return innerGet0(tx,
            readSwap,
            readThrough,
            evt,
            unmarshal,
            updateMetrics,
            tmp,
            subjId,
            transformClo,
            taskName,
            expirePlc);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "RedundantTypeArguments", "TooBroadScope"})
    private CacheObject innerGet0(IgniteInternalTx tx,
        boolean readSwap,
        boolean readThrough,
        boolean evt,
        boolean unmarshal,
        boolean updateMetrics,
        boolean tmp,
        UUID subjId,
        Object transformClo,
        String taskName,
        @Nullable IgniteCacheExpiryPolicy expiryPlc)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        // Disable read-through if there is no store.
        if (readThrough && !cctx.readThrough())
            readThrough = false;

        GridCacheMvccCandidate owner;

        CacheObject old;
        CacheObject ret = null;

        GridCacheVersion startVer;

        boolean expired = false;

        CacheObject expiredVal = null;

        boolean hasOldBytes;

        synchronized (this) {
            checkObsolete();

            // Cache version for optimistic check.
            startVer = ver;

            GridCacheMvcc mvcc = mvccExtras();

            owner = mvcc == null ? null : mvcc.anyOwner();

            double delta;

            long expireTime = expireTimeExtras();

            if (expireTime > 0) {
                delta = expireTime - U.currentTimeMillis();

                if (log.isDebugEnabled())
                    log.debug("Checked expiration time for entry [timeLeft=" + delta + ", entry=" + this + ']');

                if (delta <= 0)
                    expired = true;
            }

            CacheObject val = this.val;

            hasOldBytes = valPtr != 0;

            if ((unmarshal || isOffHeapValuesOnly()) && !expired && val == null && hasOldBytes)
                val = rawGetOrUnmarshalUnlocked(tmp);

            boolean valid = valid(tx != null ? tx.topologyVersion() : cctx.affinity().affinityTopologyVersion());

            // Attempt to load from swap.
            if (val == null && !hasOldBytes && readSwap) {
                // Only promote when loading initial state.
                if (isNew() || !valid) {
                    // If this entry is already expired (expiration time was too low),
                    // we simply remove from swap and clear index.
                    if (expired) {
                        releaseSwap();

                        // Previous value is guaranteed to be null
                        clearIndex(null);
                    }
                    else {
                        // Read and remove swap entry.
                        if (tmp) {
                            unswap(false, false);

                            val = rawGetOrUnmarshalUnlocked(true);
                        }
                        else
                            val = unswap();

                        // Recalculate expiration after swap read.
                        if (expireTime > 0) {
                            delta = expireTime - U.currentTimeMillis();

                            if (log.isDebugEnabled())
                                log.debug("Checked expiration time for entry [timeLeft=" + delta +
                                    ", entry=" + this + ']');

                            if (delta <= 0)
                                expired = true;
                        }
                    }
                }
            }

            old = expired || !valid ? null : val;

            if (expired) {
                expiredVal = val;

                value(null, null);
            }

            if (old == null && !hasOldBytes) {
                if (updateMetrics && cctx.cache().configuration().isStatisticsEnabled())
                    cctx.cache().metrics0().onRead(false);
            }
            else {
                if (updateMetrics && cctx.cache().configuration().isStatisticsEnabled())
                    cctx.cache().metrics0().onRead(true);

                // Set retVal here for event notification.
                ret = old;
            }

            if (evt && expired) {
                if (cctx.events().isRecordable(EVT_CACHE_OBJECT_EXPIRED)) {
                    cctx.events().addEvent(partition(),
                        key,
                        tx,
                        owner,
                        EVT_CACHE_OBJECT_EXPIRED,
                        null,
                        false,
                        expiredVal,
                        expiredVal != null || hasOldBytes,
                        subjId,
                        null,
                        taskName);
                }

                cctx.continuousQueries().onEntryExpired(this, key, expiredVal, null);

                // No more notifications.
                evt = false;
            }

            if (evt && !expired && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                cctx.events().addEvent(partition(), key, tx, owner, EVT_CACHE_OBJECT_READ, ret, ret != null, old,
                    hasOldBytes || old != null, subjId,
                    transformClo != null ? transformClo.getClass().getName() : null, taskName);

                // No more notifications.
                evt = false;
            }

            if (ret != null && expiryPlc != null)
                updateTtl(expiryPlc);
        }

        if (ret != null)
            // If return value is consistent, then done.
            return ret;

        boolean loadedFromStore = false;

        if (ret == null && readThrough) {
            IgniteInternalTx tx0 = null;

            if (tx != null && tx.local()) {
                if (cctx.isReplicated() || cctx.isColocated() || tx.near())
                    tx0 = tx;
                else if (tx.dht()) {
                    GridCacheVersion ver = tx.nearXidVersion();

                    tx0 = cctx.dht().near().context().tm().tx(ver);
                }
            }

            // TODO IGNITE-51.
            Object storeVal = readThrough(tx0, key, false, subjId, taskName);

            if (storeVal != null)
                ret = cctx.toCacheObject(storeVal);

            loadedFromStore = true;
        }

        synchronized (this) {
            long ttl = ttlExtras();

            // If version matched, set value.
            if (startVer.equals(ver)) {
                if (ret != null) {
                    // Detach value before index update.
                    ret = (CacheObject)cctx.kernalContext().portable().detachPortable(ret, cctx);

                    GridCacheVersion nextVer = nextVersion();

                    CacheObject prevVal = rawGetOrUnmarshalUnlocked(false);

                    long expTime = CU.toExpireTime(ttl);

                    if (loadedFromStore)
                        // Update indexes before actual write to entry.
                        updateIndex(ret, null, expTime, nextVer, prevVal);

                    boolean hadValPtr = valPtr != 0;

                    // Don't change version for read-through.
                    update(ret, null, expTime, ttl, nextVer);

                    if (hadValPtr && cctx.offheapTiered())
                        cctx.swap().removeOffheap(key);

                    if (cctx.deferredDelete() && deletedUnlocked() && !isInternal() && !detached())
                        deletedUnlocked(false);
                }

                if (evt && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ))
                    cctx.events().addEvent(partition(), key, tx, owner, EVT_CACHE_OBJECT_READ, ret, ret != null,
                        old, hasOldBytes, subjId, transformClo != null ? transformClo.getClass().getName() : null,
                        taskName);
            }
        }

        return ret;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "TooBroadScope"})
    @Nullable @Override public final CacheObject innerReload()
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        cctx.denyOnFlag(READ);

        CU.checkStore(cctx);

        GridCacheVersion startVer;

        boolean wasNew;

        synchronized (this) {
            checkObsolete();

            // Cache version for optimistic check.
            startVer = ver;

            wasNew = isNew();
        }

        String taskName = cctx.kernalContext().job().currentTaskName();

        // Check before load.
        CacheObject ret = cctx.toCacheObject(readThrough(null, key, true, cctx.localNodeId(), taskName));

        boolean touch = false;

        try {
            synchronized (this) {
                long ttl = ttlExtras();

                // Generate new version.
                GridCacheVersion nextVer = cctx.versions().nextForLoad(ver);

                // If entry was loaded during read step.
                if (wasNew && !isNew())
                    // Map size was updated on entry creation.
                    return ret;

                // If version matched, set value.
                if (startVer.equals(ver)) {
                    releaseSwap();

                    CacheObject old = rawGetOrUnmarshalUnlocked(false);

                    long expTime = CU.toExpireTime(ttl);

                    // Detach value before index update.
                    ret = (CacheObject)cctx.kernalContext().portable().detachPortable(ret, cctx);

                    // Update indexes.
                    if (ret != null) {
                        updateIndex(ret, null, expTime, nextVer, old);

                        if (cctx.deferredDelete() && !isInternal() && !detached() && deletedUnlocked())
                            deletedUnlocked(false);
                    }
                    else {
                        clearIndex(old);

                        if (cctx.deferredDelete() && !isInternal() && !detached() && !deletedUnlocked())
                            deletedUnlocked(true);
                    }

                    update(ret, null, expTime, ttl, nextVer);

                    touch = true;

                    // If value was set - return, otherwise try again.
                    return ret;
                }
            }

            touch = true;

            return ret;
        }
        finally {
            if (touch)
                cctx.evicts().touch(this, cctx.affinity().affinityTopologyVersion());
        }
    }

    /**
     * @param nodeId Node ID.
     */
    protected void recordNodeId(UUID nodeId) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public final GridCacheUpdateTxResult innerSet(
        @Nullable IgniteInternalTx tx,
        UUID evtNodeId,
        UUID affNodeId,
        CacheObject val,
        @Nullable byte[] valBytes,
        boolean writeThrough,
        boolean retval,
        long ttl,
        boolean evt,
        boolean metrics,
        long topVer,
        IgnitePredicate<Cache.Entry<Object, Object>>[] filter,
        GridDrType drType,
        long drExpireTime,
        @Nullable GridCacheVersion explicitVer,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException {
        CacheObject old;

        boolean valid = valid(tx != null ? tx.topologyVersion() : topVer);

        // Lock should be held by now.
        if (!cctx.isAll(this, filter))
            return new GridCacheUpdateTxResult(false, null);

        final GridCacheVersion newVer;

        boolean intercept = cctx.config().getInterceptor() != null;

        Object key0 = null;
        Object val0 = null;

        synchronized (this) {
            checkObsolete();

            if (cctx.kernalContext().config().isCacheSanityCheckEnabled()) {
                if (tx != null && tx.groupLock())
                    groupLockSanityCheck(tx);
                else
                    assert tx == null || (!tx.local() && tx.onePhaseCommit()) || tx.ownsLock(this) :
                        "Transaction does not own lock for update [entry=" + this + ", tx=" + tx + ']';
            }

            // Load and remove from swap if it is new.
            boolean startVer = isStartVersion();

            if (startVer)
                unswap(true, retval);

            newVer = explicitVer != null ? explicitVer : tx == null ?
                nextVersion() : tx.writeVersion();

            assert newVer != null : "Failed to get write version for tx: " + tx;

            old = (retval || intercept) ? rawGetOrUnmarshalUnlocked(!retval) : this.val;

            GridCacheValueBytes oldBytes = valueBytesUnlocked();

            if (intercept) {
                key0 = key.value(cctx, false);
                val0 = CU.value(val, cctx, false);

                Object interceptorVal = cctx.config().getInterceptor().onBeforePut(key0,
                    CU.value(old, cctx, false),
                    val0);

                if (interceptorVal == null)
                    return new GridCacheUpdateTxResult(false, (CacheObject)cctx.unwrapTemporary(old));
                else if (interceptorVal != val0) {
                    val = cctx.toCacheKeyObject(cctx.unwrapTemporary(interceptorVal));

                    valBytes = null;
                }
            }

            // Determine new ttl and expire time.
            long expireTime;

            if (drExpireTime >= 0) {
                assert ttl >= 0 : ttl;

                expireTime = drExpireTime;
            }
            else {
                if (ttl == -1L) {
                    ttl = ttlExtras();
                    expireTime = expireTimeExtras();
                }
                else
                    expireTime = CU.toExpireTime(ttl);
            }

            assert ttl >= 0 : ttl;
            assert expireTime >= 0 : expireTime;

            // Detach value before index update.
            val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

            // Update index inside synchronization since it can be updated
            // in load methods without actually holding entry lock.
            if (val != null || valBytes != null) {
                updateIndex(val, valBytes, expireTime, newVer, old);

                if (cctx.deferredDelete() && deletedUnlocked() && !isInternal() && !detached())
                    deletedUnlocked(false);
            }

            update(val, valBytes, expireTime, ttl, newVer);

            drReplicate(drType, val, valBytes, newVer);

            recordNodeId(affNodeId);

            if (metrics && cctx.cache().configuration().isStatisticsEnabled())
                cctx.cache().metrics0().onWrite();

            if (evt && newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_PUT)) {
                CacheObject evtOld = cctx.unwrapTemporary(old);

                cctx.events().addEvent(partition(),
                    key,
                    evtNodeId,
                    tx == null ? null : tx.xid(),
                    newVer,
                    EVT_CACHE_OBJECT_PUT,
                    val,
                    val != null,
                    evtOld,
                    evtOld != null || hasValueUnlocked(),
                    subjId, null, taskName);
            }

            if (cctx.isLocal() || cctx.isReplicated() || (tx != null && tx.local() && !isNear()))
                cctx.continuousQueries().onEntryUpdated(this, key, val, valueBytesUnlocked(), old, oldBytes, false);

            cctx.dataStructures().onEntryUpdated(key, false);
        }

        if (log.isDebugEnabled())
            log.debug("Updated cache entry [val=" + val + ", old=" + old + ", entry=" + this + ']');

        // Persist outside of synchronization. The correctness of the
        // value will be handled by current transaction.
        if (writeThrough)
            cctx.store().putToStore(tx, key, val, newVer);

        if (intercept)
            cctx.config().getInterceptor().onAfterPut(key0, val0);

        return valid ? new GridCacheUpdateTxResult(true, retval ? old : null) :
            new GridCacheUpdateTxResult(false, null);
    }

    /** {@inheritDoc} */
    @Override public final GridCacheUpdateTxResult innerRemove(
        @Nullable IgniteInternalTx tx,
        UUID evtNodeId,
        UUID affNodeId,
        boolean writeThrough,
        boolean retval,
        boolean evt,
        boolean metrics,
        long topVer,
        IgnitePredicate<Cache.Entry<Object, Object>>[] filter,
        GridDrType drType,
        @Nullable GridCacheVersion explicitVer,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException {
        assert cctx.transactional();

        CacheObject old;

        GridCacheVersion newVer;

        boolean valid = valid(tx != null ? tx.topologyVersion() : topVer);

        // Lock should be held by now.
        if (!cctx.isAll(this, filter))
            return new GridCacheUpdateTxResult(false, null);

        GridCacheVersion obsoleteVer = null;

        boolean intercept = cctx.config().getInterceptor() != null;

        IgniteBiTuple<Boolean, Object> interceptRes = null;

        Object key0 = null;
        Object old0 = null;

        synchronized (this) {
            checkObsolete();

            if (tx != null && tx.groupLock() && cctx.kernalContext().config().isCacheSanityCheckEnabled())
                groupLockSanityCheck(tx);
            else
                assert tx == null || (!tx.local() && tx.onePhaseCommit()) || tx.ownsLock(this) :
                    "Transaction does not own lock for remove[entry=" + this + ", tx=" + tx + ']';

            boolean startVer = isStartVersion();

            if (startVer) {
                // Release swap.
                releaseSwap();
            }

            newVer = explicitVer != null ? explicitVer : tx == null ? nextVersion() : tx.writeVersion();

            old = (retval || intercept) ? rawGetOrUnmarshalUnlocked(!retval) : val;

            if (intercept) {
                key0 = key.value(cctx, false);
                old0 = CU.value(old, cctx, false);

                interceptRes = cctx.config().getInterceptor().onBeforeRemove(key0, old0);

                if (cctx.cancelRemove(interceptRes)) {
                    CacheObject ret = cctx.toCacheKeyObject(cctx.unwrapTemporary(interceptRes.get2()));

                    return new GridCacheUpdateTxResult(false, ret);
                }
            }

            GridCacheValueBytes oldBytes = valueBytesUnlocked();

            if (old == null)
                old = saveValueForIndexUnlocked();

            // Clear indexes inside of synchronization since indexes
            // can be updated without actually holding entry lock.
            clearIndex(old);

            boolean hadValPtr = valPtr != 0;

            update(null, null, 0, 0, newVer);

            if (cctx.offheapTiered() && hadValPtr) {
                boolean rmv = cctx.swap().removeOffheap(key);

                assert rmv;
            }

            if (cctx.deferredDelete() && !detached() && !isInternal()) {
                if (!deletedUnlocked()) {
                    deletedUnlocked(true);

                    if (tx != null) {
                        GridCacheMvcc mvcc = mvccExtras();

                        if (mvcc == null || mvcc.isEmpty(tx.xidVersion()))
                            clearReaders();
                        else
                            clearReader(tx.originatingNodeId());
                    }
                }
            }

            drReplicate(drType, null, null, newVer);

            if (metrics && cctx.cache().configuration().isStatisticsEnabled())
                cctx.cache().metrics0().onRemove();

            if (tx == null)
                obsoleteVer = newVer;
            else {
                // Only delete entry if the lock is not explicit.
                if (tx.groupLock() || lockedBy(tx.xidVersion()))
                    obsoleteVer = tx.xidVersion();
                else if (log.isDebugEnabled())
                    log.debug("Obsolete version was not set because lock was explicit: " + this);
            }

            if (evt && newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED)) {
                CacheObject evtOld = cctx.unwrapTemporary(old);

                cctx.events().addEvent(partition(),
                    key,
                    evtNodeId,
                    tx == null ? null : tx.xid(), newVer,
                    EVT_CACHE_OBJECT_REMOVED,
                    null,
                    false,
                    evtOld,
                    evtOld != null || hasValueUnlocked(),
                    subjId,
                    null,
                    taskName);
            }

                if (cctx.isLocal() || cctx.isReplicated() || (tx != null && tx.local() && !isNear()))
                    cctx.continuousQueries().onEntryUpdated(this, key, null, null, old, oldBytes, false);

            cctx.dataStructures().onEntryUpdated(key, true);
        }

        // Persist outside of synchronization. The correctness of the
        // value will be handled by current transaction.
        if (writeThrough)
            cctx.store().removeFromStore(tx, key);

        if (!cctx.deferredDelete()) {
            boolean marked = false;

            synchronized (this) {
                // If entry is still removed.
                if (newVer == ver) {
                    if (obsoleteVer == null || !(marked = markObsolete0(obsoleteVer, true))) {
                        if (log.isDebugEnabled())
                            log.debug("Entry could not be marked obsolete (it is still used): " + this);
                    }
                    else {
                        recordNodeId(affNodeId);

                        // If entry was not marked obsolete, then removed lock
                        // will be registered whenever removeLock is called.
                        cctx.mvcc().addRemoved(cctx, obsoleteVer);

                        if (log.isDebugEnabled())
                            log.debug("Entry was marked obsolete: " + this);
                    }
                }
            }

            if (marked)
                onMarkedObsolete();
        }

        if (intercept)
            cctx.config().getInterceptor().onAfterRemove(key0, old0);

        if (valid) {
            CacheObject ret;

            if (interceptRes != null)
                ret = cctx.toCacheObject(cctx.unwrapTemporary(interceptRes.get2()));
            else
                ret = old;

            return new GridCacheUpdateTxResult(true, ret);
        }
        else
            return new GridCacheUpdateTxResult(false, null);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridTuple3<Boolean, CacheObject, EntryProcessorResult<Object>> innerUpdateLocal(
        GridCacheVersion ver,
        GridCacheOperation op,
        @Nullable Object writeObj,
        @Nullable Object[] invokeArgs,
        boolean writeThrough,
        boolean retval,
        @Nullable ExpiryPolicy expiryPlc,
        boolean evt,
        boolean metrics,
        @Nullable IgnitePredicate<Cache.Entry<Object, Object>>[] filter,
        boolean intercept,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException {
        assert cctx.isLocal() && cctx.atomic();

        CacheObject old;

        boolean res = true;

        IgniteBiTuple<Boolean, ?> interceptorRes = null;

        EntryProcessorResult<Object> invokeRes = null;

        synchronized (this) {
            boolean needVal = retval || intercept || op == GridCacheOperation.TRANSFORM || !F.isEmpty(filter);

            checkObsolete();

            // Load and remove from swap if it is new.
            if (isNew())
                unswap(true, retval);

            // Possibly get old value form store.
            old = needVal ? rawGetOrUnmarshalUnlocked(!retval) : val;

            GridCacheValueBytes oldBytes = valueBytesUnlocked();

            boolean readThrough = false;

            Object old0 = null;

            if (needVal && old == null &&
                (cctx.readThrough() && (op == GridCacheOperation.TRANSFORM || cctx.loadPreviousValue()))) {
                    old0 = readThrough(null, key, false, subjId, taskName);

                old = cctx.toCacheObject(old0);

                long ttl = CU.TTL_ETERNAL;
                long expireTime = CU.EXPIRE_TIME_ETERNAL;

                if (expiryPlc != null && old != null) {
                    ttl = CU.toTtl(expiryPlc.getExpiryForCreation());

                    if (ttl == CU.TTL_ZERO) {
                        ttl = CU.TTL_MINIMUM;
                        expireTime = CU.expireTimeInPast();
                    }
                    else if (ttl == CU.TTL_NOT_CHANGED)
                        ttl = CU.TTL_ETERNAL;
                    else
                        expireTime = CU.toExpireTime(ttl);
                }

                // Detach value before index update.
                old = (CacheObject)cctx.kernalContext().portable().detachPortable(old, cctx);

                if (old != null)
                    updateIndex(old, null, expireTime, ver, null);
                else
                    clearIndex(null);

                update(old, null, expireTime, ttl, ver);
            }

            // Apply metrics.
            if (metrics && cctx.cache().configuration().isStatisticsEnabled() && needVal) {
                // PutIfAbsent methods mustn't update hit/miss statistics
                if (op != GridCacheOperation.UPDATE || F.isEmpty(filter) || filter != cctx.noPeekArray())
                    cctx.cache().metrics0().onRead(old != null);
            }

            // Check filter inside of synchronization.
            if (!F.isEmpty(filter)) {
                boolean pass = cctx.isAll(wrapFilterLocked(), filter);

                if (!pass) {
                    if (expiryPlc != null && !readThrough && filter != cctx.noPeekArray() && hasValueUnlocked())
                        updateTtl(expiryPlc);

                    return new T3<>(false, retval ? old : null, null);
                }
            }

            String transformCloClsName = null;

            CacheObject updated;

            // Calculate new value.
            if (op == GridCacheOperation.TRANSFORM) {
                transformCloClsName = writeObj.getClass().getName();

                EntryProcessor<Object, Object, ?> entryProcessor = (EntryProcessor<Object, Object, ?>)writeObj;

                assert entryProcessor != null;

                // TODO IGNITE-51.
                CacheInvokeEntry<Object, Object> entry = new CacheInvokeEntry<>(cctx,
                    key.value(cctx, false),
                    old.value(cctx, false));

                try {
                    Object computed = entryProcessor.process(entry, invokeArgs);

                    if (entry.modified())
                        updated = cctx.toCacheObject(cctx.unwrapTemporary(entry.getValue()));
                    else
                        updated = old;

                    invokeRes = computed != null ? new CacheInvokeResult<>(cctx.unwrapTemporary(computed)) : null;
                }
                catch (Exception e) {
                    updated = old;

                    invokeRes = new CacheInvokeResult<>(e);
                }

                if (!entry.modified()) {
                    if (expiryPlc != null && !readThrough && hasValueUnlocked())
                        updateTtl(expiryPlc);

                    return new GridTuple3<>(false, null, invokeRes);
                }
            }
            else
                updated = (CacheObject)writeObj;

            op = updated == null ? GridCacheOperation.DELETE : GridCacheOperation.UPDATE;

            if (intercept) {
// TODO IGNITE-51.
//                if (op == GridCacheOperation.UPDATE) {
//                    updated = (V)cctx.config().getInterceptor().onBeforePut(key, old, updated);
//
//                    if (updated == null)
//                        return new GridTuple3<>(false, cctx.<V>unwrapTemporary(old), invokeRes);
//                }
//                else {
//                    interceptorRes = cctx.config().getInterceptor().onBeforeRemove(key, old);
//
//                    if (cctx.cancelRemove(interceptorRes))
//                        return new GridTuple3<>(false, cctx.<V>unwrapTemporary(interceptorRes.get2()), invokeRes);
//                }
            }

            boolean hadVal = hasValueUnlocked();

            long ttl = CU.TTL_ETERNAL;
            long expireTime = CU.EXPIRE_TIME_ETERNAL;

            if (op == GridCacheOperation.UPDATE) {
                if (expiryPlc != null) {
                    ttl = CU.toTtl(hadVal ? expiryPlc.getExpiryForUpdate() : expiryPlc.getExpiryForCreation());

                    if (ttl == CU.TTL_NOT_CHANGED) {
                        ttl = ttlExtras();
                        expireTime = expireTimeExtras();
                    }
                    else if (ttl != CU.TTL_ZERO)
                        expireTime = CU.toExpireTime(ttl);
                }
                else {
                    ttl = ttlExtras();
                    expireTime = expireTimeExtras();
                }
            }

            if (ttl == CU.TTL_ZERO)
                op = GridCacheOperation.DELETE;

            // Try write-through.
            if (op == GridCacheOperation.UPDATE) {
                // Detach value before index update.
                updated = (CacheObject)cctx.kernalContext().portable().detachPortable(updated, cctx);

                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().putToStore(null, key, updated, ver);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                updateIndex(updated, null, expireTime, ver, old);

                assert ttl != CU.TTL_ZERO;

                update(updated, null, expireTime, ttl, ver);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformCloClsName != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                        evtOld = cctx.unwrapTemporary(old);

                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null,
                            (GridCacheVersion)null, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformCloClsName, taskName);
                    }

                    if (cctx.events().isRecordable(EVT_CACHE_OBJECT_PUT)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(old);

                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null,
                            (GridCacheVersion)null, EVT_CACHE_OBJECT_PUT, updated, updated != null, evtOld,
                            evtOld != null || hadVal, subjId, null, taskName);
                    }
                }
            }
            else {
                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().removeFromStore(null, key);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                clearIndex(old);

                update(null, null, CU.TTL_ETERNAL, CU.EXPIRE_TIME_ETERNAL, ver);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformCloClsName != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ))
                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null,
                            (GridCacheVersion)null, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformCloClsName, taskName);

                    if (cctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(old);

                        cctx.events().addEvent(partition(), key, cctx.localNodeId(), null, (GridCacheVersion) null,
                            EVT_CACHE_OBJECT_REMOVED, null, false, evtOld, evtOld != null || hadVal, subjId, null,
                            taskName);
                    }
                }

                res = hadVal;
            }

            if (res)
                updateMetrics(op, metrics);

            cctx.continuousQueries().onEntryUpdated(this, key, val, valueBytesUnlocked(), old, oldBytes, false);

            cctx.dataStructures().onEntryUpdated(key, op == GridCacheOperation.DELETE);

            if (intercept) {
                if (op == GridCacheOperation.UPDATE)
                    cctx.config().getInterceptor().onAfterPut(key, val);
                else
                    cctx.config().getInterceptor().onAfterRemove(key, old);
            }
        }

        // TODO IGNITE-51.
        return new GridTuple3<>(res,
            cctx.<CacheObject>unwrapTemporary(interceptorRes != null ? interceptorRes.get2() : old),
            invokeRes);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridCacheUpdateAtomicResult innerUpdate(
        GridCacheVersion newVer,
        UUID evtNodeId,
        UUID affNodeId,
        GridCacheOperation op,
        @Nullable Object writeObj,
        @Nullable byte[] valBytes,
        @Nullable Object[] invokeArgs,
        boolean writeThrough,
        boolean retval,
        @Nullable IgniteCacheExpiryPolicy expiryPlc,
        boolean evt,
        boolean metrics,
        boolean primary,
        boolean verCheck,
        @Nullable IgnitePredicate<Cache.Entry<Object, Object>>[] filter,
        GridDrType drType,
        long explicitTtl,
        long explicitExpireTime,
        @Nullable GridCacheVersion conflictVer,
        boolean conflictResolve,
        boolean intercept,
        @Nullable UUID subjId,
        String taskName
    ) throws IgniteCheckedException, GridCacheEntryRemovedException, GridClosureException {
        assert cctx.atomic();

        boolean res = true;

        CacheObject oldVal;
        CacheObject updated;

        GridCacheVersion enqueueVer = null;

        GridCacheVersionConflictContext<?, ?> conflictCtx = null;

        CacheInvokeDirectResult invokeRes = null;

        // System TTL/ET which may have special values.
        long newSysTtl;
        long newSysExpireTime;

        // TTL/ET which will be passed to entry on update.
        long newTtl;
        long newExpireTime;

        synchronized (this) {
            boolean needVal = intercept || retval || op == GridCacheOperation.TRANSFORM || !F.isEmptyOrNulls(filter);

            checkObsolete();

            // Load and remove from swap if it is new.
            if (isNew())
                unswap(true, retval);

            Object transformClo = null;

            // Request-level conflict resolution is needed, i.e. we do not know who will win in advance.
            if (conflictResolve) {
                GridCacheVersion oldConflictVer = version().conflictVersion();

                // Cache is conflict-enabled.
                if (cctx.conflictNeedResolve()) {
// TODO IGNITE-51.
//                    // Get new value, optionally unmarshalling and/or transforming it.
//                    if (writeObj == null && valBytes != null)
//                        writeObj = cctx.marshaller().unmarshal(valBytes, cctx.deploy().globalLoader());
//
//                    if (op == GridCacheOperation.TRANSFORM) {
//                        transformClo = writeObj;
//
//                        writeObj = ((IgniteClosure<V, V>)writeObj).apply(rawGetOrUnmarshalUnlocked(true));
//                        valBytes = null;
//                    }
//
//                    GridTuple3<Long, Long, Boolean> expiration = ttlAndExpireTime(expiryPlc, explicitTtl,
//                        explicitExpireTime);
//
//                    // Prepare old and new entries for conflict resolution.
//                    GridCacheVersionedEntryEx oldEntry = versionedEntry();
//                    GridCacheVersionedEntryEx newEntry = new GridCachePlainVersionedEntry<>(key, (V)writeObj,
//                        expiration.get1(), expiration.get2(), conflictVer != null ? conflictVer : newVer);
//
//                    // Resolve conflict.
//                    conflictCtx = cctx.conflictResolve(oldEntry, newEntry, verCheck);
//
//                    assert conflictCtx != null;
//
//                    // Use old value?
//                    if (conflictCtx.isUseOld()) {
//                        GridCacheVersion newConflictVer = conflictVer != null ? conflictVer : newVer;
//
//                        // Handle special case with atomic comparator.
//                        if (!isNew() &&                                                           // Not initial value,
//                            verCheck &&                                                           // and atomic version check,
//                            oldConflictVer.dataCenterId() == newConflictVer.dataCenterId() &&     // and data centers are equal,
//                            ATOMIC_VER_COMPARATOR.compare(oldConflictVer, newConflictVer) == 0 && // and both versions are equal,
//                            cctx.writeThrough() &&                                                // and store is enabled,
//                            primary)                                                              // and we are primary.
//                        {
//                            V val = rawGetOrUnmarshalUnlocked(false);
//
//                            if (val == null) {
//                                assert deletedUnlocked();
//
//                                cctx.store().removeFromStore(null, key());
//                            }
//                            else
//                                cctx.store().putToStore(null, key(), val, ver);
//                        }
//
//                        return new GridCacheUpdateAtomicResult<>(false,
//                            retval ? rawGetOrUnmarshalUnlocked(false) : null,
//                            null,
//                            invokeRes,
//                            CU.TTL_ETERNAL,
//                            CU.EXPIRE_TIME_ETERNAL,
//                            null,
//                            null,
//                            false);
//                    }
//                    // Will update something.
//                    else {
//                        // Merge is a local update which override passed value bytes.
//                        if (conflictCtx.isMerge()) {
//                            writeObj = conflictCtx.mergeValue();
//                            valBytes = null;
//
//                            conflictVer = null;
//                        }
//                        else
//                            assert conflictCtx.isUseNew();
//
//                        // Update value is known at this point, so update operation type.
//                        op = writeObj != null ? GridCacheOperation.UPDATE : GridCacheOperation.DELETE;
//                    }
                }
                else
                    // Nullify conflict version on this update, so that we will use regular version during next updates.
                    conflictVer = null;
            }

            // Perform version check only in case there was no explicit conflict resolution.
            if (conflictCtx == null) {
                if (verCheck) {
                    if (!isNew() && ATOMIC_VER_COMPARATOR.compare(ver, newVer) >= 0) {
                        if (ATOMIC_VER_COMPARATOR.compare(ver, newVer) == 0 && cctx.writeThrough() && primary) {
                            if (log.isDebugEnabled())
                                log.debug("Received entry update with same version as current (will update store) " +
                                    "[entry=" + this + ", newVer=" + newVer + ']');

                            CacheObject val = rawGetOrUnmarshalUnlocked(false);

                            if (val == null) {
                                assert deletedUnlocked();

                                cctx.store().removeFromStore(null, key());
                            }
                            else
                                cctx.store().putToStore(null, key(), val, ver);
                        }
                        else {
                            if (log.isDebugEnabled())
                                log.debug("Received entry update with smaller version than current (will ignore) " +
                                    "[entry=" + this + ", newVer=" + newVer + ']');
                        }

                        return new GridCacheUpdateAtomicResult(false,
                            retval ? rawGetOrUnmarshalUnlocked(false) : null,
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                    }
                }
                else
                    assert isNew() || ATOMIC_VER_COMPARATOR.compare(ver, newVer) <= 0 :
                        "Invalid version for inner update [entry=" + this + ", newVer=" + newVer + ']';
            }

            // Prepare old value and value bytes.
            oldVal = needVal ? rawGetOrUnmarshalUnlocked(!retval) : val;
            GridCacheValueBytes oldValBytes = valueBytesUnlocked();

            // Possibly read value from store.
            boolean readThrough = false;

            Object old0 = null;
            Object updated0 = null;

            if (needVal && oldVal == null && (cctx.readThrough() &&
                (op == GridCacheOperation.TRANSFORM || cctx.loadPreviousValue()))) {
                old0 = readThrough(null, key, false, subjId, taskName);

                oldVal = cctx.toCacheObject(oldVal);

                readThrough = true;

                // Detach value before index update.
                oldVal = (CacheObject)cctx.kernalContext().portable().detachPortable(oldVal, cctx);

                // Calculate initial TTL and expire time.
                long initTtl;
                long initExpireTime;

                if (expiryPlc != null && oldVal != null) {
                    IgniteBiTuple<Long, Long> initTtlAndExpireTime = initialTtlAndExpireTime(expiryPlc);

                    initTtl = initTtlAndExpireTime.get1();
                    initExpireTime = initTtlAndExpireTime.get2();
                }
                else {
                    initTtl = CU.TTL_ETERNAL;
                    initExpireTime = CU.EXPIRE_TIME_ETERNAL;
                }

                if (oldVal != null)
                    updateIndex(oldVal, null, initExpireTime, ver, null);
                else
                    clearIndex(null);

                update(oldVal, null, initExpireTime, initTtl, ver);

                if (deletedUnlocked() && oldVal != null && !isInternal())
                    deletedUnlocked(false);
            }

            // Apply metrics.
            if (metrics && cctx.cache().configuration().isStatisticsEnabled() && needVal) {
                // PutIfAbsent methods mustn't update hit/miss statistics
                if (op != GridCacheOperation.UPDATE || F.isEmpty(filter) || filter != cctx.noPeekArray())
                    cctx.cache().metrics0().onRead(oldVal != null);
            }

            // Check filter inside of synchronization.
            if (!F.isEmptyOrNulls(filter)) {
                // TODO IGNITE-51 can get key/value only once.
                boolean pass = cctx.isAll(wrapFilterLocked(), filter);

                if (!pass) {
                    if (expiryPlc != null && !readThrough && hasValueUnlocked() && filter != cctx.noPeekArray())
                        updateTtl(expiryPlc);

                    return new GridCacheUpdateAtomicResult(false,
                        retval ? oldVal : null,
                        null,
                        invokeRes,
                        CU.TTL_ETERNAL,
                        CU.EXPIRE_TIME_ETERNAL,
                        null,
                        null,
                        false);
                }
            }

            Object key0 = null;

            // Calculate new value in case we met transform.
            if (op == GridCacheOperation.TRANSFORM) {
                assert conflictCtx == null : "Cannot be TRANSFORM here if conflict resolution was performed earlier.";

                transformClo = writeObj;

                EntryProcessor<Object, Object, ?> entryProcessor = (EntryProcessor<Object, Object, ?>)writeObj;

                key0 = key.value(cctx, false);
                old0 = value(old0, oldVal, false);

                CacheInvokeEntry<Object, Object> entry = new CacheInvokeEntry<>(cctx, key0, old0);

                try {
                    Object computed = entryProcessor.process(entry, invokeArgs);

                    updated0 = cctx.unwrapTemporary(entry.getValue());

                    if (entry.modified())
                        updated = cctx.toCacheObject(updated0);
                    else
                        updated = oldVal;

                    if (computed != null)
                        invokeRes = new CacheInvokeDirectResult(key,
                            cctx.toCacheObject(cctx.unwrapTemporary(computed)));

                    valBytes = null;
                }
                catch (Exception e) {
                    invokeRes = new CacheInvokeDirectResult(key, e);

                    updated = oldVal;

                    valBytes = oldValBytes.getIfMarshaled();
                }

                if (!entry.modified()) {
                    if (expiryPlc != null && !readThrough && hasValueUnlocked())
                        updateTtl(expiryPlc);

                    return new GridCacheUpdateAtomicResult(false,
                        retval ? oldVal : null,
                        null,
                        invokeRes,
                        CU.TTL_ETERNAL,
                        CU.EXPIRE_TIME_ETERNAL,
                        null,
                        null,
                        false);
                }
            }
            else
                updated = (CacheObject)writeObj;

            op = updated == null ? GridCacheOperation.DELETE : GridCacheOperation.UPDATE;

            assert op == GridCacheOperation.UPDATE || (op == GridCacheOperation.DELETE && updated == null);

            boolean hadVal = hasValueUnlocked();

            // Incorporate conflict version into new version if needed.
            if (conflictVer != null && conflictVer != newVer)
                newVer = new GridCacheVersionEx(newVer.topologyVersion(),
                    newVer.globalTime(),
                    newVer.order(),
                    newVer.nodeOrder(),
                    newVer.dataCenterId(),
                    conflictVer);


            if (op == GridCacheOperation.UPDATE) {
                // Conflict context is null if there were no explicit conflict resolution.
                if (conflictCtx == null) {
                    // Calculate TTL and expire time for local update.
                    if (explicitTtl != CU.TTL_NOT_CHANGED) {
                        // If conflict existed, expire time must be explicit.
                        assert conflictVer == null || explicitExpireTime != CU.EXPIRE_TIME_CALCULATE;

                        newSysTtl = newTtl = explicitTtl;
                        newSysExpireTime = explicitExpireTime;

                        newExpireTime = explicitExpireTime != CU.EXPIRE_TIME_CALCULATE ?
                            explicitExpireTime : CU.toExpireTime(explicitTtl);
                    }
                    else {
                        newSysTtl = expiryPlc == null ? CU.TTL_NOT_CHANGED :
                            hadVal ? expiryPlc.forUpdate() : expiryPlc.forCreate();

                        if (newSysTtl == CU.TTL_NOT_CHANGED) {
                            newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;
                            newTtl = ttlExtras();
                            newExpireTime = expireTimeExtras();
                        }
                        else if (newSysTtl == CU.TTL_ZERO) {
                            op = GridCacheOperation.DELETE;

                            newSysTtl = CU.TTL_NOT_CHANGED;
                            newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;

                            newTtl = CU.TTL_ETERNAL;
                            newExpireTime = CU.EXPIRE_TIME_ETERNAL;

                            updated = null;
                            valBytes = null;
                        }
                        else {
                            newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;
                            newTtl = newSysTtl;
                            newExpireTime = CU.toExpireTime(newTtl);
                        }
                    }
                }
                else {
                    newSysTtl = newTtl = conflictCtx.ttl();
                    newSysExpireTime = newExpireTime = conflictCtx.expireTime();
                }
            }
            else {
                assert op == GridCacheOperation.DELETE;

                newSysTtl = CU.TTL_NOT_CHANGED;
                newSysExpireTime = CU.EXPIRE_TIME_CALCULATE;

                newTtl = CU.TTL_ETERNAL;
                newExpireTime = CU.EXPIRE_TIME_ETERNAL;
            }

            // TTL and expire time must be resolved at this point.
            assert newTtl != CU.TTL_NOT_CHANGED && newTtl != CU.TTL_ZERO && newTtl >= 0;
            assert newExpireTime != CU.EXPIRE_TIME_CALCULATE && newExpireTime >= 0;

            IgniteBiTuple<Boolean, Object> interceptRes = null;

            // Actual update.
            if (op == GridCacheOperation.UPDATE) {
                if (intercept) {
                    key0 = value(key0, key, false);
                    old0 = value(old0, oldVal, false);
                    updated0 = value(updated0, updated, false);

                    Object interceptorVal = cctx.config().getInterceptor().onBeforePut(key0, old0, updated0);

                    if (interceptorVal == null)
                        return new GridCacheUpdateAtomicResult(false,
                            retval ? oldVal : null,
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                    else if (interceptorVal != updated0) {
                        updated = cctx.toCacheObject(cctx.unwrapTemporary(updated0));
                        valBytes = null;
                    }
                }

                // Try write-through.
                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().putToStore(null, key, updated, newVer);

                if (!hadVal) {
                    boolean new0 = isNew();

                    assert deletedUnlocked() || new0 || isInternal(): "Invalid entry [entry=" + this + ", locNodeId=" +
                        cctx.localNodeId() + ']';

                    if (!new0 && !isInternal())
                        deletedUnlocked(false);
                }
                else {
                    assert !deletedUnlocked() : "Invalid entry [entry=" + this +
                        ", locNodeId=" + cctx.localNodeId() + ']';

                    // Do not change size.
                }

                updated = (CacheObject)cctx.kernalContext().portable().detachPortable(updated, cctx);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                updateIndex(updated, valBytes, newExpireTime, newVer, oldVal);

                update(updated, valBytes, newExpireTime, newTtl, newVer);

                drReplicate(drType, updated, valBytes, newVer);

                recordNodeId(affNodeId);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformClo != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                        evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null,
                            newVer, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformClo.getClass().getName(), taskName);
                    }

                    if (newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_PUT)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null,
                            newVer, EVT_CACHE_OBJECT_PUT, updated, updated != null, evtOld,
                            evtOld != null || hadVal, subjId, null, taskName);
                    }
                }
            }
            else {
                if (intercept) {
                    key0 = value(key0, key, false);
                    old0 = value(old0, oldVal, false);

                    interceptRes = cctx.config().getInterceptor().onBeforeRemove(key0, old0);

                    if (cctx.cancelRemove(interceptRes))
                        return new GridCacheUpdateAtomicResult(false,
                            cctx.toCacheObject(cctx.unwrapTemporary(interceptRes.get2())),
                            null,
                            invokeRes,
                            CU.TTL_ETERNAL,
                            CU.EXPIRE_TIME_ETERNAL,
                            null,
                            null,
                            false);
                }

                if (writeThrough)
                    // Must persist inside synchronization in non-tx mode.
                    cctx.store().removeFromStore(null, key);

                // Update index inside synchronization since it can be updated
                // in load methods without actually holding entry lock.
                clearIndex(oldVal);

                if (hadVal) {
                    assert !deletedUnlocked();

                    if (!isInternal())
                        deletedUnlocked(true);
                }
                else {
                    boolean new0 = isNew();

                    assert deletedUnlocked() || new0 || isInternal() : "Invalid entry [entry=" + this + ", locNodeId=" +
                        cctx.localNodeId() + ']';

                    if (new0) {
                        if (!isInternal())
                            deletedUnlocked(true);
                    }
                }

                enqueueVer = newVer;

                boolean hasValPtr = valPtr != 0;

                // Clear value on backup. Entry will be removed from cache when it got evicted from queue.
                update(null, null, CU.TTL_ETERNAL, CU.EXPIRE_TIME_ETERNAL, newVer);

                assert newSysTtl == CU.TTL_NOT_CHANGED;
                assert newSysExpireTime == CU.EXPIRE_TIME_CALCULATE;

                if (cctx.offheapTiered() && hasValPtr) {
                    boolean rmv = cctx.swap().removeOffheap(key);

                    assert rmv;
                }

                clearReaders();

                recordNodeId(affNodeId);

                drReplicate(drType, null, null, newVer);

                if (evt) {
                    CacheObject evtOld = null;

                    if (transformClo != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_READ)) {
                        evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null,
                            newVer, EVT_CACHE_OBJECT_READ, evtOld, evtOld != null || hadVal, evtOld,
                            evtOld != null || hadVal, subjId, transformClo.getClass().getName(), taskName);
                    }

                    if (newVer != null && cctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED)) {
                        if (evtOld == null)
                            evtOld = cctx.unwrapTemporary(oldVal);

                        cctx.events().addEvent(partition(), key, evtNodeId, null, newVer,
                            EVT_CACHE_OBJECT_REMOVED, null, false, evtOld, evtOld != null || hadVal,
                            subjId, null, taskName);
                    }
                }

                res = hadVal;
            }

            if (res)
                updateMetrics(op, metrics);

            if (cctx.isReplicated() || primary)
                cctx.continuousQueries().onEntryUpdated(this, key, val, valueBytesUnlocked(),
                    oldVal, oldValBytes, false);

            cctx.dataStructures().onEntryUpdated(key, op == GridCacheOperation.DELETE);

            if (intercept) {
                key0 = value(key0, key, false);

                if (op == GridCacheOperation.UPDATE)
                    cctx.config().getInterceptor().onAfterPut(key0, val.value(cctx, false));
                else {
                    old0 = value(old0, oldVal, false);

                    cctx.config().getInterceptor().onAfterRemove(key0, old0);
                }

                if (interceptRes != null)
                    oldVal = cctx.unwrapTemporary(interceptRes.get2());
            }
        }

        if (log.isDebugEnabled())
            log.debug("Updated cache entry [val=" + val + ", old=" + oldVal + ", entry=" + this + ']');

        return new GridCacheUpdateAtomicResult(res,
            oldVal,
            updated,
            invokeRes,
            newSysTtl,
            newSysExpireTime,
            enqueueVer,
            conflictCtx,
            true);
    }

    /**
     * @param val Value.
     * @param cacheObj Cache object.
     * @param cpy Copy flag.
     * @return Cache object value.
     */
    @Nullable private Object value(@Nullable Object val, @Nullable CacheObject cacheObj, boolean cpy) {
        if (val != null)
            return val;

        return cacheObj != null ? cacheObj.value(cctx, cpy) : null;
    }

    /**
     * @param expiry Expiration policy.
     * @return Tuple holding initial TTL and expire time with the given expiry.
     */
    private static IgniteBiTuple<Long, Long> initialTtlAndExpireTime(IgniteCacheExpiryPolicy expiry) {
        assert expiry != null;

        long initTtl = expiry.forCreate();
        long initExpireTime;

        if (initTtl == CU.TTL_ZERO) {
            initTtl = CU.TTL_MINIMUM;
            initExpireTime = CU.expireTimeInPast();
        }
        else if (initTtl == CU.TTL_NOT_CHANGED) {
            initTtl = CU.TTL_ETERNAL;
            initExpireTime = CU.EXPIRE_TIME_ETERNAL;
        }
        else
            initExpireTime = CU.toExpireTime(initTtl);

        return F.t(initTtl, initExpireTime);
    }

    /**
     * Get TTL, expire time and remove flag for the given entry, expiration policy and explicit TTL and expire time.
     *
     * @param expiry Expiration policy.
     * @param ttl Explicit TTL.
     * @param expireTime Explicit expire time.
     * @return Result.
     */
    private GridTuple3<Long, Long, Boolean> ttlAndExpireTime(IgniteCacheExpiryPolicy expiry, long ttl, long expireTime)
        throws GridCacheEntryRemovedException {
        boolean rmv = false;

        // 1. If TTL is not changed, then calculate it based on expiry.
        if (ttl == CU.TTL_NOT_CHANGED) {
            if (expiry != null)
                ttl = hasValueUnlocked() ? expiry.forUpdate() : expiry.forCreate();
        }

        // 2. If TTL is zero, then set delete marker.
        if (ttl == CU.TTL_ZERO) {
            rmv = true;

            ttl = CU.TTL_ETERNAL;
        }

        // 3. If TTL is still not changed, then either use old entry TTL or set it to "ETERNAL".
        if (ttl == CU.TTL_NOT_CHANGED) {
            if (isNew())
                ttl = CU.TTL_ETERNAL;
            else {
                ttl = ttlExtras();
                expireTime = expireTimeExtras();
            }
        }

        // 4 If expire time was not set explicitly, then calculate it.
        if (expireTime == CU.EXPIRE_TIME_CALCULATE)
            expireTime = CU.toExpireTime(ttl);

        return F.t(ttl, expireTime, rmv);
    }

    /**
     * Perform DR if needed.
     *
     * @param drType DR type.
     * @param val Value.
     * @param valBytes Value bytes.
     * @param ver Version.
     * @throws IgniteCheckedException In case of exception.
     */
    private void drReplicate(GridDrType drType, @Nullable CacheObject val, @Nullable byte[] valBytes, GridCacheVersion ver)
        throws IgniteCheckedException {
// TODO IGNITE-51.
//        if (cctx.isDrEnabled() && drType != DR_NONE && !isInternal())
//            cctx.dr().replicate(key, null, val, valBytes, rawTtl(), rawExpireTime(), ver.conflictVersion(), drType);
    }

    /**
     * @return {@code true} if entry has readers. It makes sense only for dht entry.
     * @throws GridCacheEntryRemovedException If removed.
     */
    protected boolean hasReaders() throws GridCacheEntryRemovedException {
        return false;
    }

    /**
     *
     */
    protected void clearReaders() {
        // No-op.
    }

    /**
     * @param nodeId Node ID to clear.
     */
    protected void clearReader(UUID nodeId) throws GridCacheEntryRemovedException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean clear(GridCacheVersion ver, boolean readers,
        @Nullable IgnitePredicate<Cache.Entry<K, V>>[] filter) throws IgniteCheckedException {
        cctx.denyOnFlag(READ);

        boolean ret;
        boolean rmv;
        boolean marked;

        while (true) {
            ret = false;
            rmv = false;
            marked = false;

            // For optimistic check.
            GridCacheVersion startVer = null;

            if (!F.isEmptyOrNulls(filter)) {
                synchronized (this) {
                    startVer = this.ver;
                }

                if (!cctx.isAll(this, filter))
                    return false;
            }

            synchronized (this) {
                if (startVer != null && !startVer.equals(this.ver))
                    // Version has changed since filter checking.
                    continue;

                CacheObject val = saveValueForIndexUnlocked();

                try {
                    if ((!hasReaders() || readers)) {
                        // markObsolete will clear the value.
                        if (!(marked = markObsolete0(ver, true))) {
                            if (log.isDebugEnabled())
                                log.debug("Entry could not be marked obsolete (it is still used): " + this);

                            break;
                        }

                        clearReaders();
                    }
                    else {
                        if (log.isDebugEnabled())
                            log.debug("Entry could not be marked obsolete (it still has readers): " + this);

                        break;
                    }
                }
                catch (GridCacheEntryRemovedException ignore) {
                    if (log.isDebugEnabled())
                        log.debug("Got removed entry when clearing (will simply return): " + this);

                    ret = true;

                    break;
                }

                if (log.isDebugEnabled())
                    log.debug("Entry has been marked obsolete: " + this);

                clearIndex(val);

                releaseSwap();

                ret = true;
                rmv = true;

                break;
            }
        }

        if (marked)
            onMarkedObsolete();

        if (rmv)
            cctx.cache().removeEntry(this); // Clear cache.

        return ret;
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheVersion obsoleteVersion() {
        return obsoleteVersionExtras();
    }

    /** {@inheritDoc} */
    @Override public boolean markObsolete(GridCacheVersion ver) {
        boolean obsolete;

        synchronized (this) {
            obsolete = markObsolete0(ver, true);
        }

        if (obsolete)
            onMarkedObsolete();

        return obsolete;
    }

    /** {@inheritDoc} */
    @Override public boolean markObsoleteIfEmpty(@Nullable GridCacheVersion ver) throws IgniteCheckedException {
        boolean obsolete = false;
        boolean deferred = false;

        try {
            synchronized (this) {
                if (obsoleteVersionExtras() != null)
                    return false;

                if (!hasValueUnlocked() || checkExpired()) {
                    if (ver == null)
                        ver = nextVersion();

                    if (cctx.deferredDelete() && !isStartVersion() && !detached() && !isInternal()) {
                        if (!deletedUnlocked()) {
                            update(null, null, 0L, 0L, ver);

                            deletedUnlocked(true);

                            deferred = true;
                        }
                    }
                    else
                        obsolete = markObsolete0(ver, true);
                }
            }
        }
        finally {
            if (obsolete)
                onMarkedObsolete();

            if (deferred)
                cctx.onDeferredDelete(this, ver);
        }

        return obsolete;
    }

    /** {@inheritDoc} */
    @Override public boolean markObsoleteVersion(GridCacheVersion ver) {
        assert cctx.deferredDelete();

        boolean marked;

        synchronized (this) {
            if (obsoleteVersionExtras() != null)
                return true;

            if (!this.ver.equals(ver))
                return false;

            marked = markObsolete0(ver, true);
        }

        if (marked)
            onMarkedObsolete();

        return marked;
    }

    /**
     * <p>
     * Note that {@link #onMarkedObsolete()} should always be called after this method
     * returns {@code true}.
     *
     * @param ver Version.
     * @param clear {@code True} to clear.
     * @return {@code True} if entry is obsolete, {@code false} if entry is still used by other threads or nodes.
     */
    protected final boolean markObsolete0(GridCacheVersion ver, boolean clear) {
        assert Thread.holdsLock(this);

        GridCacheVersion obsoleteVer = obsoleteVersionExtras();

        if (ver != null) {
            // If already obsolete, then do nothing.
            if (obsoleteVer != null)
                return true;

            GridCacheMvcc mvcc = mvccExtras();

            if (mvcc == null || mvcc.isEmpty(ver)) {
                obsoleteVer = ver;

                obsoleteVersionExtras(obsoleteVer);

                if (clear)
                    value(null, null);
            }

            return obsoleteVer != null;
        }
        else
            return obsoleteVer != null;
    }

    /** {@inheritDoc} */
    @Override public void onMarkedObsolete() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public final synchronized boolean obsolete() {
        return obsoleteVersionExtras() != null;
    }

    /** {@inheritDoc} */
    @Override public final synchronized boolean obsolete(GridCacheVersion exclude) {
        GridCacheVersion obsoleteVer = obsoleteVersionExtras();

        return obsoleteVer != null && !obsoleteVer.equals(exclude);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean invalidate(@Nullable GridCacheVersion curVer, GridCacheVersion newVer)
        throws IgniteCheckedException {
        assert newVer != null;

        if (curVer == null || ver.equals(curVer)) {
            CacheObject val = saveValueForIndexUnlocked();

            value(null, null);

            ver = newVer;

            releaseSwap();

            clearIndex(val);

            onInvalidate();
        }

        return obsoleteVersionExtras() != null;
    }

    /**
     * Called when entry invalidated.
     */
    protected void onInvalidate() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean invalidate(@Nullable IgnitePredicate<Cache.Entry<K, V>>[] filter)
        throws GridCacheEntryRemovedException, IgniteCheckedException {
        if (F.isEmptyOrNulls(filter)) {
            synchronized (this) {
                checkObsolete();

                invalidate(null, nextVersion());

                return true;
            }
        }
        else {
            // For optimistic checking.
            GridCacheVersion startVer;

            synchronized (this){
                checkObsolete();

                startVer = ver;
            }

            if (!cctx.isAll(this, filter))
                return false;

            synchronized (this) {
                checkObsolete();

                if (startVer.equals(ver)) {
                    invalidate(null, nextVersion());

                    return true;
                }
            }

            // If version has changed then repeat the process.
            return invalidate(filter);
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean compact(@Nullable IgnitePredicate<Cache.Entry<K, V>>[] filter)
        throws GridCacheEntryRemovedException, IgniteCheckedException {
        // For optimistic checking.
        GridCacheVersion startVer;

        synchronized (this) {
            checkObsolete();

            startVer = ver;
        }

        if (!cctx.isAll(this, filter))
            return false;

        synchronized (this) {
            checkObsolete();

            if (deletedUnlocked())
                return false; // Cannot compact soft-deleted entries.

            if (startVer.equals(ver)) {
                if (hasValueUnlocked() && !checkExpired()) {
// TODO IGNITE-51.
//                    if (!isOffHeapValuesOnly()) {
//                        if (val != null)
//                            valBytes = null;
//                    }

                    return false;
                }
                else
                    return clear(nextVersion(), false, filter);
            }
        }

        // If version has changed do it again.
        return compact(filter);
    }

    /**
     *
     * @param val New value.
     * @param valBytes New value bytes.
     * @param expireTime Expiration time.
     * @param ttl Time to live.
     * @param ver Update version.
     */
    protected final void update(@Nullable CacheObject val, @Nullable byte[] valBytes, long expireTime, long ttl,
        GridCacheVersion ver) {
        assert ver != null;
        assert Thread.holdsLock(this);
        assert ttl != CU.TTL_ZERO && ttl != CU.TTL_NOT_CHANGED && ttl >= 0 : ttl;

        long oldExpireTime = expireTimeExtras();

        if (oldExpireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().removeTrackedEntry(this);

        value(val, valBytes);

        ttlAndExpireTimeExtras(ttl, expireTime);

        if (expireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().addTrackedEntry(this);

        this.ver = ver;
    }

    /**
     * Update TTL if it is changed.
     *
     * @param expiryPlc Expiry policy.
     */
    private void updateTtl(ExpiryPolicy expiryPlc) {
        long ttl = CU.toTtl(expiryPlc.getExpiryForAccess());

        if (ttl != CU.TTL_NOT_CHANGED)
            updateTtl(ttl);
    }

    /**
     * Update TTL is it is changed.
     *
     * @param expiryPlc Expiry policy.
     * @throws IgniteCheckedException If failed.
     * @throws GridCacheEntryRemovedException If failed.
     */
    private void updateTtl(IgniteCacheExpiryPolicy expiryPlc)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        long ttl = expiryPlc.forAccess();

        if (ttl != CU.TTL_NOT_CHANGED) {
            updateTtl(ttl);

            expiryPlc.ttlUpdated(key(),
                version(),
                hasReaders() ? ((GridDhtCacheEntry)this).readers() : null);
        }
    }

    /**
     * @param ttl Time to live.
     */
    private void updateTtl(long ttl) {
        assert ttl >= 0 || ttl == CU.TTL_ZERO : ttl;
        assert Thread.holdsLock(this);

        long expireTime;

        if (ttl == CU.TTL_ZERO) {
            ttl = CU.TTL_MINIMUM;
            expireTime = CU.expireTimeInPast();
        }
        else
            expireTime = CU.toExpireTime(ttl);

        long oldExpireTime = expireTimeExtras();

        if (oldExpireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().removeTrackedEntry(this);

        ttlAndExpireTimeExtras(ttl, expireTime);

        if (expireTime != 0 && expireTime != oldExpireTime && cctx.config().isEagerTtl())
            cctx.ttl().addTrackedEntry(this);
    }

    /**
     * @return {@code true} If value bytes should be stored.
     */
    protected boolean isStoreValueBytes() {
        return cctx.config().isStoreValueBytes();
    }

    /**
     * @return {@code True} if values should be stored off-heap.
     */
    protected boolean isOffHeapValuesOnly() {
        return cctx.config().getMemoryMode() == CacheMemoryMode.OFFHEAP_VALUES;
    }

    /**
     * @throws GridCacheEntryRemovedException If entry is obsolete.
     */
    protected void checkObsolete() throws GridCacheEntryRemovedException {
        assert Thread.holdsLock(this);

        if (obsoleteVersionExtras() != null)
            throw new GridCacheEntryRemovedException();
    }

    /** {@inheritDoc} */
    @Override public KeyCacheObject key() {
        return key;
    }

    /** {@inheritDoc} */
    @Override public IgniteTxKey txKey() {
        return cctx.txKey(key);
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheVersion version() throws GridCacheEntryRemovedException {
        checkObsolete();

        return ver;
    }

    /**
     * Gets hash value for the entry key.
     *
     * @return Hash value.
     */
    int hash() {
        return hash;
    }

    /**
     * Gets next entry in bucket linked list within a hash map segment.
     *
     * @param segId Segment ID.
     * @return Next entry.
     */
    GridCacheMapEntry next(int segId) {
        return segId % 2 == 0 ? next0 : next1;
    }

    /**
     * Sets next entry in bucket linked list within a hash map segment.
     *
     * @param segId Segment ID.
     * @param next Next entry.
     */
    void next(int segId, @Nullable GridCacheMapEntry next) {
        if (segId % 2 == 0)
            next0 = next;
        else
            next1 = next;
    }

    /** {@inheritDoc} */
    @Nullable @Override public <K, V> CacheObject peek(GridCachePeekMode mode,
        IgnitePredicate<Cache.Entry<K, V>>[] filter)
        throws GridCacheEntryRemovedException {
        try {
            GridTuple<CacheObject> peek = peek0(false, mode, filter, cctx.tm().localTxx());

            return peek != null ? peek.get() : null;
        }
        catch (GridCacheFilterFailedException ignore) {
            assert false;

            return null;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Unable to perform entry peek() operation.", e);
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public CacheObject peek(boolean heap,
        boolean offheap,
        boolean swap,
        long topVer,
        @Nullable IgniteCacheExpiryPolicy expiryPlc)
        throws GridCacheEntryRemovedException, IgniteCheckedException
    {
        assert heap || offheap || swap;

        try {
            if (heap) {
                GridTuple<CacheObject> val = peekGlobal(false, topVer, null, expiryPlc);

                if (val != null)
                    return val.get();
            }

            if (offheap || swap) {
                GridCacheSwapEntry  e = cctx.swap().read(this, false, offheap, swap);

                return e != null ? e.value() : null;
            }

            return null;
        }
        catch (GridCacheFilterFailedException ignored) {
            assert false;

            return null;
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> CacheObject peek(Collection<GridCachePeekMode> modes,
        IgnitePredicate<Cache.Entry<K, V>>[] filter)
        throws GridCacheEntryRemovedException {
        assert modes != null;

        for (GridCachePeekMode mode : modes) {
            try {
                GridTuple<CacheObject> val = peek0(false, mode, filter, cctx.tm().localTxx());

                if (val != null)
                    return val.get();
            }
            catch (GridCacheFilterFailedException ignored) {
                assert false;

                return null;
            }
            catch (IgniteCheckedException e) {
                throw new IgniteException("Unable to perform entry peek() operation.", e);
            }
        }

        return null;
    }

    /**
     * @param failFast Fail-fast flag.
     * @param mode Peek mode.
     * @param filter Filter.
     * @param tx Transaction to peek value at (if mode is TX value).
     * @return Peeked value.
     * @throws IgniteCheckedException In case of error.
     * @throws GridCacheEntryRemovedException If removed.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable @Override public <K, V> GridTuple<CacheObject> peek0(boolean failFast, GridCachePeekMode mode,
        IgnitePredicate<Cache.Entry<K, V>>[] filter, @Nullable IgniteInternalTx tx)
        throws GridCacheEntryRemovedException, GridCacheFilterFailedException, IgniteCheckedException {
        assert tx == null || tx.local();

        long topVer = tx != null ? tx.topologyVersion() : cctx.affinity().affinityTopologyVersion();

        switch (mode) {
            case TX:
                return peekTx(failFast, filter, tx);

            case GLOBAL:
                return peekGlobal(failFast, topVer, filter, null);

            case NEAR_ONLY:
                return peekGlobal(failFast, topVer, filter, null);

            case PARTITIONED_ONLY:
                return peekGlobal(failFast, topVer, filter, null);

            case SMART:
                /*
                 * If there is no ongoing transaction, or transaction is NOT in ACTIVE state,
                 * which means that it is either rolling back, preparing to commit, or committing,
                 * then we only check the global cache storage because value has already been
                 * validated against filter and enlisted into transaction and, therefore, second
                 * validation against the same enlisted value will be invalid (it will always be false).
                 *
                 * However, in ACTIVE state, we must also validate against other values that
                 * may have enlisted into the same transaction and that's why we pass 'true'
                 * to 'e.peek(true)' method in this case.
                 */
                return tx == null || tx.state() != ACTIVE ? peekGlobal(failFast, topVer, filter, null) :
                    peekTxThenGlobal(failFast, filter, tx);

            case SWAP:
                return peekSwap(failFast, filter);

            case DB:
                return F.t(peekDb(failFast, filter));

            default: // Should never be reached.
                assert false;

                return null;
        }
    }

    /** {@inheritDoc} */
    @Override public CacheObject poke(CacheObject val) throws GridCacheEntryRemovedException, IgniteCheckedException {
        assert val != null;

        CacheObject old;

        synchronized (this) {
            checkObsolete();

            if (isNew() || !valid(-1))
                unswap(true, true);

            if (deletedUnlocked())
                return null;

            old = rawGetOrUnmarshalUnlocked(false);

            GridCacheVersion nextVer = nextVersion();

            // Update index inside synchronization since it can be updated
            // in load methods without actually holding entry lock.
            long expireTime = expireTimeExtras();

            val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

            updateIndex(val, null, expireTime, nextVer, old);

            update(val, null, expireTime, ttlExtras(), nextVer);
        }

        if (log.isDebugEnabled())
            log.debug("Poked cache entry [newVal=" + val + ", oldVal=" + old + ", entry=" + this + ']');

        return old;
    }

    /**
     * Checks that entries in group locks transactions are not locked during commit.
     *
     * @param tx Transaction to check.
     * @throws GridCacheEntryRemovedException If entry is obsolete.
     * @throws IgniteCheckedException If entry was externally locked.
     */
    private void groupLockSanityCheck(IgniteInternalTx tx) throws GridCacheEntryRemovedException, IgniteCheckedException {
        assert tx.groupLock();

        IgniteTxEntry txEntry = tx.entry(txKey());

        if (txEntry.groupLockEntry()) {
            if (lockedByAny())
                throw new IgniteCheckedException("Failed to update cache entry (entry was externally locked while " +
                    "accessing entry within group lock transaction) [entry=" + this + ", tx=" + tx + ']');
        }
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @param tx Transaction to peek value at (if mode is TX value).
     * @return Peeked value.
     * @throws GridCacheFilterFailedException If filter failed.
     * @throws GridCacheEntryRemovedException If entry got removed.
     * @throws IgniteCheckedException If unexpected cache failure occurred.
     */
    @Nullable private <K, V> GridTuple<CacheObject> peekTxThenGlobal(boolean failFast,
        IgnitePredicate<Cache.Entry<K, V>>[] filter,
        IgniteInternalTx tx)
        throws GridCacheFilterFailedException, GridCacheEntryRemovedException, IgniteCheckedException
    {
        GridTuple<CacheObject> peek = peekTx(failFast, filter, tx);

        // If transaction has value (possibly null, which means value is to be deleted).
        if (peek != null)
            return peek;

        long topVer = tx == null ? cctx.affinity().affinityTopologyVersion() : tx.topologyVersion();

        return peekGlobal(failFast, topVer, filter, null);
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @param tx Transaction to peek value at (if mode is TX value).
     * @return Peeked value.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @Nullable private <K, V> GridTuple<CacheObject> peekTx(boolean failFast,
        IgnitePredicate<Cache.Entry<K, V>>[] filter,
        @Nullable IgniteInternalTx tx) throws GridCacheFilterFailedException {
        return tx == null ? null : tx.peek(cctx, failFast, key, filter);
    }

    /**
     * @param failFast Fail fast flag.
     * @param topVer Topology version.
     * @param filter Filter.
     * @param expiryPlc Optional expiry policy.
     * @return Peeked value.
     * @throws GridCacheFilterFailedException If filter failed.
     * @throws GridCacheEntryRemovedException If entry got removed.
     * @throws IgniteCheckedException If unexpected cache failure occurred.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable private <K, V> GridTuple<CacheObject> peekGlobal(boolean failFast,
        long topVer,
        IgnitePredicate<Cache.Entry<K, V>>[] filter,
        @Nullable IgniteCacheExpiryPolicy expiryPlc
        )
        throws GridCacheEntryRemovedException, GridCacheFilterFailedException, IgniteCheckedException {
        if (!valid(topVer))
            return null;

        boolean rmv = false;

        try {
            while (true) {
                GridCacheVersion ver;
                CacheObject val;

                synchronized (this) {
                    if (checkExpired()) {
                        rmv = markObsolete0(cctx.versions().next(this.ver), true);

                        return null;
                    }

                    checkObsolete();

                    ver = this.ver;
                    val = rawGetOrUnmarshalUnlocked(false);

                    if (val != null && expiryPlc != null)
                        updateTtl(expiryPlc);
                }

                if (!cctx.isAll(this.<K, V>wrap(), filter))
                    return F.t(CU.<CacheObject>failed(failFast));

                if (F.isEmptyOrNulls(filter) || ver.equals(version()))
                    return F.t(val);
            }
        }
        finally {
            if (rmv) {
                onMarkedObsolete();

                cctx.cache().map().removeEntry(this);
            }
        }
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @return Value from swap storage.
     * @throws IgniteCheckedException In case of any errors.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private <K, V> GridTuple<CacheObject> peekSwap(boolean failFast,
        IgnitePredicate<Cache.Entry<K, V>>[] filter)
        throws IgniteCheckedException, GridCacheFilterFailedException
    {
        if (!cctx.isAll(this.<K, V>wrap(), filter))
            return F.t((CacheObject)CU.failed(failFast));

        synchronized (this) {
            if (checkExpired())
                return null;
        }

        GridCacheSwapEntry e = cctx.swap().read(this, false, true, true);

        return e != null ? F.t(e.value()) : null;
    }

    /**
     * @param failFast Fail fast flag.
     * @param filter Filter.
     * @return Value from persistent store.
     * @throws IgniteCheckedException In case of any errors.
     * @throws GridCacheFilterFailedException If filter failed.
     */
    @SuppressWarnings({"unchecked"})
    @Nullable private <K, V> CacheObject peekDb(boolean failFast, IgnitePredicate<Cache.Entry<K, V>>[] filter)
        throws IgniteCheckedException, GridCacheFilterFailedException {
        if (!cctx.isAll(this.<K, V>wrap(), filter))
            return CU.failed(failFast);

        synchronized (this) {
            if (checkExpired())
                return null;
        }

        // TODO IGNITE-51.
        return cctx.toCacheObject(cctx.store().loadFromStore(cctx.tm().localTxx(), key));
    }

    /**
     * TODO: GG-4009: do we need to generate event and invalidate value?
     *
     * @return {@code true} if expired.
     * @throws IgniteCheckedException In case of failure.
     */
    private boolean checkExpired() throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        long expireTime = expireTimeExtras();

        if (expireTime > 0) {
            long delta = expireTime - U.currentTimeMillis();

            if (log.isDebugEnabled())
                log.debug("Checked expiration time for entry [timeLeft=" + delta + ", entry=" + this + ']');

            if (delta <= 0) {
                releaseSwap();

                clearIndex(saveValueForIndexUnlocked());

                return true;
            }
        }

        return false;
    }

    /**
     * @return Value.
     */
    @Override public synchronized CacheObject rawGet() {
        return val;
    }

    /** {@inheritDoc} */
    @Nullable @Override public synchronized CacheObject rawGetOrUnmarshal(boolean tmp) throws IgniteCheckedException {
        return rawGetOrUnmarshalUnlocked(tmp);
    }

    /**
     * @param tmp If {@code true} can return temporary instance.
     * @return Value (unmarshalled if needed).
     * @throws IgniteCheckedException If failed.
     */
    @Nullable public CacheObject rawGetOrUnmarshalUnlocked(boolean tmp) throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        CacheObject val = this.val;

        if (val != null)
            return val;

        if (valPtr != 0)
            return cctx.fromOffheap(valPtr, tmp);

        return null;
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasValue() {
        return hasValueUnlocked();
    }

    /**
     * @return {@code True} if this entry has value.
     */
    protected boolean hasValueUnlocked() {
        assert Thread.holdsLock(this);

        return val != null || valPtr != 0;
    }

    /** {@inheritDoc} */
    @Override public synchronized CacheObject rawPut(CacheObject val, long ttl) {
        CacheObject old = this.val;

        update(val, null, CU.toExpireTime(ttl), ttl, nextVersion());

        return old;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Override public boolean initialValue(
        CacheObject val,
        byte[] valBytes,
        GridCacheVersion ver,
        long ttl,
        long expireTime,
        boolean preload,
        long topVer,
        GridDrType drType)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
// TODO IGNITE-51.
//        if (cctx.isUnmarshalValues() && valBytes != null && val == null && isNewLocked())
//            val = cctx.marshaller().<V>unmarshal(valBytes, cctx.deploy().globalLoader());

        synchronized (this) {
            checkObsolete();

            if (isNew() || (!preload && deletedUnlocked())) {
                long expTime = expireTime < 0 ? CU.toExpireTime(ttl) : expireTime;

                val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

                if (val != null || valBytes != null)
                    updateIndex(val, valBytes, expTime, ver, null);

                // Version does not change for load ops.
                update(val, valBytes, expTime, ttl, ver);

                boolean skipQryNtf = false;

                if (val == null && valBytes == null) {
                    skipQryNtf = true;

                    if (cctx.deferredDelete() && !isInternal()) {
                        assert !deletedUnlocked();

                        deletedUnlocked(true);
                    }
                }
                else if (deletedUnlocked())
                    deletedUnlocked(false);

                drReplicate(drType, val, valBytes, ver);

                if (!skipQryNtf) {
                    if (cctx.isLocal() || cctx.isReplicated() || cctx.affinity().primary(cctx.localNode(), key, topVer))
                        cctx.continuousQueries().onEntryUpdated(this, key, val, valueBytesUnlocked(), null, null,
                            preload);

                    cctx.dataStructures().onEntryUpdated(key, false);
                }

                return true;
            }

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean initialValue(KeyCacheObject key, GridCacheSwapEntry unswapped) throws
        IgniteCheckedException,
        GridCacheEntryRemovedException {
        checkObsolete();

        if (isNew()) {
            CacheObject val = unswapped.value();

            if (cctx.portableEnabled()) {
                val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

                if (cctx.offheapTiered() && !unswapped.valueIsByteArray())
                    unswapped.valueBytes(cctx.convertPortableBytes(unswapped.valueBytes()));
            }

            // Version does not change for load ops.
            update(val,
                unswapped.valueBytes(),
                unswapped.expireTime(),
                unswapped.ttl(),
                unswapped.version()
            );

            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheVersionedEntryEx versionedEntry() throws IgniteCheckedException {
        boolean isNew = isStartVersion();

        return new GridCachePlainVersionedEntry<>(key, isNew ? unswap(true, true) : rawGetOrUnmarshalUnlocked(false),
            ttlExtras(), expireTimeExtras(), ver.conflictVersion(), isNew);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean versionedValue(CacheObject val,
        GridCacheVersion curVer,
        GridCacheVersion newVer)
        throws IgniteCheckedException, GridCacheEntryRemovedException
    {
        checkObsolete();

        if (curVer == null || curVer.equals(ver)) {
            if (val != this.val) {
                if (newVer == null)
                    newVer = nextVersion();

                CacheObject old = rawGetOrUnmarshalUnlocked(false);

                long ttl = ttlExtras();

                long expTime = CU.toExpireTime(ttl);

                // Detach value before index update.
                val = (CacheObject)cctx.kernalContext().portable().detachPortable(val, cctx);

                if (val != null) {
                    updateIndex(val, null, expTime, newVer, old);

                    if (deletedUnlocked())
                        deletedUnlocked(false);
                }

                // Version does not change for load ops.
                update(val, null, expTime, ttl, newVer);
            }

            return true;
        }

        return false;
    }

    /**
     * Gets next version for this cache entry.
     *
     * @return Next version.
     */
    private GridCacheVersion nextVersion() {
        // Do not change topology version when generating next version.
        return cctx.versions().next(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasLockCandidate(GridCacheVersion ver) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.hasCandidate(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasLockCandidate(long threadId) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.localCandidate(threadId) != null;
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByAny(GridCacheVersion... exclude)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && !mvcc.isEmpty(exclude);
    }

    /** {@inheritDoc} */
    @Override public boolean lockedByThread() throws GridCacheEntryRemovedException {
        return lockedByThread(Thread.currentThread().getId());
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedLocally(GridCacheVersion lockVer) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwned(lockVer);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByThread(long threadId, GridCacheVersion exclude)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByThread(threadId, false, exclude);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedLocallyByIdOrThread(GridCacheVersion lockVer, long threadId)
        throws GridCacheEntryRemovedException {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByIdOrThread(lockVer, threadId);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByThread(long threadId) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByThread(threadId, true);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedBy(GridCacheVersion ver) throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isOwnedBy(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByThreadUnsafe(long threadId) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwnedByThread(threadId, true);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedByUnsafe(GridCacheVersion ver) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isOwnedBy(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean lockedLocallyUnsafe(GridCacheVersion lockVer) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.isLocallyOwned(lockVer);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean hasLockCandidateUnsafe(GridCacheVersion ver) {
        GridCacheMvcc mvcc = mvccExtras();

        return mvcc != null && mvcc.hasCandidate(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized Collection<GridCacheMvccCandidate> localCandidates(GridCacheVersion... exclude)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? Collections.<GridCacheMvccCandidate>emptyList() : mvcc.localCandidates(exclude);
    }

    /** {@inheritDoc} */
    @Override public Collection<GridCacheMvccCandidate> remoteMvccSnapshot(GridCacheVersion... exclude) {
        return Collections.emptyList();
    }

    /** {@inheritDoc} */
    @Nullable @Override public synchronized GridCacheMvccCandidate candidate(GridCacheVersion ver)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.candidate(ver);
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheMvccCandidate localCandidate(long threadId)
        throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.localCandidate(threadId);
    }

    /** {@inheritDoc} */
    @Override public GridCacheMvccCandidate candidate(UUID nodeId, long threadId)
        throws GridCacheEntryRemovedException {
        boolean loc = cctx.nodeId().equals(nodeId);

        synchronized (this) {
            checkObsolete();

            GridCacheMvcc mvcc = mvccExtras();

            return mvcc == null ? null : loc ? mvcc.localCandidate(threadId) :
                mvcc.remoteCandidate(nodeId, threadId);
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheMvccCandidate localOwner() throws GridCacheEntryRemovedException {
        checkObsolete();

        GridCacheMvcc mvcc = mvccExtras();

        return mvcc == null ? null : mvcc.localOwner();
    }

    /** {@inheritDoc} */
    @Override public synchronized long rawExpireTime() {
        return expireTimeExtras();
    }

    /** {@inheritDoc} */
    @Override public long expireTimeUnlocked() {
        assert Thread.holdsLock(this);

        return expireTimeExtras();
    }

    /** {@inheritDoc} */
    @Override public boolean onTtlExpired(GridCacheVersion obsoleteVer) {
        boolean obsolete = false;
        boolean deferred = false;

        try {
            synchronized (this) {
                CacheObject expiredVal = val;

                boolean hasOldBytes = valPtr != 0;

                boolean expired = checkExpired();

                if (expired) {
                    if (cctx.deferredDelete() && !detached() && !isInternal()) {
                        if (!deletedUnlocked()) {
                            update(null, null, 0L, 0L, ver);

                            deletedUnlocked(true);

                            deferred = true;
                        }
                    }
                    else {
                        if (markObsolete0(obsoleteVer, true))
                            obsolete = true; // Success, will return "true".
                    }

                    if (cctx.events().isRecordable(EVT_CACHE_OBJECT_EXPIRED)) {
                        cctx.events().addEvent(partition(),
                            key,
                            cctx.localNodeId(),
                            null,
                            EVT_CACHE_OBJECT_EXPIRED,
                            null,
                            false,
                            expiredVal,
                            expiredVal != null || hasOldBytes,
                            null,
                            null,
                            null);
                    }

                    cctx.continuousQueries().onEntryExpired(this, key, expiredVal, null);
                }
            }
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to clean up expired cache entry: " + this, e);
        }
        finally {
            if (obsolete)
                onMarkedObsolete();

            if (deferred)
                cctx.onDeferredDelete(this, obsoleteVer);
        }

        return obsolete;
    }

    /** {@inheritDoc} */
    @Override public synchronized long rawTtl() {
        return ttlExtras();
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"IfMayBeConditional"})
    @Override public long expireTime() throws GridCacheEntryRemovedException {
        IgniteTxLocalAdapter tx = currentTx();

        if (tx != null) {
            long time = tx.entryExpireTime(txKey());

            if (time > 0)
                return time;
        }

        synchronized (this) {
            checkObsolete();

            return expireTimeExtras();
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"IfMayBeConditional"})
    @Override public long ttl() throws GridCacheEntryRemovedException {
        IgniteTxLocalAdapter tx = currentTx();

        if (tx != null) {
            long entryTtl = tx.entryTtl(txKey());

            if (entryTtl > 0)
                return entryTtl;
        }

        synchronized (this) {
            checkObsolete();

            return ttlExtras();
        }
    }

    /**
     * @return Current transaction.
     */
    private IgniteTxLocalAdapter currentTx() {
        if (cctx.isDht())
            return cctx.dht().near().context().tm().localTx();
        else
            return cctx.tm().localTx();
    }

    /** {@inheritDoc} */
    @Override public void updateTtl(@Nullable GridCacheVersion ver, long ttl) {
        synchronized (this) {
            updateTtl(ttl);

            /*
            TODO IGNITE-305.
            try {
                if (var == null || ver.equals(version()))
                    updateTtl(ttl);
            }
            catch (GridCacheEntryRemovedException ignored) {
                // No-op.
            }
            */
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized void keyBytes(byte[] keyBytes) throws GridCacheEntryRemovedException {
        checkObsolete();

// TODO IGNITE-51.
//        if (keyBytes != null)
//            this.keyBytes = keyBytes;
    }

    /** {@inheritDoc} */
    @Override public synchronized byte[] keyBytes() {
// TODO IGNITE-51.
//        return keyBytes;
        return null;
    }

    /** {@inheritDoc} */
    @Override public byte[] getOrMarshalKeyBytes() throws IgniteCheckedException {
// TODO IGNITE-51.
//        byte[] bytes = keyBytes();
//
//        if (bytes != null)
//            return bytes;
//
//        bytes = CU.marshal(cctx.shared(), key);
//
//        synchronized (this) {
//            keyBytes = bytes;
//        }
//
//        return bytes;
        return null;
    }

    /** {@inheritDoc} */
    @Override public synchronized GridCacheValueBytes valueBytes() throws GridCacheEntryRemovedException {
        checkObsolete();

        return valueBytesUnlocked();
    }

    /** {@inheritDoc} */
    @Nullable @Override public GridCacheValueBytes valueBytes(@Nullable GridCacheVersion ver)
        throws IgniteCheckedException, GridCacheEntryRemovedException {
        CacheObject val = null;
        GridCacheValueBytes valBytes = GridCacheValueBytes.nil();

// TODO IGNITE-51.
//        synchronized (this) {
//            checkObsolete();
//
//            if (ver == null || this.ver.equals(ver)) {
//                val = this.val;
//                ver = this.ver;
//                valBytes = valueBytesUnlocked();
//
//                if (valBytes.isNull() && cctx.offheapTiered() && valPtr != 0)
//                    valBytes = offheapValueBytes();
//            }
//            else
//                ver = null;
//        }
//
//        if (valBytes.isNull()) {
//            if (val != null)
//                valBytes = (val instanceof byte[]) ? GridCacheValueBytes.plain(val) :
//                    GridCacheValueBytes.marshaled(CU.marshal(cctx.shared(), val));
//
//            if (ver != null && !isOffHeapValuesOnly()) {
//                synchronized (this) {
//                    checkObsolete();
//
//                    if (this.val == val)
//                        this.valBytes = isStoreValueBytes() ? valBytes.getIfMarshaled() : null;
//                }
//            }
//        }

        return valBytes;
    }

    /**
     * Updates cache index.
     *
     * @param val Value.
     * @param valBytes Value bytes.
     * @param expireTime Expire time.
     * @param ver New entry version.
     * @param prevVal Previous value.
     * @throws IgniteCheckedException If update failed.
     */
    protected void updateIndex(@Nullable CacheObject val,
        @Nullable byte[] valBytes,
        long expireTime,
        GridCacheVersion ver,
        @Nullable CacheObject prevVal) throws IgniteCheckedException {
        assert Thread.holdsLock(this);
        assert val != null || valBytes != null : "null values in update index for key: " + key;

        try {
            GridCacheQueryManager qryMgr = cctx.queries();

            if (qryMgr != null)
                qryMgr.store(key, null, val, valBytes, ver, expireTime);
        }
        catch (IgniteCheckedException e) {
            throw new GridCacheIndexUpdateException(e);
        }
    }

    /**
     * Clears index.
     *
     * @param prevVal Previous value (if needed for index update).
     * @throws IgniteCheckedException If failed.
     */
    protected void clearIndex(@Nullable CacheObject prevVal) throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        try {
            GridCacheQueryManager<?, ?> qryMgr = cctx.queries();

            if (qryMgr != null)
                qryMgr.remove(key());
        }
        catch (IgniteCheckedException e) {
            throw new GridCacheIndexUpdateException(e);
        }
    }

    /**
     * This method will return current value only if clearIndex(V) will require previous value (this is the case
     * for Mongo caches). If previous value is not required, this method will return {@code null}.
     *
     * @return Previous value or {@code null}.
     * @throws IgniteCheckedException If failed to retrieve previous value.
     */
    protected CacheObject saveValueForIndexUnlocked() throws IgniteCheckedException {
        assert Thread.holdsLock(this);

        if (!cctx.cache().isMongoDataCache() && !cctx.cache().isMongoMetaCache())
            return null;

        return rawGetOrUnmarshalUnlocked(false);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <K, V> Cache.Entry<K, V> wrap() {
        try {
            IgniteInternalTx tx = cctx.tm().userTx();

            CacheObject val;

            if (tx != null) {
                GridTuple<CacheObject> peek = tx.peek(cctx, false, key, null);

                val = peek == null ? rawGetOrUnmarshal(false) : peek.get();
            }
            else
                val = rawGetOrUnmarshal(false);

            return new CacheEntryImpl<>(key.<K>value(cctx, false), val != null ? val.<V>value(cctx, false) : null);
        }
        catch (GridCacheFilterFailedException ignored) {
            throw new IgniteException("Should never happen.");
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to wrap entry: " + this, e);
        }
    }

    /** {@inheritDoc} */
    @Override public <K, V> Cache.Entry<K, V> wrapLazyValue() {
        return new LazyValueEntry(key);
    }

        /** {@inheritDoc} */
    @Override public <K, V> Cache.Entry<K, V> wrapFilterLocked() throws IgniteCheckedException {
        CacheObject val = rawGetOrUnmarshal(true);

        return new CacheEntryImpl<>(key.<K>value(cctx, false), val != null ? val.<V>value(cctx, false) : null) ;
    }

    /** {@inheritDoc} */
    @Override public <K, V> EvictableEntry<K, V> wrapEviction() {
        return new EvictableEntryImpl<>(this);
    }

    /** {@inheritDoc} */
    @Override public synchronized <K, V> CacheVersionedEntryImpl<K, V> wrapVersioned() {
        return new CacheVersionedEntryImpl<>(key.<K>value(cctx, false), null, ver);
    }

    /** {@inheritDoc} */
    @Override public <K, V> boolean evictInternal(boolean swap, GridCacheVersion obsoleteVer,
        @Nullable IgnitePredicate<Cache.Entry<K, V>>[] filter) throws IgniteCheckedException {
        boolean marked = false;

        try {
            if (F.isEmptyOrNulls(filter)) {
                synchronized (this) {
                    CacheObject prev = saveValueForIndexUnlocked();

                    if (!hasReaders() && markObsolete0(obsoleteVer, false)) {
                        if (swap) {
                            if (!isStartVersion()) {
                                try {
                                    // Write to swap.
                                    swap();
                                }
                                catch (IgniteCheckedException e) {
                                    U.error(log, "Failed to write entry to swap storage: " + this, e);
                                }
                            }
                        }
                        else
                            clearIndex(prev);

                        // Nullify value after swap.
                        value(null, null);

                        marked = true;

                        return true;
                    }
                }
            }
            else {
                // For optimistic check.
                while (true) {
                    GridCacheVersion v;

                    synchronized (this) {
                        v = ver;
                    }

                    if (!cctx.isAll(/*version needed for sync evicts*/this.<K, V>wrapVersioned(), filter))
                        return false;

                    synchronized (this) {
                        if (!v.equals(ver))
                            // Version has changed since entry passed the filter. Do it again.
                            continue;

                        CacheObject prevVal = saveValueForIndexUnlocked();

                        if (!hasReaders() && markObsolete0(obsoleteVer, false)) {
                            if (swap) {
                                if (!isStartVersion()) {
                                    try {
                                        // Write to swap.
                                        swap();
                                    }
                                    catch (IgniteCheckedException e) {
                                        U.error(log, "Failed to write entry to swap storage: " + this, e);
                                    }
                                }
                            }
                            else
                                clearIndex(prevVal);

                            // Nullify value after swap.
                            value(null, null);

                            marked = true;

                            return true;
                        }
                        else
                            return false;
                    }
                }
            }
        }
        catch (GridCacheEntryRemovedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Got removed entry when evicting (will simply return): " + this);

            return true;
        }
        finally {
            if (marked)
                onMarkedObsolete();
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public GridCacheBatchSwapEntry evictInBatchInternal(GridCacheVersion obsoleteVer)
        throws IgniteCheckedException {
        assert Thread.holdsLock(this);
        assert cctx.isSwapOrOffheapEnabled();

        GridCacheBatchSwapEntry ret = null;

        try {
            if (!hasReaders() && markObsolete0(obsoleteVer, false)) {
                if (!isStartVersion() && hasValueUnlocked()) {
                    IgniteUuid valClsLdrId = null;

                    if (val != null)
                        valClsLdrId = cctx.deploy().getClassLoaderId(U.detectObjectClassLoader(val.value(cctx, false)));

                    IgniteBiTuple<byte[], Boolean> valBytes = valueBytes0();

                    ret = new GridCacheBatchSwapEntry(key(),
                        partition(),
                        ByteBuffer.wrap(valBytes.get1()),
                        valBytes.get2(),
                        ver,
                        ttlExtras(),
                        expireTimeExtras(),
                        cctx.deploy().getClassLoaderId(U.detectObjectClassLoader(key.value(cctx, false))),
                        valClsLdrId);
                }

                value(null, null);
            }
        }
        catch (GridCacheEntryRemovedException ignored) {
            if (log.isDebugEnabled())
                log.debug("Got removed entry when evicting (will simply return): " + this);
        }

        return ret;
    }

    /**
     * @param filter Entry filter.
     * @return {@code True} if entry is visitable.
     */
    public <K, V> boolean visitable(IgnitePredicate<Cache.Entry<K, V>>[] filter) {
        try {
            if (obsoleteOrDeleted() || (filter != CU.<K, V>empty() &&
                !cctx.isAll(this.<K, V> wrapLazyValue(), filter)))
                return false;
        }
        catch (IgniteCheckedException e) {
            U.error(log, "An exception was thrown while filter checking.", e);

            RuntimeException ex = e.getCause(RuntimeException.class);

            if (ex != null)
                throw ex;

            Error err = e.getCause(Error.class);

            if (err != null)
                throw err;

            return false;
        }

        IgniteInternalTx tx = cctx.tm().localTxx();

        return tx == null || !tx.removed(txKey());
    }

    /**
     * Ensures that internal data storage is created.
     *
     * @param size Amount of data to ensure.
     * @return {@code true} if data storage was created.
     */
    private boolean ensureData(int size) {
        if (attributeDataExtras() == null) {
            attributeDataExtras(new GridLeanMap<String, Object>(size));

            return true;
        }
        else
            return false;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 addMeta(String name, V1 val) {
        A.notNull(name, "name", val, "val");

        synchronized (this) {
            ensureData(1);

            return (V1)attributeDataExtras().put(name, val);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 meta(String name) {
        A.notNull(name, "name");

        synchronized (this) {
            GridLeanMap<String, Object> attrData = attributeDataExtras();

            return attrData == null ? null : (V1)attrData.get(name);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 removeMeta(String name) {
        A.notNull(name, "name");

        synchronized (this) {
            GridLeanMap<String, Object> attrData = attributeDataExtras();

            if (attrData == null)
                return null;

            V1 old = (V1)attrData.remove(name);

            if (attrData.isEmpty())
                attributeDataExtras(null);

            return old;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public <V1> boolean removeMeta(String name, V1 val) {
        A.notNull(name, "name", val, "val");

        synchronized (this) {
            GridLeanMap<String, Object> attrData = attributeDataExtras();

            if (attrData == null)
                return false;

            V1 old = (V1)attrData.get(name);

            if (old != null && old.equals(val)) {
                attrData.remove(name);

                if (attrData.isEmpty())
                    attributeDataExtras(null);

                return true;
            }

            return false;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean hasMeta(String name) {
        return meta(name) != null;
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Nullable @Override public <V1> V1 putMetaIfAbsent(String name, V1 val) {
        A.notNull(name, "name", val, "val");

        synchronized (this) {
            V1 v = meta(name);

            if (v == null)
                return addMeta(name, val);

            return v;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked", "ClassReferencesSubclass"})
    @Nullable @Override public <V1> V1 putMetaIfAbsent(String name, Callable<V1> c) {
        A.notNull(name, "name", c, "c");

        synchronized (this) {
            V1 v = meta(name);

            if (v == null)
                try {
                    return addMeta(name, c.call());
                }
                catch (Exception e) {
                    throw F.wrap(e);
                }

            return v;
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Override public <V1> boolean replaceMeta(String name, V1 curVal, V1 newVal) {
        A.notNull(name, "name", newVal, "newVal", curVal, "curVal");

        synchronized (this) {
            if (hasMeta(name)) {
                V1 val = this.<V1>meta(name);

                if (val != null && val.equals(curVal)) {
                    addMeta(name, newVal);

                    return true;
                }
            }

            return false;
        }
    }

    /**
     * Convenience way for super-classes which implement {@link Externalizable} to
     * serialize metadata. Super-classes must call this method explicitly from
     * within {@link Externalizable#writeExternal(ObjectOutput)} methods implementation.
     *
     * @param out Output to write to.
     * @throws IOException If I/O error occurred.
     */
    @SuppressWarnings({"TooBroadScope"})
    protected void writeExternalMeta(ObjectOutput out) throws IOException {
        Map<String, Object> cp;

        // Avoid code warning (suppressing is bad here, because we need this warning for other places).
        synchronized (this) {
            cp = new GridLeanMap<>(attributeDataExtras());
        }

        out.writeObject(cp);
    }

    /**
     * Convenience way for super-classes which implement {@link Externalizable} to
     * serialize metadata. Super-classes must call this method explicitly from
     * within {@link Externalizable#readExternal(ObjectInput)} methods implementation.
     *
     * @param in Input to read from.
     * @throws IOException If I/O error occurred.
     * @throws ClassNotFoundException If some class could not be found.
     */
    @SuppressWarnings({"unchecked"})
    protected void readExternalMeta(ObjectInput in) throws IOException, ClassNotFoundException {
        GridLeanMap<String, Object> cp = (GridLeanMap<String, Object>)in.readObject();

        synchronized (this) {
            attributeDataExtras(cp);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean deleted() {
        if (!cctx.deferredDelete())
            return false;

        synchronized (this) {
            return deletedUnlocked();
        }
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean obsoleteOrDeleted() {
        return obsoleteVersionExtras() != null ||
            (cctx.deferredDelete() && (deletedUnlocked() || !hasValueUnlocked()));
    }

    /**
     * @return {@code True} if deleted.
     */
    @SuppressWarnings("SimplifiableIfStatement")
    protected boolean deletedUnlocked() {
        assert Thread.holdsLock(this);

        if (!cctx.deferredDelete())
            return false;

        return (flags & IS_DELETED_MASK) != 0;
    }

    /**
     * @param deleted {@code True} if deleted.
     */
    protected void deletedUnlocked(boolean deleted) {
        assert Thread.holdsLock(this);
        assert cctx.deferredDelete();

        if (deleted) {
            assert !deletedUnlocked();

            flags |= IS_DELETED_MASK;

            cctx.decrementPublicSize(this);
        }
        else {
            assert deletedUnlocked();

            flags &= ~IS_DELETED_MASK;

            cctx.incrementPublicSize(this);
        }
    }

    /**
     * @return Attribute data.
     */
    @Nullable private GridLeanMap<String, Object> attributeDataExtras() {
        return extras != null ? extras.attributesData() : null;
    }

    /**
     * @param attrData Attribute data.
     */
    private void attributeDataExtras(@Nullable GridLeanMap<String, Object> attrData) {
        extras = (extras != null) ? extras.attributesData(attrData) : attrData != null ?
            new GridCacheAttributesEntryExtras(attrData) : null;
    }

    /**
     * @return MVCC.
     */
    @Nullable protected GridCacheMvcc mvccExtras() {
        return extras != null ? extras.mvcc() : null;
    }

    /**
     * @param mvcc MVCC.
     */
    protected void mvccExtras(@Nullable GridCacheMvcc mvcc) {
        extras = (extras != null) ? extras.mvcc(mvcc) : mvcc != null ? new GridCacheMvccEntryExtras(mvcc) : null;
    }

    /**
     * @return Obsolete version.
     */
    @Nullable protected GridCacheVersion obsoleteVersionExtras() {
        return extras != null ? extras.obsoleteVersion() : null;
    }

    /**
     * @param obsoleteVer Obsolete version.
     */
    protected void obsoleteVersionExtras(@Nullable GridCacheVersion obsoleteVer) {
        extras = (extras != null) ? extras.obsoleteVersion(obsoleteVer) : obsoleteVer != null ?
            new GridCacheObsoleteEntryExtras(obsoleteVer) : null;
    }

    /**
     * Updates metrics.
     *
     * @param op Operation.
     * @param metrics Update merics flag.
     */
    private void updateMetrics(GridCacheOperation op, boolean metrics) {
        if (metrics && cctx.cache().configuration().isStatisticsEnabled()) {
            if (op == GridCacheOperation.DELETE)
                cctx.cache().metrics0().onRemove();
            else
                cctx.cache().metrics0().onWrite();
        }
    }

    /**
     * @return TTL.
     */
    public long ttlExtras() {
        return extras != null ? extras.ttl() : 0;
    }

    /**
     * @return Expire time.
     */
    public long expireTimeExtras() {
        return extras != null ? extras.expireTime() : 0L;
    }

    /**
     * @param ttl TTL.
     * @param expireTime Expire time.
     */
    protected void ttlAndExpireTimeExtras(long ttl, long expireTime) {
        assert ttl != CU.TTL_NOT_CHANGED && ttl != CU.TTL_ZERO;

        extras = (extras != null) ? extras.ttlAndExpireTime(ttl, expireTime) : ttl != CU.TTL_ETERNAL ?
            new GridCacheTtlEntryExtras(ttl, expireTime) : null;
    }

    /**
     * @return Size of extras object.
     */
    private int extrasSize() {
        return extras != null ? extras.size() : 0;
    }

    /**
     * @return Value bytes read from offheap.
     * @throws IgniteCheckedException If failed.
     */
    private GridCacheValueBytes offheapValueBytes() throws IgniteCheckedException {
        assert cctx.offheapTiered() && valPtr != 0;

        long ptr = valPtr;

        boolean plainByteArr = UNSAFE.getByte(ptr++) != 0;

        if (plainByteArr || !cctx.portableEnabled()) {
            int size = UNSAFE.getInt(ptr);

            byte[] bytes = U.copyMemory(ptr + 4, size);

            return plainByteArr ? GridCacheValueBytes.plain(bytes) : GridCacheValueBytes.marshaled(bytes);
        }

        assert cctx.portableEnabled();

        return GridCacheValueBytes.marshaled(CU.marshal(cctx.shared(), cctx.portable().unmarshal(valPtr, true)));
    }

    /**
     * @param tmp If {@code true} can return temporary object.
     * @return Unmarshalled value.
     * @throws IgniteCheckedException If unmarshalling failed.
     */
    private CacheObject unmarshalOffheap(boolean tmp) throws IgniteCheckedException {
        assert cctx.offheapTiered() && valPtr != 0;

        if (cctx.portableEnabled())
            return (CacheObject)cctx.portable().unmarshal(valPtr, !tmp);

        long ptr = valPtr;

        boolean plainByteArr = UNSAFE.getByte(ptr++) != 0;

        int size = UNSAFE.getInt(ptr);

        byte[] res = U.copyMemory(ptr + 4, size);

// TODO IGNITE-51.
//        if (plainByteArr)
//            return (V)res;

        IgniteUuid valClsLdrId = U.readGridUuid(ptr + 4 + size);

        ClassLoader ldr = valClsLdrId != null ? cctx.deploy().getClassLoader(valClsLdrId) :
            cctx.deploy().localLoader();

        return cctx.marshaller().unmarshal(res, ldr);
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        // Identity comparison left on purpose.
        return o == this;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return hash;
    }

    /** {@inheritDoc} */
    @Override public synchronized String toString() {
        return S.toString(GridCacheMapEntry.class, this);
    }

    /**
     *
     */
    private class LazyValueEntry<K, V> implements Cache.Entry<K, V> {
        /** */
        private final KeyCacheObject key;

        /**
         * @param key Key.
         */
        private LazyValueEntry(KeyCacheObject key) {
            this.key = key;
        }

        /** {@inheritDoc} */
        @Override public K getKey() {
            return key.value(cctx, false);
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public V getValue() {
            try {
                IgniteInternalTx tx = cctx.tm().userTx();

                if (tx != null) {
                    GridTuple<CacheObject> peek = tx.peek(cctx, false, key, null);

                    if (peek != null)
                        return peek.get().value(cctx, false);
                }

                if (detached())
                    return rawGet().value(cctx, false);

                for (;;) {
                    GridCacheEntryEx e = cctx.cache().peekEx(key);

                    if (e == null)
                        return null;

                    try {
                        return CU.value(e.peek(GridCachePeekMode.GLOBAL, CU.<K, V>empty()), cctx, false);
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        // No-op.
                    }
                }
            }
            catch (GridCacheFilterFailedException ignored) {
                throw new IgniteException("Should never happen.");
            }
        }

        /** {@inheritDoc} */
        @SuppressWarnings("unchecked")
        @Override public <T> T unwrap(Class<T> cls) {
            if (cls.isAssignableFrom(IgniteCache.class))
                return (T)cctx.grid().jcache(cctx.name());

            if (cls.isAssignableFrom(getClass()))
                return (T)this;

            if (cls.isAssignableFrom(EvictableEntry.class))
                return (T)wrapEviction();

            if (cls.isAssignableFrom(CacheVersionedEntryImpl.class))
                return (T)wrapVersioned();

            if (cls.isAssignableFrom(GridCacheMapEntry.this.getClass()))
                return (T)GridCacheMapEntry.this;

            throw new IllegalArgumentException("Unwrapping to class is not supported: " + cls);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "IteratorEntry [key=" + key + ']';
        }
    }
}
