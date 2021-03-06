/**
 * (c) Copyright 2012 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.schema.impl;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.annotations.ApiAudience;
import org.kiji.schema.EntityId;
import org.kiji.schema.EntityIdFactory;
import org.kiji.schema.InternalKijiError;
import org.kiji.schema.Kiji;
import org.kiji.schema.KijiIOException;
import org.kiji.schema.KijiReaderFactory;
import org.kiji.schema.KijiRegion;
import org.kiji.schema.KijiTable;
import org.kiji.schema.KijiTableReader;
import org.kiji.schema.KijiTableWriter;
import org.kiji.schema.KijiURI;
import org.kiji.schema.KijiWriterFactory;
import org.kiji.schema.RuntimeInterruptedException;
import org.kiji.schema.avro.RowKeyFormat;
import org.kiji.schema.avro.RowKeyFormat2;
import org.kiji.schema.hbase.KijiManagedHBaseTableName;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.layout.impl.ColumnNameTranslator;
import org.kiji.schema.layout.impl.TableLayoutMonitor;
import org.kiji.schema.layout.impl.TableLayoutMonitor.LayoutTracker;
import org.kiji.schema.layout.impl.TableLayoutMonitor.LayoutUpdateHandler;
import org.kiji.schema.layout.impl.ZooKeeperClient;
import org.kiji.schema.util.Debug;
import org.kiji.schema.util.JvmId;
import org.kiji.schema.util.VersionInfo;

/**
 * <p>A KijiTable that exposes the underlying HBase implementation.</p>
 *
 * <p>Within the internal Kiji code, we use this class so that we have
 * access to the HTable interface.  Methods that Kiji clients should
 * have access to should be added to org.kiji.schema.KijiTable.</p>
 */
@ApiAudience.Private
public final class HBaseKijiTable implements KijiTable {
  private static final Logger LOG = LoggerFactory.getLogger(HBaseKijiTable.class);
  private static final Logger CLEANUP_LOG =
      LoggerFactory.getLogger("cleanup." + HBaseKijiTable.class.getName());

  /** The kiji instance this table belongs to. */
  private final HBaseKiji mKiji;

  /** The name of this table (the Kiji name, not the HBase name). */
  private final String mName;

  /** URI of this table. */
  private final KijiURI mTableURI;

  /** Whether the table is open. */
  private final AtomicBoolean mIsOpen = new AtomicBoolean(false);

  /** String representation of the call stack at the time this object is constructed. */
  private final String mConstructorStack;

  /** HTableInterfaceFactory for creating new HTables associated with this KijiTable. */
  private final HTableInterfaceFactory mHTableFactory;

  /** Factory for HTableInterface, compatible with the HTablePool. */
  private final HBaseTableInterfaceFactory mHBaseTableFactory;

  /** The factory for EntityIds. */
  private final EntityIdFactory mEntityIdFactory;

  /** Retain counter. When decreased to 0, the HBase KijiTable may be closed and disposed of. */
  private final AtomicInteger mRetainCount = new AtomicInteger(0);

  /** Configuration object for new HTables. */
  private final Configuration mConf;

  /** Writer factory for this table. */
  private final KijiWriterFactory mWriterFactory;

  /** Reader factory for this table. */
  private final KijiReaderFactory mReaderFactory;

  /** Pool of HTable connections. Safe for concurrent access. */
  private final HTablePool mHTablePool;

  /** Name of the HBase table backing this Kiji table. */
  private final String mHBaseTableName;

  /** Unique identifier for this KijiTable instance as a live Kiji client. */
  private final String mKijiClientId =
      String.format("%s;HBaseKijiTable@%s", JvmId.get(), System.identityHashCode(this));

  /** Monitor for the layout of this table. */
  private final TableLayoutMonitor mLayoutMonitor;

  /**
   * The LayoutUpdateHandler which performs layout modifications. This object's update() method is
   * called by mLayoutTracker
   */
  private final InnerLayoutUpdater mInnerLayoutUpdater = new InnerLayoutUpdater();

