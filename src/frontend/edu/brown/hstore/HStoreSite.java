/***************************************************************************
 *   Copyright (C) 2012 by H-Store Project                                 *
 *   Brown University                                                      *
 *   Massachusetts Institute of Technology                                 *
 *   Yale University                                                       *
 *                                                                         *
 *   Permission is hereby granted, free of charge, to any person obtaining *
 *   a copy of this software and associated documentation files (the       *
 *   "Software"), to deal in the Software without restriction, including   *
 *   without limitation the rights to use, copy, modify, merge, publish,   *
 *   distribute, sublicense, and/or sell copies of the Software, and to    *
 *   permit persons to whom the Software is furnished to do so, subject to *
 *   the following conditions:                                             *
 *                                                                         *
 *   The above copyright notice and this permission notice shall be        *
 *   included in all copies or substantial portions of the Software.       *
 *                                                                         *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,       *
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF    *
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.*
 *   IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR     *
 *   OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, *
 *   ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR *
 *   OTHER DEALINGS IN THE SOFTWARE.                                       *
 ***************************************************************************/
package edu.brown.hstore;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.voltdb.CatalogContext;
import org.voltdb.ClientResponseImpl;
import org.voltdb.MemoryStats;
import org.voltdb.ParameterSet;
import org.voltdb.StatsAgent;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.SysProcSelector;
import org.voltdb.TransactionIdManager;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Host;
import org.voltdb.catalog.Partition;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;
import org.voltdb.compiler.AdHocPlannedStmt;
import org.voltdb.compiler.AsyncCompilerResult;
import org.voltdb.compiler.AsyncCompilerWorkThread;
import org.voltdb.exceptions.ClientConnectionLostException;
import org.voltdb.exceptions.EvictedTupleAccessException;
import org.voltdb.exceptions.MispredictionException;
import org.voltdb.exceptions.SerializableException;
import org.voltdb.exceptions.ServerFaultException;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.network.Connection;
import org.voltdb.network.VoltNetwork;
import org.voltdb.utils.DBBPool;
import org.voltdb.utils.EstTime;
import org.voltdb.utils.EstTimeUpdater;
import org.voltdb.utils.Pair;
import org.voltdb.utils.SystemStatsCollector;

import com.google.protobuf.RpcCallback;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hashing.AbstractHasher;
import edu.brown.hstore.ClientInterface.ClientInputHandler;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.Hstoreservice.WorkFragment;
import edu.brown.hstore.callbacks.ClientResponseCallback;
import edu.brown.hstore.callbacks.TransactionCleanupCallback;
import edu.brown.hstore.callbacks.TransactionFinishCallback;
import edu.brown.hstore.callbacks.TransactionInitQueueCallback;
import edu.brown.hstore.callbacks.TransactionPrepareCallback;
import edu.brown.hstore.callbacks.TransactionRedirectCallback;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.txns.AbstractTransaction;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.hstore.txns.MapReduceTransaction;
import edu.brown.hstore.txns.RemoteTransaction;
import edu.brown.hstore.util.MapReduceHelperThread;
import edu.brown.hstore.util.TxnCounter;
import edu.brown.hstore.wal.CommandLogWriter;
import edu.brown.interfaces.Loggable;
import edu.brown.interfaces.Shutdownable;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.markov.EstimationThresholds;
import edu.brown.markov.TransactionEstimator;
import edu.brown.plannodes.PlanNodeUtil;
import edu.brown.profilers.HStoreSiteProfiler;
import edu.brown.profilers.ProfileMeasurement;
import edu.brown.statistics.Histogram;
import edu.brown.utils.ClassUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.EventObservable;
import edu.brown.utils.EventObservableExceptionHandler;
import edu.brown.utils.EventObserver;
import edu.brown.utils.ParameterMangler;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.PartitionSet;

/**
 * 
 * @author pavlo
 */
