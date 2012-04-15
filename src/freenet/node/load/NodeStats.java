package freenet.node.load;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map;

import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.xfer.BlockTransmitter.BlockTimeCallback;
import freenet.io.xfer.BulkTransmitter;
import freenet.keys.NodeSSK;
import freenet.l10n.NodeL10n;
import freenet.node.ConfigurablePersister;
import freenet.node.Location;
import freenet.node.MemoryChecker;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodePinger;
import freenet.node.PeerManager;
import freenet.node.PeerNode;
import freenet.node.PeerNodeStatus;
import freenet.node.RequestStarter;
import freenet.node.SecurityLevelListener;
import freenet.node.SecurityLevels;
import freenet.node.opennet.OpennetManager;
import freenet.node.opennet.SeedClientPeerNode;
import freenet.node.requests.CHKInsertSender;
import freenet.node.requests.RequestSender;
import freenet.node.requests.RequestTracker;
import freenet.node.requests.RequestTracker.CountedRequests;
import freenet.node.SecurityLevels.NETWORK_THREAT_LEVEL;
import freenet.node.requests.UIDTag;
import freenet.node.stats.HourlyStats;
import freenet.node.stats.store.StatsNotAvailableException;
import freenet.node.stats.store.StoreLocationStats;
import freenet.store.CHKStore;
import freenet.support.HTMLNode;
import freenet.support.Histogram2;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.SizeUtil;
import freenet.support.StringCounter;
import freenet.support.TimeUtil;
import freenet.support.TokenBucket;
import freenet.support.api.BooleanCallback;
import freenet.support.api.IntCallback;
import freenet.support.api.LongCallback;
import freenet.support.io.NativeThread;
import freenet.support.io.Persistable;
import freenet.support.io.Persister;
import freenet.support.math.BootstrappingDecayingRunningAverage;
import freenet.support.math.DecayingKeyspaceAverage;
import freenet.support.math.RunningAverage;
import freenet.support.math.TimeDecayingRunningAverage;
import freenet.support.math.TrivialRunningAverage;

/** Node (as opposed to NodeClientCore) level statistics. Includes shouldRejectRequest(), but not limited
 * to stuff required to implement that. */
public class NodeStats implements Persistable, BlockTimeCallback {

	public static enum RequestType {
		CHK_REQUEST,
		SSK_REQUEST,
		CHK_INSERT,
		SSK_INSERT,
		CHK_OFFER_FETCH,
		SSK_OFFER_FETCH;
	}
	
	/** Sub-max ping time. If ping is greater than this, we reject some requests. */
	public static final long DEFAULT_SUB_MAX_PING_TIME = 700;
	/** Maximum overall average ping time. If ping is greater than this,
	 * we reject all requests. */
	public static final long DEFAULT_MAX_PING_TIME = 1500;
	/** Maximum throttled packet delay. If the throttled packet delay is greater
	 * than this, reject all packets. */
	public static final long MAX_THROTTLE_DELAY_RT = 2000;
	public static final long MAX_THROTTLE_DELAY_BULK = 10000;
	/** If the throttled packet delay is less than this, reject no packets; if it's
	 * between the two, reject some packets. */
	public static final long SUB_MAX_THROTTLE_DELAY_RT = 1000;
	public static final long SUB_MAX_THROTTLE_DELAY_BULK = 5000;
	/** How high can bwlimitDelayTime be before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD = MAX_THROTTLE_DELAY_BULK;
	/** How high can nodeAveragePingTime be before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD = DEFAULT_MAX_PING_TIME;
	/** How long we're over the bwlimitDelayTime threshold before we alert (in milliseconds)*/
	public static final long MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** How long we're over the nodeAveragePingTime threshold before we alert (in milliseconds)*/
	public static final long MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY = 10*60*1000;  // 10 minutes
	/** Accept one request every 10 seconds regardless, to ensure we update the
	 * block send time.
	 */
	public static final int MAX_INTERREQUEST_TIME = 10*1000;
	/** Locations of incoming requests */
	private final int[] incomingRequestsByLoc = new int[10];
	private int incomingRequestsAccounted = 0;
	/** Locations of outgoing requests */
	private final int[] outgoingLocalRequestByLoc = new int[10];
	private int outgoingLocalRequestsAccounted = 0;
	private final int[] outgoingRequestByLoc = new int[10];
	private int outgoingRequestsAccounted = 0;
	private volatile long subMaxPingTime;
	private volatile long maxPingTime;
    private final double nodeLoc=0.0;

	final Node node;
	private MemoryChecker myMemoryChecker;
	public final PeerManager peers;

	final RandomSource hardRandom;

	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	/** first time bwlimitDelay was over PeerManagerUserAlert threshold */
	private long firstBwlimitDelayTimeThresholdBreak ;
	/** first time nodeAveragePing was over PeerManagerUserAlert threshold */
	private long firstNodeAveragePingTimeThresholdBreak;
	/** bwlimitDelay PeerManagerUserAlert should happen if true */
	private boolean bwlimitDelayAlertRelevant;
	/** nodeAveragePing PeerManagerUserAlert should happen if true */
	private boolean nodeAveragePingAlertRelevant;
	/** Average proportion of requests rejected immediately due to overload */
	private final TimeDecayingRunningAverage pInstantRejectIncoming;
	private boolean ignoreLocalVsRemoteBandwidthLiability;

	/** Average delay caused by throttling for sending a packet */
	private final RunningAverage throttledPacketSendAverage;
	private final RunningAverage throttledPacketSendAverageRT;
	private final RunningAverage throttledPacketSendAverageBulk;

	// Bytes used by each different type of local/remote chk/ssk request/insert
	// FIXME these should not be accessed directly, they should be accessed via a method.
	private final TimeDecayingRunningAverage remoteChkFetchBytesSentAverage;
	private final TimeDecayingRunningAverage remoteSskFetchBytesSentAverage;
	private final TimeDecayingRunningAverage remoteChkInsertBytesSentAverage;
	private final TimeDecayingRunningAverage remoteSskInsertBytesSentAverage;
	private final TimeDecayingRunningAverage remoteChkFetchBytesReceivedAverage;
	private final TimeDecayingRunningAverage remoteSskFetchBytesReceivedAverage;
	private final TimeDecayingRunningAverage remoteChkInsertBytesReceivedAverage;
	private final TimeDecayingRunningAverage remoteSskInsertBytesReceivedAverage;
	// FIXME these should be private. They are needed for RequestStarterGroup.
	// It should just call a method with identifying parameters or something ...
	public final TimeDecayingRunningAverage localChkFetchBytesSentAverage;
	public final TimeDecayingRunningAverage localSskFetchBytesSentAverage;
	public final TimeDecayingRunningAverage localChkInsertBytesSentAverage;
	public final TimeDecayingRunningAverage localSskInsertBytesSentAverage;
	public final TimeDecayingRunningAverage localChkFetchBytesReceivedAverage;
	public final TimeDecayingRunningAverage localSskFetchBytesReceivedAverage;
	public final TimeDecayingRunningAverage localChkInsertBytesReceivedAverage;
	public final TimeDecayingRunningAverage localSskInsertBytesReceivedAverage;

	// Bytes used by successful chk/ssk request/insert.
	// Note: These are used to determine whether to accept a request,
	// hence they should be roughly representative of incoming - NOT LOCAL -
	// requests. Therefore, while we DO report local successful requests,
	// we only report the portion which will be consistent with a remote
	// request. If there is both a Handler and a Sender, it's a remote
	// request, report both. If there is only a Sender, report only the
	// received bytes (for a request). Etc.

	// Note that these are always reported in the Handler or the NodeClientCore
	// call taking its place.
	private final TimeDecayingRunningAverage successfulChkFetchBytesSentAverage;
	private final TimeDecayingRunningAverage successfulSskFetchBytesSentAverage;
	private final TimeDecayingRunningAverage successfulChkInsertBytesSentAverage;
	private final TimeDecayingRunningAverage successfulSskInsertBytesSentAverage;
	private final TimeDecayingRunningAverage successfulChkOfferReplyBytesSentAverage;
	private final TimeDecayingRunningAverage successfulSskOfferReplyBytesSentAverage;
	private final TimeDecayingRunningAverage successfulChkFetchBytesReceivedAverage;
	private final TimeDecayingRunningAverage successfulSskFetchBytesReceivedAverage;
	private final TimeDecayingRunningAverage successfulChkInsertBytesReceivedAverage;
	private final TimeDecayingRunningAverage successfulSskInsertBytesReceivedAverage;
	private final TimeDecayingRunningAverage successfulChkOfferReplyBytesReceivedAverage;
	private final TimeDecayingRunningAverage successfulSskOfferReplyBytesReceivedAverage;

	private final TrivialRunningAverage globalFetchPSuccess;
	private final TrivialRunningAverage chkLocalFetchPSuccess;
	private final TrivialRunningAverage chkRemoteFetchPSuccess;
	private final TrivialRunningAverage sskLocalFetchPSuccess;
	private final TrivialRunningAverage sskRemoteFetchPSuccess;
	private final TrivialRunningAverage blockTransferPSuccessRT;
	private final TrivialRunningAverage blockTransferPSuccessBulk;
	private final TrivialRunningAverage blockTransferPSuccessLocal;
	private final TrivialRunningAverage blockTransferFailTimeout;
	
	private final TrivialRunningAverage successfulLocalCHKFetchTimeAverageRT;
	private final TrivialRunningAverage unsuccessfulLocalCHKFetchTimeAverageRT;
	private final TrivialRunningAverage localCHKFetchTimeAverageRT;
	private final TrivialRunningAverage successfulLocalCHKFetchTimeAverageBulk;
	private final TrivialRunningAverage unsuccessfulLocalCHKFetchTimeAverageBulk;
	private final TrivialRunningAverage localCHKFetchTimeAverageBulk;

	private final TrivialRunningAverage successfulLocalSSKFetchTimeAverageRT;
	private final TrivialRunningAverage unsuccessfulLocalSSKFetchTimeAverageRT;
	private final TrivialRunningAverage localSSKFetchTimeAverageRT;
	private final TrivialRunningAverage successfulLocalSSKFetchTimeAverageBulk;
	private final TrivialRunningAverage unsuccessfulLocalSSKFetchTimeAverageBulk;
	private final TrivialRunningAverage localSSKFetchTimeAverageBulk;

	private final Histogram2 chkSuccessRatesByLocation;

	private long previous_input_stat;
	private long previous_output_stat;
	private long previous_io_stat_time;
	private long last_input_stat;
	private long last_output_stat;
	private long last_io_stat_time;
	private final Object ioStatSync = new Object();
	/** Next time to update the node I/O stats */
	private long nextNodeIOStatsUpdateTime = -1;
	/** Node I/O stats update interval (milliseconds) */
	private static final long nodeIOStatsUpdateInterval = 2000;

	/** Token bucket for output bandwidth used by requests */
	public final TokenBucket requestOutputThrottle;
	/** Token bucket for input bandwidth used by requests */
	public final TokenBucket requestInputThrottle;

	// various metrics
	private final RunningAverage routingMissDistanceLocal;
	private final RunningAverage routingMissDistanceRemote;
	private final RunningAverage routingMissDistanceOverall;
	private final RunningAverage routingMissDistanceBulk;
	private final RunningAverage routingMissDistanceRT;
	private final RunningAverage backedOffPercent;
	private final DecayingKeyspaceAverage avgCacheCHKLocation;
	private final DecayingKeyspaceAverage avgSlashdotCacheCHKLocation;
	//public final DecayingKeyspaceAverage avgSlashdotCacheLocation;
	private final DecayingKeyspaceAverage avgStoreCHKLocation;
	//public final DecayingKeyspaceAverage avgStoreLocation;
	private final DecayingKeyspaceAverage avgStoreCHKSuccess;

	// FIXME: does furthest{Store,Cache}Success need to be synchronized?
	private double furthestCacheCHKSuccess=0.0;
	private double furthestClientCacheCHKSuccess=0.0;
	private double furthestSlashdotCacheCHKSuccess=0.0;
	private double furthestStoreCHKSuccess=0.0;
	private double furthestStoreSSKSuccess=0.0;
	private double furthestCacheSSKSuccess=0.0;
	private double furthestClientCacheSSKSuccess=0.0;
	private double furthestSlashdotCacheSSKSuccess=0.0;
	protected final Persister persister;

	protected final DecayingKeyspaceAverage avgRequestLocation;

	// ThreadCounting stuffs
	public final ThreadGroup rootThreadGroup;
	private int threadLimit;

	public final NodePinger nodePinger;

	private final StringCounter preemptiveRejectReasons;
	private final StringCounter localPreemptiveRejectReasons;

	// Enable this if you run into hard to debug OOMs.
	// Disabled to prevent long pauses every 30 seconds.
	private int aggressiveGCModificator = -1 /*250*/;

	// Peers stats
	/** Next time to update PeerManagerUserAlert stats */
	private long nextPeerManagerUserAlertStatsUpdateTime = -1;
	/** PeerManagerUserAlert stats update interval (milliseconds) */
	private static final long peerManagerUserAlertStatsUpdateInterval = 1000;  // 1 second

	// Backoff stats
	private final Hashtable<String, TrivialRunningAverage> avgMandatoryBackoffTimesRT;
	private final Hashtable<String, TrivialRunningAverage> avgMandatoryBackoffTimesBulk;
	private final Hashtable<String, TrivialRunningAverage> avgRoutingBackoffTimesRT;
	private final Hashtable<String, TrivialRunningAverage> avgRoutingBackoffTimesBulk;
	private final Hashtable<String, TrivialRunningAverage> avgTransferBackoffTimesRT;
	private final Hashtable<String, TrivialRunningAverage> avgTransferBackoffTimesBulk;

	// Database stats
	private final Hashtable<String, TrivialRunningAverage> avgDatabaseJobExecutionTimes;
	private final DecayingKeyspaceAverage avgClientCacheCHKLocation;
	private final DecayingKeyspaceAverage avgCacheCHKSuccess;
	private final DecayingKeyspaceAverage avgSlashdotCacheCHKSucess;
	private final DecayingKeyspaceAverage avgClientCacheCHKSuccess;

	private final DecayingKeyspaceAverage avgStoreSSKLocation;
	private final DecayingKeyspaceAverage avgCacheSSKLocation;
	private final DecayingKeyspaceAverage avgSlashdotCacheSSKLocation;
	private final DecayingKeyspaceAverage avgClientCacheSSKLocation;

	private final DecayingKeyspaceAverage avgCacheSSKSuccess;
	private final DecayingKeyspaceAverage avgSlashdotCacheSSKSuccess;
	private final DecayingKeyspaceAverage avgClientCacheSSKSuccess;
	private final DecayingKeyspaceAverage avgStoreSSKSuccess;
	
	private volatile boolean enableNewLoadManagementRT;
	private volatile boolean enableNewLoadManagementBulk;