  /**
   * Listener for updates to this table's layout in ZooKeeper.  Calls mInnerLayoutUpdater.update()
   * in response to a change to the ZooKeeper node representing this table's layout.
   */
  private final LayoutTracker mLayoutTracker;

  /**
   * Capsule containing all objects which should be mutated in response to a table layout update.
   * The capsule itself is immutable and should be replaced atomically with a new capsule.
   * References only the LayoutCapsule for the most recent layout for this table.
   */
  private volatile LayoutCapsule mLayoutCapsule = null;

  /**
   * Monitor used to delay construction of this KijiTable until the LayoutCapsule is initialized
   * from ZooKeeper.  This lock should not be used to synchronize any other operations.
   */
  private final Object mLayoutCapsuleInitializationLock = new Object();

  /**
   * Set of outstanding layout consumers associated with this table.  Updating the layout of this
   * table requires calling
   * {@link LayoutConsumer#update(org.kiji.schema.impl.HBaseKijiTable.LayoutCapsule)} on all
   * registered consumers.
   */
  private final Set<LayoutConsumer> mLayoutConsumers = new HashSet<LayoutConsumer>();

  /**
   * Container class encapsulating the KijiTableLayout and related objects which must all reflect
   * layout updates atomically.  This object represents a snapshot of the table layout at a moment
   * in time which is valuable for maintaining consistency within a short-lived operation.  Because
   * this object represents a snapshot it should not be cached.
   * Does not include CellDecoderProvider or CellEncoderProvider because
   * readers and writers need to be able to override CellSpecs.  Does not include EntityIdFactory
   * because currently there are no valid table layout updates that modify the row key encoding.
   */
  public static final class LayoutCapsule {
    private final KijiTableLayout mLayout;
    private final ColumnNameTranslator mTranslator;
    private final KijiTable mTable;

    /**
     * Default constructor.
     *
     * @param layout the layout of the table.
     * @param translator the ColumnNameTranslator for the given layout.
     * @param table the KijiTable to which this capsule is associated.
     */
    private LayoutCapsule(
        final KijiTableLayout layout,
        final ColumnNameTranslator translator,
        final KijiTable table) {
      mLayout = layout;
      mTranslator = translator;
      mTable = table;
    }

    /**
     * Get the KijiTableLayout for the associated layout.
     * @return the KijiTableLayout for the associated layout.
     */
    public KijiTableLayout getLayout() {
      return mLayout;
    }

    /**
     * Get the ColumnNameTranslator for the associated layout.
     * @return the ColumnNameTranslator for the associated layout.
     */
    public ColumnNameTranslator getColumnNameTranslator() {
      return mTranslator;
    }

    /**
     * Get the KijiTable to which this capsule is associated.
     * @return the KijiTable to which this capsule is associated.
     */
    public KijiTable getTable() {
      return mTable;
    }
  }