public class HStoreSite implements VoltProcedureListener.Handler, Shutdownable, Loggable, Runnable {
    public static final Logger LOG = Logger.getLogger(HStoreSite.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    private static boolean d;
    private static boolean t;
    static {
        LoggerUtil.setupLogging();
        LoggerUtil.attachObserver(LOG, debug, trace);
        d = debug.get();
        t = trace.get();
    }
    
    // ----------------------------------------------------------------------------
    // INSTANCE MEMBERS
    // ----------------------------------------------------------------------------

    /**
     * The H-Store Configuration Object
     */
    private final HStoreConf hstore_conf;

    /** Catalog Stuff **/
    private long instanceId;
    private final CatalogContext catalogContext;
    private final Host catalog_host;
    private final Site catalog_site;
    private final int site_id;
    private final String site_name;
    
    /**
     * This buffer pool is used to serialize ClientResponses to send back
     * to clients.
     */
    private final DBBPool buffer_pool = new DBBPool(false, false);
    
    /**
     * Incoming request deserializer
     */
    private final IdentityHashMap<Thread, FastDeserializer> incomingDeserializers =
                        new IdentityHashMap<Thread, FastDeserializer>();
    
    /**
     * Outgoing response serializers
     */
    private final IdentityHashMap<Thread, FastSerializer> outgoingSerializers = 
                        new IdentityHashMap<Thread, FastSerializer>();
    
    /**
     * This is the object that we use to generate unqiue txn ids used by our
     * H-Store specific code. There can either be a single manager for the entire site,
     * or we can use one per partition. 
     * @see HStoreConf.site.txn_partition_id_managers
     */
    private final TransactionIdManager txnIdManagers[];

    /**
     * The TransactionInitializer is used to figure out what txns will do
     *  before we start executing them
     */
    private final TransactionInitializer txnInitializer;
    
    /**
     * This class determines what partitions transactions/queries will
     * need to execute on based on their input parameters.
     */
    private final PartitionEstimator p_estimator;
    private final AbstractHasher hasher;
    
    /**
     * All of the partitions in the cluster
     */
    private final PartitionSet all_partitions;

    /**
     * Keep track of which txns that we have in-flight right now
     */
    private final Map<Long, AbstractTransaction> inflight_txns = 
                        new ConcurrentHashMap<Long, AbstractTransaction>();
    
    /**
     * Reusable Object Pools
     */
    private final HStoreObjectPools objectPools;
    
    // ----------------------------------------------------------------------------
    // STATS STUFF
    // ----------------------------------------------------------------------------
    
    private final StatsAgent statsAgent = new StatsAgent();
    private final MemoryStats memoryStats = new MemoryStats();
    
    // ----------------------------------------------------------------------------
    // NETWORKING STUFF
    // ----------------------------------------------------------------------------
    
    /**
     * This thread is responsible for listening for incoming txn requests from 
     * clients. It will then forward the request to HStoreSite.procedureInvocation()
     */
//    private VoltProcedureListener voltListeners[];
//    private final NIOEventLoop procEventLoops[];
    
    private VoltNetwork voltNetwork;
    private ClientInterface clientInterface;
    
    // ----------------------------------------------------------------------------
    // TRANSACTION COORDINATOR/PROCESSING THREADS
    // ----------------------------------------------------------------------------
    
    /**
     * This manager is used to pin threads to specific CPU cores
     */
    private final HStoreThreadManager threadManager;
    
    /**
     * PartitionExecutors
     * These are the single-threaded execution engines that have exclusive
     * access to a partition. Any transaction that needs to access data at a partition
     * will have to first get queued up by one of these executors.
     */
    private final PartitionExecutor executors[];
    private final Thread executor_threads[];
    
    /**
     * The queue manager is responsible for deciding what distributed transaction
     * is allowed to acquire the locks for each partition. It can also requeue
     * restart transactions. 
     */
    private final TransactionQueueManager txnQueueManager;
    
    /**
     * The HStoreCoordinator is responsible for communicating with other HStoreSites
     * in the cluster to execute distributed transactions.
     * NOTE: We will bind this variable after construction so that we can inject some
     * testing code as needed.
     */
    private HStoreCoordinator hstore_coordinator;

    /**
     * TransactionPreProcessor Threads
     */
    private final List<TransactionPreProcessor> preProcessors;
    private final BlockingQueue<Pair<ByteBuffer, RpcCallback<ClientResponseImpl>>> preProcessorQueue;
    
    /**
     * TransactionPostProcessor Thread
     * These threads allow a PartitionExecutor to send back ClientResponses back to
     * the clients without blocking
     */
    private final List<TransactionPostProcessor> postProcessors;
    private final BlockingQueue<Pair<LocalTransaction, ClientResponseImpl>> postProcessorQueue;
    
    /**
     * MapReduceHelperThread
     */
    private boolean mr_helper_started = false;
    private final MapReduceHelperThread mr_helper;
    
    /**
     * Transaction Command Logger (WAL)
     */
    private final CommandLogWriter commandLogger;

    /**
     * AdHoc: This thread waits for AdHoc queries. 
     */
    private boolean adhoc_helper_started = false;
    private final AsyncCompilerWorkThread asyncCompilerWork_thread;
    
    /**
     * Anti-Cache Abstraction Layer
     */
    private final AntiCacheManager anticacheManager;
    
    /**
     * This catches any exceptions that are thrown in the various
     * threads spawned by this HStoreSite
     */
    private final EventObservableExceptionHandler exceptionHandler = new EventObservableExceptionHandler();
    
    // ----------------------------------------------------------------------------
    // INTERNAL STATE OBSERVABLES
    // ----------------------------------------------------------------------------
    
    /**
     * EventObservable for when the HStoreSite is finished initializing
     * and is now ready to execute transactions.
     */
    private boolean ready = false;
    private final EventObservable<HStoreSite> ready_observable = new EventObservable<HStoreSite>();
    
    /**
     * EventObservable for when we receive the first non-sysproc stored procedure
     * Other components of the system can attach to the EventObservable to be told when this occurs 
     */
    private boolean startWorkload = false;
    private final EventObservable<HStoreSite> startWorkload_observable = 
                        new EventObservable<HStoreSite>();
    
    /**
     * EventObservable for when the HStoreSite has been told that it needs to shutdown.
     */
    private Shutdownable.ShutdownState shutdown_state = ShutdownState.INITIALIZED;
    private final EventObservable<Object> shutdown_observable = new EventObservable<Object>();
    
    // ----------------------------------------------------------------------------
    // PARTITION SPECIFIC MEMBERS
    // ----------------------------------------------------------------------------
    
    /**
     * Collection of local partitions managed at this HStoreSite
     */
    private final PartitionSet local_partitions = new PartitionSet();
    
    /**
     * Integer list of all local partitions managed at this HStoreSite
     */
    protected final Integer local_partitions_arr[];
    
    /**
     * PartitionId -> Internal Offset
     * This is so that we don't have to keep long arrays of local partition information
     */
    private final int local_partition_offsets[];
    
    /**
     * For a given offset from LOCAL_PARTITION_OFFSETS, this array
     * will contain the partition id
     */
    private final int local_partition_reverse[];
    
    /**
     * PartitionId -> SiteId
     */
    private final int partition_site_xref[];
    
    /**
     * PartitionId -> Singleton set of that PartitionId
     */
    private final PartitionSet single_partition_sets[];
    
    // ----------------------------------------------------------------------------
    // TRANSACTION ESTIMATION
    // ----------------------------------------------------------------------------

    /**
     * Estimation Thresholds
     */
    private EstimationThresholds thresholds = new EstimationThresholds(); // default values
    
    /**
     * If we're using the TransactionEstimator, then we need to convert all primitive array ProcParameters
     * into object arrays...
     */
    private final Map<Procedure, ParameterMangler> param_manglers = new IdentityHashMap<Procedure, ParameterMangler>();

    // ----------------------------------------------------------------------------
    // STATUS + PROFILING MEMBERS
    // ----------------------------------------------------------------------------

    /**
     * Status Monitor
     */
    private HStoreSiteStatus status_monitor = null;
    
    /**
     * The number of incoming transaction requests per partition 
     */
    private final Histogram<Integer> network_incoming_partitions = new Histogram<Integer>();
    
    private final HStoreSiteProfiler profiler;
    
    // ----------------------------------------------------------------------------
    // CACHED STRINGS
    // ----------------------------------------------------------------------------
    
    private final String REJECTION_MESSAGE;
    
    // ----------------------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     * @param coordinators
     * @param p_estimator
     */
    protected HStoreSite(Site catalog_site, HStoreConf hstore_conf) {
    	
        assert(catalog_site != null);
        
        this.hstore_conf = hstore_conf;
        this.catalogContext = new CatalogContext(catalog_site.getCatalog(), CatalogContext.NO_PATH);
        this.catalog_site = catalog_site;
        this.catalog_host = this.catalog_site.getHost(); 
        this.site_id = this.catalog_site.getId();
        this.site_name = HStoreThreadManager.getThreadName(this.site_id, null);
        
        this.all_partitions = catalogContext.getAllPartitionIdCollection();
        final int num_partitions = this.all_partitions.size();
        this.local_partitions.addAll(CatalogUtil.getLocalPartitionIds(catalog_site));
        int num_local_partitions = this.local_partitions.size();
        
        // **IMPORTANT**
        // We have to setup the partition offsets before we do anything else here
        this.local_partitions_arr = new Integer[num_local_partitions];
        this.executors = new PartitionExecutor[num_partitions];
        this.executor_threads = new Thread[num_partitions];
        this.single_partition_sets = new PartitionSet[num_partitions];
        
        // Get the hasher we will use for this HStoreSite
        this.hasher = ClassUtil.newInstance(hstore_conf.global.hasherClass,
                                             new Object[]{ this.catalogContext.database, num_partitions },
                                             new Class<?>[]{ Database.class, int.class });
        this.p_estimator = new PartitionEstimator(this.catalogContext, this.hasher);

        // **IMPORTANT**
        // Always clear out the CatalogUtil and BatchPlanner before we start our new HStoreSite
        // TODO: Move this cache information into CatalogContext
        CatalogUtil.clearCache(this.catalogContext.database);
        BatchPlanner.clear(this.all_partitions.size());

        // Only preload stuff if we were asked to
        if (hstore_conf.site.preload) {
            if (d) LOG.debug("Preloading cached objects");
            try {
                // Don't forget our CatalogUtil friend!
                CatalogUtil.preload(this.catalogContext.database);
                
                // Load up everything the QueryPlanUtil
                PlanNodeUtil.preload(this.catalogContext.database);
                
                // Then load up everything in the PartitionEstimator
                this.p_estimator.preload();
            } catch (Exception ex) {
                throw new RuntimeException("Failed to prepare HStoreSite", ex);
            }
        }
        
        // Offset Hack
        this.local_partition_offsets = new int[num_partitions];
        Arrays.fill(this.local_partition_offsets, -1);
        this.local_partition_reverse = new int[num_local_partitions];
        int offset = 0;
        for (int partition : this.local_partitions) {
            this.local_partition_offsets[partition] = offset;
            this.local_partition_reverse[offset] = partition; 
            this.local_partitions_arr[offset] = partition;
            this.single_partition_sets[partition] = new PartitionSet(Collections.singleton(partition));
            offset++;
        } // FOR
        this.partition_site_xref = new int[num_partitions];
        for (Partition catalog_part : CatalogUtil.getAllPartitions(catalog_site)) {
            this.partition_site_xref[catalog_part.getId()] = ((Site)catalog_part.getParent()).getId();
        } // FOR
        
        // Object Pools
        this.objectPools = new HStoreObjectPools(this);
        
        // -------------------------------
        // THREADS
        // -------------------------------
        
        EventObserver<Pair<Thread, Throwable>> observer = new EventObserver<Pair<Thread, Throwable>>() {
            @Override
            public void update(EventObservable<Pair<Thread, Throwable>> o, Pair<Thread, Throwable> arg) {
                Thread thread = arg.getFirst();
                Throwable error = arg.getSecond();
                LOG.fatal(String.format("Thread %s had a fatal error: %s", thread.getName(), (error != null ? error.getMessage() : null)));
                hstore_coordinator.shutdownClusterBlocking(error);
            }
        };
        this.exceptionHandler.addObserver(observer);
        
        // HStoreSite Thread Manager (this always get invoked first)
        this.threadManager = new HStoreThreadManager(this);
        
        // Distributed Transaction Queue Manager
        this.txnQueueManager = new TransactionQueueManager(this);
        
        // MapReduce Transaction helper thread
        if (catalogContext.getMapReduceProcedures().isEmpty() == false) { 
            this.mr_helper = new MapReduceHelperThread(this);
        } else {
            this.mr_helper = null;
        }
        
        // Separate TransactionIdManager per partition
        if (hstore_conf.site.txn_partition_id_managers) {
            this.txnIdManagers = new TransactionIdManager[num_partitions];
            for (int partition : this.local_partitions) {
                this.txnIdManagers[partition] = new TransactionIdManager(partition);
            } // FOR
        }
        // Single TransactionIdManager for the entire site
        else {
            this.txnIdManagers = new TransactionIdManager[] {
                new TransactionIdManager(this.site_id)
            };
        }
        
        // Command Logger
        if (hstore_conf.site.commandlog_enable) {
            // It would be nice if we could come up with a unique name for this
            // invocation of the system (like the cluster instanceId). But for now
            // we'll just write out to our directory...
            File logFile = new File(hstore_conf.site.commandlog_dir +
                                    File.separator +
                                    this.getSiteName().toLowerCase() + ".log");
            this.commandLogger = new CommandLogWriter(this, logFile);
        } else {
            this.commandLogger = null;
        }

        // AdHoc Support
        if (hstore_conf.site.exec_adhoc_sql) {
            this.asyncCompilerWork_thread = new AsyncCompilerWorkThread(this, this.site_id);
        } else {
            this.asyncCompilerWork_thread = null;
        }
        
        // The AntiCacheManager will allow us to do special things down in the EE
        // for evicted tuples
        if (hstore_conf.site.anticache_enable) {
            this.anticacheManager = new AntiCacheManager(this);
        } else {
            this.anticacheManager = null;
        }
        
        // -------------------------------
        // STATS SETUP
        // -------------------------------
        this.statsAgent.registerStatsSource(SysProcSelector.MEMORY, 0, this.memoryStats);
        
        // -------------------------------
        // NETWORK SETUP
        // -------------------------------
        
        this.voltNetwork = new VoltNetwork();
        this.clientInterface = ClientInterface.create(this,
                                                      this.voltNetwork,
                                                      this.catalogContext,
                                                      this.getSiteId(),
                                                      this.getSiteId(),
                                                      catalog_site.getProc_port(),
                                                      null);
        
        
        // -------------------------------
        // TRANSACTION PROCESSING THREADS
        // -------------------------------

        List<TransactionPreProcessor> _preProcessors = null;
        List<TransactionPostProcessor> _postProcessors = null;
        BlockingQueue<Pair<ByteBuffer, RpcCallback<ClientResponseImpl>>> _preQueue = null;
        BlockingQueue<Pair<LocalTransaction, ClientResponseImpl>> _postQueue = null;
        
        if (hstore_conf.site.exec_preprocessing_threads || hstore_conf.site.exec_postprocessing_threads) {
            // Transaction Pre/Post Processing Threads
            // We need at least one core per partition and one core for the VoltProcedureListener
            // Everything else we can give to the pre/post processing guys
            int num_available_cores = threadManager.getNumCores() - (num_local_partitions + 1);

            // If there are no available cores left, then we won't create any extra processors
            if (num_available_cores <= 0) {
                LOG.warn("Insufficient number of cores on " + catalog_host.getIpaddr() + ". " +
                         "Disabling transaction pre/post processing threads");
                hstore_conf.site.exec_preprocessing_threads = false;
                hstore_conf.site.exec_postprocessing_threads = false;
            } else {
                int num_preProcessors = 0;
                int num_postProcessors = 0;
                
                // Both Types of Processors
                if (hstore_conf.site.exec_preprocessing_threads && hstore_conf.site.exec_postprocessing_threads) {
                    int split = (int)Math.ceil(num_available_cores / 2d);
                    num_preProcessors = split;
                    num_postProcessors = split;
                }
                // TransactionPreProcessor Only
                else if (hstore_conf.site.exec_preprocessing_threads) {
                    num_preProcessors = num_available_cores;
                }
                // TransactionPostProcessor Only
                else {
                    num_postProcessors = num_available_cores;
                }
                
                // Overrides
                if (hstore_conf.site.exec_preprocessing_threads_count >= 0) {
                    num_preProcessors = hstore_conf.site.exec_preprocessing_threads_count;
                }
                if (hstore_conf.site.exec_postprocessing_threads_count >= 0) {
                    num_postProcessors = hstore_conf.site.exec_postprocessing_threads_count;
                }
                
                // Initialize TransactionPreProcessors
                if (num_preProcessors > 0) {
                    if (d) 
                        LOG.debug(String.format("Starting %d %s threads",
                                                   num_preProcessors,
                                                   TransactionPreProcessor.class.getSimpleName()));
                    _preProcessors = new ArrayList<TransactionPreProcessor>();
                    _preQueue = new LinkedBlockingQueue<Pair<ByteBuffer, RpcCallback<ClientResponseImpl>>>();
                    for (int i = 0; i < num_preProcessors; i++) {
                        TransactionPreProcessor t = new TransactionPreProcessor(this, _preQueue);
                        _preProcessors.add(t);
                    } // FOR
                }
                // Initialize TransactionPostProcessors
                if (num_postProcessors > 0) {
                    if (d) 
                        LOG.debug(String.format("Starting %d %s threads",
                                                   num_postProcessors,
                                                   TransactionPostProcessor.class.getSimpleName()));
                    _postProcessors = new ArrayList<TransactionPostProcessor>();
                    _postQueue = new LinkedBlockingQueue<Pair<LocalTransaction, ClientResponseImpl>>();
                    for (int i = 0; i < num_postProcessors; i++) {
                        TransactionPostProcessor t = new TransactionPostProcessor(this, _postQueue);
                        _postProcessors.add(t);
                    } // FOR
                }
            }
        }
        this.preProcessors = _preProcessors;
        this.preProcessorQueue = _preQueue;
        this.postProcessors = _postProcessors;
        this.postProcessorQueue = _postQueue;
        
        // -------------------------------
        // TRANSACTION ESTIMATION
        // -------------------------------
        
        // Transaction Properties Initializer
        this.txnInitializer = new TransactionInitializer(this);
        
        // Create all of our parameter manglers
        for (Procedure catalog_proc : this.catalogContext.database.getProcedures()) {
            if (catalog_proc.getSystemproc()) continue;
            this.param_manglers.put(catalog_proc, new ParameterMangler(catalog_proc));
        } // FOR
        if (d) LOG.debug(String.format("Created ParameterManglers for %d procedures", this.param_manglers.size()));
        
        // CACHED MESSAGES
        this.REJECTION_MESSAGE = "Transaction was rejected by " + this.getSiteName();
        
        // Profiling
        if (hstore_conf.site.network_profiling) {
            this.profiler = new HStoreSiteProfiler();
            if (hstore_conf.site.status_show_executor_info) {
                this.profiler.network_idle_time.resetOnEventObservable(this.startWorkload_observable);
            }
        } else {
            this.profiler = null;
        }
        
        LoggerUtil.refreshLogging(hstore_conf.global.log_refresh);
    }
    
    // ----------------------------------------------------------------------------
    // ADDITIONAL INITIALIZATION METHODS
    // ----------------------------------------------------------------------------
    
    public void addPartitionExecutor(int partition, PartitionExecutor executor) {
        assert(this.shutdown_state != ShutdownState.STARTED);
        assert(executor != null);
        this.executors[partition] = executor;
    }
    
    /**
     * Return a new HStoreCoordinator for this HStoreSite. Note that this
     * should only be called by HStoreSite.init(), otherwise the 
     * internal state for this HStoreSite will be incorrect. If you want
     * the HStoreCoordinator at runtime, use HStoreSite.getHStoreCoordinator()
     * @return
     */
    protected HStoreCoordinator initHStoreCoordinator() {
        assert(this.shutdown_state != ShutdownState.STARTED);
        return new HStoreCoordinator(this);        
    }
    
    protected void setTransactionIdManagerTimeDelta(long delta) {
        for (TransactionIdManager t : this.txnIdManagers) {
            if (t != null) t.setTimeDelta(delta);
        } // FOR
    }
    
    protected void setThresholds(EstimationThresholds thresholds) {
        this.thresholds = thresholds;
        if (d) LOG.debug("Set new EstimationThresholds: " + thresholds);
    }
    
    // ----------------------------------------------------------------------------
    // CATALOG METHODS
    // ----------------------------------------------------------------------------

    public CatalogContext getCatalogContext() {
        return (this.catalogContext);
    }
    
    public Catalog getCatalog() {
        return (this.catalogContext.catalog);
    }
    
    public Database getDatabase() {
        return (this.catalogContext.database);
    }
    
    /**
     * Return the Site catalog object for this HStoreSiteNode
     */
    public Site getSite() {
        return (this.catalog_site);
    }
    public int getSiteId() {
        return (this.site_id);
    }
    public String getSiteName() {
        return (this.site_name);
    }
    
    public Host getHost() {
        return (this.catalog_host);
    }
    public int getHostId() {
        return (this.catalog_host.getId());
    }
    
    /**
     * Return the list of all the partition ids in this H-Store database cluster
     * TODO: Moved to CatalogContext
     */
    @Deprecated
    public PartitionSet getAllPartitionIds() {
        return (this.all_partitions);
    }
    
    /**
     * Return the list of partition ids managed by this HStoreSite
     * TODO: Moved to CatalogContext 
     */
    public PartitionSet getLocalPartitionIds() {
        return (this.local_partitions);
    }
    /**
     * Return an immutable array of the local partition ids managed by this HStoreSite
     * Use this array is prefable to the PartitionSet if you must iterate of over them.
     * This avoids having to create a new Iterator instance each time.
     * TODO: Moved to CatalogContext
     */
    public Integer[] getLocalPartitionIdArray() {
        return (this.local_partitions_arr);
    }
    /**
     * Returns true if the given partition id is managed by this HStoreSite
     * @param partition
     * @return
     * TODO: Moved to CatalogContext
     */
    public boolean isLocalPartition(int partition) {
        return (this.local_partition_offsets[partition] != -1);
    }
    
    /**
     * Return the site id for the given partition
     * @param partition_id
     * @return
     * TODO: Moved to CatalogContext
     */
    public int getSiteIdForPartitionId(int partition_id) {
        return (this.partition_site_xref[partition_id]);
    }
    
    /**
     * Return a Collection that only contains the given partition id
     * @param partition
     * TODO: Moved to CatalogContext
     */
    public PartitionSet getSingletonPartitionList(int partition) {
        return (this.single_partition_sets[partition]);
    }
    
    // ----------------------------------------------------------------------------
    // THREAD UTILITY METHODS
    // ----------------------------------------------------------------------------
    
    protected final Thread.UncaughtExceptionHandler getExceptionHandler() {
        return (this.exceptionHandler);
    }
    
    /**
     * Start the MapReduceHelper Thread
     */
    private void startMapReduceHelper() {
        assert(this.mr_helper_started == false);
        if (d) LOG.debug("Starting " + this.mr_helper.getClass().getSimpleName());
        Thread t = new Thread(this.mr_helper);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(this.exceptionHandler);
        t.start();
        this.mr_helper_started = true;
    }
    
    /**
     * Start threads for processing AdHoc queries 
     */
    private void startAdHocHelper() {
        assert(this.adhoc_helper_started == false);
//        if (d) LOG.debug("Starting " + this.periodicWorkTimer_thread.getClass().getSimpleName());
//        this.periodicWorkTimer_thread.start();
        if (d) LOG.debug("Starting " + this.asyncCompilerWork_thread.getClass().getSimpleName());
        this.asyncCompilerWork_thread.start();
        this.adhoc_helper_started = true;
    }
    
    /**
     * Get the MapReduce Helper thread 
     */
    public MapReduceHelperThread getMapReduceHelper() {
        return (this.mr_helper);
    }
    
    // ----------------------------------------------------------------------------
    // UTILITY METHODS
    // ----------------------------------------------------------------------------

    @Override
    public void updateLogging() {
        d = debug.get();
        t = trace.get();
    }
    
    @Override
    public long getInstanceId() {
        return (this.instanceId);
    }
    protected void setInstanceId(long instanceId) {
        if (d) LOG.debug("Setting Cluster InstanceId: " + instanceId);
        this.instanceId = instanceId;
    }
    
    public HStoreCoordinator getHStoreCoordinator() {
        return (this.hstore_coordinator);
    }
    public HStoreConf getHStoreConf() {
        return (this.hstore_conf);
    }
    public HStoreObjectPools getObjectPools() {
        return (this.objectPools);
    }
    public TransactionQueueManager getTransactionQueueManager() {
        return (this.txnQueueManager);
    }
    public AntiCacheManager getAntiCacheManager() {
        return (this.anticacheManager);
    }
    public ClientInterface getClientInterface() {
        return (this.clientInterface);
    }
    public StatsAgent getStatsAgent() {
        return (this.statsAgent);
    }
    public VoltNetwork getVoltNetwork() {
        return (this.voltNetwork);
    }
    
    public DBBPool getBufferPool() {
        return (this.buffer_pool);
    }
    public CommandLogWriter getCommandLogWriter() {
        return (this.commandLogger);
    }
    
    /**
     * Convenience method to dump out status of this HStoreSite
     * @return
     */
    public String statusSnapshot() {
        return new HStoreSiteStatus(this, hstore_conf).snapshot(true, true, false, false);
    }
    
    public HStoreThreadManager getThreadManager() {
        return (this.threadManager);
    }
    public PartitionEstimator getPartitionEstimator() {
        return (this.p_estimator);
    }
    public AbstractHasher getHasher() {
        return (this.hasher);
    }
    public TransactionInitializer getTransactionInitializer() {
        return (this.txnInitializer);
    }
    public PartitionExecutor getPartitionExecutor(int partition) {
        PartitionExecutor es = this.executors[partition]; 
        assert(es != null) : 
            String.format("Unexpected null PartitionExecutor for partition #%d on %s",
                          partition, this.getSiteName());
        return (es);
    }
    
    public Collection<TransactionPreProcessor> getTransactionPreProcessors() {
        return (this.preProcessors);
    }
    public Collection<TransactionPostProcessor> getTransactionPostProcessors() {
        return (this.postProcessors);
    }

    
    public Map<Procedure, ParameterMangler> getParameterManglers() {
        return (this.param_manglers);
    }
    public ParameterMangler getParameterMangler(Procedure catalog_proc) {
        return (this.param_manglers.get(catalog_proc));
    }
    public ParameterMangler getParameterMangler(String proc_name) {
        Procedure catalog_proc = catalogContext.database.getProcedures().getIgnoreCase(proc_name);
        assert(catalog_proc != null) : "Invalid Procedure name '" + proc_name + "'";
        return (this.param_manglers.get(catalog_proc));
    }

    
    /**
     * Get the TransactionIdManager for the given partition
     * If there are not separate managers per partition, we will just
     * return the global one for this HStoreSite 
     * @param partition
     * @return
     */
    public TransactionIdManager getTransactionIdManager(int partition) {
        if (this.txnIdManagers.length == 1) {
            return (this.txnIdManagers[0]);
        } else {
            return (this.txnIdManagers[partition]);
        }
    }

    
    public EstimationThresholds getThresholds() {
        return thresholds;
    }
    
    @SuppressWarnings("unchecked")
    public <T extends AbstractTransaction> T getTransaction(Long txn_id) {
        return ((T)this.inflight_txns.get(txn_id));
    }

    
    /**
     * Return a thread-safe FastDeserializer
     * @return
     */
    private FastDeserializer getIncomingDeserializer() {
        Thread t = Thread.currentThread();
        FastDeserializer fds = this.incomingDeserializers.get(t);
        if (fds == null) {
            fds = new FastDeserializer(new byte[0]);
            this.incomingDeserializers.put(t, fds);
        }
        assert(fds != null);
        return (fds);
    }
    
    /**
     * Return a thread-safe FastSerializer
     * @return
     */
    private FastSerializer getOutgoingSerializer() {
        Thread t = Thread.currentThread();
        FastSerializer fs = this.outgoingSerializers.get(t);
        if (fs == null) {
            fs = new FastSerializer(this.buffer_pool);
            this.outgoingSerializers.put(t, fs);
        }
        assert(fs != null);
        return (fs);
    }
    
    
    // ----------------------------------------------------------------------------
    // LOCAL PARTITION OFFSETS
    // ----------------------------------------------------------------------------
    
    /**
     * For the given partition id, return its offset in the list of 
     * all the local partition ids managed by this HStoreSite.
     * This will fail if the given partition is not local to this HStoreSite.
     * @param partition
     * @return
     */
    public int getLocalPartitionOffset(int partition) {
        assert(partition < this.local_partition_offsets.length) :
            String.format("Unable to get offset of local partition %d %s [hashCode=%d]",
                          partition, Arrays.toString(this.local_partition_offsets), this.hashCode());
        return this.local_partition_offsets[partition];
    }
    
    /**
     * For the given local partition offset generated by getLocalPartitionOffset(),
     * return its corresponding partition id
     * @param offset
     * @return
     * @see HStoreSite.getLocalPartitionOffset
     */
    public int getLocalPartitionFromOffset(int offset) {
        return this.local_partition_reverse[offset];
    }
    
    // ----------------------------------------------------------------------------
    // EVENT OBSERVABLES
    // ----------------------------------------------------------------------------

    /**
     * Get the Observable handle for this HStoreSite that can alert others when the party is
     * getting started
     */
    public EventObservable<HStoreSite> getReadyObservable() {
        return (this.ready_observable);
    }
    /**
     * Get the Observable handle for this HStore for when the first non-sysproc
     * transaction request arrives and we are technically beginning the workload
     * portion of a benchmark run.
     */
    public EventObservable<HStoreSite> getStartWorkloadObservable() {
        return (this.startWorkload_observable);
    }
    
    private synchronized void notifyStartWorkload() {
        if (this.startWorkload == false) {
            this.startWorkload = true;
            this.startWorkload_observable.notifyObservers(this);
        }
    }
    
    /**
     * Get the Oberservable handle for this HStoreSite that can alert others when the party is ending
     * @return
     */
    public EventObservable<Object> getShutdownObservable() {
        return (this.shutdown_observable);
    }
    
    // ----------------------------------------------------------------------------
    // INITIALIZATION STUFF
    // ----------------------------------------------------------------------------

    /**
     * Initializes all the pieces that we need to start this HStore site up
     */
    protected HStoreSite init() {
        if (d) LOG.debug("Initializing HStoreSite " + this.getSiteName());

        this.hstore_coordinator = this.initHStoreCoordinator();
        
        // First we need to tell the HStoreCoordinator to start-up and initialize its connections
        if (d) LOG.debug("Starting HStoreCoordinator for " + this.getSiteName());
        this.hstore_coordinator.start();

        // Start TransactionQueueManager
        Thread t = new Thread(this.txnQueueManager);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(this.exceptionHandler);
        t.start();
        
        // Start VoltNetwork
        t = new Thread(this.voltNetwork);
        t.setName(HStoreThreadManager.getThreadName(this, "voltnetwork"));
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(this.exceptionHandler);
        t.start();
        
        // Initialize Status Monitor
        if (hstore_conf.site.status_enable) {
            assert(hstore_conf.site.status_interval >= 0);
            this.status_monitor = new HStoreSiteStatus(this, hstore_conf);
        }
        
        // TransactionPreProcessors
        if (this.preProcessors != null) {
            for (TransactionPreProcessor tpp : this.preProcessors) {
                t = new Thread(tpp);
                t.setDaemon(true);
                t.setUncaughtExceptionHandler(this.exceptionHandler);
                t.start();    
            } // FOR
        }
        // TransactionPostProcessors
        if (this.postProcessors != null) {
            for (TransactionPostProcessor tpp : this.postProcessors) {
                t = new Thread(tpp);
                t.setDaemon(true);
                t.setUncaughtExceptionHandler(this.exceptionHandler);
                t.start();    
            } // FOR
        }
        
        // Then we need to start all of the PartitionExecutor in threads
        if (d) LOG.debug("Starting PartitionExecutor threads for " + this.local_partitions_arr.length + " partitions on " + this.getSiteName());
        for (int partition : this.local_partitions_arr) {
            PartitionExecutor executor = this.getPartitionExecutor(partition);
            executor.initHStoreSite(this);
            
            t = new Thread(executor);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY); // Probably does nothing...
            t.setUncaughtExceptionHandler(this.exceptionHandler);
            this.executor_threads[partition] = t;
            t.start();
        } // FOR
        
        this.schedulePeriodicWorks();
        
        // Add in our shutdown hook
        // Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHook()));
        
        return (this);
    }
    
