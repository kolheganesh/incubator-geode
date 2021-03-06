/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.cache;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.cache.CacheStatistics;
import com.gemstone.gemfire.cache.EntryDestroyedException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.StatisticsDisabledException;
import com.gemstone.gemfire.internal.cache.versions.VersionStamp;
import com.gemstone.gemfire.internal.cache.versions.VersionTag;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
   * A Region.Entry implementation for remote entries and all PR entries
   * 
   * @since 5.1
   * @author bruce
   */
  public class EntrySnapshot implements Region.Entry, DataSerializable {
private static final long serialVersionUID = -2139749921655693280L;
    /**
     * True if at the time this entry was created it represented a local data store.
     * False if it was fetched remotely.
     * This field is used by unit tests.
     */
    private boolean startedLocal;
    /** whether this entry has been destroyed */
    private boolean entryDestroyed;
    public transient LocalRegion region = null; 
    /**
     * the internal entry for this Entry's key
     */
    public transient NonLocalRegionEntry regionEntry; // would be final except for serialization needing default constructor

    /**
     * creates a new Entry that wraps the given RegionEntry object for the given
     * storage Region
     * @param allowTombstones TODO
     */
    public EntrySnapshot(RegionEntry regionEntry, LocalRegion dataRegion,LocalRegion region, boolean allowTombstones) {
      this.region = region;
      if (regionEntry instanceof NonLocalRegionEntry) {
        this.regionEntry = (NonLocalRegionEntry)regionEntry;
        this.startedLocal = false;
      } else {
        this.startedLocal = true;
        // note we always make these non-local now to handle PR buckets moving
        // out from under this Region.Entry.
        if (regionEntry.hasStats()) {
          this.regionEntry = new NonLocalRegionEntryWithStats(regionEntry, dataRegion, allowTombstones);
        } else {
          this.regionEntry = new NonLocalRegionEntry(regionEntry, dataRegion, allowTombstones);
        }
      }
    }

    /**
     * Used by unit tests. Only available on PR.
     * If, at the time this entry was created, it was initialized from a local data store
     * then this method returns true.
     * @since 6.0
     */
    public boolean wasInitiallyLocal() {
      return this.startedLocal;
    }
    public Object getKey() {
      checkEntryDestroyed();
      return regionEntry.getKey();
    }
    
    public VersionTag getVersionTag() {
      VersionStamp stamp = regionEntry.getVersionStamp();
      if (stamp != null) {
        return stamp.asVersionTag();
      }
      return null;
    }
    
    public Object getRawValue() {
      Object v = this.regionEntry.getValue(null);
      if (v == null) {
        return null;
      }
      if (v instanceof CachedDeserializable) {
        if (region.isCopyOnRead()) {
          v = ((CachedDeserializable)v).getDeserializedWritableCopy(null, null);
        }
        else {
          v = ((CachedDeserializable)v).getDeserializedValue(null, null);
        }
        if (v == Token.INVALID || v == Token.LOCAL_INVALID) {
          v = null;
        }
      }
      else {
        if (v == Token.INVALID || v == Token.LOCAL_INVALID) {
          v = null;
        }
        else {
          v = conditionalCopy(v);
        }
      }
      return v;
    }

    public Object getValue() {
      checkEntryDestroyed();
      return getRawValue();
    }

    /**
     * Makes a copy, if copy-on-get is enabled, of the specified object.
     * 
     * @since 4.0
     */
    private Object conditionalCopy(Object o) {
      return o;
    }

    public Object getUserAttribute() {
      checkEntryDestroyed();
      Map userAttr = region.entryUserAttributes;
      if (userAttr == null) {
        return null;
      }
      return userAttr.get(this.regionEntry.getKey());
    }

    public Object setUserAttribute(Object value) {
      checkEntryDestroyed();
      if (region.entryUserAttributes == null) {
        region.entryUserAttributes = new Hashtable();
      }
      return region.entryUserAttributes.put(this.regionEntry
          .getKey(), value);
    }

    public boolean isDestroyed() {
      if (this.entryDestroyed) {
        return true;
      }
      if (region.isDestroyed()) {
        this.entryDestroyed = true;
      }
      else if (this.regionEntry.isRemoved()) {
        this.entryDestroyed = true;
      }
      // else the entry is somewhere else and we don't know if it's destroyed
      return this.entryDestroyed;
    }

    public Region getRegion() {
      checkEntryDestroyed();
      return region;
    }
    
    public CacheStatistics getStatistics() throws StatisticsDisabledException {
      checkEntryDestroyed();
      if (!regionEntry.hasStats()
          || !region.statisticsEnabled) {
          throw new StatisticsDisabledException(LocalizedStrings.PartitionedRegion_STATISTICS_DISABLED_FOR_REGION_0.toLocalizedString(region.getFullPath()));
      }
      return new CacheStatisticsImpl(this.regionEntry, region);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof EntrySnapshot)) {
        return false;
      }
      EntrySnapshot ent = (EntrySnapshot)obj;
      return this.regionEntry.getKey().equals(ent.getKey());
    }

    @Override
    public int hashCode() {
      return this.regionEntry.getKey().hashCode();
    }

    public Object setValue(Object arg) {
      Object returnValue = region.put(this.getKey(), arg);
      this.regionEntry.setCachedValue(arg);
      return returnValue;
    }

    /*
     * (non-Javadoc)
     * @see com.gemstone.gemfire.cache.Region.Entry#isLocal()
     */
    public boolean isLocal() {
      // pr entries are always non-local to support the bucket being moved out
      // from under an entry
      return false;
    }

    @Override
    public String toString() {
      if (this.isDestroyed()) {
        return "EntrySnapshot(#destroyed#" + regionEntry.getKey()
            + "; version=" + this.getVersionTag() + ")";
      }
      else {
        return "EntrySnapshot(" + this.regionEntry + ")";
      }
    }

    /**
     * get the underlying RegionEntry object, which will not be fully functional
     * if isLocal() returns false
     * 
     * @return the underlying RegionEntry for this Entry
     */
    public RegionEntry getRegionEntry() {
      return this.regionEntry;
    }

    // ////////////////////////////////////
    // /////////////////////// P R I V A T E M E T H O D S
    // ////////////////////////////////////

    private void checkEntryDestroyed() throws EntryDestroyedException {
      if (isDestroyed()) {
        throw new EntryDestroyedException(LocalizedStrings.PartitionedRegion_ENTRY_DESTROYED.toLocalizedString());
      }
    }

    // for deserialization
    public EntrySnapshot() {
    }

    public EntrySnapshot(DataInput in,LocalRegion region) throws IOException, ClassNotFoundException {
      this.fromData(in);
      this.region = region;
    }
    
    public void setRegion(LocalRegion r) {
      this.region = r;
    }
    
    public void setRegionEntry(NonLocalRegionEntry re) {
      this.regionEntry = re;
    }

    // when externalized, we write the state of a non-local RegionEntry so it
    // can
    // be reconstituted anywhere
    public void toData(DataOutput out) throws IOException {
      out.writeBoolean(this.regionEntry instanceof NonLocalRegionEntryWithStats);
      this.regionEntry.toData(out);
    }

    public void fromData(DataInput in) throws IOException,
        ClassNotFoundException {
      this.startedLocal = false;
      boolean hasStats = in.readBoolean();
      if (hasStats) {
       this.regionEntry = new NonLocalRegionEntryWithStats();
      } else {
       this.regionEntry = new NonLocalRegionEntry();
      }
      this.regionEntry.fromData(in);
    }

  }