  /** Updates the layout of this table in response to a layout update pushed from ZooKeeper. */
  private final class InnerLayoutUpdater implements LayoutUpdateHandler {
    /** {@inheritDoc} */
    @Override
    public void update(final byte[] layoutBytes) {
      final String currentLayoutId =
          (mLayoutCapsule == null) ? null : mLayoutCapsule.getLayout().getDesc().getLayoutId();
      final String newLayoutId = Bytes.toString(layoutBytes);
      if (currentLayoutId == null) {
        LOG.info("Setting initial layout for table {} to layout ID {}.",
            mTableURI, newLayoutId);
      } else {
        LOG.info("Updating layout for table {} from layout ID {} to layout ID {}.",
            mTableURI, currentLayoutId, newLayoutId);
      }

      // TODO(SCHEMA-503): the meta-table doesn't provide a way to look-up a layout by ID:
      final KijiTableLayout newLayout = getMostRecentLayout();
      Preconditions.checkState(
          Objects.equal(newLayout.getDesc().getLayoutId(), newLayoutId),
          "New layout ID %s does not match most recent layout ID %s from meta-table.",
          newLayoutId, newLayout.getDesc().getLayoutId());

      mLayoutCapsule =
          new LayoutCapsule(newLayout, new ColumnNameTranslator(newLayout), HBaseKijiTable.this);

      // Propagates the new layout to all consumers:
      synchronized (mLayoutConsumers) {
        for (LayoutConsumer consumer : mLayoutConsumers) {
          try {
            consumer.update(mLayoutCapsule);
          } catch (IOException ioe) {
            // TODO(SCHEMA-505): Handle exceptions decently
            throw new KijiIOException(ioe);
          }
        }
      }

      // Registers this KijiTable in ZooKeeper as a user of the new table layout,
      // and unregisters as a user of the former table layout.
      try {
        mLayoutMonitor.registerTableUser(mTableURI, mKijiClientId, newLayoutId);
        if (currentLayoutId != null) {
          mLayoutMonitor.unregisterTableUser(mTableURI, mKijiClientId, currentLayoutId);
        }
      } catch (KeeperException ke) {
        // TODO(SCHEMA-505): Handle exceptions decently
        throw new KijiIOException(ke);
      }

      // Now, there is a layout defined for the table, KijiTable constructor may complete:
      synchronized (mLayoutCapsuleInitializationLock) {
        mLayoutCapsuleInitializationLock.notifyAll();
      }
    }

    /**
     * Reads the most recent layout of this Kiji table from the Kiji instance meta-table.
     *
     * @return the most recent layout of this Kiji table from the Kiji instance meta-table.
     */
    private KijiTableLayout getMostRecentLayout() {
      try {
        return getKiji().getMetaTable().getTableLayout(getName());
      } catch (IOException ioe) {
        throw new KijiIOException(ioe);
      }
    }
  }

  /**
   * Construct an opened Kiji table stored in HBase.
   *
   * @param kiji The Kiji instance.
   * @param name The name of the Kiji user-space table to open.
   * @param conf The Hadoop configuration object.
   * @param htableFactory A factory that creates HTable objects.
   *
   * @throws IOException On an HBase error.
   *     <p> Throws KijiTableNotFoundException if the table does not exist. </p>
   */
  HBaseKijiTable(
      HBaseKiji kiji,
      String name,
      Configuration conf,
      HTableInterfaceFactory htableFactory)
      throws IOException {

    mConstructorStack = CLEANUP_LOG.isDebugEnabled() ? Debug.getStackTrace() : null;

    mKiji = kiji;
    mName = name;
    mHTableFactory = htableFactory;
    mConf = conf;
    mTableURI = KijiURI.newBuilder(mKiji.getURI()).withTableName(mName).build();
    LOG.debug("Opening Kiji table '{}' with client version '{}'.",
        mTableURI, VersionInfo.getSoftwareVersion());
    mHBaseTableName =
        KijiManagedHBaseTableName.getKijiTableName(mTableURI.getInstance(), mName).toString();

    // TODO(SCHEMA-504): Add a manual switch to disable ZooKeeper on system-2.0 instances:
    if (getKiji().getSystemTable().getDataVersion().compareTo(VersionInfo.SYSTEM_2_0) >= 0) {
      // system-2.0 clients retrieve the table layout from ZooKeeper notifications:

      mLayoutMonitor = createLayoutMonitor(mKiji.getZKClient());
      mLayoutTracker = mLayoutMonitor.newTableLayoutTracker(mTableURI, mInnerLayoutUpdater);
      // Opening the LayoutTracker will trigger an initial layout update after a short delay.
      mLayoutTracker.open();

      // Wait for the LayoutCapsule to be initialized by a call to InnerLayoutUpdater.update()
      waitForLayoutInitialized();
    } else {
      // system-1.x clients retrieve the table layout from the meta-table:

      // throws KijiTableNotFoundException
      final KijiTableLayout layout = mKiji.getMetaTable().getTableLayout(name);
      mLayoutMonitor = null;
      mLayoutTracker = null;
      mLayoutCapsule = new LayoutCapsule(layout, new ColumnNameTranslator(layout), this);
    }

    mWriterFactory = new HBaseKijiWriterFactory(this);
    mReaderFactory = new HBaseKijiReaderFactory(this);

    mEntityIdFactory = createEntityIdFactory(mLayoutCapsule);

    mHBaseTableFactory = new HBaseTableInterfaceFactory();
    mHTablePool = new HTablePool(mConf, Integer.MAX_VALUE, mHBaseTableFactory);

    // Retain the Kiji instance only if open succeeds:
    mKiji.retain();

    // Table is now open and must be released properly:
    mIsOpen.set(true);
    mRetainCount.set(1);
  }