    /**
     * Schedule all the periodic works
     */
    private void schedulePeriodicWorks() {
        // Internal Updates
        this.threadManager.schedulePeriodicWork(new Runnable() {
            @Override
            public void run() {
                HStoreSite.this.processPeriodicWork();
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        
        // HStoreStatus
        if (this.status_monitor != null) {
            this.threadManager.schedulePeriodicWork(
                this.status_monitor,
                hstore_conf.site.status_interval,
                hstore_conf.site.status_interval,
                TimeUnit.MILLISECONDS);
        }
        
        // AntiCache Memory Monitor
        if (this.anticacheManager != null) {
            if (this.anticacheManager.getEvictableTables().isEmpty() == false) {
                this.threadManager.schedulePeriodicWork(
                        this.anticacheManager.getMemoryMonitorThread(),
                        hstore_conf.site.anticache_check_interval,
                        hstore_conf.site.anticache_check_interval,
                        TimeUnit.MILLISECONDS);
            } else {
                LOG.warn("There are no tables marked as evictable. Disabling anti-cache monitoring");
            }
        }
        
        // small stats samples
        this.threadManager.schedulePeriodicWork(new Runnable() {
            @Override
            public void run() {
                SystemStatsCollector.asyncSampleSystemNow(false, false);
            }
        }, 0, 5, TimeUnit.SECONDS);

        // medium stats samples
        this.threadManager.schedulePeriodicWork(new Runnable() {
            @Override
            public void run() {
                SystemStatsCollector.asyncSampleSystemNow(true, false);
            }
        }, 0, 1, TimeUnit.MINUTES);

        // large stats samples
        this.threadManager.schedulePeriodicWork(new Runnable() {
            @Override
            public void run() {
                SystemStatsCollector.asyncSampleSystemNow(true, true);
            }
        }, 0, 6, TimeUnit.MINUTES);
    }
    
    /**
     * Launch all of the threads needed by this HStoreSite. This is a blocking call
     */
    @Override
    public void run() {
        if (this.ready) {
            throw new RuntimeException("Trying to start " + this.getSiteName() + " more than once");
        }
        
        this.init();
        
        try {
            this.clientInterface.startAcceptingConnections();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        
        this.shutdown_state = ShutdownState.STARTED;
        if (hstore_conf.site.network_profiling) {
            this.profiler.network_idle_time.start();
        }
        this.ready = true;
        this.ready_observable.notifyObservers(this);

        // IMPORTANT: This message must always be printed in order for the BenchmarkController
        //            to know that we're ready! That's why we have to use System.out instead of LOG
        String msg = String.format("%s / Site=%s / Address=%s:%d / Partitions=%s",
                HStoreConstants.SITE_READY_MSG,
                this.getSiteName(),
                this.catalog_site.getHost().getIpaddr(),
                CollectionUtil.first(CatalogUtil.getExecutionSitePorts(this.catalog_site)),
                Arrays.toString(this.local_partitions_arr));
        System.out.println(msg);
        System.out.flush();
        
        // XXX: We have to join on all of our PartitionExecutor threads
        try {
            for (Thread t : this.executor_threads) {
                if (t != null) t.join();
            }
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * Returns true if this HStoreSite is fully initialized and running
     * This will be set to false if the system is shutting down
     */
    public boolean isRunning() {
        return (this.ready);
    }

    /**
     * Returns true if this HStoreSite is throttling incoming transactions
     */
    protected Histogram<Integer> getIncomingPartitionHistogram() {
        return (this.network_incoming_partitions);
    }
    
    public HStoreSiteProfiler getProfiler() {
        return (this.profiler);
    }
    
    // ----------------------------------------------------------------------------
    // SHUTDOWN STUFF
    // ----------------------------------------------------------------------------
    
    @Override
    public void prepareShutdown(boolean error) {
        this.shutdown_state = ShutdownState.PREPARE_SHUTDOWN;
                
        if (this.hstore_coordinator != null)
            this.hstore_coordinator.prepareShutdown(false);
        
        this.txnQueueManager.prepareShutdown(error);
        
        if (this.preProcessors != null) {
            for (TransactionPreProcessor tpp : this.preProcessors) {
                tpp.prepareShutdown(false);
            } // FOR
        }
        if (this.postProcessors != null) {
            for (TransactionPostProcessor tpp : this.postProcessors) {
                tpp.prepareShutdown(false);
            } // FOR
        }
        
        if (this.mr_helper != null) {
            this.mr_helper.prepareShutdown(error);
        }
        if (this.commandLogger != null) {
            this.commandLogger.prepareShutdown(error);
        }
        if (this.anticacheManager != null) {
            this.anticacheManager.prepareShutdown(error);
        }
        
        if (this.adhoc_helper_started) {
            if (this.asyncCompilerWork_thread != null)
                this.asyncCompilerWork_thread.prepareShutdown(error);
        }
        
        for (int p : this.local_partitions_arr) {
            if (this.executors[p] != null) 
                this.executors[p].prepareShutdown(error);
        } // FOR
    }
    
    /**
     * Perform shutdown operations for this HStoreSiteNode
     */
	@Override
    public synchronized void shutdown(){
        if (this.shutdown_state == ShutdownState.SHUTDOWN) {
            if (d) LOG.debug("Already told to shutdown... Ignoring");
            return;
        }
        if (this.shutdown_state != ShutdownState.PREPARE_SHUTDOWN) this.prepareShutdown(false);
        this.shutdown_state = ShutdownState.SHUTDOWN;
        if (d) 
            LOG.debug("Shutting down everything at " + this.getSiteName());

        // Stop the monitor thread
        if (this.status_monitor != null) this.status_monitor.shutdown();
        
        // Kill the queue manager
        this.txnQueueManager.shutdown();
        
        if (this.mr_helper_started && this.mr_helper != null) {
            this.mr_helper.shutdown();
        }
        if (this.commandLogger != null) {
            this.commandLogger.shutdown();
        }
        if (this.anticacheManager != null) {
            this.anticacheManager.shutdown();
        }
      
        // this.threadManager.getPeriodicWorkExecutor().shutdown();
        
        // Stop AdHoc threads
        if (this.adhoc_helper_started) {
            if (this.asyncCompilerWork_thread != null)
                this.asyncCompilerWork_thread.shutdown();
        }

        if (this.preProcessors != null) {
            for (TransactionPreProcessor tpp : this.preProcessors) {
                tpp.shutdown();
            } // FOR
        }
        if (this.postProcessors != null) {
            for (TransactionPostProcessor tpp : this.postProcessors) {
                tpp.shutdown();
            } // FOR
        }
        
        // Tell anybody that wants to know that we're going down
        if (t) LOG.trace("Notifying " + this.shutdown_observable.countObservers() + " observers that we're shutting down");
        this.shutdown_observable.notifyObservers();
        
        // Tell all of our event loops to stop
//        if (t) LOG.trace("Telling Procedure Listener event loops to exit");
//        for (int i = 0; i < this.voltListeners.length; i++) {
//            this.procEventLoops[i].exitLoop();
//            if (this.voltListeners[i] != null) this.voltListeners[i].close();
//        } // FOR
        
        if (this.hstore_coordinator != null) {
            this.hstore_coordinator.shutdown();
        }
        
        if (this.voltNetwork != null) {
            try {
                this.voltNetwork.shutdown();
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            this.clientInterface.shutdown();
        }
        
        
        // Tell our local boys to go down too
        for (int p : this.local_partitions_arr) {
            this.executors[p].shutdown();
        } // FOR
        
        LOG.info(String.format("Completed shutdown process at %s [hashCode=%d]",
                               this.getSiteName(), this.hashCode()));
    }
    
    /**
     * Returns true if HStoreSite is in the process of shutting down
     * @return
     */
    @Override
    public boolean isShuttingDown() {
        return (this.shutdown_state == ShutdownState.SHUTDOWN || this.shutdown_state == ShutdownState.PREPARE_SHUTDOWN);
    }
    
    
    // ----------------------------------------------------------------------------
    // INCOMING INVOCATION HANDLER METHODS
    // ----------------------------------------------------------------------------
    
    protected void invocationQueue(ByteBuffer buffer, ClientInputHandler handler, Connection c) {
        int messageSize = buffer.capacity();
        RpcCallback<ClientResponseImpl> callback = new ClientResponseCallback(this.clientInterface, c, messageSize);
        this.clientInterface.increaseBackpressure(messageSize);
        
        if (this.preProcessorQueue != null) {
            this.preProcessorQueue.add(Pair.of(buffer, callback));
        } else {
            this.invocationProcess(buffer, callback);
        }
    }
    
    @Override
    public void invocationQueue(ByteBuffer buffer, final RpcCallback<byte[]> clientCallback) {
        // XXX: This is a big hack. We should just deal with the ClientResponseImpl directly
        RpcCallback<ClientResponseImpl> wrapperCallback = new RpcCallback<ClientResponseImpl>() {
            @Override
            public void run(ClientResponseImpl parameter) {
                if (trace.get()) LOG.trace("Serializing ClientResponse to byte array:\n" + parameter);
                
                FastSerializer fs = new FastSerializer();
                try {
                    parameter.writeExternal(fs);
                    clientCallback.run(fs.getBBContainer().b.array());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    fs.clear();
                }
            }
        };
        
        if (this.preProcessorQueue != null) {
            this.preProcessorQueue.add(Pair.of(buffer, wrapperCallback));
        } else {
            this.invocationProcess(buffer, wrapperCallback);
        }
    }
    
    /**
     * This is the main method that takes in a ByteBuffer request from the client and queues
     * it up for execution. The clientCallback expects to get back a ClientResponse generated
     * after the txn is executed. 
     * @param buffer
     * @param clientCallback
     */
    public void invocationProcess(ByteBuffer buffer, RpcCallback<ClientResponseImpl> clientCallback) {
        if (hstore_conf.site.network_profiling || hstore_conf.site.txn_profiling) {
            long timestamp = ProfileMeasurement.getTime();
            if (hstore_conf.site.network_profiling) {
                ProfileMeasurement.swap(timestamp, this.profiler.network_idle_time, this.profiler.network_processing_time);
            }
            // TODO: Write profiling timestamp into StoredProcedureInvocation bytes
        }

        // Extract the stuff we need to figure out whether this guy belongs at our site
        // We don't need to create a StoredProcedureInvocation anymore in order to
        // extract out the data that we need in this request
        FastDeserializer incomingDeserializer = this.getIncomingDeserializer();
        final long client_handle = StoredProcedureInvocation.getClientHandle(buffer);
        final int procId = StoredProcedureInvocation.getProcedureId(buffer);
        int base_partition = StoredProcedureInvocation.getBasePartition(buffer);
        if (t) LOG.trace(String.format("Raw Request: [clientHandle=%d / procId=%d / basePartition=%d]",
                                       client_handle, procId, base_partition));
        
        // Optimization: We can get the Procedure catalog handle from its procId
        Procedure catalog_proc = catalogContext.getProcedureById(procId);
        String procName = null;
     
        // Otherwise, we have to get the procedure name and do a look up with that.
        if (catalog_proc == null) {
            incomingDeserializer.setBuffer(buffer);
            procName = StoredProcedureInvocation.getProcedureName(incomingDeserializer);
            this.catalogContext.database.getProcedures().get(procName);
            if (catalog_proc == null) {
                catalog_proc = this.catalogContext.database.getProcedures().getIgnoreCase(procName);
            }
            
            // TODO: This should be an error message back to the client, not an exception
            if (catalog_proc == null) {
                String msg = "Unknown procedure '" + procName + "'";
                this.responseError(client_handle,
                                       Status.ABORT_UNEXPECTED,
                                       msg,
                                       clientCallback,
                                       EstTime.currentTimeMillis());
                return;
            }
        } else {
            procName = catalog_proc.getName();
        }
        boolean sysproc = catalog_proc.getSystemproc();
        
        // -------------------------------
        // PARAMETERSET INITIALIZATION
        // -------------------------------
        
        // Initialize the ParameterSet
        ParameterSet procParams = null;
        try {
//            procParams = objectPools.PARAMETERSETS.borrowObject();
            procParams = new ParameterSet();
            StoredProcedureInvocation.seekToParameterSet(buffer);
            incomingDeserializer.setBuffer(buffer);
            procParams.readExternal(incomingDeserializer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } 
        assert(procParams != null) :
            "The parameters object is null for new txn from client #" + client_handle;
        if (d) LOG.debug(String.format("Received new stored procedure invocation request for %s [handle=%d]",
                                       catalog_proc.getName(), client_handle));
        
        // System Procedure Check
        // If this method returns true, then we want to halt processing the
        // request any further and immediately return
        if (sysproc && this.processSysProc(client_handle, catalog_proc, procParams, clientCallback)) {
            return;
        }
        
        // If this is the first non-sysproc transaction that we've seen, then
        // we will notify anybody that is waiting for this event. This is used to clear
        // out any counters or profiling information that got recorded when we were loading data
        if (this.startWorkload == false && sysproc == false) {
            this.notifyStartWorkload();
        }
        
        // -------------------------------
        // BASE PARTITION
        // -------------------------------
        
        // Profiling Updates
        if (hstore_conf.site.status_show_txn_info) TxnCounter.RECEIVED.inc(procName);
        if (hstore_conf.site.network_profiling && base_partition != -1) {
            this.network_incoming_partitions.put(base_partition);
        }
        
        base_partition = this.txnInitializer.calculateBasePartition(client_handle,
                                                                    catalog_proc,
                                                                    procParams,
                                                                    base_partition);
        
        // -------------------------------
        // REDIRECT TXN TO PROPER BASE PARTITION
        // -------------------------------
        if (this.isLocalPartition(base_partition) == false) {
            // If the base_partition isn't local, then we need to ship it off to
            // the right HStoreSite
            this.transactionRedirect(catalog_proc, buffer, base_partition, clientCallback);
            return;
        }
        
        PartitionExecutor executor = this.executors[base_partition];
        boolean success = false;
        
        // If we are using the Markov models, then we have to initialize the transaction
        // right here.
        // TODO: We need to measure whether it is faster to do it this way (with and without
        // the models) or whether it is faster to queue things up in the PartitionExecutor
        // and let it be responsible for sorting things out
        if (hstore_conf.site.markov_enable) {
            LocalTransaction ts = this.txnInitializer.initInvocation(buffer,
                                                                     client_handle,
                                                                     base_partition,
                                                                     catalog_proc,
                                                                     procParams,
                                                                     clientCallback);
            this.transactionQueue(ts);
        }
        // We should queue the txn at the proper partition
        // The PartitionExecutor thread will be responsible for creating
        // the LocalTransaction handle and figuring out whatever else we need to
        // about this txn...
        else {
            success = executor.queueNewTransaction(buffer,
                                                   catalog_proc,
                                                   procParams,
                                                   clientCallback);
            if (success == false) {
                Status status = Status.ABORT_REJECT;
                if (d) LOG.debug(String.format("Hit with a %s response from partition %d [queueSize=%d]",
                                               status, base_partition, executor.getWorkQueueSize()));
                this.responseError(client_handle,
                                       status,
                                       REJECTION_MESSAGE,
                                       clientCallback,
                                       EstTime.currentTimeMillis());
            }
        }

        if (hstore_conf.site.status_show_txn_info) {
            (success ? TxnCounter.EXECUTED : TxnCounter.REJECTED).inc(catalog_proc);
        }
        
        if (d) LOG.debug(String.format("Finished initial processing of new txn. [success=%s]", success));
        EstTimeUpdater.update(System.currentTimeMillis());
        if (hstore_conf.site.network_profiling) {
            ProfileMeasurement.swap(this.profiler.network_processing_time, this.profiler.network_idle_time);
        }
    }
    
    
    /**
     * Special handling for certain incoming sysproc requests. These are just for
     * specialized sysprocs where we need to do some pre-processing that is separate
     * from how the regular sysproc txns are executed.
     * @param catalog_proc
     * @param done
     * @param request
     * @return True if this request was handled and the caller does not need to do anything further
     */
    private boolean processSysProc(long client_handle,
                                   Procedure catalog_proc,
                                   ParameterSet params,
                                   RpcCallback<ClientResponseImpl> done) {
        
        // -------------------------------
        // SHUTDOWN
        // TODO: Execute as a regular sysproc transaction
        // -------------------------------
        if (catalog_proc.getName().equals("@Shutdown")) {
            this.responseError(client_handle, Status.OK, "", done, EstTime.currentTimeMillis());

            // Non-blocking....
            Exception error = new Exception("Shutdown command received at " + this.getSiteName());
            this.hstore_coordinator.shutdownCluster(error);
            return (true);
        }
        
        // -------------------------------
        // ADHOC
        // -------------------------------
        else if (catalog_proc.getName().equals("@AdHoc")) {
            String msg = null;
            
            // Is this feature disabled?
            if (hstore_conf.site.exec_adhoc_sql == false) {
                msg = "AdHoc queries are disabled";
            }
            // Check that variable 'request' in this func. is same as 
            // 'task' in ClientInterface.handleRead()
            else if (params.size() != 1) {
                msg = "AdHoc system procedure requires exactly one parameter, " +
                	  "the SQL statement to execute.";
            }
            
            if (msg != null) {
                this.responseError(client_handle, Status.ABORT_GRACEFUL, msg, done, EstTime.currentTimeMillis());
                return (true);
            }
            
            // Check if we need to start our threads now
            if (this.adhoc_helper_started == false) {
                this.startAdHocHelper();
            }
            
            // Create a LocalTransaction handle that will carry into the
            // the adhoc compiler. Since we don't know what this thing will do, we have
            // to assume that it needs to touch all partitions.
            int idx = (int)(Math.abs(client_handle) % this.local_partitions_arr.length);
            int base_partition = this.local_partitions_arr[idx].intValue();
            
            LocalTransaction ts = null;
            try {
                ts = objectPools.getLocalTransactionPool(base_partition).borrowObject();
                assert (ts.isInitialized() == false);
            } catch (Throwable ex) {
                LOG.fatal(String.format("Failed to instantiate new LocalTransactionState for %s txn",
                                        catalog_proc.getName()));
                throw new RuntimeException(ex);
            }
            ts.init(-1l, client_handle, base_partition,
                    this.all_partitions, false, true,
                    catalog_proc, params, done);
            
            String sql = (String)params.toArray()[0];
            this.asyncCompilerWork_thread.planSQL(ts, sql);
            return (true);
        }
        
        return (false);
    }

    // ----------------------------------------------------------------------------
    // TRANSACTION HANDLE CREATION METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Create a MapReduceTransaction handle. This should only be invoked on a remote site.
     * @param txn_id
     * @param invocation
     * @param base_partition
     * @return
     */
    public MapReduceTransaction createMapReduceTransaction(Long txn_id,
                                                           long client_handle,
                                                           int base_partition,
                                                           int procId,
                                                           ByteBuffer paramsBuffer) {
        Procedure catalog_proc = this.catalogContext.getProcedureById(procId);
        if (catalog_proc == null) {
            throw new RuntimeException("Unknown procedure id '" + procId + "'");
        }
        
        // Initialize the ParameterSet
        FastDeserializer incomingDeserializer = this.getIncomingDeserializer();
        ParameterSet procParams = null;
        try {
//            procParams = objectPools.PARAMETERSETS.borrowObject();
            procParams = new ParameterSet();
            incomingDeserializer.setBuffer(StoredProcedureInvocation.getParameterSet(paramsBuffer));
            procParams.readExternal(incomingDeserializer);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } 
        assert(procParams != null) :
            "The parameters object is null for new txn from client #" + client_handle;
        
        MapReduceTransaction ts = null;
        try {
            ts = objectPools.getMapReduceTransactionPool(base_partition).borrowObject();
            assert(ts.isInitialized() == false);
        } catch (Throwable ex) {
            LOG.fatal(String.format("Failed to instantiate new MapReduceTransaction state for %s txn #%s",
                                    catalog_proc.getName(), txn_id));
            throw new RuntimeException(ex);
        }
        // We should never already have a transaction handle for this txnId
        AbstractTransaction dupe = this.inflight_txns.put(txn_id, ts);
        assert(dupe == null) : "Trying to create multiple transaction handles for " + dupe;

        ts.init(txn_id, client_handle, base_partition, catalog_proc, procParams);
        if (d) LOG.debug(String.format("Created new MapReduceTransaction state %s from remote partition %d",
                                       ts, base_partition));
        return (ts);
    }
    
    /**
     * Create a RemoteTransaction handle. This obviously only for a remote site.
     * @param txn_id
     * @param request
     * @return
     */
    public RemoteTransaction createRemoteTransaction(Long txn_id, int base_partition, int proc_id) {
        RemoteTransaction ts = null;
        Procedure catalog_proc = this.catalogContext.getProcedureById(proc_id);
        try {
            // Remote Transaction
            ts = objectPools.getRemoteTransactionPool(base_partition).borrowObject();
            ts.init(txn_id, base_partition, catalog_proc, true);
            if (d) LOG.debug(String.format("Creating new RemoteTransactionState %s from remote partition %d [singlePartitioned=%s, hashCode=%d]",
                                           ts, base_partition, false, ts.hashCode()));
        } catch (Exception ex) {
            LOG.fatal("Failed to construct TransactionState for txn #" + txn_id, ex);
            throw new RuntimeException(ex);
        }
        AbstractTransaction dupe = this.inflight_txns.put(txn_id, ts);
        assert(dupe == null) : "Trying to create multiple transaction handles for " + dupe;
        
        if (t) LOG.trace(String.format("Stored new transaction state for %s", ts));
        return (ts);
    }
    
    // ----------------------------------------------------------------------------
    // TRANSACTION OPERATION METHODS
    // ----------------------------------------------------------------------------

    /**
     * Queue a new transaction for execution. If it is a single-partition txn, then it will
     * be queued at its base partition's PartitionExecutor queue. If it is distributed transaction,
     * then it will need to first acquire the locks for all of the partitions that it wants to
     * access.
     * <B>Note:</B> This method should only be used for distributed transactions.
     * Single-partition txns should be queued up directly within their base PartitionExecutor.
     * @param ts
     */
    public void transactionQueue(LocalTransaction ts) {
        assert(ts.isInitialized()) : 
            "Unexpected uninitialized LocalTranaction for " + ts;
        Long txn_id = ts.getTransactionId();
        final int base_partition = ts.getBasePartition();
        
        // Make sure that we start the MapReduceHelperThread
        if (ts.isMapReduce() && this.mr_helper_started == false) {
            assert(this.mr_helper != null);
            this.startMapReduceHelper();
        }
                
        // For some odd reason we sometimes get duplicate transaction ids from the VoltDB id generator
        // So we'll just double check to make sure that it's unique, and if not, we'll just ask for a new one
        LocalTransaction dupe = (LocalTransaction)this.inflight_txns.put(txn_id, ts);
        if (dupe != null) {
            // HACK!
            this.inflight_txns.put(txn_id, dupe);
            // long new_txn_id = this.txnid_managers[base_partition].getNextUniqueTransactionId();
            Long new_txn_id = this.getTransactionIdManager(base_partition).getNextUniqueTransactionId();
            if (new_txn_id == txn_id) {
                String msg = "Duplicate transaction id #" + txn_id;
                LOG.fatal("ORIG TRANSACTION:\n" + dupe);
                LOG.fatal("NEW TRANSACTION:\n" + ts);
                Exception error = new Exception(msg);
                this.hstore_coordinator.shutdownClusterBlocking(error);
            }
            LOG.warn(String.format("Had to fix duplicate txn ids: %d -> %d", txn_id, new_txn_id));
            txn_id = new_txn_id;
            ts.setTransactionId(txn_id);
            this.inflight_txns.put(txn_id, ts);
        }
        if (d) LOG.debug(ts + " - Dispatching new transaction invocation");
        
        // -------------------------------
        // SINGLE-PARTITION or NON-BLOCKING MAPREDUCE TRANSACTION
        // -------------------------------
        if (ts.isPredictSinglePartition() || (ts.isMapReduce() && hstore_conf.site.mr_map_blocking == false)) {
            if (d) LOG.debug(String.format("%s - Fast path single-partition execution on partition %d [handle=%d]",
                             ts, base_partition, ts.getClientHandle()));
            this.transactionStart(ts, base_partition);
        }
        // -------------------------------    
        // DISTRIBUTED TRANSACTION
        // -------------------------------
        else {
            if (d) LOG.debug(String.format("%s - Queuing distributed transaction to execute at partition %d [handle=%d]",
                                           ts, base_partition, ts.getClientHandle()));
            
            // Check whether our transaction can't run right now because its id is less than
            // the last seen txnid from the remote partitions that it wants to touch
            for (Integer partition : ts.getPredictTouchedPartitions()) {
                Long last_txn_id = this.txnQueueManager.getLastLockTransaction(partition.intValue()); 
                if (txn_id.compareTo(last_txn_id) < 0) {
                    // If we catch it here, then we can just block ourselves until
                    // we generate a txn_id with a greater value and then re-add ourselves
                    if (d) {
                        LOG.warn(String.format("%s - Unable to queue transaction because the last txn id at partition %d is %d. Restarting...",
                                       ts, partition, last_txn_id));
                        LOG.warn(String.format("LastTxnId:#%s / NewTxnId:#%s",
                                           TransactionIdManager.toString(last_txn_id),
                                           TransactionIdManager.toString(txn_id)));
                    }
                    if (hstore_conf.site.status_show_txn_info && ts.getRestartCounter() == 1) TxnCounter.BLOCKED_LOCAL.inc(ts.getProcedure());
                    this.txnQueueManager.blockTransaction(ts, partition.intValue(), last_txn_id);
                    return;
                }
            } // FOR
            
            // This callback prevents us from making additional requests to the Dtxn.Coordinator until
            // we get hear back about our our initialization request
            if (hstore_conf.site.txn_profiling) ts.profiler.startInitDtxn();
            this.txnQueueManager.initTransaction(ts);
        }
    }
    
    /**
     * Add the given transaction id to this site's queue manager with all of the partitions that
     * it needs to lock. This is only for distributed transactions.
     * The callback will be invoked once the transaction has acquired all of the locks for the
     * partitions provided, or aborted if the transaction is unable to lock those partitions.
     * @param txn_id
     * @param partitions The list of partitions that this transaction needs to access
     * @param callback
     */
    public void transactionInit(Long txn_id, PartitionSet partitions, TransactionInitQueueCallback callback) {
        // We should always force a txn from a remote partition into the queue manager
        this.txnQueueManager.lockInsert(txn_id, partitions, callback);
    }

    /**
     * Queue the transaction to start executing on its base partition.
     * This function can block a transaction executing on that partition
     * <B>IMPORTANT:</B> The transaction could be deleted after calling this if it is rejected
     * @param ts, base_partition
     */
    public void transactionStart(LocalTransaction ts, int base_partition) {
        final Long txn_id = ts.getTransactionId();
        final Procedure catalog_proc = ts.getProcedure();
        final boolean singlePartitioned = ts.isPredictSinglePartition();
        
        if (d) LOG.debug(String.format("Starting %s %s on partition %d",
                        (singlePartitioned ? "single-partition" : "distributed"), ts, base_partition));
        
        PartitionExecutor executor = this.executors[base_partition];
        assert(executor != null) :
            "Unable to start " + ts + " - No PartitionExecutor exists for partition #" + base_partition + " at HStoreSite " + this.site_id;
        
        if (hstore_conf.site.txn_profiling) ts.profiler.startQueue();
        boolean success = executor.queueNewTransaction(ts);
        if (hstore_conf.site.status_show_txn_info && success) {
            assert(catalog_proc != null) :
                String.format("Null Procedure for txn #%d [hashCode=%d]", txn_id, ts.hashCode());
            TxnCounter.EXECUTED.inc(catalog_proc);
        }
        
        if (success == false) {
            // Depending on what we need to do for this type txn, we will send
            // either an ABORT_THROTTLED or an ABORT_REJECT in our response
            // An ABORT_THROTTLED means that the client will back-off of a bit
            // before sending another txn request, where as an ABORT_REJECT means
            // that it will just try immediately
            Status status = Status.ABORT_REJECT;
            if (d) LOG.debug(String.format("%s - Hit with a %s response from partition %d [queueSize=%d]",
                                           ts, status, base_partition,
                                           executor.getWorkQueueSize()));
            if (singlePartitioned == false) {
                TransactionFinishCallback finish_callback = ts.initTransactionFinishCallback(status);
                this.hstore_coordinator.transactionFinish(ts, status, finish_callback);
            }
            // We will want to delete this transaction after we reject it if it is a single-partition txn
            // Otherwise we will let the normal distributed transaction process clean things up
            this.transactionReject(ts, status);
            if (singlePartitioned) {
                ts.markAsDeletable();
                this.deleteTransaction(ts, status);
            }
        }        
    }
    
    /**
     * Execute a WorkFragment on a particular PartitionExecutor
     * @param request
     * @param clientCallback
     */
    public void transactionWork(AbstractTransaction ts, WorkFragment fragment) {
        if (d) LOG.debug(String.format("%s - Queuing %s on partition %d [prefetch=%s]",
                                       ts, fragment.getClass().getSimpleName(),
                                       fragment.getPartitionId(), fragment.getPrefetch()));
        assert(this.isLocalPartition(fragment.getPartitionId())) :
            "Trying to queue work for " + ts + " at non-local partition " + fragment.getPartitionId();
        
        this.executors[fragment.getPartitionId()].queueWork(ts, fragment);
    }


    /**
     * This method is the first part of two phase commit for a transaction.
     * If speculative execution is enabled, then we'll notify each the PartitionExecutors
     * for the listed partitions that it is done. This will cause all the 
     * that are blocked on this transaction to be released immediately and queued 
     * @param txn_id
     * @param partitions
     * @param updated
     */
    public void transactionPrepare(Long txn_id, PartitionSet partitions, PartitionSet updated) {
        if (d) LOG.debug(String.format("2PC:PREPARE Txn #%d [partitions=%s]", txn_id, partitions));
        
        // We could have been asked to participate in a distributed transaction but
        // they never actually sent us anything, so we should just tell the queue manager
        // that the txn is done. There is nothing that we need to do at the PartitionExecutors
        AbstractTransaction ts = this.inflight_txns.get(txn_id);
        TransactionPrepareCallback callback = null;
        if (ts instanceof LocalTransaction) {
            callback = ((LocalTransaction)ts).getTransactionPrepareCallback();
        }
        
        int spec_cnt = 0;
        for (Integer p : partitions) {
            if (this.local_partition_offsets[p.intValue()] == -1) continue;
            
            // Always tell the queue stuff that the transaction is finished at this partition
            if (d) LOG.debug(String.format("Telling queue manager that txn #%d is finished at partition %d", txn_id, p));
            this.txnQueueManager.lockFinished(txn_id, Status.OK, p.intValue());
            
            // TODO: If this txn is read-only, then we should invoke finish right here
            // Because this txn didn't change anything at this partition, we should
            // release all of its locks and immediately allow the partition to execute
            // transactions without speculative execution. We sort of already do that
            // because we will allow spec exec read-only txns to commit immediately 
            // but it would reduce the number of messages that the base partition needs
            // to wait for when it does the 2PC:FINISH
            // Berstein's book says that most systems don't actually do this because a txn may 
            // need to execute triggers... but since we don't have any triggers we can do it!
            // More Info: https://github.com/apavlo/h-store/issues/31
            
            // If speculative execution is enabled, then we'll turn it on at the PartitionExecutor
            // for this partition
            if (ts != null && hstore_conf.site.specexec_enable) {
                if (d) LOG.debug(String.format("Telling partition %d to enable speculative execution because of txn #%d", p, txn_id));
                boolean ret = this.executors[p.intValue()].enableSpeculativeExecution(ts);
                if (d && ret) {
                    spec_cnt++;
                    LOG.debug(String.format("Partition %d - Speculative Execution!", p));
                }
            }
            if (updated != null) updated.add(p);
            if (callback != null) callback.decrementCounter(1);

        } // FOR
        if (d && spec_cnt > 0)
            LOG.debug(String.format("Enabled speculative execution at %d partitions because of waiting for txn #%d", spec_cnt, txn_id));
    }
    
    /**
     * This method is used to finish a distributed transaction.
     * The PartitionExecutor will either commit or abort the transaction at the specified partitions
     * This is a non-blocking call that doesn't wait to know that the txn was finished successfully at 
     * each PartitionExecutor.
     * @param txn_id
     * @param status
     * @param partitions
     */
    public void transactionFinish(Long txn_id, Status status, PartitionSet partitions) {
        if (d) LOG.debug(String.format("2PC:FINISH Txn #%d [commitStatus=%s, partitions=%s]",
                                       txn_id, status, partitions));
        boolean commit = (status == Status.OK);
        
        // If we don't have a AbstractTransaction handle, then we know that we never did anything
        // for this transaction and we can just ignore this finish request. We do have to tell
        // the TransactionQueue manager that we're done though
        AbstractTransaction ts = this.inflight_txns.get(txn_id);
        TransactionFinishCallback finish_callback = null;
        TransactionCleanupCallback cleanup_callback = null;
        if (ts != null) {
            ts.setStatus(status);
            
            if (ts instanceof RemoteTransaction || ts instanceof MapReduceTransaction) {
                if (d) LOG.debug(ts + " - Initialzing the TransactionCleanupCallback");
                // TODO(xin): We should not be invoking this callback at the basePartition's site
                if ( !(ts instanceof MapReduceTransaction && this.isLocalPartition(ts.getBasePartition()))) {
                    cleanup_callback = ts.getCleanupCallback();
                    assert(cleanup_callback != null);
                    cleanup_callback.init(ts, status, partitions);
                }
            } else {
                finish_callback = ((LocalTransaction)ts).getTransactionFinishCallback();
                assert(finish_callback != null);
            }
        }
        
        for (Integer p : partitions) {
            if (this.isLocalPartition(p.intValue()) == false) {
                if (t) LOG.trace(String.format("#%d - Skipping finish at partition %d", txn_id, p));
                continue;
            }
            if (t) LOG.trace(String.format("#%d - Invoking finish at partition %d", txn_id, p));
            
            // We only need to tell the queue stuff that the transaction is finished
            // if it's not a commit because there won't be a 2PC:PREPARE message
            if (commit == false) this.txnQueueManager.lockFinished(txn_id, status, p.intValue());

            // Then actually commit the transaction in the execution engine
            // We only need to do this for distributed transactions, because all single-partition
            // transactions will commit/abort immediately
            if (ts != null && ts.isPredictSinglePartition() == false && ts.needsFinish(p.intValue())) {
                if (d) LOG.debug(String.format("%s - Calling finishTransaction on partition %d", ts, p));
                try {
                    this.executors[p.intValue()].queueFinish(ts, status);
                } catch (Throwable ex) {
                    LOG.error(String.format("Unexpected error when trying to finish %s\nHashCode: %d / Status: %s / Partitions: %s",
                                            ts, ts.hashCode(), status, partitions));
                    throw new RuntimeException(ex);
                }
            }
            // If this is a LocalTransaction, then we want to just decrement their TransactionFinishCallback counter
            else if (finish_callback != null) {
                if (d) LOG.debug(String.format("%s - Notifying %s that the txn is finished at partition %d",
                                               ts, finish_callback.getClass().getSimpleName(), p));
                finish_callback.decrementCounter(1);
            }
            // If we didn't queue the transaction to be finished at this partition, then we need to make sure
            // that we mark the transaction as finished for this callback
            else if (cleanup_callback != null) {
                if (d) LOG.debug(String.format("%s - Notifying %s that the txn is finished at partition %d",
                                               ts, cleanup_callback.getClass().getSimpleName(), p));
                cleanup_callback.run(p);
            }
        } // FOR            
    }

    // ----------------------------------------------------------------------------
    // FAILED TRANSACTIONS (REQUEUE / REJECT / RESTART)
    // ----------------------------------------------------------------------------
    
    /**
     * Send the transaction request to another node for execution. We will create
     * a TransactionRedirectCallback that will automatically send the ClientResponse
     * generated from the remote node for this txn back to the client 
     * @param catalog_proc
     * @param serializedRequest
     * @param base_partition
     * @param clientCallback
     */
    public void transactionRedirect(Procedure catalog_proc,
                                    ByteBuffer serializedRequest,
                                    int base_partition,
                                    RpcCallback<ClientResponseImpl> clientCallback) {
        if (d) LOG.debug(String.format("Forwarding %s request to partition %d", catalog_proc.getName(), base_partition));
        
        // Make a wrapper for the original callback so that when the result comes back frm the remote partition
        // we will just forward it back to the client. How sweet is that??
        TransactionRedirectCallback callback = null;
        try {
            callback = (TransactionRedirectCallback)objectPools.CALLBACKS_TXN_REDIRECT_REQUEST.borrowObject();
            callback.init(clientCallback);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to get TransactionRedirectCallback", ex);
        }
        
        // Mark this request as having been redirected
        // XXX: This sucks because we have to copy the bytes, which will then
        // get copied again when we have to serialize it out to a ByteString
        serializedRequest.rewind();
        ByteBuffer copy = ByteBuffer.allocate(serializedRequest.capacity());
        copy.put(serializedRequest);
        StoredProcedureInvocation.setBasePartition(base_partition, copy);
        
        this.hstore_coordinator.transactionRedirect(copy.array(),
                                                    callback,
                                                    base_partition);
        if (hstore_conf.site.status_show_txn_info) TxnCounter.REDIRECTED.inc(catalog_proc);
    }
    
    /**
     * A non-blocking method to requeue an aborted transaction using the
     * TransactionQueueManager. This allows a PartitionExecutor to tell us that
     * they can't execute some transaction and we'll let the queue manager's 
     * thread take care of it for us.
     * This will eventually call HStoreSite.transactionRestart()
     * @param ts
     * @param status
     */
    public void transactionRequeue(LocalTransaction ts, Status status) {
        assert(ts != null);
        assert(status != Status.OK) :
            "Unexpected requeue status " + status + " for " + ts;
        ts.setStatus(status);
        this.txnQueueManager.restartTransaction(ts, status);
    }
    
    /**
     * Rejects a transaction and returns an empty result back to the client
     * @param ts
     */
    public void transactionReject(LocalTransaction ts, Status status) {
        assert(ts.isInitialized());
        if (d) LOG.debug(String.format("%s - Rejecting transaction with status %s [clientHandle=%d]",
                                       ts, status, ts.getClientHandle()));
        
        ts.setStatus(status);
        ClientResponseImpl cresponse = new ClientResponseImpl();
        cresponse.init(ts.getTransactionId(),
                       ts.getClientHandle(),
                       ts.getBasePartition(),
                       status,
                       HStoreConstants.EMPTY_RESULT,
                       this.REJECTION_MESSAGE,
                       ts.getPendingError());
        this.responseSend(ts, cresponse);

        if (hstore_conf.site.status_show_txn_info) {
            if (status == Status.ABORT_REJECT) {
                TxnCounter.REJECTED.inc(ts.getProcedure());
            } else {
                assert(false) : "Unexpected rejection status for " + ts + ": " + status;
            }
        }
    }

    /**
     * Restart the given transaction with a brand new transaction handle.
     * This method will perform the following operations:
     *  (1) Restart the transaction as new multi-partitioned transaction
     *  (2) Mark the original transaction as aborted so that is rolled back
     *  
     * <B>IMPORTANT:</B> If the return status of the transaction is ABORT_REJECT, then
     *                   you will probably need to delete the transaction handle.
     * <B>IMPORTANT:</B> This is a blocking call and should not be invoked by the PartitionExecutor
     *                    
     * @param status Final status of this transaction
     * @param ts
     * @return Returns the final status of this transaction
     */
    public Status transactionRestart(LocalTransaction orig_ts, Status status) {
        assert(orig_ts != null) : "Null LocalTransaction handle [status=" + status + "]";
        assert(orig_ts.isInitialized()) : "Uninitialized transaction??";
        if (d) LOG.debug(String.format("%s got hit with a %s! Going to clean-up our mess and re-execute [restarts=%d]",
                                   orig_ts , status, orig_ts.getRestartCounter()));
        int base_partition = orig_ts.getBasePartition();
        SerializableException orig_error = orig_ts.getPendingError();
        
        // If this txn has been restarted too many times, then we'll just give up
        // and reject it outright
        int restart_limit = (orig_ts.isSysProc() ? hstore_conf.site.txn_restart_limit_sysproc :
                                                   hstore_conf.site.txn_restart_limit);
        if (orig_ts.getRestartCounter() > restart_limit) {
            if (orig_ts.isSysProc()) {
                throw new RuntimeException(String.format("%s has been restarted %d times! Rejecting...",
                                                         orig_ts, orig_ts.getRestartCounter()));
            } else {
                this.transactionReject(orig_ts, Status.ABORT_REJECT);
                return (Status.ABORT_REJECT);
            }
        }
        
        // -------------------------------
        // REDIRECTION
        // -------------------------------
        if (hstore_conf.site.exec_db2_redirects && 
                 status != Status.ABORT_RESTART && 
                 status != Status.ABORT_EVICTEDACCESS) {
            // Figure out whether this transaction should be redirected based on what partitions it
            // tried to touch before it was aborted
            Histogram<Integer> touched = orig_ts.getTouchedPartitions();
            Collection<Integer> most_touched = touched.getMaxCountValues();
            assert(most_touched != null) :
                "Failed to get most touched partition for " + orig_ts + "\n" + touched;
            
            // XXX: We should probably decrement the base partition by one 
            //      so that we only consider where they actually executed queries
            if (d) LOG.debug(String.format("Touched partitions for mispredicted %s\n%s",
                                           orig_ts, touched));
            Integer redirect_partition = null;
            if (most_touched.size() == 1) {
                redirect_partition = CollectionUtil.first(most_touched);
            } else if (most_touched.isEmpty() == false) {
                redirect_partition = CollectionUtil.random(most_touched);
            } else {
                redirect_partition = CollectionUtil.random(this.all_partitions);
            }
            assert(redirect_partition != null) : "Redirect partition is null!\n" + orig_ts.debug();
            if (t) {
                LOG.trace("Redirect Partition: " + redirect_partition + " -> " + (this.isLocalPartition(redirect_partition) == false));
                LOG.trace("Local Partitions: " + Arrays.toString(this.local_partitions_arr));
            }
            
            // If the txn wants to execute on another node, then we'll send them off *only* if this txn wasn't
            // already redirected at least once. If this txn was already redirected, then it's going to just
            // execute on the same partition, but this time as a multi-partition txn that locks all partitions.
            // That's what you get for messing up!!
            if (this.isLocalPartition(redirect_partition.intValue()) == false && orig_ts.getRestartCounter() == 0) {
                if (d) LOG.debug(String.format("%s - Redirecting to partition %d because of misprediction",
                                               orig_ts, redirect_partition));
                
                Procedure catalog_proc = orig_ts.getProcedure();
                StoredProcedureInvocation spi = new StoredProcedureInvocation(orig_ts.getClientHandle(),
                                                                              catalog_proc.getId(),
                                                                              catalog_proc.getName(),
                                                                              orig_ts.getProcedureParameters().toArray());
                spi.setBasePartition(redirect_partition.intValue());
                spi.setRestartCounter(orig_ts.getRestartCounter()+1);
                
                FastSerializer out = this.getOutgoingSerializer();
                try {
                    out.writeObject(spi);
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to serialize StoredProcedureInvocation to redirect %s" + orig_ts, ex);
                }
                
                TransactionRedirectCallback callback;
                try {
                    callback = (TransactionRedirectCallback)objectPools.CALLBACKS_TXN_REDIRECT_REQUEST.borrowObject();
                    callback.init(orig_ts.getClientCallback());
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to get ForwardTxnRequestCallback", ex);   
                }
                this.hstore_coordinator.transactionRedirect(out.getBytes(),
                                                            callback,
                                                            redirect_partition);
                out.clear();
                if (hstore_conf.site.status_show_txn_info) TxnCounter.REDIRECTED.inc(orig_ts.getProcedure());
                return (Status.ABORT_RESTART);
                
            // Allow local redirect
            } else if (orig_ts.getRestartCounter() <= 1) {
                if (redirect_partition.intValue() != base_partition &&
                    this.isLocalPartition(redirect_partition.intValue())) {
                    if (d) LOG.debug(String.format("Redirecting %s to local partition %d. restartCtr=%d]\n%s",
                                                    orig_ts, redirect_partition, orig_ts.getRestartCounter(), touched));
                    base_partition = redirect_partition.intValue();
                }
            } else {
                if (d) LOG.debug(String.format("Mispredicted %s has already been aborted once before. " +
                                               "Restarting as all-partition txn [restartCtr=%d, redirectPartition=%d]\n%s",
                                               orig_ts, orig_ts.getRestartCounter(), redirect_partition, touched));
                touched.putAll(this.local_partitions);
            }
        }

        // -------------------------------
        // LOCAL RE-EXECUTION
        // -------------------------------
        Long new_txn_id = this.getTransactionIdManager(base_partition).getNextUniqueTransactionId();
        LocalTransaction new_ts = null;
        try {
            new_ts = objectPools.getLocalTransactionPool(base_partition).borrowObject();
        } catch (Exception ex) {
            LOG.fatal("Failed to instantiate new LocalTransactionState for mispredicted " + orig_ts);
            throw new RuntimeException(ex);
        }
        if (hstore_conf.site.txn_profiling) new_ts.profiler.startTransaction(ProfileMeasurement.getTime());
        
        // Figure out what partitions they tried to touch so that we can make sure to lock
        // those when the txn is restarted
        boolean malloc = false;
        PartitionSet predict_touchedPartitions = null;
        if (status == Status.ABORT_RESTART || status == Status.ABORT_EVICTEDACCESS) {
            predict_touchedPartitions = orig_ts.getPredictTouchedPartitions();
        } else if (orig_ts.getRestartCounter() == 0) {
            // HACK: Ignore ConcurrentModificationException
            // This can occur if we are trying to requeue the transactions but there are still
            // pieces of it floating around at this site that modify the TouchedPartitions histogram
            predict_touchedPartitions = new PartitionSet();
            malloc = true;
            Collection<Integer> orig_touchedPartitions = orig_ts.getTouchedPartitions().values();
            while (true) {
                try {
                    predict_touchedPartitions.addAll(orig_touchedPartitions);
                } catch (ConcurrentModificationException ex) {
                    continue;
                }
                break;
            } // WHILE
        } else {
            predict_touchedPartitions = this.all_partitions;
        }
        
        // -------------------------------
        // MISPREDICTION
        // -------------------------------
        if (status == Status.ABORT_MISPREDICT && orig_error instanceof MispredictionException) {
            MispredictionException ex = (MispredictionException)orig_error;
            Collection<Integer> partitions = ex.getPartitions().values();
            assert(partitions.isEmpty() == false) :
                "Unexpected empty MispredictionException PartitionSet for " + orig_ts;

            if (predict_touchedPartitions.containsAll(partitions) == false) {
                if (malloc == false) {
                    // XXX: Since the MispredictionException isn't re-used, we can 
                    //      probably reuse the PartitionSet 
                    predict_touchedPartitions = new PartitionSet(predict_touchedPartitions);
                    malloc = true;
                }
                predict_touchedPartitions.addAll(partitions);
            }
            if (d) LOG.debug(orig_ts + " Mispredicted Partitions: " + partitions);
        }
        
        if (predict_touchedPartitions.contains(base_partition) == false) {
            if (malloc == false) {
                predict_touchedPartitions = new PartitionSet(predict_touchedPartitions);
                malloc = true;
            }
            predict_touchedPartitions.add(base_partition);
        }
        if (predict_touchedPartitions.isEmpty()) predict_touchedPartitions = this.all_partitions;
        
        // -------------------------------
        // NEW TXN INITIALIZATION
        // -------------------------------
        boolean predict_readOnly = orig_ts.getProcedure().getReadonly(); // FIXME
        boolean predict_abortable = true; // FIXME
        new_ts.init(new_txn_id,
                    orig_ts.getClientHandle(),
                    base_partition,
                    predict_touchedPartitions,
                    predict_readOnly,
                    predict_abortable,
                    orig_ts.getProcedure(),
                    orig_ts.getProcedureParameters(),
                    orig_ts.getClientCallback()
        );
        // Make sure that we remove the ParameterSet from the original LocalTransaction
        // so that they don't get returned back to the object pool when it is deleted
        orig_ts.removeProcedureParameters();
        
        // Increase the restart counter in the new transaction
        new_ts.setRestartCounter(orig_ts.getRestartCounter() + 1);
        
        // -------------------------------
        // ANTI-CACHING REQUEUE
        // -------------------------------
        if (status == Status.ABORT_EVICTEDACCESS) {
            if (this.anticacheManager == null) {
                String message = "Got eviction notice but anti-caching is not enabled";
                throw new ServerFaultException(message, orig_error, orig_ts.getTransactionId());
            }

            EvictedTupleAccessException error = (EvictedTupleAccessException)orig_error;
            Table catalog_tbl = error.getTableId(this.catalogContext.database);
            short block_ids[] = error.getBlockIds();
            this.anticacheManager.queue(new_ts, base_partition, catalog_tbl, block_ids);
        }
            
        // -------------------------------
        // REGULAR TXN REQUEUE
        // -------------------------------
        else {
            if (d) {
                LOG.debug(String.format("Re-executing %s as new %s-partition %s on partition %d [restarts=%d, partitions=%s]",
                                        orig_ts,
                                        (predict_touchedPartitions.size() == 1 ? "single" : "multi"),
                                        new_ts,
                                        base_partition,
                                        new_ts.getRestartCounter(),
                                        predict_touchedPartitions));
                if (t && status == Status.ABORT_MISPREDICT)
                    LOG.trace(String.format("%s Mispredicted partitions\n%s", new_ts, orig_ts.getTouchedPartitions().values()));
            }
            
            this.transactionQueue(new_ts);    
        }
        
        return (Status.ABORT_RESTART);
    }

    // ----------------------------------------------------------------------------
    // TRANSACTION FINISH/CLEANUP METHODS
    // ----------------------------------------------------------------------------

    /**
     * Send back the given ClientResponse to the actual client waiting for it
     * At this point the transaction should been properly committed or aborted at
     * the PartitionExecutor, including if it was mispredicted.
     * This method may not actually send the ClientResponse right away if command-logging
     * is enabled. Instead it will be queued up and held until we know that the txn's information
     * was successfully flushed to disk.
     * 
     * <B>Note:</B> The ClientResponse's status cannot be ABORT_MISPREDICT or ABORT_EVICTEDACCESS.
     * @param ts
     * @param cresponse
     */
    public void responseSend(LocalTransaction ts, ClientResponseImpl cresponse) {
        Status status = cresponse.getStatus();
        assert(cresponse != null) :
            "Missing ClientResponse for " + ts;
        assert(cresponse.getClientHandle() != -1) :
            "The client handle for " + ts + " was not set properly";
        assert(status != Status.ABORT_MISPREDICT && status != Status.ABORT_EVICTEDACCESS) :
            "Trying to send back a client response for " + ts + " but the status is " + status;
        
        boolean sendResponse = true;
        if (this.commandLogger != null && status == Status.OK && ts.isSysProc() == false) {
            sendResponse = this.commandLogger.appendToLog(ts, cresponse);
        }

        if (sendResponse) {
            // NO GROUP COMMIT -- SEND OUT AND COMPLETE
            // NO COMMAND LOGGING OR TXN ABORTED -- SEND OUT AND COMPLETE
            this.responseSend(cresponse,
                                    ts.getClientCallback(),
                                    ts.getInitiateTime(),
                                    ts.getRestartCounter());
        } else if (d) { 
            LOG.debug(String.format("%s - Holding the ClientResponse until logged to disk", ts));
        }
    }
    
    /**
     * Instead of having the PartitionExecutor send the ClientResponse directly back
     * to the client, this method will queue it up at one of the TransactionPostProcessors.
     * This feature is not useful if the command-logging is enabled.  
     * @param es
     * @param ts
     * @param cr
     */
    public void responseQueue(LocalTransaction ts, ClientResponseImpl cr) {
        assert(hstore_conf.site.exec_postprocessing_threads);
        if (d) LOG.debug(String.format("Adding ClientResponse for %s from partition %d to processing queue [status=%s, size=%d]",
                                       ts, ts.getBasePartition(), cr.getStatus(), this.postProcessorQueue.size()));
        this.postProcessorQueue.add(Pair.of(ts,cr));
    }

    /**
     * Convenience method for sending an error ClientResponse back to the client
     * @param client_handle
     * @param status
     * @param message
     * @param clientCallback
     * @param initiateTime
     */
    private void responseError(long client_handle,
                               Status status,
                               String message,
                               RpcCallback<ClientResponseImpl> clientCallback,
                               long initiateTime) {
        
        ClientResponseImpl cresponse = new ClientResponseImpl(
                                            -1,
                                            client_handle,
                                            -1,
                                            status,
                                            HStoreConstants.EMPTY_RESULT,
                                            message);
        this.responseSend(cresponse, clientCallback, initiateTime, 0);
    }
    
    /**
     * This is the only place that we will invoke the original Client callback
     * and send back the results. This should not be called directly by anything
     * but the HStoreSite or the CommandLogWriter
     * @param ts
     * @param cresponse
     * @param logTxn
     */
    public void responseSend(ClientResponseImpl cresponse,
                             RpcCallback<ClientResponseImpl> clientCallback,
                             long initiateTime,
                             int restartCounter) {
        Status status = cresponse.getStatus();
 
        // If the txn committed/aborted, then we can send the response directly back to the
        // client here. Note that we don't even need to call HStoreSite.finishTransaction()
        // since that doesn't do anything that we haven't already done!
        if (d) LOG.debug(String.format("Txn #%d - Sending back ClientResponse [status=%s]",
                                       cresponse.getTransactionId(), status));
        
        long now = System.currentTimeMillis();
        EstTimeUpdater.update(now);
        cresponse.setClusterRoundtrip((int)(now - initiateTime));
        cresponse.setRestartCounter(restartCounter);
        try {
            clientCallback.run(cresponse);
        } catch (ClientConnectionLostException ex) {
            if (d) LOG.debug("Failed to send back ClientResponse for txn #" + cresponse.getTransactionId(), ex);
        }
    }
    
    
    /**
     * Perform final cleanup and book keeping for a completed txn
     * If you call this, you can never access anything in this txn's AbstractTransaction again
     * @param txn_id
     */
    public void deleteTransaction(final Long txn_id, final Status status) {
        assert(txn_id != null) : "Unexpected null transaction id";
        if (d) LOG.debug("Deleting internal info for txn #" + txn_id);
        AbstractTransaction abstract_ts = this.inflight_txns.remove(txn_id);
        
        // It's ok for us to not have a transaction handle, because it could be
        // for a remote transaction that told us that they were going to need one
        // of our partitions but then they never actually sent work to us
        if (abstract_ts == null) {
            if (d) LOG.warn(String.format("Ignoring clean-up request for txn #%d because we don't have a handle [status=%s]",
                                          txn_id, status));
            return;
        }
        
        assert(txn_id.equals(abstract_ts.getTransactionId())) :
            String.format("Mismatched %s - Expected[%d] != Actual[%s]", abstract_ts, txn_id, abstract_ts.getTransactionId());

        // Nothing else to do for RemoteTransactions other than to just
        // return the object back into the pool
        if (abstract_ts instanceof RemoteTransaction) {
            if (d) LOG.debug(String.format("Returning %s to ObjectPool [hashCode=%d]", abstract_ts, abstract_ts.hashCode()));
            objectPools.getRemoteTransactionPool(abstract_ts.getBasePartition())
                       .returnObject((RemoteTransaction)abstract_ts);
            return;
        }
        
        this.deleteTransaction((LocalTransaction)abstract_ts, status);
    }

    /**
     * Clean-up all of the state information about a LocalTransaction that is finished
     * <B>Note:</B> This should only be invoked for single-partition txns
     * @param ts
     * @param status
     */
    public void deleteTransaction(LocalTransaction ts, final Status status) {
        final int base_partition = ts.getBasePartition();
        final Procedure catalog_proc = ts.getProcedure();
        final boolean singlePartitioned = ts.isPredictSinglePartition();
       
        if (d) LOG.debug(ts + " - State before delete:\n" + ts.debug());
        assert(ts.checkDeletableFlag()) :
            String.format("Trying to delete %s before it was marked as ready!", ts);
        
        // Update Transaction profiles
        // We have to calculate the profile information *before* we call PartitionExecutor.cleanup!
        // XXX: Should we include totals for mispredicted txns?
        if (hstore_conf.site.txn_profiling && this.status_monitor != null &&
            ts.profiler.isDisabled() == false && status != Status.ABORT_MISPREDICT) {
            ts.profiler.stopTransaction();
            this.status_monitor.addTxnProfile(catalog_proc, ts.profiler);
        }
        
        // Clean-up any extra information that we may have for the txn
        TransactionEstimator t_estimator = null;
        if (ts.getEstimatorState() != null) {
            t_estimator = this.executors[base_partition].getTransactionEstimator();
            assert(t_estimator != null);
        }
        try {
            switch (status) {
                case OK:
                    if (t_estimator != null) {
                        if (t) LOG.trace("Telling the TransactionEstimator to COMMIT " + ts);
                        t_estimator.commit(ts.getTransactionId());
                    }
                    // We always need to keep track of how many txns we process 
                    // in order to check whether we are hung or not
                    if (hstore_conf.site.status_show_txn_info || hstore_conf.site.status_kill_if_hung) 
                        TxnCounter.COMPLETED.inc(catalog_proc);
                    break;
                case ABORT_USER:
                    if (t_estimator != null) {
                        if (t) LOG.trace("Telling the TransactionEstimator to ABORT " + ts);
                        t_estimator.abort(ts.getTransactionId());
                    }
                    if (hstore_conf.site.status_show_txn_info)
                        TxnCounter.ABORTED.inc(catalog_proc);
                    break;
                case ABORT_MISPREDICT:
                case ABORT_RESTART:
                case ABORT_EVICTEDACCESS:
                    if (t_estimator != null) {
                        if (t) LOG.trace("Telling the TransactionEstimator to IGNORE " + ts);
                        t_estimator.mispredict(ts.getTransactionId());
                    }
                    if (hstore_conf.site.status_show_txn_info) {
                        if (status == Status.ABORT_EVICTEDACCESS) {
                            TxnCounter.EVICTEDACCESS.inc(catalog_proc);
                        } else {
                            (ts.isSpeculative() ? TxnCounter.RESTARTED : TxnCounter.MISPREDICTED).inc(catalog_proc);
                        }
                    }
                    break;
                case ABORT_REJECT:
                    if (hstore_conf.site.status_show_txn_info)
                        TxnCounter.REJECTED.inc(catalog_proc);
                    break;
                case ABORT_UNEXPECTED:
                case ABORT_GRACEFUL:
                    // TODO: Make new counter?
                    break;
                default:
                    LOG.warn(String.format("Unexpected status %s for %s", status, ts));
            } // SWITCH
        } catch (Throwable ex) {
            LOG.error(String.format("Unexpected error when cleaning up %s transaction %s",
                                    status, ts), ex);
            // Pass...
        }
        
        // Then update transaction profiling counters
        if (hstore_conf.site.status_show_txn_info) {
            if (ts.isSpeculative()) TxnCounter.SPECULATIVE.inc(catalog_proc);
            if (ts.isExecNoUndoBuffer(base_partition)) TxnCounter.NO_UNDO.inc(catalog_proc);
            if (ts.isSysProc()) {
                TxnCounter.SYSPROCS.inc(catalog_proc);
            } else if (status != Status.ABORT_MISPREDICT &&
                       status != Status.ABORT_REJECT &&
                       status != Status.ABORT_EVICTEDACCESS) {
                (singlePartitioned ? TxnCounter.SINGLE_PARTITION : TxnCounter.MULTI_PARTITION).inc(catalog_proc);
            }
        }
        
        // SANITY CHECK
        if (hstore_conf.site.exec_validate_work) {
            for (Integer p : this.local_partitions_arr) {
                assert(ts.equals(this.executors[p.intValue()].getCurrentDtxn()) == false) :
                    String.format("About to finish %s but it is still the current DTXN at partition %d", ts, p);
            } // FOR
        }

        // Return the ParameterSet back to our pool
//        ParameterSet params = ts.getProcedureParameters();
//        if (params != null) {
//            objectPools.PARAMETERSETS.returnObject(params);
//        }
        
        // HACK: Make sure the txn_id is removed from our internal map
        // This is unnecessary for single-partition txns
        this.inflight_txns.remove(ts.getTransactionId());
        
        assert(ts.isInitialized()) : "Trying to return uninititlized txn #" + ts.getTransactionId();
        if (d) LOG.debug(String.format("%s - Returning to ObjectPool [hashCode=%d]", ts, ts.hashCode()));
        if (ts.isMapReduce()) {
            objectPools.getMapReduceTransactionPool(base_partition).returnObject((MapReduceTransaction)ts);
        } else {
            objectPools.getLocalTransactionPool(base_partition).returnObject(ts);
        }
    }

    // ----------------------------------------------------------------------------
    // UTILITY WORK
    // ----------------------------------------------------------------------------
    
    /**
     * Added for @AdHoc processes, periodically checks for AdHoc queries waiting to be compiled.
     * 
     */
	private void processPeriodicWork() {
	    if (t)
		    LOG.trace("Checking for PeriodicWork...");

	    if (this.clientInterface != null) {
	        this.clientInterface.checkForDeadConnections(EstTime.currentTimeMillis());
	    }
	    
	    // poll planner queue
	    if (asyncCompilerWork_thread != null) {
	        try {
	            checkForFinishedCompilerWork();
	        } catch (Throwable ex) {
	            ex.printStackTrace();
	            throw new RuntimeException(ex);
	        }
	    }

        return;
	}

	/**
     * Added for @AdHoc processes
     * 
     */
	private void checkForFinishedCompilerWork() {
		if (t) LOG.trace("HStoreSite - Checking for finished compiled work.");
        AsyncCompilerResult result = null;
 
        while ((result = asyncCompilerWork_thread.getPlannedStmt()) != null) {
            if (d) LOG.debug("AsyncCompilerResult\n" + result);
            
            // ----------------------------------
            // BUSTED!
            // ----------------------------------
            if (result.errorMsg != null) {
                if (d)
                    LOG.error("Unexpected AsyncCompiler Error:\n" + result.errorMsg);
                
                ClientResponseImpl errorResponse =
                        new ClientResponseImpl(-1,
                                               result.clientHandle,
                                               this.local_partition_reverse[0],
                                               Status.ABORT_UNEXPECTED,
                                               HStoreConstants.EMPTY_RESULT,
                                               result.errorMsg);
                this.responseSend(result.ts, errorResponse);
                
                // We can just delete the LocalTransaction handle directly
                result.ts.markAsDeletable();
                this.deleteTransaction(result.ts, Status.ABORT_UNEXPECTED);
            }
            // ----------------------------------
            // AdHocPlannedStmt
            // ----------------------------------
            else if (result instanceof AdHocPlannedStmt) {
                AdHocPlannedStmt plannedStmt = (AdHocPlannedStmt) result;

                // Modify the StoredProcedureInvocation
                ParameterSet params = result.ts.getProcedureParameters();
                assert(params != null) : "Unexpected null ParameterSet";
                params.setParameters(
                    plannedStmt.aggregatorFragment,
                    plannedStmt.collectorFragment,
                    plannedStmt.sql,
                    plannedStmt.isReplicatedTableDML ? 1 : 0
        		);

                // initiate the transaction
                int base_partition = result.ts.getBasePartition();
                Long txn_id = this.getTransactionIdManager(base_partition).getNextUniqueTransactionId();
                result.ts.setTransactionId(txn_id);
                
                if (d) LOG.debug("Queuing AdHoc transaction: " + result.ts);
                this.transactionQueue(result.ts);
                
            }
            // ----------------------------------
            // Unexpected
            // ----------------------------------
            else {
                throw new RuntimeException(
                        "Should not be able to get here (HStoreSite.checkForFinishedCompilerWork())");
            }
        } // WHILE
	}
	
    // ----------------------------------------------------------------------------
    // DEBUG METHODS
    // ----------------------------------------------------------------------------
	
	/**
     * Get the total number of transactions inflight for all partitions 
     */
    protected int getInflightTxnCount() {
        return (this.inflight_txns.size());
    }
    /**
     * Get the collection of inflight Transaction state handles
     * THIS SHOULD ONLY BE USED FOR TESTING!
     * @return
     */
    protected Collection<AbstractTransaction> getInflightTransactions() {
        return (this.inflight_txns.values());
    }
    
    protected int getQueuedResponseCount() {
        return (this.postProcessorQueue.size());
    }


}