	public NodeStats(Node node, int sortOrder, SubConfig statsConfig, int obwLimit, int ibwLimit, int lastVersion) throws NodeInitException {
		this.node = node;
		this.peers = node.peers;
		this.hardRandom = node.random;
		this.routingMissDistanceLocal = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.routingMissDistanceRemote = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.routingMissDistanceOverall = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.routingMissDistanceBulk = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.routingMissDistanceRT = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		this.backedOffPercent = new TimeDecayingRunningAverage(0.0, 180000, 0.0, 1.0, node);
		preemptiveRejectReasons = new StringCounter();
		localPreemptiveRejectReasons = new StringCounter();
		pInstantRejectIncoming = new TimeDecayingRunningAverage(0, 60000, 0.0, 1.0, node);
		ThreadGroup tg = Thread.currentThread().getThreadGroup();
		while(tg.getParent() != null) tg = tg.getParent();
		this.rootThreadGroup = tg;
		throttledPacketSendAverage =
			new BootstrappingDecayingRunningAverage(0, 0, Long.MAX_VALUE, 100, null);
		throttledPacketSendAverageRT =
			new BootstrappingDecayingRunningAverage(0, 0, Long.MAX_VALUE, 100, null);
		throttledPacketSendAverageBulk =
			new BootstrappingDecayingRunningAverage(0, 0, Long.MAX_VALUE, 100, null);
		nodePinger = new NodePinger(node);

		previous_input_stat = 0;
		previous_output_stat = 0;
		previous_io_stat_time = 1;
		last_input_stat = 0;
		last_output_stat = 0;
		last_io_stat_time = 3;

		int defaultThreadLimit;
		long memoryLimit = Runtime.getRuntime().maxMemory();
		
		System.out.println("Memory is "+SizeUtil.formatSize(memoryLimit)+" ("+memoryLimit+" bytes)");
		if(memoryLimit > 0 && memoryLimit < 100*1024*1024) {
			defaultThreadLimit = 200;
			System.out.println("Severe memory pressure, setting 200 thread limit. Freenet may not work well!");
		} else if(memoryLimit > 0 && memoryLimit < 160*1024*1024) {
			defaultThreadLimit = 300;
			System.out.println("Moderate memory pressure, setting 300 thread limit. Increase your memory limit in wrapper.conf if possible.");
		// FIXME: reinstate this once either we raise the default or memory autodetection works on Windows.
//		else if(memoryLimit > 0 && memoryLimit < 256*1024*1024)
//			defaultThreadLimit = 400;
		} else {
			System.out.println("Setting standard 500 thread limit. This should be enough for most nodes but more memory is usually a good thing.");
			defaultThreadLimit = 500;
		}
		statsConfig.register("threadLimit", defaultThreadLimit, sortOrder++, true, true, "NodeStat.threadLimit", "NodeStat.threadLimitLong",
				new IntCallback() {
					@Override
					public Integer get() {
						return threadLimit;
					}
					@Override
					public void set(Integer val) throws InvalidConfigValueException {
						if (get().equals(val))
					        return;
						if(val < 100)
							throw new InvalidConfigValueException(l10n("valueTooLow"));
						threadLimit = val;
					}
		},false);
		
		if(lastVersion > 0 && lastVersion < 1270 && memoryLimit > 160*1024*1024 && memoryLimit < 192*1024*1024)
			statsConfig.fixOldDefault("threadLimit", "300");
		
		threadLimit = statsConfig.getInt("threadLimit");

		// Yes it could be in seconds insteed of multiples of 0.12, but we don't want people to play with it :)
		statsConfig.register("aggressiveGC", aggressiveGCModificator, sortOrder++, true, false, "NodeStat.aggressiveGC", "NodeStat.aggressiveGCLong",
				new IntCallback() {
					@Override
					public Integer get() {
						return aggressiveGCModificator;
					}
					@Override
					public void set(Integer val) throws InvalidConfigValueException {
						if (get().equals(val))
					        return;
						Logger.normal(this, "Changing aggressiveGCModificator to "+val);
						aggressiveGCModificator = val;
					}
		},false);
		aggressiveGCModificator = statsConfig.getInt("aggressiveGC");

		myMemoryChecker = new MemoryChecker(node.getTicker(), aggressiveGCModificator);
		statsConfig.register("memoryChecker", true, sortOrder++, true, false, "NodeStat.memCheck", "NodeStat.memCheckLong",
				new BooleanCallback(){
					@Override
					public Boolean get() {
						return myMemoryChecker.isRunning();
					}

					@Override
					public void set(Boolean val) throws InvalidConfigValueException {
						if (get().equals(val))
					        return;

						if(val)
							myMemoryChecker.start();
						else
							myMemoryChecker.terminate();
					}
		});
		if(statsConfig.getBoolean("memoryChecker"))
			myMemoryChecker.start();

		statsConfig.register("ignoreLocalVsRemoteBandwidthLiability", false, sortOrder++, true, false, "NodeStat.ignoreLocalVsRemoteBandwidthLiability", "NodeStat.ignoreLocalVsRemoteBandwidthLiabilityLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				synchronized(NodeStats.this) {
					return ignoreLocalVsRemoteBandwidthLiability;
				}
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException {
				synchronized(NodeStats.this) {
					ignoreLocalVsRemoteBandwidthLiability = val;
				}
			}
		});

		statsConfig.register("maxPingTime", DEFAULT_MAX_PING_TIME, sortOrder++, true, true, "NodeStat.maxPingTime", "NodeStat.maxPingTimeLong", new LongCallback() {

			@Override
			public Long get() {
				return maxPingTime;
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException, NodeNeedRestartException {
				maxPingTime = val;
			}

		}, false);
		maxPingTime = statsConfig.getLong("maxPingTime");

		statsConfig.register("subMaxPingTime", DEFAULT_SUB_MAX_PING_TIME, sortOrder++, true, true, "NodeStat.subMaxPingTime", "NodeStat.subMaxPingTimeLong", new LongCallback() {

			@Override
			public Long get() {
				return subMaxPingTime;
			}

			@Override
			public void set(Long val) throws InvalidConfigValueException, NodeNeedRestartException {
				subMaxPingTime = val;
			}

		}, false);
		subMaxPingTime = statsConfig.getLong("subMaxPingTime");

		// This is a *network* level setting, because it affects the rate at which we initiate local
		// requests, which could be seen by distant nodes.

		node.securityLevels.addNetworkThreatLevelListener(new SecurityLevelListener<NETWORK_THREAT_LEVEL>() {

			@Override
			public void onChange(NETWORK_THREAT_LEVEL oldLevel, NETWORK_THREAT_LEVEL newLevel) {
				if(newLevel == NETWORK_THREAT_LEVEL.MAXIMUM)
					ignoreLocalVsRemoteBandwidthLiability = true;
				if(oldLevel == NETWORK_THREAT_LEVEL.MAXIMUM)
					ignoreLocalVsRemoteBandwidthLiability = false;
				// Otherwise leave it as it was. It defaults to false.
			}

		});
		
		statsConfig.register("enableNewLoadManagementRT", false, sortOrder++, true, false, "Node.enableNewLoadManagementRT", "Node.enableNewLoadManagementRTLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableNewLoadManagementRT;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException,
					NodeNeedRestartException {
				enableNewLoadManagementRT = val;
			}
			
		});
		enableNewLoadManagementRT = statsConfig.getBoolean("enableNewLoadManagementRT");

		statsConfig.register("enableNewLoadManagementBulk", false, sortOrder++, true, false, "Node.enableNewLoadManagementBulk", "Node.enableNewLoadManagementBulkLong", new BooleanCallback() {

			@Override
			public Boolean get() {
				return enableNewLoadManagementBulk;
			}

			@Override
			public void set(Boolean val) throws InvalidConfigValueException,
					NodeNeedRestartException {
				enableNewLoadManagementBulk = val;
			}
			
		});
		enableNewLoadManagementBulk = statsConfig.getBoolean("enableNewLoadManagementBulk");
		
		persister = new ConfigurablePersister(this, statsConfig, "nodeThrottleFile", "node-throttle.dat", sortOrder++, true, false,
				"NodeStat.statsPersister", "NodeStat.statsPersisterLong", node.ticker, node.getRunDir());

		SimpleFieldSet throttleFS = persister.read();
		if(logMINOR) Logger.minor(this, "Read throttleFS:\n"+throttleFS);

		// Guesstimates. Hopefully well over the reality.
		localChkFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesSentAverage"), node);
		localSskFetchBytesSentAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesSentAverage"), node);
		localChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesSentAverage"), node);
		localSskInsertBytesSentAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalSskInsertBytesSentAverage"), node);
		localChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+2048/*path folding*/, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalChkFetchBytesReceivedAverage"), node);
		localSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalSskFetchBytesReceivedAverage"), node);
		localChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"), node);
		localSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("LocalChkInsertBytesReceivedAverage"), node);

		remoteChkFetchBytesSentAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesSentAverage"), node);
		remoteSskFetchBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesSentAverage"), node);
		remoteChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768+32768+1024, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesSentAverage"), node);
		remoteSskInsertBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesSentAverage"), node);
		remoteChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkFetchBytesReceivedAverage"), node);
		remoteSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskFetchBytesReceivedAverage"), node);
		remoteChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteChkInsertBytesReceivedAverage"), node);
		remoteSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("RemoteSskInsertBytesReceivedAverage"), node);

		successfulChkFetchBytesSentAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkFetchBytesSentAverage"), node);
		successfulSskFetchBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskFetchBytesSentAverage"), node);
		successfulChkInsertBytesSentAverage = new TimeDecayingRunningAverage(32768+32768+1024, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkInsertBytesSentAverage"), node);
		successfulSskInsertBytesSentAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskInsertBytesSentAverage"), node);
		successfulChkOfferReplyBytesSentAverage = new TimeDecayingRunningAverage(32768+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("successfulChkOfferReplyBytesSentAverage"), node);
		successfulSskOfferReplyBytesSentAverage = new TimeDecayingRunningAverage(3072, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("successfulSskOfferReplyBytesSentAverage"), node);
		successfulChkFetchBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500+2048/*path folding*/, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkFetchBytesReceivedAverage"), node);
		successfulSskFetchBytesReceivedAverage = new TimeDecayingRunningAverage(2048+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskFetchBytesReceivedAverage"), node);
		successfulChkInsertBytesReceivedAverage = new TimeDecayingRunningAverage(32768+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulChkInsertBytesReceivedAverage"), node);
		successfulSskInsertBytesReceivedAverage = new TimeDecayingRunningAverage(1024+1024+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("SuccessfulSskInsertBytesReceivedAverage"), node);
		successfulChkOfferReplyBytesReceivedAverage = new TimeDecayingRunningAverage(32768+500, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("successfulChkOfferReplyBytesReceivedAverage"), node);
		successfulSskOfferReplyBytesReceivedAverage = new TimeDecayingRunningAverage(3072, 180000, 0.0, 200*1024, throttleFS == null ? null : throttleFS.subset("successfulSskOfferReplyBytesReceivedAverage"), node);

		globalFetchPSuccess = new TrivialRunningAverage();
		chkLocalFetchPSuccess = new TrivialRunningAverage();
		chkRemoteFetchPSuccess = new TrivialRunningAverage();
		sskLocalFetchPSuccess = new TrivialRunningAverage();
		sskRemoteFetchPSuccess = new TrivialRunningAverage();
		blockTransferPSuccessRT = new TrivialRunningAverage();
		blockTransferPSuccessBulk = new TrivialRunningAverage();
		blockTransferPSuccessLocal = new TrivialRunningAverage();
		blockTransferFailTimeout = new TrivialRunningAverage();

		successfulLocalCHKFetchTimeAverageRT = new TrivialRunningAverage();
		unsuccessfulLocalCHKFetchTimeAverageRT = new TrivialRunningAverage();
		localCHKFetchTimeAverageRT = new TrivialRunningAverage();
		successfulLocalCHKFetchTimeAverageBulk = new TrivialRunningAverage();
		unsuccessfulLocalCHKFetchTimeAverageBulk = new TrivialRunningAverage();
		localCHKFetchTimeAverageBulk = new TrivialRunningAverage();

		successfulLocalSSKFetchTimeAverageRT = new TrivialRunningAverage();
		unsuccessfulLocalSSKFetchTimeAverageRT = new TrivialRunningAverage();
		localSSKFetchTimeAverageRT = new TrivialRunningAverage();
		successfulLocalSSKFetchTimeAverageBulk = new TrivialRunningAverage();
		unsuccessfulLocalSSKFetchTimeAverageBulk = new TrivialRunningAverage();
		localSSKFetchTimeAverageBulk = new TrivialRunningAverage();

		chkSuccessRatesByLocation = new Histogram2(10, 1.0);

		requestOutputThrottle =
			new TokenBucket(Math.max(obwLimit*60, 32768*20), (int)((1000L*1000L*1000L) / (obwLimit)), 0);
		requestInputThrottle =
			new TokenBucket(Math.max(ibwLimit*60, 32768*20), (int)((1000L*1000L*1000L) / (ibwLimit)), 0);

		estimatedSizeOfOneThrottledPacket = 1024 + DMT.packetTransmitSize(1024, 32) +
			node.estimateFullHeadersLengthOneMessage();

		double nodeLoc=node.lm.getLocation();
		this.avgCacheCHKLocation   = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageCacheCHKLocation"));
		this.avgStoreCHKLocation   = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageStoreCHKLocation"));
		this.avgSlashdotCacheCHKLocation = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheCHKLocation"));
		this.avgClientCacheCHKLocation = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageClientCacheCHKLocation"));

		this.avgCacheCHKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageCacheCHKSuccessLocation"));
		this.avgSlashdotCacheCHKSucess =  new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheCHKSuccessLocation"));
		this.avgClientCacheCHKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageClientCacheCHKSuccessLocation"));
		this.avgStoreCHKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageStoreCHKSuccessLocation"));
		this.avgRequestLocation = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageRequestLocation"));

		this.avgCacheSSKLocation   = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageCacheSSKLocation"));
		this.avgStoreSSKLocation   = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageStoreSSKLocation"));
		this.avgSlashdotCacheSSKLocation = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheSSKLocation"));
		this.avgClientCacheSSKLocation = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageClientCacheSSKLocation"));

		this.avgCacheSSKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageCacheSSKSuccessLocation"));
		this.avgSlashdotCacheSSKSuccess =  new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageSlashdotCacheSSKSuccessLocation"));
		this.avgClientCacheSSKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageClientCacheSSKSuccessLocation"));
		this.avgStoreSSKSuccess    = new DecayingKeyspaceAverage(nodeLoc, 10000, throttleFS == null ? null : throttleFS.subset("AverageStoreSSKSuccessLocation"));

		hourlyStatsRT = new HourlyStats(node);
		hourlyStatsBulk = new HourlyStats(node);

		avgMandatoryBackoffTimesRT = new Hashtable<String, TrivialRunningAverage>();
		avgMandatoryBackoffTimesBulk = new Hashtable<String, TrivialRunningAverage>();
		avgRoutingBackoffTimesRT = new Hashtable<String, TrivialRunningAverage>();
		avgRoutingBackoffTimesBulk = new Hashtable<String, TrivialRunningAverage>();
		avgTransferBackoffTimesRT = new Hashtable<String, TrivialRunningAverage>();
		avgTransferBackoffTimesBulk = new Hashtable<String, TrivialRunningAverage>();

		avgDatabaseJobExecutionTimes = new Hashtable<String, TrivialRunningAverage>();
	}

	protected String l10n(String key) {
		return NodeL10n.getBase().getString("NodeStats."+key);
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("NodeStats."+key, patterns, values);
	}

	public void start() throws NodeInitException {
		node.executor.execute(new Runnable() {
			@Override
			public void run() {
				nodePinger.start();
			}
		}, "Starting NodePinger");
		persister.start();
	}

	/** Every 60 seconds, check whether we need to adjust the bandwidth delay time because of idleness.
	 * (If no packets have been sent, the throttledPacketSendAverage should decrease; if it doesn't, it may go high,
	 * and then no requests will be accepted, and it will stay high forever. */
	static final int CHECK_THROTTLE_TIME = 60 * 1000;
	/** Absolute limit of 4MB queued to any given peer. FIXME make this configurable.
	 * Note that for many MessageItem's, the actual memory usage will be significantly more than this figure. */
	private static final long MAX_PEER_QUEUE_BYTES = 4 * 1024 * 1024;
	/** Don't accept requests if it'll take more than 1 minutes to send the current message queue.
	 * On the assumption that most of the message queue is block transfer data.
	 * Note that this only applies to data on the queue before calling shouldRejectRequest(): we
	 * do *not* attempt to include any estimate of how much the request will add to it. This is
	 * important because if we did, the AIMD may not have reached sufficient speed to transfer it
	 * in 60 seconds yet, because it hasn't had enough data in transit to need to increase its speed.
	 * 
	 * Interaction with output bandwidth liability: This must be slightly larger than the
	 * output bandwidth liability time limit (combined for both types).
	 * 
	 * A fast peer can have slightly more than half our output limit queued in requests to run. 
	 * If they all complete, they will take half the time limit. If they are all served from the 
	 * store, this will be shown on the queue time. But the queue time is estimated based on 
	 * using at most half the limit, so the time will be slightly over the overall limit. */
	
	// FIXME increase to 4 minutes when bulk/realtime flag merged!
	
	private static final double MAX_PEER_QUEUE_TIME = 2 * 60 * 1000.0;

	private long lastAcceptedRequest = -1;

	final int estimatedSizeOfOneThrottledPacket;

	static final double DEFAULT_OVERHEAD = 0.7;
	static final long DEFAULT_ONLY_PERIOD = 60*1000;
	static final long DEFAULT_TRANSITION_PERIOD = 240*1000;
	/** Relatively high minimum overhead. A low overhead estimate becomes a self-fulfilling
	 * prophecy, and it takes a long time to shake it off as the averages gradually increase.
	 * If we accept no requests then everything is overhead! Whereas with a high minimum
	 * overhead the worst case is that more stuff succeeds than expected and we have a few
	 * timeouts (because output bandwidth liability was assuming a lower overhead than
	 * actually happens) - but this should be very rare. */
	static final double MIN_NON_OVERHEAD = 0.5;
	
	/** All requests must be able to complete in this many seconds given the bandwidth
	 * available, even if they all succeed. Bulk requests. */
	static final int BANDWIDTH_LIABILITY_LIMIT_SECONDS_BULK = 120;
	/** All requests must be able to complete in this many seconds given the bandwidth
	 * available, even if they all succeed. Realtime requests - separate from bulk 
	 * requests, given higher priority but expected to be bursty and lower capacity. */
	static final int BANDWIDTH_LIABILITY_LIMIT_SECONDS_REALTIME = 60;
	
	/** Stats to send to a single peer so it can determine whether we are likely to reject 
	 * a request. Includes the various limits, but also, the expected transfers
	 * from requests already accepted from other peers (but NOT from this peer).
	 * Note that requests which are sourceRestarted() or reassignToSelf() are
	 * included in "from other peers", as are local requests. */
	public class PeerLoadStats {
		
		public final PeerNode peer;
		/** These do not include those from the peer */
		public final int expectedTransfersOutCHK;
		public final int expectedTransfersInCHK;
		public final int expectedTransfersOutSSK;
		public final int expectedTransfersInSSK;
		public final double outputBandwidthLowerLimit;
		public final double outputBandwidthUpperLimit;
		public final double outputBandwidthPeerLimit;
		public final double inputBandwidthLowerLimit;
		public final double inputBandwidthUpperLimit;
		public final double inputBandwidthPeerLimit;
		public final int totalRequests;
		public final int averageTransfersOutPerInsert;
		public final boolean realTime;
		/** Maximum transfers out - hard limit based on congestion control. */
		public final int maxTransfersOut;
		/** Maximum transfers out - per-peer limit. If total is over the lower limit, we will be accepted
		 * as long as we are below this limit. */
		public final int maxTransfersOutPeerLimit;
		/** Maximum transfers out - lower overall limit. If total is over this limit, we will be accepted
		 * as long as the per-peer usage is above the peer limit. */
		public final int maxTransfersOutLowerLimit;
		/** Maximum transfers out - upper overall limit. Nothing is accepted above this limit. */
		public final int maxTransfersOutUpperLimit;
		
		@Override
		public boolean equals(Object o) {
			if(!(o instanceof PeerLoadStats)) return false;
			PeerLoadStats s = (PeerLoadStats)o;
			if(s.peer != peer) return false;
			if(s.expectedTransfersOutCHK != expectedTransfersOutCHK) return false;
			if(s.expectedTransfersInCHK != expectedTransfersInCHK) return false;
			if(s.expectedTransfersOutSSK != expectedTransfersOutSSK) return false;
			if(s.expectedTransfersInSSK != expectedTransfersInSSK) return false;
			if(s.totalRequests != totalRequests) return false;
			if(s.averageTransfersOutPerInsert != averageTransfersOutPerInsert) return false;
			if(s.outputBandwidthLowerLimit != outputBandwidthLowerLimit) return false;
			if(s.outputBandwidthUpperLimit != outputBandwidthUpperLimit) return false;
			if(s.outputBandwidthPeerLimit != outputBandwidthPeerLimit) return false;
			if(s.inputBandwidthLowerLimit != inputBandwidthLowerLimit) return false;
			if(s.inputBandwidthUpperLimit != inputBandwidthUpperLimit) return false;
			if(s.inputBandwidthPeerLimit != inputBandwidthPeerLimit) return false;
			if(s.maxTransfersOut != maxTransfersOut) return false;
			if(s.maxTransfersOutPeerLimit != maxTransfersOutPeerLimit) return false;
			if(s.maxTransfersOutLowerLimit != maxTransfersOutLowerLimit) return false;
			if(s.maxTransfersOutUpperLimit != maxTransfersOutUpperLimit) return false;
			return true;
		}
		
		@Override
		public String toString() {
			return peer.toString()+":output:{lower="+outputBandwidthLowerLimit+",upper="+outputBandwidthUpperLimit+",this="+outputBandwidthPeerLimit+"},input:lower="+inputBandwidthLowerLimit+",upper="+inputBandwidthUpperLimit+",peer="+inputBandwidthPeerLimit+"},requests:"+
				"in:"+expectedTransfersInCHK+"chk/"+expectedTransfersInSSK+"ssk:out:"+
				expectedTransfersOutCHK+"chk/"+expectedTransfersOutSSK+"ssk transfers="+maxTransfersOut+"/"+maxTransfersOutPeerLimit+"/"+maxTransfersOutLowerLimit+"/"+maxTransfersOutUpperLimit;
		}
		
		public PeerLoadStats(PeerNode peer, int transfersPerInsert, boolean realTimeFlag) {
			this.peer = peer;
			this.realTime = realTimeFlag;
			long[] total = node.collector.getTotalIO();
			long totalSent = total[0];
			long totalOverhead = getSentOverhead();
			long uptime = node.getUptime();
			
			long now = System.currentTimeMillis();
			
			double nonOverheadFraction = getNonOverheadFraction(totalSent, totalOverhead, uptime, now);
			
			int limit = realTimeFlag ? BANDWIDTH_LIABILITY_LIMIT_SECONDS_REALTIME : BANDWIDTH_LIABILITY_LIMIT_SECONDS_BULK;
			
			boolean ignoreLocalVsRemote = ignoreLocalVsRemoteBandwidthLiability();
			
			RunningRequestsSnapshot runningLocal = new RunningRequestsSnapshot(node.requestTracker, peer, false, ignoreLocalVsRemote, transfersPerInsert, realTimeFlag);
			
			int peers = node.peers.countConnectedPeers();
			
			// Peer limits are adjusted to deduct any requests already allocated by sourceRestarted() requests.
			// I.e. requests which were sent before the peer restarted, which it doesn't know about or failed for when the bootID changed.
			// We try to complete these quickly, but they can stick around for a while sometimes.
			
			// This implies that the sourceRestarted() requests are not counted when checking whether the peer is over the limit.
			
			outputBandwidthUpperLimit = getOutputBandwidthUpperLimit(totalSent, totalOverhead, uptime, limit, nonOverheadFraction);
			outputBandwidthLowerLimit = getLowerLimit(outputBandwidthUpperLimit, peers);
			
			inputBandwidthUpperLimit = getInputBandwidthUpperLimit(limit);
			inputBandwidthLowerLimit = getLowerLimit(inputBandwidthUpperLimit, peers);
			
			maxTransfersOutUpperLimit = getMaxTransfersUpperLimit(realTimeFlag, nonOverheadFraction);
			maxTransfersOutLowerLimit = (int)Math.max(1,getLowerLimit(maxTransfersOutUpperLimit, peers));
			maxTransfersOutPeerLimit = (int)Math.max(1,getPeerLimit(peer, maxTransfersOutUpperLimit - maxTransfersOutLowerLimit, false, transfersPerInsert, realTimeFlag, peers, runningLocal.expectedTransfersOutCHKSR + runningLocal.expectedTransfersOutSSKSR));
			maxTransfersOut = calculateMaxTransfersOut(peer, realTime, nonOverheadFraction, maxTransfersOutUpperLimit);
			
			outputBandwidthPeerLimit = getPeerLimit(peer, outputBandwidthUpperLimit - outputBandwidthLowerLimit, false, transfersPerInsert, realTimeFlag, peers, runningLocal.calculateSR(ignoreLocalVsRemote, false));
			inputBandwidthPeerLimit = getPeerLimit(peer, inputBandwidthUpperLimit - inputBandwidthLowerLimit, true, transfersPerInsert, realTimeFlag, peers, runningLocal.calculateSR(ignoreLocalVsRemote, true));
			
			this.averageTransfersOutPerInsert = transfersPerInsert;
			
			RunningRequestsSnapshot runningGlobal = new RunningRequestsSnapshot(node.requestTracker, ignoreLocalVsRemote, transfersPerInsert, realTimeFlag);
			expectedTransfersInCHK = runningGlobal.expectedTransfersInCHK - runningLocal.expectedTransfersInCHK;
			expectedTransfersInSSK = runningGlobal.expectedTransfersInSSK - runningLocal.expectedTransfersInSSK;
			expectedTransfersOutCHK = runningGlobal.expectedTransfersOutCHK - runningLocal.expectedTransfersOutCHK;
			expectedTransfersOutSSK = runningGlobal.expectedTransfersOutSSK - runningLocal.expectedTransfersOutSSK;
			totalRequests = runningGlobal.totalRequests - runningLocal.totalRequests;
		}

		public PeerLoadStats(PeerNode source, Message m) {
			peer = source;
			if(m.getSpec() == DMT.FNPPeerLoadStatusInt) {
				expectedTransfersInCHK = m.getInt(DMT.OTHER_TRANSFERS_IN_CHK);
				expectedTransfersInSSK = m.getInt(DMT.OTHER_TRANSFERS_IN_SSK);
				expectedTransfersOutCHK = m.getInt(DMT.OTHER_TRANSFERS_OUT_CHK);
				expectedTransfersOutSSK = m.getInt(DMT.OTHER_TRANSFERS_OUT_SSK);
				averageTransfersOutPerInsert = m.getInt(DMT.AVERAGE_TRANSFERS_OUT_PER_INSERT);
				maxTransfersOut = m.getInt(DMT.MAX_TRANSFERS_OUT);
				maxTransfersOutUpperLimit = m.getInt(DMT.MAX_TRANSFERS_OUT_UPPER_LIMIT);
				maxTransfersOutLowerLimit = m.getInt(DMT.MAX_TRANSFERS_OUT_LOWER_LIMIT);
				maxTransfersOutPeerLimit = m.getInt(DMT.MAX_TRANSFERS_OUT_PEER_LIMIT);
			} else if(m.getSpec() == DMT.FNPPeerLoadStatusShort) {
				expectedTransfersInCHK = m.getShort(DMT.OTHER_TRANSFERS_IN_CHK) & 0xFFFF;
				expectedTransfersInSSK = m.getShort(DMT.OTHER_TRANSFERS_IN_SSK) & 0xFFFF;
				expectedTransfersOutCHK = m.getShort(DMT.OTHER_TRANSFERS_OUT_CHK) & 0xFFFF;
				expectedTransfersOutSSK = m.getShort(DMT.OTHER_TRANSFERS_OUT_SSK) & 0xFFFF;
				averageTransfersOutPerInsert = m.getShort(DMT.AVERAGE_TRANSFERS_OUT_PER_INSERT) & 0xFFFF;
				maxTransfersOut = m.getShort(DMT.MAX_TRANSFERS_OUT) & 0xFFFF;
				maxTransfersOutUpperLimit = m.getShort(DMT.MAX_TRANSFERS_OUT_UPPER_LIMIT) & 0xFFFF;
				maxTransfersOutLowerLimit = m.getShort(DMT.MAX_TRANSFERS_OUT_LOWER_LIMIT) & 0xFFFF;
				maxTransfersOutPeerLimit = m.getShort(DMT.MAX_TRANSFERS_OUT_PEER_LIMIT) & 0xFFFF;
			} else if(m.getSpec() == DMT.FNPPeerLoadStatusByte) {
				expectedTransfersInCHK = m.getByte(DMT.OTHER_TRANSFERS_IN_CHK) & 0xFF;
				expectedTransfersInSSK = m.getByte(DMT.OTHER_TRANSFERS_IN_SSK) & 0xFF;
				expectedTransfersOutCHK = m.getByte(DMT.OTHER_TRANSFERS_OUT_CHK) & 0xFF;
				expectedTransfersOutSSK = m.getByte(DMT.OTHER_TRANSFERS_OUT_SSK) & 0xFF;
				averageTransfersOutPerInsert = m.getByte(DMT.AVERAGE_TRANSFERS_OUT_PER_INSERT) & 0xFF;
				maxTransfersOut = m.getByte(DMT.MAX_TRANSFERS_OUT) & 0xFF;
				maxTransfersOutUpperLimit = m.getByte(DMT.MAX_TRANSFERS_OUT_UPPER_LIMIT) & 0xFF;
				maxTransfersOutLowerLimit = m.getByte(DMT.MAX_TRANSFERS_OUT_LOWER_LIMIT) & 0xFF;
				maxTransfersOutPeerLimit = m.getByte(DMT.MAX_TRANSFERS_OUT_PEER_LIMIT) & 0xFF;
			} else throw new IllegalArgumentException();
			outputBandwidthLowerLimit = m.getInt(DMT.OUTPUT_BANDWIDTH_LOWER_LIMIT);
			outputBandwidthUpperLimit = m.getInt(DMT.OUTPUT_BANDWIDTH_UPPER_LIMIT);
			outputBandwidthPeerLimit = m.getInt(DMT.OUTPUT_BANDWIDTH_PEER_LIMIT);
			inputBandwidthLowerLimit = m.getInt(DMT.INPUT_BANDWIDTH_LOWER_LIMIT);
			inputBandwidthUpperLimit = m.getInt(DMT.INPUT_BANDWIDTH_UPPER_LIMIT);
			inputBandwidthPeerLimit = m.getInt(DMT.INPUT_BANDWIDTH_PEER_LIMIT);
			totalRequests = -1;
			realTime = m.getBoolean(DMT.REAL_TIME_FLAG);
		}

		public RunningRequestsSnapshot getOtherRunningRequests() {
			return new RunningRequestsSnapshot(this);
		}
		
		public double peerLimit(boolean input) {
			if(input)
				return inputBandwidthPeerLimit;
			else
				return outputBandwidthPeerLimit;
		}

		public double lowerLimit(boolean input) {
			if(input)
				return inputBandwidthLowerLimit;
			else
				return outputBandwidthLowerLimit;
		}
		
	}
	
	public class RunningRequestsSnapshot {
		
		final int expectedTransfersOutCHK;
		final int expectedTransfersInCHK;
		final int expectedTransfersOutSSK;
		final int expectedTransfersInSSK;
		final int totalRequests;
		final int expectedTransfersOutCHKSR;
		final int expectedTransfersInCHKSR;
		final int expectedTransfersOutSSKSR;
		final int expectedTransfersInSSKSR;
		final int totalRequestsSR;
		final int averageTransfersPerInsert;
		final boolean realTimeFlag;
		
		RunningRequestsSnapshot(RequestTracker node, boolean ignoreLocalVsRemote, int transfersPerInsert, boolean realTimeFlag) {
			this.averageTransfersPerInsert = transfersPerInsert;
			this.realTimeFlag = realTimeFlag;
			CountedRequests countCHK = new CountedRequests();
			CountedRequests countSSK = new CountedRequests();
			CountedRequests countCHKSR = new CountedRequests();
			CountedRequests countSSKSR = new CountedRequests();
			node.countRequests(true, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(true, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(true, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(true, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(false, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(false, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(false, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(false, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(false, false, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(false, true, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			this.expectedTransfersInCHK = countCHK.expectedTransfersIn;
			this.expectedTransfersInSSK = countSSK.expectedTransfersIn;
			this.expectedTransfersOutCHK = countCHK.expectedTransfersOut;
			this.expectedTransfersOutSSK = countSSK.expectedTransfersOut;
			this.totalRequests = countCHK.total + countSSK.total;
			this.expectedTransfersInCHKSR = countCHKSR.expectedTransfersIn;
			this.expectedTransfersInSSKSR = countSSKSR.expectedTransfersIn;
			this.expectedTransfersOutCHKSR = countCHKSR.expectedTransfersOut;
			this.expectedTransfersOutSSKSR = countSSKSR.expectedTransfersOut;
			this.totalRequestsSR = countCHKSR.total + countSSKSR.total;
		}
		
		/**
		 * Create a snapshot of either the requests from a node, or the requests 
		 * routed to a node. If we are counting requests from a node, we also fill
		 * in the *SR counters with the counts for requests which have sourceRestarted()
		 * i.e. the requests where the peer has reconnected after a timeout but the 
		 * requests are still running. These are only counted in the *SR totals,
		 * they are not in the basic totals. The caller will reduce the limits
		 * according to the *SR totals, and only compare the non-SR requests when
		 * deciding whether the peer is over the limit. The updated limits are 
		 * sent to the downstream node so that it can send the right number of requests.
		 * @param node We need this to count the requests.
		 * @param source The peer we are interested in.
		 * @param requestsToNode If true, count requests sent to the node and currently
		 * running. If false, count requests originated by the node.
		 */
		RunningRequestsSnapshot(RequestTracker node, PeerNode source, boolean requestsToNode, boolean ignoreLocalVsRemote, int transfersPerInsert, boolean realTimeFlag) {
			this.averageTransfersPerInsert = transfersPerInsert;
			this.realTimeFlag = realTimeFlag;
			// We are calculating what part of their resources we use. Therefore, we have
			// to see it from their point of view - meaning all the requests are remote.
			if(requestsToNode) ignoreLocalVsRemote = true;
			CountedRequests countCHK = new CountedRequests();
			CountedRequests countSSK = new CountedRequests();
			CountedRequests countCHKSR = null;
			CountedRequests countSSKSR = null;
			if(!requestsToNode) {
				// No point counting if it's requests to the node.
				// Restarted only matters for requests from a node.
				countCHKSR = new CountedRequests();
				countSSKSR = new CountedRequests();
			}
			node.countRequests(source, requestsToNode, true, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(source, requestsToNode, true, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(source, requestsToNode, true, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(source, requestsToNode, true, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(source, requestsToNode, false, false, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(source, requestsToNode, false, true, false, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(source, requestsToNode, false, false, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(source, requestsToNode, false, true, true, false, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			node.countRequests(source, requestsToNode, false, false, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countCHK, countCHKSR);
			node.countRequests(source, requestsToNode, false, true, false, true, realTimeFlag, transfersPerInsert, ignoreLocalVsRemote, countSSK, countSSKSR);
			if(!requestsToNode) {
				this.expectedTransfersInCHKSR = countCHKSR.expectedTransfersIn;
				this.expectedTransfersInSSKSR = countSSKSR.expectedTransfersIn;
				this.expectedTransfersOutCHKSR = countCHKSR.expectedTransfersOut;
				this.expectedTransfersOutSSKSR = countSSKSR.expectedTransfersOut;
				this.totalRequestsSR = countCHKSR.total + countSSKSR.total;
				this.expectedTransfersInCHK = countCHK.expectedTransfersIn - expectedTransfersInCHKSR;
				this.expectedTransfersInSSK = countSSK.expectedTransfersIn - expectedTransfersInSSKSR;
				this.expectedTransfersOutCHK = countCHK.expectedTransfersOut - expectedTransfersOutCHKSR;
				this.expectedTransfersOutSSK = countSSK.expectedTransfersOut - expectedTransfersOutSSKSR;
				this.totalRequests = (countCHK.total + countSSK.total) - totalRequestsSR;
			} else {
				this.expectedTransfersInCHK = countCHK.expectedTransfersIn;
				this.expectedTransfersInSSK = countSSK.expectedTransfersIn;
				this.expectedTransfersOutCHK = countCHK.expectedTransfersOut;
				this.expectedTransfersOutSSK = countSSK.expectedTransfersOut;
				this.totalRequests = countCHK.total + countSSK.total;
				this.expectedTransfersInCHKSR = 0;
				this.expectedTransfersInSSKSR = 0;
				this.expectedTransfersOutCHKSR = 0;
				this.expectedTransfersOutSSKSR = 0;
				this.totalRequestsSR = 0;
			}
		}

		public RunningRequestsSnapshot(PeerLoadStats stats) {
			this.realTimeFlag = stats.realTime;
			// Assume they are all remote.
			this.expectedTransfersInCHK = stats.expectedTransfersInCHK;
			this.expectedTransfersInSSK = stats.expectedTransfersInSSK;
			this.expectedTransfersOutCHK = stats.expectedTransfersOutCHK;
			this.expectedTransfersOutSSK = stats.expectedTransfersOutSSK;
			this.totalRequests = stats.totalRequests;
			this.averageTransfersPerInsert = stats.averageTransfersOutPerInsert;
			this.expectedTransfersInCHKSR = 0;
			this.expectedTransfersInSSKSR = 0;
			this.expectedTransfersOutCHKSR = 0;
			this.expectedTransfersOutSSKSR = 0;
			this.totalRequestsSR = 0;
		}

		public void log() {
			log(null);
		}
		
		public void log(PeerNode source) {
			String message = 
				"Running (adjusted): CHK in: "+expectedTransfersInCHK+" out: "+expectedTransfersOutCHK+
					" SSK in: "+expectedTransfersInSSK+" out: "+expectedTransfersOutSSK
					+" total="+totalRequests+(source == null ? "" : (" for "+source)) + (realTimeFlag ? " (realtime)" : " (bulk)");
			if(expectedTransfersInCHK < 0 || expectedTransfersOutCHK < 0 ||
					expectedTransfersInSSK < 0 || expectedTransfersOutSSK < 0)
				Logger.error(this, message);
			else
				if(logMINOR) Logger.minor(this, message);
		}

		public double calculate(boolean ignoreLocalVsRemoteBandwidthLiability, boolean input) {
			
			if(input)
				return this.expectedTransfersInCHK * (32768+256) +
					this.expectedTransfersInSSK * (2048+256) +
					this.expectedTransfersOutCHK * TRANSFER_OUT_IN_OVERHEAD +
					this.expectedTransfersOutSSK * TRANSFER_OUT_IN_OVERHEAD;
			else
				return this.expectedTransfersOutCHK * (32768+256) +
					this.expectedTransfersOutSSK * (2048+256) +
					expectedTransfersInCHK * TRANSFER_IN_OUT_OVERHEAD +
					expectedTransfersInSSK * TRANSFER_IN_OUT_OVERHEAD;
		}

		public double calculateSR(boolean ignoreLocalVsRemoteBandwidthLiability, boolean input) {
			
			if(input)
				return this.expectedTransfersInCHKSR * (32768+256) +
					this.expectedTransfersInSSKSR * (2048+256) +
					this.expectedTransfersOutCHKSR * TRANSFER_OUT_IN_OVERHEAD +
					this.expectedTransfersOutSSKSR * TRANSFER_OUT_IN_OVERHEAD;
			else
				return this.expectedTransfersOutCHKSR * (32768+256) +
					this.expectedTransfersOutSSKSR * (2048+256) +
					expectedTransfersInCHKSR * TRANSFER_IN_OUT_OVERHEAD +
					expectedTransfersInSSKSR * TRANSFER_IN_OUT_OVERHEAD;
		}

		/**
		 * @return The number of requests running or -1 if not known (remote doesn't tell us).
		 */
		public int totalRequests() {
			return totalRequests;
		}

		public int totalOutTransfers() {
			return expectedTransfersOutCHK + expectedTransfersOutSSK;
		}

	}
	
	// Look plausible from my node-throttle.dat stats as of 01/11/2010.
	/** Output bytes required for an inbound transfer. Includes e.g. sending the request
	 * in the first place. */
	static final int TRANSFER_IN_OUT_OVERHEAD = 256;
	/** Input bytes required for an outbound transfer. Includes e.g. sending the insert
	 * etc. */
	static final int TRANSFER_OUT_IN_OVERHEAD = 256;
	
	public static class RejectReason {
		public final String name;
		/** If true, rejected because of preemptive bandwidth limiting, i.e. "soft", at least somewhat predictable, can be retried.
		 * If false, hard rejection, should backoff and not retry. */
		public final boolean soft;
		RejectReason(String n, boolean s) {
			name = n;
			soft = s;
		}
		@Override
		public String toString() {
			return (soft ? "SOFT" : "HARD") + ":" + name;
		}
	}
	
	private final Object serializeShouldRejectRequest = new Object();
	
	/** Should a request be accepted by this node, based on its local capacity?
	 * This includes thread limits and ping times, but more importantly, 
	 * mechanisms based on predicting worst case bandwidth usage for all running
	 * requests, and fairly sharing that capacity between peers. Currently there
	 * is no mechanism for fairness between request types, this should be
	 * implemented on the sender side, and is with new load management. New load
	 * management has caused various changes here but that's probably sorted out
	 * now, i.e. changes involved in new load management will probably be mainly
	 * in PeerNode and RequestSender now.
	 * @param canAcceptAnyway Periodically we ignore the ping time and accept a
	 * request anyway. This is because the ping time partly depends on whether
	 * we have accepted any requests... FIXME this should be reconsidered.
	 * @param isInsert Whether this is an insert.
	 * @param isSSK Whether this is a request/insert for an SSK.
	 * @param isLocal Is this request originated locally? This can affect our
	 * estimate of likely bandwidth usage. Whether it should be used is unclear,
	 * since an attacker can observe bandwidth usage. It is configurable.
	 * @param isOfferReply Is this request actually a GetOfferedKey? This is a
	 * non-relayed fetch of a block which we recently offered via ULPRs.
	 * @param source The node that sent us this request. This should be null
	 * on local requests and non-null on remote requests, but in some parts of
	 * the code that doesn't always hold. It *should* hold here, but that needs
	 * more checking before we can remove isLocal.
	 * @param hasInStore If this is a request, do we have the block in the 
	 * datastore already? This affects whether we accept it, which gives a 
	 * significant performance gain. Arguably there is a security issue, 
	 * although timing attacks are pretty easy anyway, and making requests go
	 * further may give attackers more samples...
	 * @param preferInsert If true, prefer inserts to requests (slightly). There
	 * is a flag for this on inserts. The idea is that when inserts are 
	 * misrouted this causes long-term problems because the data is stored in 
	 * the wrong place. New load management should avoid the need for this.
	 * @param realTimeFlag Is this a realtime request (low latency, low capacity)
	 * or a bulk request (high latency, high capacity)? They are accounted for 
	 * separately.
	 * @return The reason for rejecting it, or null to accept it.
	 */
	public RejectReason shouldRejectRequest(boolean canAcceptAnyway, boolean isInsert, boolean isSSK, boolean isLocal, boolean isOfferReply, PeerNode source, boolean hasInStore, boolean preferInsert, boolean realTimeFlag, UIDTag tag) {
		// Serialise shouldRejectRequest.
		// It's not always called on the same thread, and things could be problematic if they interfere with each other.
		synchronized(serializeShouldRejectRequest) {
		if(logMINOR) dumpByteCostAverages();

		if(source != null) {
			if(source.isDisconnecting())
				return new RejectReason("disconnecting", false);
		}
		
		int threadCount = getActiveThreadCount();
		if(threadLimit < threadCount) {
			pInstantRejectIncoming.report(1.0);
			rejected(">threadLimit", isLocal, realTimeFlag);
			return new RejectReason(">threadLimit ("+threadCount+'/'+threadLimit+')', false);
		}

		long[] total = node.collector.getTotalIO();
		long totalSent = total[0];
		long totalOverhead = getSentOverhead();
		long uptime = node.getUptime();
		long now = System.currentTimeMillis();
		
		double nonOverheadFraction = getNonOverheadFraction(totalSent, totalOverhead, uptime, now);
		
		// If no recent reports, no packets have been sent; correct the average downwards.
		double pingTime;
		pingTime = nodePinger.averagePingTime();
		synchronized(this) {
			// Round trip time
			if(pingTime > maxPingTime) {
				if((now - lastAcceptedRequest > MAX_INTERREQUEST_TIME) && canAcceptAnyway) {
					if(logMINOR) Logger.minor(this, "Accepting request anyway (take one every 10 secs to keep bwlimitDelayTime updated)");
				} else {
					pInstantRejectIncoming.report(1.0);
					rejected(">MAX_PING_TIME", isLocal, realTimeFlag);
					return new RejectReason(">MAX_PING_TIME ("+TimeUtil.formatTime((long)pingTime, 2, true)+ ')', false);
				}
			} else if(pingTime > subMaxPingTime) {
				double x = ((pingTime - subMaxPingTime)) / (maxPingTime - subMaxPingTime);
				if(randomLessThan(x, preferInsert)) {
					pInstantRejectIncoming.report(1.0);
					rejected(">SUB_MAX_PING_TIME", isLocal, realTimeFlag);
					return new RejectReason(">SUB_MAX_PING_TIME ("+TimeUtil.formatTime((long)pingTime, 2, true)+ ')', false);
				}
			}

		}

		// Pre-emptive rejection based on avoiding timeouts, with fair sharing
		// between peers. We calculate the node's capacity for requests and then
		// decide whether we will exceed it, or whether a particular peer will
		// exceed its slice of it. Peers are guaranteed a proportion of the
		// total ("peer limit"), but can opportunistically use a bit more, 
		// provided the total is less than the "lower limit". The overall usage
		// should not go over the "upper limit".
		
		// This should normally account for the bulk of request rejections.
		
		int transfersPerInsert = outwardTransfersPerInsert();
		
		/** Requests running, globally */
		RunningRequestsSnapshot requestsSnapshot = new RunningRequestsSnapshot(node.requestTracker, ignoreLocalVsRemoteBandwidthLiability, transfersPerInsert, realTimeFlag);
		
		// Don't need to decrement because it won't be counted until setAccepted() below.

		if(logMINOR)
			requestsSnapshot.log();
		
		long limit = realTimeFlag ? BANDWIDTH_LIABILITY_LIMIT_SECONDS_REALTIME : BANDWIDTH_LIABILITY_LIMIT_SECONDS_BULK;
		
		// Allow a bit more if the data is in the store and can therefore be served immediately.
		// This should improve performance.
		if(hasInStore) {
			limit += 10;
			if(logMINOR) Logger.minor(this, "Maybe accepting extra request due to it being in datastore (limit now "+limit+"s)...");
		}
		
		int peers = node.peers.countConnectedPeers();
		
		// These limits are by transfers.
		// We limit the total number of transfers running in parallel to ensure
		// that they don't get starved: The number of seconds a transfer has to
		// wait (for all the others) before it can send needs to be reasonable.
		
		// Whereas the bandwidth-based limit is by bytes, on the principle that
		// it must be possible to complete all transfers within a reasonable time.
		
		/** Requests running for this specific peer (local counts as a peer).
		 * Note that this separately counts requests which have sourceRestarted,
		 * which are not included in the count, and are decremented from the peer limit
		 * before it is used and sent to the peer. This ensures that the peer
		 * doesn't use more than it should after a restart. */
		RunningRequestsSnapshot peerRequestsSnapshot = new RunningRequestsSnapshot(node.requestTracker, source, false, ignoreLocalVsRemoteBandwidthLiability, transfersPerInsert, realTimeFlag);
		if(logMINOR)
			peerRequestsSnapshot.log(source);
		
		int maxTransfersOutUpperLimit = getMaxTransfersUpperLimit(realTimeFlag, nonOverheadFraction);
		int maxTransfersOutLowerLimit = (int)Math.max(1,getLowerLimit(maxTransfersOutUpperLimit, peers));
		int maxTransfersOutPeerLimit = (int)Math.max(1,getPeerLimit(source, maxTransfersOutUpperLimit - maxTransfersOutLowerLimit, false, transfersPerInsert, realTimeFlag, peers, (peerRequestsSnapshot.expectedTransfersOutCHKSR + peerRequestsSnapshot.expectedTransfersOutSSKSR)));
		/** Per-peer limit based on current state of the connection. */
		int maxOutputTransfers = this.calculateMaxTransfersOut(source, realTimeFlag, nonOverheadFraction, maxTransfersOutUpperLimit);
		
		// Check bandwidth-based limits, with fair sharing.
		
		String ret = checkBandwidthLiability(getOutputBandwidthUpperLimit(totalSent, totalOverhead, uptime, limit, nonOverheadFraction), requestsSnapshot, peerRequestsSnapshot, false, limit,
				source, isLocal, isSSK, isInsert, isOfferReply, hasInStore, transfersPerInsert, realTimeFlag, maxOutputTransfers, maxTransfersOutPeerLimit);  
		if(ret != null) return new RejectReason(ret, true);
		
		ret = checkBandwidthLiability(getInputBandwidthUpperLimit(limit), requestsSnapshot, peerRequestsSnapshot, true, limit,
				source, isLocal, isSSK, isInsert, isOfferReply, hasInStore, transfersPerInsert, realTimeFlag, maxOutputTransfers, maxTransfersOutPeerLimit);  
		if(ret != null) return new RejectReason(ret, true);
		
		// Check transfer-based limits, with fair sharing.
		
		ret = checkMaxOutputTransfers(maxOutputTransfers, maxTransfersOutUpperLimit, maxTransfersOutLowerLimit, maxTransfersOutPeerLimit,
				requestsSnapshot, peerRequestsSnapshot, isLocal, realTimeFlag);
		if(ret != null) return new RejectReason(ret, true);
		
		// Do we have the bandwidth?
		// The throttles should not be used much now, the timeout-based 
		// preemptive rejection and fair sharing code above should catch it in
		// all cases. FIXME can we get rid of the throttles here?
		double expected = this.getThrottle(isLocal, isInsert, isSSK, true).currentValue();
		int expectedSent = (int)Math.max(expected / nonOverheadFraction, 0);
		if(logMINOR)
			Logger.minor(this, "Expected sent bytes: "+expected+" -> "+expectedSent);
		if(!requestOutputThrottle.instantGrab(expectedSent)) {
			pInstantRejectIncoming.report(1.0);
			rejected("Insufficient output bandwidth", isLocal, realTimeFlag);
			return new RejectReason("Insufficient output bandwidth", false);
		}
		expected = this.getThrottle(isLocal, isInsert, isSSK, false).currentValue();
		int expectedReceived = (int)Math.max(expected, 0);
		if(logMINOR)
			Logger.minor(this, "Expected received bytes: "+expectedReceived);
		if(!requestInputThrottle.instantGrab(expectedReceived)) {
			requestOutputThrottle.recycle(expectedSent);
			pInstantRejectIncoming.report(1.0);
			rejected("Insufficient input bandwidth", isLocal, realTimeFlag);
			return new RejectReason("Insufficient input bandwidth", false);
		}

		// Message queues - when the link level has far more queued than it
		// can transmit in a reasonable time, don't accept requests.
		if(source != null) {
			if(source.getMessageQueueLengthBytes() > MAX_PEER_QUEUE_BYTES) {
				rejected(">MAX_PEER_QUEUE_BYTES", isLocal, realTimeFlag);
				return new RejectReason("Too many message bytes queued for peer", false);
			}
			if(source.getProbableSendQueueTime() > MAX_PEER_QUEUE_TIME) {
				rejected(">MAX_PEER_QUEUE_TIME", isLocal, realTimeFlag);
				return new RejectReason("Peer's queue will take too long to transfer", false);
			}
		}
		
		synchronized(this) {
			if(logMINOR) Logger.minor(this, "Accepting request? (isSSK="+isSSK+")");
			lastAcceptedRequest = now;
		}
		
		pInstantRejectIncoming.report(0.0);

		if(tag != null) tag.setAccepted();
		
		// Accept
		return null;
		}
	}
	
	public int calculateMaxTransfersOut(PeerNode peer, boolean realTime,
			double nonOverheadFraction, int maxTransfersOutUpperLimit) {
		if(peer == null) return Integer.MAX_VALUE;
		else return Math.min(maxTransfersOutUpperLimit, peer.calculateMaxTransfersOut(getAcceptableBlockTime(realTime), nonOverheadFraction));
	}

	private int getAcceptableBlockTime(boolean realTime) {
		return realTime ? 2 : 15;
	}

	static final double ONE_PEER_MAX_PEERS_EQUIVALENT = 2.0;
	
	public double getLowerLimit(double upperLimit, int peerCount) {
		// Bandwidth scheduling is now unfair, based on deadlines.
		// Therefore we can allocate a large chunk of our capacity to a single peer.
		return upperLimit / 2;
	}

	public int outwardTransfersPerInsert() {
		// FIXME compute an average
		return 1;
	}

	private double getInputBandwidthUpperLimit(long limit) {
		return node.getInputBandwidthLimit() * limit;
	}

	private double getNonOverheadFraction(long totalSent, long totalOverhead,
			long uptime, long now) {
		
		/** The fraction of output bytes which are used for requests */
		// FIXME consider using a shorter average
		// FIXME what happens when the bwlimit changes?
		
		double totalCouldSend = Math.max(totalSent,
				(((node.getOutputBandwidthLimit() * uptime))/1000.0));
		double nonOverheadFraction = (totalCouldSend - totalOverhead) / totalCouldSend;
		long timeFirstAnyConnections = peers.getTimeFirstAnyConnections();
		if(timeFirstAnyConnections > 0) {
			long time = now - timeFirstAnyConnections;
			if(time < DEFAULT_ONLY_PERIOD) {
				nonOverheadFraction = DEFAULT_OVERHEAD;
				if(logMINOR) Logger.minor(this, "Adjusted non-overhead fraction: "+nonOverheadFraction);
			} else if(time < DEFAULT_ONLY_PERIOD + DEFAULT_TRANSITION_PERIOD) {
				time -= DEFAULT_ONLY_PERIOD;
				nonOverheadFraction = (time * nonOverheadFraction + 
					(DEFAULT_TRANSITION_PERIOD - time) * DEFAULT_OVERHEAD) / DEFAULT_TRANSITION_PERIOD;
				if(logMINOR) Logger.minor(this, "Adjusted non-overhead fraction: "+nonOverheadFraction);
			}
		}
		if(nonOverheadFraction < MIN_NON_OVERHEAD) {
			// If there's been an auto-update, we may have used a vast amount of bandwidth for it.
			// Also, if things have broken, our overhead might be above our bandwidth limit,
			// especially on a slow node.
			
			// So impose a minimum of 20% of the bandwidth limit.
			// This will ensure we don't get stuck in any situation where all our bandwidth is overhead,
			// and we don't accept any requests because of that, so it remains that way...
			Logger.warning(this, "Non-overhead fraction is "+nonOverheadFraction+" - assuming this is self-inflicted and using default");
			nonOverheadFraction = MIN_NON_OVERHEAD;
		}
		if(nonOverheadFraction > 1.0) {
			Logger.error(this, "Non-overhead fraction is >1.0!!!");
			return 1.0;
		}
		return nonOverheadFraction;
	}

	private double getOutputBandwidthUpperLimit(long totalSent, long totalOverhead, long uptime, long limit, double nonOverheadFraction) {
		double sentOverheadPerSecond = (totalOverhead*1000.0) / (uptime);
		
		if(logMINOR) Logger.minor(this, "Output rate: "+(totalSent*1000.0)/uptime+" overhead rate "+sentOverheadPerSecond+" non-overhead fraction "+nonOverheadFraction);
		
		double outputAvailablePerSecond = node.getOutputBandwidthLimit() * nonOverheadFraction;
		
		if(logMINOR) Logger.minor(this, "Overhead per second: "+sentOverheadPerSecond+" bwlimit: "+node.getOutputBandwidthLimit()+" => output available per second: "+outputAvailablePerSecond+" but minimum of "+node.getOutputBandwidthLimit() / 5.0);
		
		return outputAvailablePerSecond * limit;
	}
	
	private int getMaxTransfersUpperLimit(boolean realTime, double nonOverheadFraction) {
		// FIXME refactor with getOutputBandwidthUpperLimit so we can avoid calling it twice.
		double outputAvailablePerSecond = node.getOutputBandwidthLimit() * nonOverheadFraction;
		
		return (int)Math.max(1, (getAcceptableBlockTime(realTime) * outputAvailablePerSecond) / 1024.0);
	}

	/** Should the request be rejected due to bandwidth liability? Enforces fair
	 * sharing between peers, while allowing peers to opportunistically use a bit
	 * more than their fair share as long as the total is below the lower limit.
	 * Used for both bandwidth-based limiting and transfer-count-based limiting.
	 * 
	 * @param bandwidthAvailableOutputUpperLimit The overall upper limit, already calculated.
	 * @param requestsSnapshot The requests running.
	 * @param peerRequestsSnapshot The requests running to this one peer.
	 * @param input True if this is input bandwidth, false if it is output bandwidth.
	 * @param limit The limit period in seconds.
	 * @param source The source of the request.
	 * @param isLocal True if the request is local.
	 * @param isSSK True if it is an SSK request.
	 * @param isInsert True if it is an insert.
	 * @param isOfferReply True if it is a GetOfferedKey.
	 * @param hasInStore True if we have the data in the store, and can return it, so 
	 * won't need to route it onwards.
	 * @param transfersPerInsert The average number of outgoing transfers per insert.
	 * @param realTimeFlag True if this is a real-time request, false if it is a bulk
	 * request.
	 * @return A string explaining why, or null if we can accept the request.
	 */
	private String checkBandwidthLiability(double bandwidthAvailableOutputUpperLimit,
			RunningRequestsSnapshot requestsSnapshot, RunningRequestsSnapshot peerRequestsSnapshot, boolean input, long limit,
			PeerNode source, boolean isLocal, boolean isSSK, boolean isInsert, boolean isOfferReply, boolean hasInStore, int transfersPerInsert, boolean realTimeFlag, int maxOutputTransfers, int maxOutputTransfersPeerLimit) {
		String name = input ? "Input" : "Output";
		int peers = node.peers.countConnectedPeers();
		
		double bandwidthAvailableOutputLowerLimit = getLowerLimit(bandwidthAvailableOutputUpperLimit, peers);
		
		double bandwidthLiabilityOutput = requestsSnapshot.calculate(ignoreLocalVsRemoteBandwidthLiability, input);
		
		// Calculate the peer limit so the peer gets notified, even if we are going to ignore it.
		
		double thisAllocation = getPeerLimit(source, bandwidthAvailableOutputUpperLimit - bandwidthAvailableOutputLowerLimit, input, transfersPerInsert, realTimeFlag, peers, peerRequestsSnapshot.calculateSR(ignoreLocalVsRemoteBandwidthLiability, input));
		
		if(SEND_LOAD_STATS_NOTICES && source != null) {
			// FIXME tell local as well somehow?
			if(!input) {
				source.onSetMaxOutputTransfers(realTimeFlag, maxOutputTransfers);
				source.onSetMaxOutputTransfersPeerLimit(realTimeFlag, maxOutputTransfersPeerLimit);
			}
			source.onSetPeerAllocation(input, (int)thisAllocation, transfersPerInsert, maxOutputTransfers, realTimeFlag);
		}
		
		// Ignore the upper limit.
		// Because we reassignToSelf() in various tricky timeout conditions, it is possible to exceed it.
		// Even if we do we still need to allow the guaranteed allocation for each peer.
		// Except when we do that, we have to offer it via ULPRs afterwards ...
		// Yes but the GetOfferedKey's are subject to load management, so no problem.
		if(bandwidthLiabilityOutput > bandwidthAvailableOutputUpperLimit) {
			Logger.warning(this, "Above upper limit. Not rejecting as this can occasionally happen due to reassigns: upper limit "+bandwidthAvailableOutputUpperLimit+" usage is "+bandwidthLiabilityOutput);
		}
		
		if(bandwidthLiabilityOutput > bandwidthAvailableOutputLowerLimit) {
			
			// Fair sharing between peers.
			
			if(logMINOR)
				Logger.minor(this, "Allocation ("+name+") for "+source+" is "+thisAllocation+" total usage is "+bandwidthLiabilityOutput+" of lower limit"+bandwidthAvailableOutputLowerLimit+" upper limit is "+bandwidthAvailableOutputUpperLimit+" for "+name);
			
			double peerUsedBytes = getPeerBandwidthLiability(peerRequestsSnapshot, source, isSSK, transfersPerInsert, input);
			if(peerUsedBytes > thisAllocation) {
				rejected(name+" bandwidth liability: fairness between peers", isLocal, realTimeFlag);
				return name+" bandwidth liability: fairness between peers (peer "+source+" used "+peerUsedBytes+" allowed "+thisAllocation+")";
			}
			
		} else {
			if(logMINOR)
				Logger.minor(this, "Total usage is "+bandwidthLiabilityOutput+" below lower limit "+bandwidthAvailableOutputLowerLimit+" for "+name);
		}
		return null;
	}
	
	private String checkMaxOutputTransfers(int maxOutputTransfers,
			int maxTransfersOutUpperLimit, int maxTransfersOutLowerLimit,
			int maxTransfersOutPeerLimit,
			RunningRequestsSnapshot requestsSnapshot,
			RunningRequestsSnapshot peerRequestsSnapshot, boolean isLocal, boolean realTime) {
		if(logMINOR) Logger.minor(this, "Max transfers: congestion control limit "+maxOutputTransfers+
				" upper "+maxTransfersOutUpperLimit+" lower "+maxTransfersOutLowerLimit+" peer "+maxTransfersOutPeerLimit+" "+(realTime ? "(rt)" : "(bulk)"));
		int peerOutTransfers = peerRequestsSnapshot.totalOutTransfers();
		int totalOutTransfers = requestsSnapshot.totalOutTransfers();
		if(peerOutTransfers > maxOutputTransfers && !isLocal) {
			// Can't handle that many transfers with current bandwidth.
			rejected("TooManyTransfers: Congestion control", isLocal, realTime);
			return "TooManyTransfers: Congestion control";
		}
		if(totalOutTransfers <= maxTransfersOutLowerLimit) {
			// If the total is below the lower limit, then fine, go for it.
			return null;
		}
		if(peerOutTransfers <= maxTransfersOutPeerLimit) {
			// The total is above the lower limit, but the per-peer is below the peer limit.
			// It is within its guaranteed space, so we accept it.
			return null;
		}
		rejected("TooManyTransfers: Fair sharing between peers", isLocal, realTime);
		return "TooManyTransfers: Fair sharing between peers";
	}



	static final boolean SEND_LOAD_STATS_NOTICES = true;
	
	/**
	 * @param source The peer.
	 * @param totalGuaranteedBandwidth The difference between the upper and lower overall
	 * bandwidth limits. If the total usage is less than the lower limit, we do not 
	 * enforce fairness. Any node may therefore optimistically try to use up to the lower
	 * limit. However, the node is only guaranteed its fair share, which is defined as its
	 * fraction of the part of the total that is above the lower limit.
	 * @param input
	 * @param dontTellPeer
	 * @param transfersPerInsert
	 * @param realTimeFlag
	 * @param peers
	 * @param sourceRestarted 
	 * @return
	 */
	private double getPeerLimit(PeerNode source, double totalGuaranteedBandwidth, boolean input, int transfersPerInsert, boolean realTimeFlag, int peers, double sourceRestarted) {
		
		double thisAllocation;
		
		// FIXME: MAKE CONFIGURABLE AND SECLEVEL DEPENDANT!
		if(RequestStarter.LOCAL_REQUESTS_COMPETE_FAIRLY) {
			thisAllocation = totalGuaranteedBandwidth / (peers + 1);
		} else {
			double totalAllocation = totalGuaranteedBandwidth;
			// FIXME: MAKE CONFIGURABLE AND SECLEVEL DEPENDANT!
			double localAllocation = totalAllocation * 0.5;
			if(source == null)
				thisAllocation = localAllocation;
			else {
				totalAllocation -= localAllocation;
				thisAllocation = totalAllocation / peers;
			}
		}
		
		if(logMINOR && sourceRestarted != 0)
			Logger.minor(this, "Allocation is "+thisAllocation+" source restarted is "+sourceRestarted);
		return thisAllocation - sourceRestarted;
		
	}

	/** Calculate the worst-case bytes used by a specific peer. This will be
	 * compared to its peer limit, and if it is higher, the request may be 
	 * rejected.
	 * @param requestsSnapshot Snapshot of requests running from the peer.
	 * @param source The peer.
	 * @param ignoreLocalVsRemote If true, pretend local requests are remote 
	 * requests.
	 * @param transfersOutPerInsert Average number of output transfers from an insert.
	 * @param input If true, calculate input bytes, if false, calculate output bytes.
	 * @return
	 */
	private double getPeerBandwidthLiability(RunningRequestsSnapshot requestsSnapshot, PeerNode source, boolean ignoreLocalVsRemote, int transfersOutPerInsert, boolean input) {
		
		return requestsSnapshot.calculate(ignoreLocalVsRemoteBandwidthLiability, input);
	}

	/** @return True if we should reject the request.
	 * @param x The threshold. We should reject the request unless a random number is greater than this threshold.
	 * @param preferInsert If true, we allow 3 chances to pass the threshold.
	 */
	private boolean randomLessThan(double x, boolean preferInsert) {
		if(preferInsert) {
			// Three chances.
			for(int i=0;i<3;i++)
				if(hardRandom.nextDouble() >= x) return false;
			return true;
		} else {
			// One chance
		}
		return hardRandom.nextDouble() < x;
	}

	private void rejected(String reason, boolean isLocal, boolean realTime) {
		reason += " "+(realTime?" (rt)":" (bulk)");
		if(logMINOR) Logger.minor(this, "Rejecting (local="+isLocal+") : "+reason);
		if(!isLocal) preemptiveRejectReasons.inc(reason);
		else this.localPreemptiveRejectReasons.inc(reason);
	}

	private RunningAverage getThrottle(boolean isLocal, boolean isInsert, boolean isSSK, boolean isSent) {
		if(isLocal) {
			if(isInsert) {
				if(isSSK) {
					return isSent ? this.localSskInsertBytesSentAverage : this.localSskInsertBytesReceivedAverage;
				} else {
					return isSent ? this.localChkInsertBytesSentAverage : this.localChkInsertBytesReceivedAverage;
				}
			} else {
				if(isSSK) {
					return isSent ? this.localSskFetchBytesSentAverage : this.localSskFetchBytesReceivedAverage;
				} else {
					return isSent ? this.localChkFetchBytesSentAverage : this.localChkFetchBytesReceivedAverage;
				}
			}
		} else {
			if(isInsert) {
				if(isSSK) {
					return isSent ? this.remoteSskInsertBytesSentAverage : this.remoteSskInsertBytesReceivedAverage;
				} else {
					return isSent ? this.remoteChkInsertBytesSentAverage : this.remoteChkInsertBytesReceivedAverage;
				}
			} else {
				if(isSSK) {
					return isSent ? this.remoteSskFetchBytesSentAverage : this.remoteSskFetchBytesReceivedAverage;
				} else {
					return isSent ? this.remoteChkFetchBytesSentAverage : this.remoteChkFetchBytesReceivedAverage;
				}
			}
		}
	}

	private void dumpByteCostAverages() {
		Logger.minor(this, "Byte cost averages: REMOTE:"+
				" CHK insert "+remoteChkInsertBytesSentAverage.currentValue()+ '/' +remoteChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+remoteSskInsertBytesSentAverage.currentValue()+ '/' +remoteSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+remoteChkFetchBytesSentAverage.currentValue()+ '/' +remoteChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+remoteSskFetchBytesSentAverage.currentValue()+ '/' +remoteSskFetchBytesReceivedAverage.currentValue());
		Logger.minor(this, "Byte cost averages: LOCAL:"+
				" CHK insert "+localChkInsertBytesSentAverage.currentValue()+ '/' +localChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+localSskInsertBytesSentAverage.currentValue()+ '/' +localSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+localChkFetchBytesSentAverage.currentValue()+ '/' +localChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+localSskFetchBytesSentAverage.currentValue()+ '/' +localSskFetchBytesReceivedAverage.currentValue());
		Logger.minor(this, "Byte cost averages: SUCCESSFUL:"+
				" CHK insert "+successfulChkInsertBytesSentAverage.currentValue()+ '/' +successfulChkInsertBytesReceivedAverage.currentValue()+
				" SSK insert "+successfulSskInsertBytesSentAverage.currentValue()+ '/' +successfulSskInsertBytesReceivedAverage.currentValue()+
				" CHK fetch "+successfulChkFetchBytesSentAverage.currentValue()+ '/' +successfulChkFetchBytesReceivedAverage.currentValue()+
				" SSK fetch "+successfulSskFetchBytesSentAverage.currentValue()+ '/' +successfulSskFetchBytesReceivedAverage.currentValue()+
				" CHK offer reply "+successfulChkOfferReplyBytesSentAverage.currentValue()+ '/' +successfulChkOfferReplyBytesReceivedAverage.currentValue()+
				" SSK offer reply "+successfulSskOfferReplyBytesSentAverage.currentValue()+ '/' +successfulSskOfferReplyBytesReceivedAverage.currentValue());

	}

	public double getBwlimitDelayTimeRT() {
		return throttledPacketSendAverageRT.currentValue();
	}

	public double getBwlimitDelayTimeBulk() {
		return throttledPacketSendAverageBulk.currentValue();
	}

	public double getBwlimitDelayTime() {
		return throttledPacketSendAverage.currentValue();
	}

	public double getNodeAveragePingTime() {
		return nodePinger.averagePingTime();
	}

	public int getOpennetSizeEstimate(long timestamp) {
		if (node.opennet == null)
			return 0;
		return node.opennet.getNetworkSizeEstimate(timestamp);
	}
	public int getDarknetSizeEstimate(long timestamp) {
		return node.lm.getNetworkSizeEstimate( timestamp );
	}

	public Object[] getKnownLocations(long timestamp) {
		return node.lm.getKnownLocations( timestamp );
	}

	public double pRejectIncomingInstantly() {
		return pInstantRejectIncoming.currentValue();
	}

	/**
	 * Update peerManagerUserAlertStats if the timer has expired.
	 * Only called from PacketSender so doesn't need sync.
	 */
	public void maybeUpdatePeerManagerUserAlertStats(long now) {
		if(now > nextPeerManagerUserAlertStatsUpdateTime) {
			if(getBwlimitDelayTime() > MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD) {
				if(firstBwlimitDelayTimeThresholdBreak == 0) {
					firstBwlimitDelayTimeThresholdBreak = now;
				}
			} else {
				firstBwlimitDelayTimeThresholdBreak = 0;
			}
			if((firstBwlimitDelayTimeThresholdBreak != 0) && ((now - firstBwlimitDelayTimeThresholdBreak) >= MAX_BWLIMIT_DELAY_TIME_ALERT_DELAY)) {
				bwlimitDelayAlertRelevant = true;
			} else {
				bwlimitDelayAlertRelevant = false;
			}
			if(getNodeAveragePingTime() > 2*maxPingTime) {
				if(firstNodeAveragePingTimeThresholdBreak == 0) {
					firstNodeAveragePingTimeThresholdBreak = now;
				}
			} else {
				firstNodeAveragePingTimeThresholdBreak = 0;
			}
			if((firstNodeAveragePingTimeThresholdBreak != 0) && ((now - firstNodeAveragePingTimeThresholdBreak) >= MAX_NODE_AVERAGE_PING_TIME_ALERT_DELAY)) {
				nodeAveragePingAlertRelevant = true;
			} else {
				nodeAveragePingAlertRelevant = false;
			}
			if(logDEBUG) Logger.debug(this, "mUPMUAS: "+now+": "+getBwlimitDelayTime()+" >? "+MAX_BWLIMIT_DELAY_TIME_ALERT_THRESHOLD+" since "+firstBwlimitDelayTimeThresholdBreak+" ("+bwlimitDelayAlertRelevant+") "+getNodeAveragePingTime()+" >? "+MAX_NODE_AVERAGE_PING_TIME_ALERT_THRESHOLD+" since "+firstNodeAveragePingTimeThresholdBreak+" ("+nodeAveragePingAlertRelevant+ ')');
			nextPeerManagerUserAlertStatsUpdateTime = now + peerManagerUserAlertStatsUpdateInterval;
		}
	}

	@Override
	public SimpleFieldSet persistThrottlesToFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("RemoteChkFetchBytesSentAverage", remoteChkFetchBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteSskFetchBytesSentAverage", remoteSskFetchBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteChkInsertBytesSentAverage", remoteChkInsertBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteSskInsertBytesSentAverage", remoteSskInsertBytesSentAverage.exportFieldSet(true));
		fs.put("RemoteChkFetchBytesReceivedAverage", remoteChkFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("RemoteSskFetchBytesReceivedAverage", remoteSskFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("RemoteChkInsertBytesReceivedAverage", remoteChkInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("RemoteSskInsertBytesReceivedAverage", remoteSskInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalChkFetchBytesSentAverage", localChkFetchBytesSentAverage.exportFieldSet(true));
		fs.put("LocalSskFetchBytesSentAverage", localSskFetchBytesSentAverage.exportFieldSet(true));
		fs.put("LocalChkInsertBytesSentAverage", localChkInsertBytesSentAverage.exportFieldSet(true));
		fs.put("LocalSskInsertBytesSentAverage", localSskInsertBytesSentAverage.exportFieldSet(true));
		fs.put("LocalChkFetchBytesReceivedAverage", localChkFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalSskFetchBytesReceivedAverage", localSskFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalChkInsertBytesReceivedAverage", localChkInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("LocalSskInsertBytesReceivedAverage", localSskInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulChkFetchBytesSentAverage", successfulChkFetchBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulSskFetchBytesSentAverage", successfulSskFetchBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulChkInsertBytesSentAverage", successfulChkInsertBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulSskInsertBytesSentAverage", successfulSskInsertBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulChkOfferReplyBytesSentAverage", successfulChkOfferReplyBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulSskOfferReplyBytesSentAverage", successfulSskOfferReplyBytesSentAverage.exportFieldSet(true));
		fs.put("SuccessfulChkFetchBytesReceivedAverage", successfulChkFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulSskFetchBytesReceivedAverage", successfulSskFetchBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulChkInsertBytesReceivedAverage", successfulChkInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulSskInsertBytesReceivedAverage", successfulSskInsertBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulChkOfferReplyBytesReceivedAverage", successfulChkOfferReplyBytesReceivedAverage.exportFieldSet(true));
		fs.put("SuccessfulSskOfferReplyBytesReceivedAverage", successfulSskOfferReplyBytesReceivedAverage.exportFieldSet(true));

		//These are not really part of the 'throttling' data, but are also running averages which should be persisted
		fs.put("AverageCacheCHKLocation", avgCacheCHKLocation.exportFieldSet(true));
		fs.put("AverageStoreCHKLocation", avgStoreCHKLocation.exportFieldSet(true));
		fs.put("AverageSlashdotCacheCHKLocation",avgSlashdotCacheCHKLocation.exportFieldSet(true));
		fs.put("AverageClientCacheCHKLocation",avgClientCacheCHKLocation.exportFieldSet(true));

		fs.put("AverageCacheCHKSuccessLocation", avgCacheCHKSuccess.exportFieldSet(true));
		fs.put("AverageSlashdotCacheCHKSuccessLocation", avgSlashdotCacheCHKSucess.exportFieldSet(true));
		fs.put("AverageClientCacheCHKSuccessLocation", avgClientCacheCHKSuccess.exportFieldSet(true));
		fs.put("AverageStoreCHKSuccessLocation", avgStoreCHKSuccess.exportFieldSet(true));

		fs.put("AverageCacheSSKLocation", avgCacheSSKLocation.exportFieldSet(true));
		fs.put("AverageStoreSSKLocation", avgStoreSSKLocation.exportFieldSet(true));
		fs.put("AverageSlashdotCacheSSKLocation",avgSlashdotCacheSSKLocation.exportFieldSet(true));
		fs.put("AverageClientCacheSSKLocation",avgClientCacheSSKLocation.exportFieldSet(true));

		fs.put("AverageCacheSSKSuccessLocation", avgCacheSSKSuccess.exportFieldSet(true));
		fs.put("AverageSlashdotCacheSSKSuccessLocation", avgSlashdotCacheSSKSuccess.exportFieldSet(true));
		fs.put("AverageClientCacheSSKSuccessLocation", avgClientCacheSSKSuccess.exportFieldSet(true));
		fs.put("AverageStoreSSKSuccessLocation", avgStoreSSKSuccess.exportFieldSet(true));

		fs.put("AverageRequestLocation", avgRequestLocation.exportFieldSet(true));

		return fs;
	}

	/**
	 * Update the node-wide bandwidth I/O stats if the timer has expired
	 */
	public void maybeUpdateNodeIOStats(long now) {
		if(now > nextNodeIOStatsUpdateTime) {
			long[] io_stats = node.collector.getTotalIO();
			long outdiff;
			long indiff;
			synchronized(ioStatSync) {
				previous_output_stat = last_output_stat;
				previous_input_stat = last_input_stat;
				previous_io_stat_time = last_io_stat_time;
				last_output_stat = io_stats[ 0 ];
				last_input_stat = io_stats[ 1 ];
				last_io_stat_time = now;
				outdiff = last_output_stat - previous_output_stat;
				indiff = last_input_stat - previous_input_stat;
			}
			if(logMINOR)
				Logger.minor(this, "Last 2 seconds: input: "+indiff+" output: "+outdiff);
			nextNodeIOStatsUpdateTime = now + nodeIOStatsUpdateInterval;
		}
	}

	public long[] getNodeIOStats() {
		long[] result = new long[6];
		synchronized(ioStatSync) {
			result[ 0 ] = previous_output_stat;
			result[ 1 ] = previous_input_stat;
			result[ 2 ] = previous_io_stat_time;
			result[ 3 ] = last_output_stat;
			result[ 4 ] = last_input_stat;
			result[ 5 ] = last_io_stat_time;
		}
		return result;
	}

	public void waitUntilNotOverloaded(boolean isInsert) {
		while(threadLimit < getActiveThreadCount()){
			try{
				Thread.sleep(5000);
			} catch (InterruptedException e) {}
		}
	}

	public int getActiveThreadCount() {
		return rootThreadGroup.activeCount() - node.executor.getWaitingThreadsCount();
	}

	public int[] getActiveThreadsByPriority() {
		return node.executor.runningThreads();
	}

	public int[] getWaitingThreadsByPriority() {
		return node.executor.waitingThreads();
	}

	public int getThreadLimit() {
		return threadLimit;
	}

	/**
	 * Gets a copy of the thread list. The result might contain null values:
	 * The end of the list is marked by a null entry in the array.
	 */
	public Thread[] getThreads() {
		int count = 0;
		Thread[] threads;
		while(true) {
			count = Math.max(rootThreadGroup.activeCount(), count);
			threads = new Thread[count*2+50];
			rootThreadGroup.enumerate(threads);
			if(threads[threads.length-1] == null) break;
		}
		
		return threads;
	}

	/**
	 * Get a list of threads with the given normalized name.
	 */
	public ArrayList<NativeThread> getNativeThreadsByNormalizedName(String normalizedThreadName) {
		Thread[] threads = getThreads();
		
		ArrayList<NativeThread> result = new ArrayList<NativeThread>(threads.length);
		
		for(Thread thread : threads) {
			if(thread == null)
				break;
		
			if(!(thread instanceof NativeThread))
				continue;
			
			final NativeThread nt = (NativeThread)thread;
		
			if(nt.getNormalizedName().equals(normalizedThreadName))
				result.add(nt);
		}

		return result;
	}

	public SimpleFieldSet exportVolatileFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		long now = System.currentTimeMillis();
		fs.put("isUsingWrapper", node.isUsingWrapper());
		long nodeUptimeSeconds = 0;
		synchronized(this) {
			fs.put("startupTime", node.startupTime);
			nodeUptimeSeconds = (now - node.startupTime) / 1000;
			if (nodeUptimeSeconds == 0) nodeUptimeSeconds = 1;	// prevent division by zero
			fs.put("uptimeSeconds", nodeUptimeSeconds);
		}
		fs.put("averagePingTime", getNodeAveragePingTime());
		fs.put("bwlimitDelayTime", getBwlimitDelayTime());
		fs.put("bwlimitDelayTimeRT", getBwlimitDelayTimeRT());
		fs.put("bwlimitDelayTimeBulk", getBwlimitDelayTimeBulk());

		// Network Size
		fs.put("opennetSizeEstimateSession", getOpennetSizeEstimate(-1));
		fs.put("networkSizeEstimateSession", getDarknetSizeEstimate(-1));
		for (int t = 1 ; t < 7; t++) {
			int hour = t * 24;
			long limit = now - t * ((long) 24 * 60 * 60 * 1000);

			fs.put("opennetSizeEstimate"+hour+"hourRecent", getOpennetSizeEstimate(limit));
			fs.put("networkSizeEstimate"+hour+"hourRecent", getDarknetSizeEstimate(limit));
		}
		fs.put("routingMissDistanceLocal", routingMissDistanceLocal.currentValue());
		fs.put("routingMissDistanceRemote", routingMissDistanceRemote.currentValue());
		fs.put("routingMissDistanceOverall", routingMissDistanceOverall.currentValue());
		fs.put("routingMissDistanceBulk", routingMissDistanceBulk.currentValue());
		fs.put("routingMissDistanceRT", routingMissDistanceRT.currentValue());
		
		fs.put("backedOffPercent", backedOffPercent.currentValue());
		fs.put("pInstantReject", pRejectIncomingInstantly());
		fs.put("unclaimedFIFOSize", node.usm.getUnclaimedFIFOSize());
		fs.put("RAMBucketPoolSize", node.clientCore.tempBucketFactory.getRamUsed());

		/* gather connection statistics */
		PeerNodeStatus[] peerNodeStatuses = peers.getPeerNodeStatuses(true);
		int numberOfSeedServers = 0;
		int numberOfSeedClients = 0;

		for (PeerNodeStatus peerNodeStatus: peerNodeStatuses) {
			if (peerNodeStatus.isSeedServer())
				numberOfSeedServers++;
			if (peerNodeStatus.isSeedClient())
				numberOfSeedClients++;
		}

		int numberOfConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_CONNECTED);
		int numberOfRoutingBackedOff = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_ROUTING_BACKED_OFF);
		int numberOfTooNew = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_NEW);
		int numberOfTooOld = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_TOO_OLD);
		int numberOfDisconnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISCONNECTED);
		int numberOfNeverConnected = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_NEVER_CONNECTED);
		int numberOfDisabled = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_DISABLED);
		int numberOfBursting = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_BURSTING);
		int numberOfListening = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTENING);
		int numberOfListenOnly = PeerNodeStatus.getPeerStatusCount(peerNodeStatuses, PeerManager.PEER_NODE_STATUS_LISTEN_ONLY);

		int numberOfSimpleConnected = numberOfConnected + numberOfRoutingBackedOff;
		int numberOfNotConnected = numberOfTooNew + numberOfTooOld + numberOfDisconnected + numberOfNeverConnected + numberOfDisabled + numberOfBursting + numberOfListening + numberOfListenOnly;

		fs.put("numberOfSeedServers", numberOfSeedServers);
		fs.put("numberOfSeedClients", numberOfSeedClients);
		fs.put("numberOfConnected", numberOfConnected);
		fs.put("numberOfRoutingBackedOff", numberOfRoutingBackedOff);
		fs.put("numberOfTooNew", numberOfTooNew);
		fs.put("numberOfTooOld", numberOfTooOld);
		fs.put("numberOfDisconnected", numberOfDisconnected);
		fs.put("numberOfNeverConnected", numberOfNeverConnected);
		fs.put("numberOfDisabled", numberOfDisabled);
		fs.put("numberOfBursting", numberOfBursting);
		fs.put("numberOfListening", numberOfListening);
		fs.put("numberOfListenOnly", numberOfListenOnly);

		fs.put("numberOfSimpleConnected", numberOfSimpleConnected);
		fs.put("numberOfNotConnected", numberOfNotConnected);

		RequestTracker tracker = node.requestTracker;
		
		fs.put("numberOfTransferringRequestSenders", tracker.getNumTransferringRequestSenders());
		fs.put("numberOfARKFetchers", node.getNumARKFetchers());

		fs.put("numberOfLocalCHKInserts", tracker.getNumLocalCHKInserts());
		fs.put("numberOfRemoteCHKInserts", tracker.getNumRemoteCHKInserts());
		fs.put("numberOfLocalSSKInserts", tracker.getNumLocalSSKInserts());
		fs.put("numberOfRemoteSSKInserts", tracker.getNumRemoteSSKInserts());
		fs.put("numberOfLocalCHKRequests", tracker.getNumLocalCHKRequests());
		fs.put("numberOfRemoteCHKRequests", tracker.getNumRemoteCHKRequests());
		fs.put("numberOfLocalSSKRequests", tracker.getNumLocalSSKRequests());
		fs.put("numberOfRemoteSSKRequests", tracker.getNumRemoteSSKRequests());
		fs.put("numberOfTransferringRequestHandlers", tracker.getNumTransferringRequestHandlers());
		fs.put("numberOfCHKOfferReplys", tracker.getNumCHKOfferReplies());
		fs.put("numberOfSSKOfferReplys", tracker.getNumSSKOfferReplies());

		fs.put("delayTimeLocalRT", nlmDelayRTLocal.currentValue());
		fs.put("delayTimeRemoteRT", nlmDelayRTRemote.currentValue());
		fs.put("delayTimeLocalBulk", nlmDelayBulkLocal.currentValue());
		fs.put("delayTimeRemoteBulk", nlmDelayBulkRemote.currentValue());
		synchronized(slotTimeoutsSync) {
		    // timeoutFractions = fatalTimeouts/(fatalTimeouts+allocatedSlot)
		    fs.put("fatalTimeoutsLocal",fatalTimeoutsInWaitLocal);
		    fs.put("fatalTimeoutsRemote",fatalTimeoutsInWaitRemote);
		    fs.put("allocatedSlotLocal",allocatedSlotLocal);
		    fs.put("allocatedSlotRemote",allocatedSlotRemote);
		}

		int[] waitingSlots = tracker.countRequestsWaitingForSlots();
		fs.put("RequestsWaitingSlotsLocal", waitingSlots[0]);
		fs.put("RequestsWaitingSlotsRemote", waitingSlots[1]);

		fs.put("successfulLocalCHKFetchTimeBulk", successfulLocalCHKFetchTimeAverageBulk.currentValue());
		fs.put("successfulLocalCHKFetchTimeRT", successfulLocalCHKFetchTimeAverageRT.currentValue());
		fs.put("unsuccessfulLocalCHKFetchTimeBulk", unsuccessfulLocalCHKFetchTimeAverageBulk.currentValue());
		fs.put("unsuccessfulLocalCHKFetchTimeRT", unsuccessfulLocalCHKFetchTimeAverageRT.currentValue());

		fs.put("successfulLocalSSKFetchTimeBulk", successfulLocalSSKFetchTimeAverageBulk.currentValue());
		fs.put("successfulLocalSSKFetchTimeRT", successfulLocalSSKFetchTimeAverageRT.currentValue());
		fs.put("unsuccessfulLocalSSKFetchTimeBulk", unsuccessfulLocalSSKFetchTimeAverageBulk.currentValue());
		fs.put("unsuccessfulLocalSSKFetchTimeRT", unsuccessfulLocalSSKFetchTimeAverageRT.currentValue());

		long[] total = node.collector.getTotalIO();
		long total_output_rate = (total[0]) / nodeUptimeSeconds;
		long total_input_rate = (total[1]) / nodeUptimeSeconds;
		long totalPayloadOutput = node.getTotalPayloadSent();
		long total_payload_output_rate = totalPayloadOutput / nodeUptimeSeconds;
		int total_payload_output_percent = (total[0]==0)?-1:(int) (100 * totalPayloadOutput / total[0]);
		fs.put("totalOutputBytes", total[0]);
		fs.put("totalOutputRate", total_output_rate);
		fs.put("totalPayloadOutputBytes", totalPayloadOutput);
		fs.put("totalPayloadOutputRate", total_payload_output_rate);
		fs.put("totalPayloadOutputPercent", total_payload_output_percent);
		fs.put("totalInputBytes", total[1]);
		fs.put("totalInputRate", total_input_rate);

		long[] rate = getNodeIOStats();
		long deltaMS = (rate[5] - rate[2]);
		double recent_output_rate = deltaMS==0?0:(1000.0 * (rate[3] - rate[0]) / deltaMS);
		double recent_input_rate = deltaMS==0?0:(1000.0 * (rate[4] - rate[1]) / deltaMS);
		fs.put("recentOutputRate", recent_output_rate);
		fs.put("recentInputRate", recent_input_rate);

		fs.put("ackOnlyBytes", getNotificationOnlyPacketsSentBytes());
		fs.put("resentBytes", getResendBytesSent());
		fs.put("updaterOutputBytes", getUOMBytesSent());
		fs.put("announcePayloadBytes", getAnnounceBytesPayloadSent());
		fs.put("announceSentBytes", getAnnounceBytesSent());

		String [] routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons(true);
		if(routingBackoffReasons.length != 0) {
			for(int i=0;i<routingBackoffReasons.length;i++) {
				fs.put("numberWithRoutingBackoffReasonsRT." + routingBackoffReasons[i], peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i], true));
			}
		}

		routingBackoffReasons = peers.getPeerNodeRoutingBackoffReasons(false);
		if(routingBackoffReasons.length != 0) {
			for(int i=0;i<routingBackoffReasons.length;i++) {
				fs.put("numberWithRoutingBackoffReasonsBulk." + routingBackoffReasons[i], peers.getPeerNodeRoutingBackoffReasonSize(routingBackoffReasons[i], false));
			}
		}

		double swaps = node.getSwaps();
		double noSwaps = node.getNoSwaps();
		double numberOfRemotePeerLocationsSeenInSwaps = node.getNumberOfRemotePeerLocationsSeenInSwaps();
		fs.put("numberOfRemotePeerLocationsSeenInSwaps", numberOfRemotePeerLocationsSeenInSwaps);
		double avgConnectedPeersPerNode = 0.0;
		if ((numberOfRemotePeerLocationsSeenInSwaps > 0.0) && ((swaps > 0.0) || (noSwaps > 0.0))) {
			avgConnectedPeersPerNode = numberOfRemotePeerLocationsSeenInSwaps/(swaps+noSwaps);
		}
		fs.put("avgConnectedPeersPerNode", avgConnectedPeersPerNode);

		int startedSwaps = node.getStartedSwaps();
		int swapsRejectedAlreadyLocked = node.getSwapsRejectedAlreadyLocked();
		int swapsRejectedNowhereToGo = node.getSwapsRejectedNowhereToGo();
		int swapsRejectedRateLimit = node.getSwapsRejectedRateLimit();
		int swapsRejectedRecognizedID = node.getSwapsRejectedRecognizedID();
		double locationChangePerSession = node.getLocationChangeSession();
		double locationChangePerSwap = 0.0;
		double locationChangePerMinute = 0.0;
		double swapsPerMinute = 0.0;
		double noSwapsPerMinute = 0.0;
		double swapsPerNoSwaps = 0.0;
		if (swaps > 0) {
			locationChangePerSwap = locationChangePerSession/swaps;
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			locationChangePerMinute = locationChangePerSession/(nodeUptimeSeconds/60.0);
		}
		if ((swaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			swapsPerMinute = swaps/(nodeUptimeSeconds/60.0);
		}
		if ((noSwaps > 0.0) && (nodeUptimeSeconds >= 60)) {
			noSwapsPerMinute = noSwaps/(nodeUptimeSeconds/60.0);
		}
		if ((swaps > 0.0) && (noSwaps > 0.0)) {
			swapsPerNoSwaps = swaps/noSwaps;
		}
		fs.put("locationChangePerSession", locationChangePerSession);
		fs.put("locationChangePerSwap", locationChangePerSwap);
		fs.put("locationChangePerMinute", locationChangePerMinute);
		fs.put("swapsPerMinute", swapsPerMinute);
		fs.put("noSwapsPerMinute", noSwapsPerMinute);
		fs.put("swapsPerNoSwaps", swapsPerNoSwaps);
		fs.put("swaps", swaps);
		fs.put("noSwaps", noSwaps);
		fs.put("startedSwaps", startedSwaps);
		fs.put("swapsRejectedAlreadyLocked", swapsRejectedAlreadyLocked);
		fs.put("swapsRejectedNowhereToGo", swapsRejectedNowhereToGo);
		fs.put("swapsRejectedRateLimit", swapsRejectedRateLimit);
		fs.put("swapsRejectedRecognizedID", swapsRejectedRecognizedID);
		long fix32kb = 32 * 1024;
		long cachedKeys = node.getChkDatacache().keyCount();
		long cachedSize = cachedKeys * fix32kb;
		long storeKeys = node.getChkDatastore().keyCount();
		long storeSize = storeKeys * fix32kb;
		long overallKeys = cachedKeys + storeKeys;
		long overallSize = cachedSize + storeSize;

		long maxOverallKeys = node.getMaxTotalKeys();
		long maxOverallSize = maxOverallKeys * fix32kb;

		double percentOverallKeysOfMax = (double)(overallKeys*100)/(double)maxOverallKeys;

		long cachedStoreHits = node.getChkDatacache().hits();
		long cachedStoreMisses = node.getChkDatacache().misses();
		long cachedStoreWrites = node.getChkDatacache().writes();
		long cacheAccesses = cachedStoreHits + cachedStoreMisses;
		long cachedStoreFalsePositives = node.getChkDatacache().getBloomFalsePositive();
		double percentCachedStoreHitsOfAccesses = (double)(cachedStoreHits*100) / (double)cacheAccesses;
		long storeHits = node.getChkDatastore().hits();
		long storeMisses = node.getChkDatastore().misses();
		long storeWrites = node.getChkDatastore().writes();
		long storeFalsePositives = node.getChkDatastore().getBloomFalsePositive();
		long storeAccesses = storeHits + storeMisses;
		double percentStoreHitsOfAccesses = (double)(storeHits*100) / (double)storeAccesses;
		long overallAccesses = storeAccesses + cacheAccesses;
		double avgStoreAccessRate = (double)overallAccesses/(double)nodeUptimeSeconds;

		fs.put("cachedKeys", cachedKeys);
		fs.put("cachedSize", cachedSize);
		fs.put("storeKeys", storeKeys);
		fs.put("storeSize", storeSize);
		fs.put("overallKeys", overallKeys);
		fs.put("overallSize", overallSize);
		fs.put("maxOverallKeys", maxOverallKeys);
		fs.put("maxOverallSize", maxOverallSize);
		fs.put("percentOverallKeysOfMax", percentOverallKeysOfMax);
		fs.put("cachedStoreHits", cachedStoreHits);
		fs.put("cachedStoreMisses", cachedStoreMisses);
		fs.put("cachedStoreWrites", cachedStoreWrites);
		fs.put("cacheAccesses", cacheAccesses);
		fs.put("cachedStoreFalsePositives", cachedStoreFalsePositives);
		fs.put("percentCachedStoreHitsOfAccesses", percentCachedStoreHitsOfAccesses);
		fs.put("storeHits", storeHits);
		fs.put("storeMisses", storeMisses);
		fs.put("storeAccesses", storeAccesses);
		fs.put("storeWrites", storeWrites);
		fs.put("storeFalsePositives", storeFalsePositives);
		fs.put("percentStoreHitsOfAccesses", percentStoreHitsOfAccesses);
		fs.put("overallAccesses", overallAccesses);
		fs.put("avgStoreAccessRate", avgStoreAccessRate);

		Runtime rt = Runtime.getRuntime();
		float freeMemory = rt.freeMemory();
		float totalMemory = rt.totalMemory();
		float maxMemory = rt.maxMemory();

		long usedJavaMem = (long)(totalMemory - freeMemory);
		long allocatedJavaMem = (long)totalMemory;
		long maxJavaMem = (long)maxMemory;
		int availableCpus = rt.availableProcessors();

		fs.put("freeJavaMemory", (long)freeMemory);
		fs.put("usedJavaMemory", usedJavaMem);
		fs.put("allocatedJavaMemory", allocatedJavaMem);
		fs.put("maximumJavaMemory", maxJavaMem);
		fs.put("availableCPUs", availableCpus);
		fs.put("runningThreadCount", getActiveThreadCount());

		fs.put("globalFetchPSuccess", globalFetchPSuccess.currentValue());
		fs.put("globalFetchCount", globalFetchPSuccess.countReports());
		fs.put("chkLocalFetchPSuccess", chkLocalFetchPSuccess.currentValue());
		fs.put("chkLocalFetchCount", chkLocalFetchPSuccess.countReports());
		fs.put("chkRemoteFetchPSuccess", chkRemoteFetchPSuccess.currentValue());
		fs.put("chkRemoteFetchCount", chkRemoteFetchPSuccess.countReports());
		fs.put("sskLocalFetchPSuccess", sskLocalFetchPSuccess.currentValue());
		fs.put("sskLocalFetchCount", sskLocalFetchPSuccess.countReports());
		fs.put("sskRemoteFetchPSuccess", sskRemoteFetchPSuccess.currentValue());
		fs.put("sskRemoteFetchCount", sskRemoteFetchPSuccess.countReports());
		fs.put("blockTransferPSuccessRT", blockTransferPSuccessRT.currentValue());
		fs.put("blockTransferCountRT", blockTransferPSuccessRT.countReports());
		fs.put("blockTransferPSuccessBulk", blockTransferPSuccessBulk.currentValue());
		fs.put("blockTransferCountBulk", blockTransferPSuccessBulk.countReports());
		fs.put("blockTransferFailTimeout", blockTransferFailTimeout.currentValue());

		return fs;
	}

	public void setOutputLimit(int obwLimit) {
		requestOutputThrottle.changeNanosAndBucketSize((int)((1000L*1000L*1000L) / (obwLimit)), Math.max(obwLimit*60, 32768*20));
		if(node.isInputLimitDefault()) {
			setInputLimit(obwLimit * 4);
		}
	}

	public void setInputLimit(int ibwLimit) {
		requestInputThrottle.changeNanosAndBucketSize((int)((1000L*1000L*1000L) / (ibwLimit)), Math.max(ibwLimit*60, 32768*20));
	}

	public boolean isTestnetEnabled() {
		return node.isTestnetEnabled();
	}

	public boolean getRejectReasonsTable(HTMLNode table) {
		return preemptiveRejectReasons.toTableRows(table) > 0;
	}

	public boolean getLocalRejectReasonsTable(HTMLNode table) {
		return localPreemptiveRejectReasons.toTableRows(table) > 0;
	}

	public synchronized void requestCompleted(boolean succeeded, boolean isRemote, boolean isSSK) {
		globalFetchPSuccess.report(succeeded ? 1.0 : 0.0);
		if(isSSK) {
			if (isRemote) {
				sskRemoteFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			} else {
				sskLocalFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			}
		} else {
			if (isRemote) {
				chkRemoteFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			} else {
				chkLocalFetchPSuccess.report(succeeded ? 1.0 : 0.0);
			}
		}
	}

	private final DecimalFormat fix3p3pct = new DecimalFormat("##0.000%");
	private final NumberFormat thousandPoint = NumberFormat.getInstance();

	public void fillSuccessRateBox(HTMLNode parent) {
		HTMLNode list = parent.addChild("table", "border", "0");
		final RunningAverage[] averages = new RunningAverage[] {
				globalFetchPSuccess,
				chkLocalFetchPSuccess,
				chkRemoteFetchPSuccess,
				sskLocalFetchPSuccess,
				sskRemoteFetchPSuccess,
				blockTransferPSuccessBulk,
				blockTransferPSuccessRT,
				blockTransferPSuccessLocal,
				blockTransferFailTimeout
		};
		final String[] names = new String[] {
				l10n("allRequests"),
				l10n("localCHKs"),
				l10n("remoteCHKs"),
				l10n("localSSKs"),
				l10n("remoteSSKs"),
				l10n("blockTransfersBulk"),
				l10n("blockTransfersRT"),
				l10n("blockTransfersLocal"),
				l10n("transfersTimedOut")
		};
		HTMLNode row = list.addChild("tr");
		row.addChild("th", l10n("group"));
		row.addChild("th", l10n("pSuccess"));
		row.addChild("th", l10n("count"));

		for(int i=0;i<averages.length;i++) {
			row = list.addChild("tr");
			row.addChild("td", names[i]);
			if (averages[i].countReports()==0) {
				row.addChild("td", "-");
				row.addChild("td", "0");
			} else {
				row.addChild("td", fix3p3pct.format(averages[i].currentValue()));
				row.addChild("td", thousandPoint.format(averages[i].countReports()));
			}
		}

		row = list.addChild("tr");
		long[] bulkSuccess = BulkTransmitter.transferSuccess();
		row = list.addChild("tr");
		row.addChild("td", l10n("bulkSends"));
		row.addChild("td", fix3p3pct.format(((double)bulkSuccess[1])/((double)bulkSuccess[0])));
		row.addChild("td", Long.toString(bulkSuccess[0]));
	}

	/* Total bytes sent by requests and inserts, excluding payload */
	private long chkRequestSentBytes;
	private long chkRequestRcvdBytes;
	private long sskRequestSentBytes;
	private long sskRequestRcvdBytes;
	private long chkInsertSentBytes;
	private long chkInsertRcvdBytes;
	private long sskInsertSentBytes;
	private long sskInsertRcvdBytes;

	public synchronized void requestSentBytes(boolean ssk, int x) {
		if(ssk)
			sskRequestSentBytes += x;
		else
			chkRequestSentBytes += x;
	}

	public synchronized void requestReceivedBytes(boolean ssk, int x) {
		if(ssk)
			sskRequestRcvdBytes += x;
		else
			chkRequestRcvdBytes += x;
	}

	public synchronized void insertSentBytes(boolean ssk, int x) {
		if(logDEBUG)
			Logger.debug(this, "insertSentBytes("+ssk+", "+x+")");
		if(ssk)
			sskInsertSentBytes += x;
		else
			chkInsertSentBytes += x;
	}

	public synchronized void insertReceivedBytes(boolean ssk, int x) {
		if(ssk)
			sskInsertRcvdBytes += x;
		else
			chkInsertRcvdBytes += x;
	}

	public synchronized long getCHKRequestTotalBytesSent() {
		return chkRequestSentBytes;
	}

	public synchronized long getSSKRequestTotalBytesSent() {
		return sskRequestSentBytes;
	}

	public synchronized long getCHKInsertTotalBytesSent() {
		return chkInsertSentBytes;
	}

	public synchronized long getSSKInsertTotalBytesSent() {
		return sskInsertSentBytes;
	}

	private long offeredKeysSenderRcvdBytes;
	private long offeredKeysSenderSentBytes;

	public synchronized void offeredKeysSenderReceivedBytes(int x) {
		offeredKeysSenderRcvdBytes += x;
	}

	/**
	 * @return The number of bytes sent in replying to FNPGetOfferedKey's.
	 */
	public synchronized void offeredKeysSenderSentBytes(int x) {
		offeredKeysSenderSentBytes += x;
	}

	public long getOfferedKeysTotalBytesReceived() {
		return offeredKeysSenderRcvdBytes;
	}

	public long getOfferedKeysTotalBytesSent() {
		return offeredKeysSenderSentBytes;
	}

	private long offerKeysRcvdBytes;
	private long offerKeysSentBytes;

	public ByteCounter sendOffersCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				offerKeysRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				offerKeysSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getOffersSentBytesSent() {
		return offerKeysSentBytes;
	}

	private long swappingRcvdBytes;
	private long swappingSentBytes;

	public synchronized void swappingReceivedBytes(int x) {
		swappingRcvdBytes += x;
	}

	public synchronized void swappingSentBytes(int x) {
		swappingSentBytes += x;
	}

	public synchronized long getSwappingTotalBytesReceived() {
		return swappingRcvdBytes;
	}

	public synchronized long getSwappingTotalBytesSent() {
		return swappingSentBytes;
	}

	private long totalAuthBytesSent;

	public synchronized void reportAuthBytes(int x) {
		totalAuthBytesSent += x;
	}

	public synchronized long getTotalAuthBytesSent() {
		return totalAuthBytesSent;
	}

	private long resendBytesSent;

	public final ByteCounter resendByteCounter = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			// Ignore
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				resendBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			Logger.error(this, "Payload sent in resendByteCounter????", new Exception("error"));
		}

	};

	public synchronized long getResendBytesSent() {
		return resendBytesSent;
	}

	private long uomBytesSent;

	public synchronized void reportUOMBytesSent(int x) {
		uomBytesSent += x;
	}

	public synchronized long getUOMBytesSent() {
		return uomBytesSent;
	}

	// Opennet-related bytes - *not* including bytes sent on requests, those are accounted towards
	// the requests' totals.

	private long announceBytesSent;
	private long announceBytesPayload;

	public final ByteCounter announceByteCounter = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			// Ignore
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				announceBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			synchronized(NodeStats.this) {
				announceBytesPayload += x;
			}
		}

	};

	public synchronized long getAnnounceBytesSent() {
		return announceBytesSent;
	}

	public synchronized long getAnnounceBytesPayloadSent() {
		return announceBytesPayload;
	}

	private long routingStatusBytesSent;

	public ByteCounter setRoutingStatusCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			// Impossible?
			Logger.error(this, "Routing status sender received bytes: "+x+" - isn't that impossible?");
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				routingStatusBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getRoutingStatusBytes() {
		return routingStatusBytesSent;
	}

	private long networkColoringReceivedBytesCounter;
	private long networkColoringSentBytesCounter;

	public synchronized void networkColoringReceivedBytes(int x) {
		networkColoringReceivedBytesCounter += x;
	}

	public synchronized void networkColoringSentBytes(int x) {
		networkColoringSentBytesCounter += x;
	}

	public synchronized long getNetworkColoringSentBytes() {
		return networkColoringSentBytesCounter;
	}

	private long pingBytesReceived;
	private long pingBytesSent;

	public synchronized void pingCounterReceived(int x) {
		pingBytesReceived += x;
	}

	public synchronized void pingCounterSent(int x) {
		pingBytesSent += x;
	}

	public synchronized long getPingSentBytes() {
		return pingBytesSent;
	}

	public ByteCounter sskRequestCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				sskRequestRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				sskRequestSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public ByteCounter chkRequestCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				chkRequestRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				chkRequestSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public ByteCounter sskInsertCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				sskInsertRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				sskInsertSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public ByteCounter chkInsertCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				chkInsertRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				chkInsertSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	private long probeRequestSentBytes;
	private long probeRequestRcvdBytes;

	public ByteCounter probeRequestCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				probeRequestRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				probeRequestSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getProbeRequestSentBytes() {
		return probeRequestSentBytes;
	}

	private long routedMessageBytesRcvd;
	private long routedMessageBytesSent;

	public ByteCounter routedMessageCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				routedMessageBytesRcvd += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				routedMessageBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getRoutedMessageSentBytes() {
		return routedMessageBytesSent;
	}

	private long disconnBytesReceived;
	private long disconnBytesSent;

	public void disconnBytesReceived(int x) {
		this.disconnBytesReceived += x;
	}

	public void disconnBytesSent(int x) {
		this.disconnBytesSent += x;
	}

	public long getDisconnBytesSent() {
		return disconnBytesSent;
	}

	private long initialMessagesBytesReceived;
	private long initialMessagesBytesSent;

	public ByteCounter initialMessagesCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				initialMessagesBytesReceived += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				initialMessagesBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public synchronized long getInitialMessagesBytesSent() {
		return initialMessagesBytesSent;
	}

	private long changedIPBytesReceived;
	private long changedIPBytesSent;

	public ByteCounter changedIPCtr = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				changedIPBytesReceived += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				changedIPBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public long getChangedIPBytesSent() {
		return changedIPBytesSent;
	}

	private long nodeToNodeRcvdBytes;
	private long nodeToNodeSentBytes;

	public final ByteCounter nodeToNodeCounter = new ByteCounter() {

		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				nodeToNodeRcvdBytes += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				nodeToNodeSentBytes += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}

	};

	public long getNodeToNodeBytesSent() {
		return nodeToNodeSentBytes;
	}
	
	private long allocationNoticesCounterBytesReceived;
	private long allocationNoticesCounterBytesSent;
	
	public final ByteCounter allocationNoticesCounter = new ByteCounter() {
		
		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				allocationNoticesCounterBytesReceived += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				allocationNoticesCounterBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}
		
	};
	
	public long getAllocationNoticesBytesSent() {
		return allocationNoticesCounterBytesSent;
	}

	private long foafCounterBytesReceived;
	private long foafCounterBytesSent;
	
	public final ByteCounter foafCounter = new ByteCounter() {
		
		@Override
		public void receivedBytes(int x) {
			synchronized(NodeStats.this) {
				foafCounterBytesReceived += x;
			}
		}

		@Override
		public void sentBytes(int x) {
			synchronized(NodeStats.this) {
				foafCounterBytesSent += x;
			}
		}

		@Override
		public void sentPayload(int x) {
			// Ignore
		}
		
	};
	
	public long getFOAFBytesSent() {
		return foafCounterBytesSent;
	}

	
	

	private long notificationOnlySentBytes;

	public synchronized void reportNotificationOnlyPacketSent(int packetSize) {
		notificationOnlySentBytes += packetSize;
	}

	public long getNotificationOnlyPacketsSentBytes() {
		return notificationOnlySentBytes;
	}

	public synchronized long getSentOverhead() {
		return offerKeysSentBytes // offers we have sent
		+ swappingSentBytes // swapping
		+ totalAuthBytesSent // connection setup
		+ resendBytesSent // resends - FIXME might be dependant on requests?
		+ uomBytesSent // update over mandatory
		+ announceBytesSent // announcements, including payload
		+ routingStatusBytesSent // routing status
		+ networkColoringSentBytesCounter // network coloring
		+ pingBytesSent // ping bytes
		+ probeRequestSentBytes // probe requests
		+ routedMessageBytesSent // routed test messages
		+ disconnBytesSent // disconnection related bytes
		+ initialMessagesBytesSent // initial messages
		+ changedIPBytesSent // changed IP
		+ nodeToNodeSentBytes // n2n messages
		+ notificationOnlySentBytes; // ack-only packets
	}

	/**
	 * The average number of bytes sent per second for things other than requests, inserts,
	 * and offer replies.
	 */
	public double getSentOverheadPerSecond() {
		long uptime = node.getUptime();
		return (getSentOverhead() * 1000.0) / uptime;
	}

	public synchronized void successfulBlockReceive(boolean realTimeFlag, boolean isLocal) {
		RunningAverage blockTransferPSuccess = realTimeFlag ? blockTransferPSuccessRT : blockTransferPSuccessBulk;
		blockTransferPSuccess.report(1.0);
		if(isLocal)
			blockTransferPSuccessLocal.report(1.0);
		if(logMINOR) Logger.minor(this, "Successful receives: "+blockTransferPSuccess.currentValue()+" count="+blockTransferPSuccess.countReports()+" realtime="+realTimeFlag);
	}

	public synchronized void failedBlockReceive(boolean normalFetch, boolean timeout, boolean realTimeFlag, boolean isLocal) {
		if(normalFetch) {
			blockTransferFailTimeout.report(timeout ? 1.0 : 0.0);
		}
		RunningAverage blockTransferPSuccess = realTimeFlag ? blockTransferPSuccessRT : blockTransferPSuccessBulk;
		blockTransferPSuccess.report(0.0);
		if(isLocal)
			blockTransferPSuccessLocal.report(0.0);
		if(logMINOR) Logger.minor(this, "Successful receives: "+blockTransferPSuccess.currentValue()+" count="+blockTransferPSuccess.countReports()+" realtime="+realTimeFlag);
	}

	public void reportIncomingRequestLocation(double loc) {
		assert((loc > 0) && (loc < 1.0));

		synchronized(incomingRequestsByLoc) {
			incomingRequestsByLoc[(int)Math.floor(loc*incomingRequestsByLoc.length)]++;
			incomingRequestsAccounted++;
		}
	}

	public int[] getIncomingRequestLocation(int[] retval) {
		int[] result = new int[incomingRequestsByLoc.length];
		synchronized(incomingRequestsByLoc) {
			System.arraycopy(incomingRequestsByLoc, 0, result, 0, incomingRequestsByLoc.length);
			retval[0] = incomingRequestsAccounted;
		}

		return result;
	}

	public void reportOutgoingLocalRequestLocation(double loc) {
		assert((loc > 0) && (loc < 1.0));

		synchronized(outgoingLocalRequestByLoc) {
			outgoingLocalRequestByLoc[(int)Math.floor(loc*outgoingLocalRequestByLoc.length)]++;
			outgoingLocalRequestsAccounted++;
		}
	}

	public int[] getOutgoingLocalRequestLocation(int[] retval) {
		int[] result = new int[outgoingLocalRequestByLoc.length];
		synchronized(outgoingLocalRequestByLoc) {
			System.arraycopy(outgoingLocalRequestByLoc, 0, result, 0, outgoingLocalRequestByLoc.length);
			retval[0] = outgoingLocalRequestsAccounted;
		}

		return result;
	}

	public void reportOutgoingRequestLocation(double loc) {
		assert((loc > 0) && (loc < 1.0));

		synchronized(outgoingRequestByLoc) {
			outgoingRequestByLoc[(int)Math.floor(loc*outgoingRequestByLoc.length)]++;
			outgoingRequestsAccounted++;
		}
	}

	public int[] getOutgoingRequestLocation(int[] retval) {
		int[] result = new int[outgoingRequestByLoc.length];
		synchronized(outgoingRequestByLoc) {
			System.arraycopy(outgoingRequestByLoc, 0, result, 0, outgoingRequestByLoc.length);
			retval[0] = outgoingRequestsAccounted;
		}

		return result;
	}

	public void reportCHKOutcome(long rtt, boolean successful, double location, boolean isRealtime) {
		if (successful) {
			(isRealtime ? successfulLocalCHKFetchTimeAverageRT : successfulLocalCHKFetchTimeAverageBulk).report(rtt);
			chkSuccessRatesByLocation.report(location, 1.0);
		} else {
			(isRealtime ? unsuccessfulLocalCHKFetchTimeAverageRT : unsuccessfulLocalCHKFetchTimeAverageBulk).report(rtt);
			chkSuccessRatesByLocation.report(location, 0.0);
		}
		(isRealtime ? localCHKFetchTimeAverageRT : localCHKFetchTimeAverageBulk).report(rtt);
	}

	public void reportSSKOutcome(long rtt, boolean successful, boolean isRealtime) {
		if (successful) {
			(isRealtime ? successfulLocalSSKFetchTimeAverageRT : successfulLocalSSKFetchTimeAverageBulk).report(rtt);
		} else {
			(isRealtime ? unsuccessfulLocalSSKFetchTimeAverageRT : unsuccessfulLocalSSKFetchTimeAverageBulk).report(rtt);
		}
		(isRealtime ? localSSKFetchTimeAverageRT : localSSKFetchTimeAverageBulk).report(rtt);
	}

	public void fillDetailedTimingsBox(HTMLNode html) {
		HTMLNode table = html.addChild("table");
		HTMLNode row = table.addChild("tr");
		row.addChild("td");
		row.addChild("td", "colspan", "2", "CHK");
		row.addChild("td", "colspan", "2", "SSK");
		row = table.addChild("tr");
		row.addChild("td", l10n("successfulHeader"));
		row.addChild("td", TimeUtil.formatTime((long)successfulLocalCHKFetchTimeAverageBulk.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)successfulLocalCHKFetchTimeAverageRT.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)successfulLocalSSKFetchTimeAverageBulk.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)successfulLocalSSKFetchTimeAverageRT.currentValue(), 2, true));
		row = table.addChild("tr");
		row.addChild("td", l10n("unsuccessfulHeader"));
		row.addChild("td", TimeUtil.formatTime((long)unsuccessfulLocalCHKFetchTimeAverageBulk.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)unsuccessfulLocalCHKFetchTimeAverageRT.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)unsuccessfulLocalSSKFetchTimeAverageBulk.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)unsuccessfulLocalSSKFetchTimeAverageRT.currentValue(), 2, true));
		row = table.addChild("tr");
		row.addChild("td", l10n("averageHeader"));
		row.addChild("td", TimeUtil.formatTime((long)localCHKFetchTimeAverageBulk.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)localCHKFetchTimeAverageRT.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)localSSKFetchTimeAverageBulk.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((long)localSSKFetchTimeAverageRT.currentValue(), 2, true));
	}

	private HourlyStats hourlyStatsRT;
	private HourlyStats hourlyStatsBulk;

	public void remoteRequest(boolean ssk, boolean success, boolean local, short htl, double location, boolean realTime, boolean fromOfferedKey) {
		if(logMINOR) Logger.minor(this, "Remote request: sucess="+success+" htl="+htl+" locally answered="+local+" location of key="+location+" from offered key = "+fromOfferedKey);
		if(!fromOfferedKey) {
			if(realTime)
				hourlyStatsRT.remoteRequest(ssk, success, local, htl, location);
			else
				hourlyStatsBulk.remoteRequest(ssk, success, local, htl, location);
		}
	}

	public void fillRemoteRequestHTLsBox(HTMLNode html, boolean realTime) {
		if(realTime)
			hourlyStatsRT.fillRemoteRequestHTLsBox(html);
		else
			hourlyStatsBulk.fillRemoteRequestHTLsBox(html);
	}

	private String sanitizeDBJobType(String jobType) {
		int typeBeginIndex = jobType.lastIndexOf('.'); // Only use the actual class name, exclude the packages
		int typeEndIndex = jobType.indexOf('@');

		if(typeBeginIndex < 0)
			typeBeginIndex = jobType.lastIndexOf(':'); // Strip "DBJobWrapper:" prefix

		if(typeBeginIndex < 0)
			typeBeginIndex = 0;
		else
			++typeBeginIndex;

		if(typeEndIndex < 0)
			typeEndIndex = jobType.length();

		return jobType.substring(typeBeginIndex, typeEndIndex);
	}

	public void reportDatabaseJob(String jobType, long executionTimeMiliSeconds) {
		jobType = sanitizeDBJobType(jobType);

		TrivialRunningAverage avg;

		synchronized(avgDatabaseJobExecutionTimes) {
			avg = avgDatabaseJobExecutionTimes.get(jobType);

			if(avg == null) {
				avg = new TrivialRunningAverage();
				avgDatabaseJobExecutionTimes.put(jobType, avg);
			}
		}

		avg.report(executionTimeMiliSeconds);
	}

	public void reportMandatoryBackoff(String backoffType, long backoffTimeMilliSeconds, boolean realtime) {
		TrivialRunningAverage avg;
		if(realtime) {
			synchronized (avgMandatoryBackoffTimesRT) {
				avg = avgMandatoryBackoffTimesRT.get(backoffType);

				if (avg == null) {
					avg = new TrivialRunningAverage();
					avgMandatoryBackoffTimesRT.put(backoffType, avg);
				}
			}
		} else {
			synchronized (avgMandatoryBackoffTimesBulk) {
				avg = avgMandatoryBackoffTimesBulk.get(backoffType);

				if (avg == null) {
					avg = new TrivialRunningAverage();
					avgMandatoryBackoffTimesBulk.put(backoffType, avg);
				}
			}
		}
		avg.report(backoffTimeMilliSeconds);
	}

	public void reportRoutingBackoff(String backoffType, long backoffTimeMilliSeconds, boolean realtime) {
		TrivialRunningAverage avg;

		if(realtime) {
			synchronized (avgRoutingBackoffTimesRT) {
				avg = avgRoutingBackoffTimesRT.get(backoffType);

				if (avg == null) {
					avg = new TrivialRunningAverage();
					avgRoutingBackoffTimesRT.put(backoffType, avg);
				}
			}
		} else {
			synchronized (avgRoutingBackoffTimesBulk) {
				avg = avgRoutingBackoffTimesBulk.get(backoffType);

				if (avg == null) {
					avg = new TrivialRunningAverage();
					avgRoutingBackoffTimesBulk.put(backoffType, avg);
				}
			}
		}

		avg.report(backoffTimeMilliSeconds);
	}

	public void reportTransferBackoff(String backoffType, long backoffTimeMilliSeconds, boolean realtime) {
		TrivialRunningAverage avg;

		if (realtime) {
			synchronized (avgTransferBackoffTimesRT) {
				avg = avgTransferBackoffTimesRT.get(backoffType);

				if (avg == null) {
					avg = new TrivialRunningAverage();
					avgTransferBackoffTimesRT.put(backoffType, avg);
				}
			}
		} else {
			synchronized (avgTransferBackoffTimesBulk) {
				avg = avgTransferBackoffTimesBulk.get(backoffType);

				if (avg == null) {
					avg = new TrivialRunningAverage();
					avgTransferBackoffTimesBulk.put(backoffType, avg);
				}
			}
		}
		avg.report(backoffTimeMilliSeconds);
	}

	/**
	 * View of stats for CHK Store
	 *
	 * @return stats for CHK Store
	 */
	public StoreLocationStats chkStoreStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgStoreCHKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgStoreCHKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestStoreCHKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgStoreCHKLocation, node.getChkDatastore());
			}
		};
	}

	/**
	 * View of stats for CHK Cache
	 *
	 * @return CHK cache stats
	 */
	public StoreLocationStats chkCacheStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgCacheCHKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgCacheCHKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestCacheCHKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgCacheCHKLocation, node.getChkDatacache());
			}
		};
	}

	/**
	 * View of stats for CHK SlashdotCache
	 *
	 * @return CHK Slashdotcache stats
	 */
	public StoreLocationStats chkSlashDotCacheStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgSlashdotCacheCHKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgSlashdotCacheCHKSucess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestSlashdotCacheCHKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgSlashdotCacheCHKLocation, node.getChkDatacache());
			}
		};
	}

		/**
	 * View of stats for CHK ClientCache
	 *
	 * @return CHK ClientCache stats
	 */
	public StoreLocationStats chkClientCacheStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgClientCacheCHKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgClientCacheCHKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestClientCacheCHKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgClientCacheCHKLocation, node.getChkDatacache());
			}
		};
	}

	/**
	 * View of stats for SSK Store
	 *
	 * @return stats for SSK Store
	 */
	public StoreLocationStats sskStoreStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgStoreSSKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgStoreSSKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestStoreSSKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgStoreSSKLocation, node.getChkDatastore());
			}
		};
	}

	/**
	 * View of stats for SSK Cache
	 *
	 * @return SSK cache stats
	 */
	public StoreLocationStats sskCacheStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgCacheSSKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgCacheSSKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestCacheSSKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgCacheSSKLocation, node.getChkDatacache());
			}
		};
	}

	/**
	 * View of stats for SSK SlashdotCache
	 *
	 * @return SSK Slashdotcache stats
	 */
	public StoreLocationStats sskSlashDotCacheStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgSlashdotCacheSSKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgSlashdotCacheSSKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestSlashdotCacheSSKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgSlashdotCacheSSKLocation, node.getChkDatacache());
			}
		};
	}

		/**
	 * View of stats for SSK ClientCache
	 *
	 * @return SSK ClientCache stats
	 */
	public StoreLocationStats sskClientCacheStats() {
		return new StoreLocationStats() {
			@Override
			public double avgLocation() {
				return avgClientCacheSSKLocation.currentValue();
			}

			@Override
			public double avgSuccess() {
				return avgClientCacheSSKSuccess.currentValue();
			}

			@Override
			public double furthestSuccess() throws StatsNotAvailableException {
				return furthestClientCacheSSKSuccess;
			}

			@Override
			public double avgDist() throws StatsNotAvailableException {
				return Location.distance(nodeLoc, avgLocation());
			}

			@Override
			public double distanceStats() throws StatsNotAvailableException {
				return cappedDistance(avgClientCacheSSKLocation, node.getChkDatacache());
			}
		};
	}


	private double cappedDistance(DecayingKeyspaceAverage avgLocation, CHKStore store) {
		double cachePercent = 1.0 * avgLocation.countReports() / store.keyCount();
		//Cap the reported value at 100%, as the decaying average does not account beyond that anyway.
		if (cachePercent > 1.0) {
			cachePercent = 1.0;
		}
		return cachePercent;
	}


	public static class TimedStats implements Comparable<TimedStats> {
		public final String keyStr;
		public final long count;
		public final long avgTime;
		public final long totalTime;

		public TimedStats(String myKeyStr, long myCount, long myAvgTime, long myTotalTime) {
			keyStr = myKeyStr;
			count = myCount;
			avgTime = myAvgTime;
			totalTime = myTotalTime;
		}

		@Override
		public int compareTo(TimedStats o) {
			if(totalTime < o.totalTime)
				return 1;
			else if(totalTime == o.totalTime)
				return 0;
			else
				return -1;
		}
	}

	public TimedStats[] getMandatoryBackoffStatistics(boolean realtime) {

		if (realtime) {
			TimedStats[] entries = new TimedStats[avgMandatoryBackoffTimesRT.size()];
			int i = 0;

			synchronized (avgMandatoryBackoffTimesRT) {
				for (Map.Entry<String, TrivialRunningAverage> entry : avgMandatoryBackoffTimesRT.entrySet()) {
					TrivialRunningAverage avg = entry.getValue();
					entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
				}
			}

			Arrays.sort(entries);
			return entries;
		} else {
			TimedStats[] entries = new TimedStats[avgMandatoryBackoffTimesBulk.size()];
			int i = 0;

			synchronized (avgMandatoryBackoffTimesBulk) {
				for (Map.Entry<String, TrivialRunningAverage> entry : avgMandatoryBackoffTimesBulk.entrySet()) {
					TrivialRunningAverage avg = entry.getValue();
					entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
				}
			}

			Arrays.sort(entries);
			return entries;
		}
	}

	public TimedStats[] getRoutingBackoffStatistics(boolean realtime) {
		if (realtime) {
			TimedStats[] entries = new TimedStats[avgRoutingBackoffTimesRT.size()];
			int i = 0;

			synchronized (avgRoutingBackoffTimesRT) {
				for (Map.Entry<String, TrivialRunningAverage> entry : avgRoutingBackoffTimesRT.entrySet()) {
					TrivialRunningAverage avg = entry.getValue();
					entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
				}
			}

			Arrays.sort(entries);
			return entries;
		} else {
			TimedStats[] entries = new TimedStats[avgRoutingBackoffTimesBulk.size()];
			int i = 0;

			synchronized (avgRoutingBackoffTimesBulk) {
				for (Map.Entry<String, TrivialRunningAverage> entry : avgRoutingBackoffTimesBulk.entrySet()) {
					TrivialRunningAverage avg = entry.getValue();
					entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
				}
			}

			Arrays.sort(entries);
			return entries;
		}
	}

	public TimedStats[] getTransferBackoffStatistics(boolean realtime) {
		if (realtime) {
			TimedStats[] entries = new TimedStats[avgTransferBackoffTimesRT.size()];
			int i = 0;

			synchronized (avgTransferBackoffTimesRT) {
				for (Map.Entry<String, TrivialRunningAverage> entry : avgTransferBackoffTimesRT.entrySet()) {
					TrivialRunningAverage avg = entry.getValue();
					entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
				}
			}

			Arrays.sort(entries);
			return entries;
		} else {
			TimedStats[] entries = new TimedStats[avgTransferBackoffTimesBulk.size()];
			int i = 0;

			synchronized (avgTransferBackoffTimesBulk) {
				for (Map.Entry<String, TrivialRunningAverage> entry : avgTransferBackoffTimesBulk.entrySet()) {
					TrivialRunningAverage avg = entry.getValue();
					entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
				}
			}

			Arrays.sort(entries);
			return entries;
		}
	}

	public TimedStats[] getDatabaseJobExecutionStatistics() {
		TimedStats[] entries = new TimedStats[avgDatabaseJobExecutionTimes.size()];
		int i = 0;

		synchronized(avgDatabaseJobExecutionTimes) {
			for(Map.Entry<String, TrivialRunningAverage> entry : avgDatabaseJobExecutionTimes.entrySet()) {
				TrivialRunningAverage avg = entry.getValue();
				entries[i++] = new TimedStats(entry.getKey(), avg.countReports(), (long) avg.currentValue(), (long) avg.totalValue());
			}
		}

		Arrays.sort(entries);
		return entries;
	}

	public StringCounter getDatabaseJobQueueStatistics() {
		final StringCounter result = new StringCounter();

		final LinkedList<Runnable>[] dbJobs = node.clientCore.clientDatabaseExecutor.getQueuedJobsByPriority();

		for(LinkedList<Runnable> list : dbJobs) {
			for(Runnable job : list) {
				result.inc(sanitizeDBJobType(job.toString()));
			}
		}

		return result;
	}

	public PeerLoadStats createPeerLoadStats(PeerNode peer, int transfersPerInsert, boolean realTimeFlag) {
		return new PeerLoadStats(peer, transfersPerInsert, realTimeFlag);
	}

	public PeerLoadStats parseLoadStats(PeerNode source, Message m) {
		return new PeerLoadStats(source, m);
	}

	public RunningRequestsSnapshot getRunningRequestsTo(PeerNode peerNode, int transfersPerInsert, boolean realTimeFlag) {
		return new RunningRequestsSnapshot(node.requestTracker, peerNode, true, false, outwardTransfersPerInsert(), realTimeFlag);
	}
	
	public boolean ignoreLocalVsRemoteBandwidthLiability() {
		return ignoreLocalVsRemoteBandwidthLiability;
	}
	
	private int totalAnnouncements;
	private int totalAnnounceForwards;
	
	public void reportAnnounceForwarded(int forwardedRefs, PeerNode source) {
		synchronized(this) {
			totalAnnouncements++;
			totalAnnounceForwards += forwardedRefs;
			if(logMINOR) Logger.minor(this, "Announcements: "+totalAnnouncements+" average "+((totalAnnounceForwards*1.0)/totalAnnouncements));
			// FIXME add to stats page
		}
		OpennetManager om = node.getOpennet();
		if(om != null && source instanceof SeedClientPeerNode)
			om.seedTracker.completedAnnounce((SeedClientPeerNode)source, forwardedRefs);
	}
	
	public synchronized int getTransfersPerAnnounce() {
		if(totalAnnouncements == 0) return 1;
		return (int)Math.max(1, Math.ceil((totalAnnounceForwards*1.0)/totalAnnouncements));
	}

	private final HashSet<Long> runningAnnouncements = new HashSet<Long>();

	// FIXME make configurable, more sophisticated.
	
	/** To prevent thread overflow */
	private final static int MAX_ANNOUNCEMENTS = 100;
	
	public boolean shouldAcceptAnnouncement(long uid) {
		int outputPerSecond = node.getOutputBandwidthLimit() / 2; // FIXME: Take overhead into account??? Be careful, it may include announcements and that would cause problems!
		int inputPerSecond = node.getInputBandwidthLimit() / 2;
		int limit = Math.min(inputPerSecond, outputPerSecond);
		synchronized(this) {
			int transfersPerAnnouncement = getTransfersPerAnnounce();
			int running = runningAnnouncements.size();
			if(running >= MAX_ANNOUNCEMENTS) {
				if(logMINOR) Logger.minor(this, "Too many announcements running: "+running);
				return false;
			}
			// Liability-style limiting as well.
			int perTransfer = OpennetManager.PADDED_NODEREF_SIZE;
			// Must all complete in 30 seconds. That is the timeout for one block.
			int bandwidthIn30Secs = limit * 30;
			if(perTransfer * transfersPerAnnouncement * running > bandwidthIn30Secs) {
				if(logMINOR) Logger.minor(this, "Can't complete "+running+" announcements in 30 secs");
				return false;
			}
			runningAnnouncements.add(uid);
			if(logMINOR) Logger.minor(this, "Accepting announcement "+uid);
			return true;
		}
	}
	
	public synchronized void endAnnouncement(long uid) {
		runningAnnouncements.remove(uid);
	}

	@Override
	public void blockTime(long interval, boolean realtime) {
		throttledPacketSendAverage.report(interval);
		if(realtime)
			throttledPacketSendAverageRT.report(interval);
		else
			throttledPacketSendAverageBulk.report(interval);
	}
	
	/** If a peer is over this threshold it is considered to be backed off. */
	public synchronized long maxPeerPingTime() {
		return 2 * maxPingTime;
	}
	
	private RunningAverage nlmDelayRTLocal = new TrivialRunningAverage();
	private RunningAverage nlmDelayRTRemote = new TrivialRunningAverage();
	private RunningAverage nlmDelayBulkLocal = new TrivialRunningAverage();
	private RunningAverage nlmDelayBulkRemote = new TrivialRunningAverage();

	public void reportNLMDelay(long waitTime, boolean realTime, boolean local) {
		if(realTime) {
			if(local)
				nlmDelayRTLocal.report(waitTime);
			else
				nlmDelayRTRemote.report(waitTime);
		} else {
			if(local)
				nlmDelayBulkLocal.report(waitTime);
			else
				nlmDelayBulkRemote.report(waitTime);
		}
		if(logMINOR) Logger.minor(this, "Delay times: realtime: local="+nlmDelayRTLocal.currentValue()+" remote = "+nlmDelayRTRemote.currentValue()+
				" bulk: local="+nlmDelayBulkLocal.currentValue()+" remote="+nlmDelayBulkRemote.currentValue());
	}

	public void drawNewLoadManagementDelayTimes(HTMLNode content) {
		int[] waitingSlots = node.requestTracker.countRequestsWaitingForSlots();
		content.addChild("p").addChild("#", l10n("slotsWaiting", new String[] { "local", "remote" }, new String[] { Integer.toString(waitingSlots[0]), Integer.toString(waitingSlots[1]) }));
		HTMLNode table = content.addChild("table", "border", "0");
		HTMLNode header = table.addChild("tr");
		header.addChild("th", l10n("delayTimes"));
		header.addChild("th", l10n("localHeader"));
		header.addChild("th", l10n("remoteHeader"));
		HTMLNode row = table.addChild("tr");
		row.addChild("th", l10n("realTimeHeader"));
		row.addChild("td", TimeUtil.formatTime((int)nlmDelayRTLocal.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((int)nlmDelayRTRemote.currentValue(), 2, true));
		row = table.addChild("tr");
		row.addChild("th", l10n("bulkHeader"));
		row.addChild("td", TimeUtil.formatTime((int)nlmDelayBulkLocal.currentValue(), 2, true));
		row.addChild("td", TimeUtil.formatTime((int)nlmDelayBulkRemote.currentValue(), 2, true));
		
		synchronized(slotTimeoutsSync) {
			if(fatalTimeoutsInWaitLocal + fatalTimeoutsInWaitRemote + 
					allocatedSlotLocal + allocatedSlotRemote > 0) {
				content.addChild("b", l10n("timeoutFractions"));
				table = content.addChild("table", "border", "0");
				header = table.addChild("tr");
				header.addChild("th", l10n("localHeader"));
				header.addChild("th", l10n("remoteHeader"));
				row = table.addChild("tr");
				row.addChild("td", this.fix3p3pct.format(((double)fatalTimeoutsInWaitLocal)/((double)(fatalTimeoutsInWaitLocal + allocatedSlotLocal))));
				row.addChild("td", this.fix3p3pct.format(((double)fatalTimeoutsInWaitRemote)/((double)(fatalTimeoutsInWaitRemote + allocatedSlotRemote))));
			}
		}
	}

	private Object slotTimeoutsSync = new Object();
	private long fatalTimeoutsInWaitLocal;
	private long fatalTimeoutsInWaitRemote;
	private long allocatedSlotLocal;
	private long allocatedSlotRemote;
	
	public void reportFatalTimeoutInWait(boolean local) {
		synchronized(slotTimeoutsSync) {
			if(local)
				fatalTimeoutsInWaitLocal++;
			else
				fatalTimeoutsInWaitRemote++;
		}
	}

	public void reportAllocatedSlot(boolean local) {
		synchronized(slotTimeoutsSync) {
			if(local)
				allocatedSlotLocal++;
			else
				allocatedSlotRemote++;
		}
	}
	
	
	public boolean enableNewLoadManagement(boolean realTimeFlag) {
		return realTimeFlag ? enableNewLoadManagementRT : enableNewLoadManagementBulk;
	}

	public double routingMissDistanceLocal() {
		return routingMissDistanceLocal.currentValue();
	}
	
	public double routingMissDistanceRemote() {
		return routingMissDistanceRemote.currentValue();
	}
	
	public double routingMissDistanceOverall() {
		return routingMissDistanceOverall.currentValue();
	}
	
	public double routingMissDistanceBulk() {
		return routingMissDistanceBulk.currentValue();
	}
	
	public double routingMissDistanceRT() {
		return routingMissDistanceRT.currentValue();
	}
	
	public double backedOffPercent() {
		return backedOffPercent.currentValue();
	}

	public int[] getCHKSuccessRatesByLocationPercentageArray(int size) {
		return chkSuccessRatesByLocation.getPercentageArray(size);
	}

	public void setMaxStoreKeys(long store, long cache) {
		int maxStoreKeys, maxCacheKeys;
		if(store > Integer.MAX_VALUE) {
			// FIXME ARBITRARY LIMITS
			Logger.warning(this, "Stats may be inaccurate: Store locations stats only support "+Integer.MAX_VALUE+" keys (store)");
			maxStoreKeys = Integer.MAX_VALUE;
		} else maxStoreKeys = (int) store;
		if(cache > Integer.MAX_VALUE) {
			// FIXME ARBITRARY LIMITS
			Logger.warning(this, "Stats may be inaccurate: Store locations stats only support "+Integer.MAX_VALUE+" keys (cache)");
			maxCacheKeys = Integer.MAX_VALUE;
		} else maxCacheKeys = (int) cache;
		avgStoreCHKLocation.changeMaxReports((int)maxStoreKeys);
		avgCacheCHKLocation.changeMaxReports((int)maxCacheKeys);
		avgSlashdotCacheCHKLocation.changeMaxReports((int)maxCacheKeys);
		avgClientCacheCHKLocation.changeMaxReports((int)maxCacheKeys);

		avgStoreSSKLocation.changeMaxReports((int)maxStoreKeys);
		avgCacheSSKLocation.changeMaxReports((int)maxCacheKeys);
		avgSlashdotCacheSSKLocation.changeMaxReports((int)maxCacheKeys);
		avgClientCacheSSKLocation.changeMaxReports((int)maxCacheKeys);
	}
	
	// FIXME these should be broken into separate objects for each store/cache type???
	// FIXME there are similar objects already (see node.stats) ... they just need extending ...

	/**
	 * Report a successful fetch from the client-cache.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportClientCacheSSKSuccess(double loc, double dist) {
		avgClientCacheSSKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestClientCacheSSKSuccess)
				furthestClientCacheSSKSuccess=dist;
		}
	}

	/**
	 * Report a successful fetch from the slashdot cache.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportSlashdotCacheSSKSuccess(double loc, double dist) {
		avgSlashdotCacheSSKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestSlashdotCacheSSKSuccess)
				furthestSlashdotCacheSSKSuccess=dist;
		}
	}
	
	/**
	 * Report a successful fetch from the store.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportStoreSSKSuccess(double loc, double dist) {
		avgStoreSSKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestStoreSSKSuccess)
				furthestStoreSSKSuccess=dist;
		}
	}

	/**
	 * Report a successful fetch from the store.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportCacheSSKSuccess(double loc, double dist) {
		avgCacheSSKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestCacheSSKSuccess)
				furthestCacheSSKSuccess=dist;
		}
	}

	/**
	 * Report a successful fetch from the client-cache.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportClientCacheCHKSuccess(double loc, double dist) {
		avgClientCacheCHKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestClientCacheCHKSuccess)
				furthestClientCacheCHKSuccess=dist;
		}
	}

	/**
	 * Report a successful fetch from the slashdot cache.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportSlashdotCacheCHKSuccess(double loc, double dist) {
		avgSlashdotCacheCHKSucess.report(loc);
		synchronized(this) {
			if (dist > furthestSlashdotCacheCHKSuccess)
				furthestSlashdotCacheCHKSuccess=dist;
		}
	}
	
	/**
	 * Report a successful fetch from the store.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportStoreCHKSuccess(double loc, double dist) {
		avgStoreCHKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestStoreCHKSuccess)
				furthestStoreCHKSuccess=dist;
		}
	}

	/**
	 * Report a successful fetch from the store.
	 * @param loc The location of the key.
	 * @param dist The distance of the key from the node's location, at the
	 * time of reporting.
	 */
	public void reportCacheCHKSuccess(double loc, double dist) {
		avgCacheCHKSuccess.report(loc);
		synchronized(this) {
			if (dist > furthestCacheCHKSuccess)
				furthestCacheCHKSuccess=dist;
		}
	}

	public void reportClientCacheCHKLocation(double loc) {
		avgClientCacheCHKLocation.report(loc);
	}

	public void reportSlashdotCacheCHKLocation(double loc) {
		avgSlashdotCacheCHKLocation.report(loc);
	}

	public void reportCacheCHKLocation(double loc) {
		avgCacheCHKLocation.report(loc);
	}

	public void reportStoreCHKLocation(double loc) {
		avgStoreCHKLocation.report(loc);
	}

	public void reportClientCacheSSKLocation(double loc) {
		avgClientCacheSSKLocation.report(loc);
	}

	public void reportSlashdotCacheSSKLocation(double loc) {
		avgSlashdotCacheSSKLocation.report(loc);
	}

	public void reportCacheSSKLocation(double loc) {
		avgCacheSSKLocation.report(loc);
	}

	public void reportStoreSSKLocation(double loc) {
		avgStoreSSKLocation.report(loc);
	}

	public void reportLocalRequestBytes(boolean isSSK, int totalSentBytes,
			int totalReceivedBytes, boolean success) {
		(isSSK ? localSskFetchBytesSentAverage : localChkFetchBytesSentAverage).report(totalSentBytes);
		(isSSK ? localSskFetchBytesReceivedAverage : localChkFetchBytesReceivedAverage).report(totalReceivedBytes);
		if(success)
			// See comments above declaration of successful* : We don't report sent bytes here.
			//nodeStats.successfulChkFetchBytesSentAverage.report(rs.getTotalSentBytes());
			(isSSK ? successfulSskFetchBytesReceivedAverage : successfulChkFetchBytesReceivedAverage).report(totalReceivedBytes);
	}
	
	public void reportRemoteRequestBytes(boolean isSSK, int sent,
			int rcvd, boolean success) {
		if(isSSK) {
			remoteSskFetchBytesSentAverage.report(sent);
			remoteSskFetchBytesReceivedAverage.report(rcvd);
			if(success) {
				// Can report both parts, because we had both a Handler and a Sender
				successfulSskFetchBytesSentAverage.report(sent);
				successfulSskFetchBytesReceivedAverage.report(rcvd);
			}
		} else {
			remoteChkFetchBytesSentAverage.report(sent);
			remoteChkFetchBytesReceivedAverage.report(rcvd);
			if(success) {
				// Can report both parts, because we had both a Handler and a Sender
				successfulChkFetchBytesSentAverage.report(sent);
				successfulChkFetchBytesReceivedAverage.report(rcvd);
			}
		}
	}

	public void reportLocalInsertBytes(boolean isSSK, int totalSentBytes,
			int totalReceivedBytes, boolean success) {
		(isSSK ? localSskInsertBytesSentAverage : localChkInsertBytesSentAverage).report(totalSentBytes);
		(isSSK ? localSskInsertBytesReceivedAverage : localChkInsertBytesReceivedAverage).report(totalReceivedBytes);
		if(success) {
			// Only report Sent bytes because we did not receive the data.
			(isSSK ? successfulSskInsertBytesSentAverage : successfulChkInsertBytesSentAverage).report(totalSentBytes);
		}
	}
	
	public void reportRemoteInsertBytes(boolean isSSK, int totalSent,
			int totalReceived, boolean reportSuccessSent, boolean reportSuccessReceived) {
    	(isSSK ? remoteSskInsertBytesSentAverage : remoteChkInsertBytesSentAverage).report(totalSent);
    	(isSSK ? remoteSskInsertBytesReceivedAverage : remoteChkInsertBytesReceivedAverage).report(totalReceived);
    	if(reportSuccessSent) {
    		(isSSK ? successfulSskInsertBytesSentAverage : successfulChkInsertBytesSentAverage).report(totalSent);
    	}
    	if(reportSuccessReceived) {
    		(isSSK ? successfulSskInsertBytesReceivedAverage : successfulChkInsertBytesReceivedAverage).report(totalReceived);
    	}
	}

	public void reportBackedOffPercent(double backedOff) {
		this.backedOffPercent.report(backedOff);
	}

	public void reportRoutingMiss(boolean isLocal, boolean realTime,
			double distance) {
		routingMissDistanceOverall.report(distance);
		(isLocal ? routingMissDistanceLocal : routingMissDistanceRemote).report(distance);
		(realTime ? routingMissDistanceRT : routingMissDistanceBulk).report(distance);
	}

	public boolean wantBWLimitAlert() {
		return bwlimitDelayAlertRelevant;
	}

	public boolean wantAveragePingAlert() {
		return nodeAveragePingAlertRelevant;
	}

	public void reportRequest(double loc) {
		avgRequestLocation.report(loc);
	}

}