  /**
   * Constructs a new table layout monitor.
   *
   * @param zkClient ZooKeeperClient to use.
   * @return a new table layout monitor.
   * @throws IOException on ZooKeeper error (wrapped KeeperException).
   */
  private static TableLayoutMonitor createLayoutMonitor(ZooKeeperClient zkClient)
      throws IOException {
    try {
      return new TableLayoutMonitor(zkClient);
    } catch (KeeperException ke) {
      throw new IOException(ke);
    }
  }

  /**
   * Waits until the table layout is initialized.
   */
  private void waitForLayoutInitialized() {
    synchronized (mLayoutCapsuleInitializationLock) {
      while (mLayoutCapsule == null) {
        try {
          mLayoutCapsuleInitializationLock.wait();
        } catch (InterruptedException ie) {
          throw new RuntimeInterruptedException(ie);
        }
      }
    }
  }

  /**
   * Constructs an Entity ID factory from a layout capsule.
   *
   * @param capsule Layout capsule to construct an entity ID factory from.
   * @return a new entity ID factory as described from the table layout.
   */
  private static EntityIdFactory createEntityIdFactory(final LayoutCapsule capsule) {
    final Object format = capsule.getLayout().getDesc().getKeysFormat();
    if (format instanceof RowKeyFormat) {
      return EntityIdFactory.getFactory((RowKeyFormat) format);
    } else if (format instanceof RowKeyFormat2) {
      return EntityIdFactory.getFactory((RowKeyFormat2) format);
    } else {
      throw new RuntimeException("Invalid Row Key format found in Kiji Table: " + format);
    }
  }

  /** {@inheritDoc} */
  @Override
  public EntityId getEntityId(Object... kijiRowKey) {
    return mEntityIdFactory.getEntityId(kijiRowKey);
  }

  /** {@inheritDoc} */
  @Override
  public Kiji getKiji() {
    return mKiji;
  }

  /** {@inheritDoc} */
  @Override
  public String getName() {
    return mName;
  }

  /** {@inheritDoc} */
  @Override
  public KijiURI getURI() {
    return mTableURI;
  }

  /**
   * Register a layout consumer that must be updated before this table will report that it has
   * completed a table layout update.  Sends the first update immediately before returning.
   *
   * @param consumer the LayoutConsumer to be registered.
   * @throws IOException in case of an error updating the LayoutConsumer.
   */
  public void registerLayoutConsumer(LayoutConsumer consumer) throws IOException {
    synchronized (mLayoutConsumers) {
      mLayoutConsumers.add(consumer);
    }
    consumer.update(mLayoutCapsule);
  }

  /**
   * Unregister a layout consumer so that it will not be updated when this table performs a layout
   * update.  Should only be called when a consumer is closed.
   *
   * @param consumer the LayoutConsumer to unregister.
   */
  public void unregisterLayoutConsumer(LayoutConsumer consumer) {
    synchronized (mLayoutConsumers) {
      mLayoutConsumers.remove(consumer);
    }
  }

  /**
   * <p>
   * Get the set of registered layout consumers.  All layout consumers should be updated using
   * {@link LayoutConsumer#update(org.kiji.schema.impl.HBaseKijiTable.LayoutCapsule)} before this
   * table reports that it has successfully update its layout.
   * </p>
   * <p>
   * This method is package private for testing purposes only.  It should not be used externally.
   * </p>
   * @return the set of registered layout consumers.
   */
  Set<LayoutConsumer> getLayoutConsumers() {
    synchronized (mLayoutConsumers) {
      return ImmutableSet.copyOf(mLayoutConsumers);
    }
  }

  /**
   * Update all registered LayoutConsumers with a new KijiTableLayout.
   *
   * This method is package private for testing purposes only.  It should not be used externally.
   *
   * @param layout the new KijiTableLayout with which to update consumers.
   * @throws IOException in case of an error updating LayoutConsumers.
   */
  void updateLayoutConsumers(KijiTableLayout layout) throws IOException {
    final LayoutCapsule capsule = new LayoutCapsule(layout, new ColumnNameTranslator(layout), this);
    synchronized (mLayoutConsumers) {
      for (LayoutConsumer consumer : mLayoutConsumers) {
        consumer.update(capsule);
      }
    }
  }

  /**
   * Opens a new connection to the HBase table backing this Kiji table.
   *
   * <p> The caller is responsible for properly closing the connection afterwards. </p>
   * <p>
   *   Note: this does not necessarily create a new HTable instance, but may instead return
   *   an already existing HTable instance from a pool managed by this HBaseKijiTable.
   *   Closing a pooled HTable instance internally moves the HTable instance back into the pool.
   * </p>
   *
   * @return A new HTable associated with this KijiTable.
   * @throws IOException in case of an error.
   */
  public HTableInterface openHTableConnection() throws IOException {
    return mHTablePool.getTable(mHBaseTableName);
  }

  /**
   * {@inheritDoc}
   * If you need both the table layout and a column name translator within a single short lived
   * operation, you should use {@link #getLayoutCapsule()} to ensure consistent state.
   */
  @Override
  public KijiTableLayout getLayout() {
    return mLayoutCapsule.getLayout();
  }

  /**
   * Get the column name translator for the current layout of this table.  Do not cache this object.
   * If you need both the table layout and a column name translator within a single short lived
   * operation, you should use {@link #getLayoutCapsule()} to ensure consistent state.
   * @return the column name translator for the current layout of this table.
   */
  public ColumnNameTranslator getColumnNameTranslator() {
    return mLayoutCapsule.getColumnNameTranslator();
  }

  /**
   * Get the LayoutCapsule containing a snapshot of the state of this table's layout and
   * corresponding ColumnNameTranslator.  Do not cache this object or its contents.
   * @return a layout capsule representing the current state of this table's layout.
   */
  public LayoutCapsule getLayoutCapsule() {
    return mLayoutCapsule;
  }

  /** {@inheritDoc} */
  @Override
  public KijiTableReader openTableReader() {
    try {
      return new HBaseKijiTableReader(this);
    } catch (IOException ioe) {
      throw new KijiIOException(ioe);
    }
  }

  /** {@inheritDoc} */
  @Override
  public KijiTableWriter openTableWriter() {
    try {
      return new HBaseKijiTableWriter(this);
    } catch (IOException ioe) {
      throw new KijiIOException(ioe);
    }
  }

  /** {@inheritDoc} */
  @Override
  public KijiReaderFactory getReaderFactory() throws IOException {
    return mReaderFactory;
  }

  /** {@inheritDoc} */
  @Override
  public KijiWriterFactory getWriterFactory() throws IOException {
    return mWriterFactory;
  }

  /**
   * Return the regions in this table as a list.
   *
   * <p>This method was copied from HFileOutputFormat of 0.90.1-cdh3u0 and modified to
   * return KijiRegion instead of ImmutableBytesWritable.</p>
   *
   * @return An ordered list of the table regions.
   * @throws IOException on I/O error.
   */
  @Override
  public List<KijiRegion> getRegions() throws IOException {
    final HBaseAdmin hbaseAdmin = ((HBaseKiji) getKiji()).getHBaseAdmin();
    final HTableInterface htable = mHTableFactory.create(mConf,  mHBaseTableName);
    try {
      final List<HRegionInfo> regions = hbaseAdmin.getTableRegions(htable.getTableName());
      final List<KijiRegion> result = Lists.newArrayList();

      // If we can get the concrete HTable, we can get location information.
      if (htable instanceof HTable) {
        LOG.debug("Casting HTableInterface to an HTable.");
        final HTable concreteHBaseTable = (HTable) htable;
        for (HRegionInfo region: regions) {
          List<HRegionLocation> hLocations =
              concreteHBaseTable.getRegionsInRange(region.getStartKey(), region.getEndKey());
          result.add(new HBaseKijiRegion(region, hLocations));
        }
      } else {
        LOG.warn("Unable to cast HTableInterface {} to an HTable.  "
            + "Creating Kiji regions without location info.", getURI());
        for (HRegionInfo region: regions) {
          result.add(new HBaseKijiRegion(region));
        }
      }

      return result;

    } finally {
      htable.close();
    }
  }

  /**
   * Releases the resources used by this table.
   *
   * @throws IOException on I/O error.
   */
  private void closeResources() throws IOException {
    final boolean opened = mIsOpen.getAndSet(false);
    Preconditions.checkState(opened,
        "HBaseKijiTable.release() on table '%s' already closed.", mTableURI);

    if (mLayoutTracker != null) {
      mLayoutTracker.close();
    }
    if (mLayoutMonitor != null) {
      // Unregister this KijiTable as a live user of the Kiji table:
      try {
        mLayoutMonitor.unregisterTableUser(
            mTableURI, mKijiClientId, mLayoutCapsule.getLayout().getDesc().getLayoutId());
      } catch (KeeperException ke) {
        throw new IOException(ke);
      }
      mLayoutMonitor.close();
    }

    LOG.debug("Closing HBaseKijiTable '{}'.", mTableURI);
    mHTablePool.close();

    mKiji.release();
    LOG.debug("HBaseKijiTable '{}' closed.", mTableURI);
  }

  /** {@inheritDoc} */
  @Override
  public KijiTable retain() {
    final int counter = mRetainCount.getAndIncrement();
    Preconditions.checkState(counter >= 1,
        "Cannot retain a closed KijiTable %s: retain counter was %s.", mTableURI, counter);
    return this;
  }

  /** {@inheritDoc} */
  @Override
  public void release() throws IOException {
    final int counter = mRetainCount.decrementAndGet();
    Preconditions.checkState(counter >= 0,
        "Cannot release closed KijiTable %s: retain counter is now %s.", mTableURI, counter);
    if (counter == 0) {
      closeResources();
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean equals(Object obj) {
    if (null == obj) {
      return false;
    }
    if (obj == this) {
      return true;
    }
    if (!getClass().equals(obj.getClass())) {
      return false;
    }
    final KijiTable other = (KijiTable) obj;

    // Equal if the two tables have the same URI:
    return mTableURI.equals(other.getURI());
  }

  /** {@inheritDoc} */
  @Override
  public int hashCode() {
    return mTableURI.hashCode();
  }

  /** {@inheritDoc} */
  @Override
  protected void finalize() throws Throwable {
    if (mIsOpen.get()) {
      CLEANUP_LOG.warn(
          "Finalizing opened HBaseKijiTable '{}' with {} retained references: "
          + "use KijiTable.release()!",
          mTableURI, mRetainCount.get());
      if (CLEANUP_LOG.isDebugEnabled()) {
        CLEANUP_LOG.debug(
            "HBaseKijiTable '{}' was constructed through:\n{}",
            mTableURI, mConstructorStack);
      }
      closeResources();
    }
    super.finalize();
  }

  /** {@inheritDoc} */
  @Override
  public String toString() {
    return Objects.toStringHelper(HBaseKijiTable.class)
        .add("id", System.identityHashCode(this))
        .add("uri", mTableURI)
        .add("retain_counter", mRetainCount.get())
        .add("layout_id", getLayoutCapsule().getLayout().getDesc().getLayoutId())
        .addValue(mIsOpen.get() ? "open" : "closed")
        .toString();
  }

  /**
   * We know that all KijiTables are really HBaseKijiTables
   * instances.  This is a convenience method for downcasting, which
   * is common within the internals of Kiji code.
   *
   * @param kijiTable The Kiji table to downcast to an HBaseKijiTable.
   * @return The given Kiji table as an HBaseKijiTable.
   */
  public static HBaseKijiTable downcast(KijiTable kijiTable) {
    if (!(kijiTable instanceof HBaseKijiTable)) {
      // This should really never happen.  Something is seriously
      // wrong with Kiji code if we get here.
      throw new InternalKijiError(
          "Found a KijiTable object that was not an instance of HBaseKijiTable.");
    }
    return (HBaseKijiTable) kijiTable;
  }

  /**
   * Creates a new HFile loader.
   *
   * @param conf Configuration object for the HFile loader.
   * @return the new HFile loader.
   */
  private static LoadIncrementalHFiles createHFileLoader(Configuration conf) {
    try {
      return new LoadIncrementalHFiles(conf); // throws Exception
    } catch (Exception exn) {
      throw new InternalKijiError(exn);
    }
  }

  /**
   * Loads partitioned HFiles directly into the regions of this Kiji table.
   *
   * @param hfilePath Path of the HFiles to load.
   * @throws IOException on I/O error.
   */
  public void bulkLoad(Path hfilePath) throws IOException {
    final LoadIncrementalHFiles loader = createHFileLoader(mConf);
    try {
      // LoadIncrementalHFiles.doBulkLoad() requires an HTable instance, not an HTableInterface:
      final HTable htable = (HTable) mHTableFactory.create(mConf, mHBaseTableName);
      try {
        final List<Path> hfilePaths = Lists.newArrayList();

        // Try to find any hfiles for partitions within the passed in path
        final FileStatus[] hfiles = FileSystem.get(mConf).globStatus(new Path(hfilePath, "*"));
        for (FileStatus hfile : hfiles) {
          String partName = hfile.getPath().getName();
          if (!partName.startsWith("_") && partName.endsWith(".hfile")) {
            Path partHFile = new Path(hfilePath, partName);
            hfilePaths.add(partHFile);
          }
        }
        if (hfilePaths.isEmpty()) {
          // If we didn't find any parts, add in the passed in parameter
          hfilePaths.add(hfilePath);
        }
        for (Path path : hfilePaths) {
          loader.doBulkLoad(path, htable);
          LOG.info("Successfully loaded: " + path.toString());
        }
      } finally {
        htable.close();
      }
    } catch (TableNotFoundException tnfe) {
      throw new InternalKijiError(tnfe);
    }
  }

  /**
   * Factory for HTableInterface, implementing the HBase factory interface.
   *
   * <p> The only purpose of this factory is to provide HTableInterface for HTablePool. </p>
   * <p> Wraps a Kiji HTableInterfaceFactory into an HBase HTableInterfaceFactory. </p>
   * <p> The HBase factory is not allowed to throw I/O Exception, and must wrap them. </p>
   */
  private final class HBaseTableInterfaceFactory
      implements org.apache.hadoop.hbase.client.HTableInterfaceFactory {
    /** {@inheritDoc} */
    @Override
    public HTableInterface createHTableInterface(Configuration conf, byte[] tableName) {
      try {
        LOG.debug("Creating new connection for Kiji table {}", mTableURI);
        return mHTableFactory.create(conf, Bytes.toString(tableName));
      } catch (IOException ioe) {
        throw new KijiIOException(ioe);
      }
    }

    /** {@inheritDoc} */
    @Override
    public void releaseHTableInterface(HTableInterface table) throws IOException {
      table.close();
    }
  }
}
