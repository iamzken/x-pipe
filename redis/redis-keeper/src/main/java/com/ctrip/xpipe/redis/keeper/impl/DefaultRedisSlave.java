package com.ctrip.xpipe.redis.keeper.impl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.xpipe.api.lifecycle.Releasable;
import com.ctrip.xpipe.api.observer.Observer;
import com.ctrip.xpipe.api.server.PARTIAL_STATE;
import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;
import com.ctrip.xpipe.netty.filechannel.ReferenceFileRegion;
import com.ctrip.xpipe.redis.core.protocal.CAPA;
import com.ctrip.xpipe.redis.core.protocal.protocal.EofType;
import com.ctrip.xpipe.redis.keeper.RedisClient;
import com.ctrip.xpipe.redis.keeper.RedisKeeperServer;
import com.ctrip.xpipe.redis.keeper.RedisSlave;
import com.ctrip.xpipe.redis.keeper.SLAVE_STATE;
import com.ctrip.xpipe.redis.keeper.exception.RedisKeeperRuntimeException;
import com.ctrip.xpipe.utils.ChannelUtil;
import com.ctrip.xpipe.utils.ClusterShardAwareThreadFactory;
import com.ctrip.xpipe.utils.IpUtils;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

/**
 * @author wenchao.meng
 *
 * May 20, 2016 4:34:09 PM
 */
public class DefaultRedisSlave implements RedisSlave {
	
	private final static Logger logger = LoggerFactory.getLogger(DefaultRedisSlave.class);
	
	public static final String KEY_RDB_DUMP_MAX_WAIT_MILLI = "rdbDumpMaxWaitMilli";
	
	private Long replAckOff;
	
	private Long replAckTime = System.currentTimeMillis();

	private SLAVE_STATE  slaveState;
	
	private PARTIAL_STATE partialState = PARTIAL_STATE.UNKNOWN;
	
	private Long rdbFileOffset;
	private EofType eofType;
		
	private ScheduledExecutorService scheduled;
	private ScheduledFuture<?> 		  pingFuture, waitDumpTimeoutFuture;
	private final int pingIntervalMilli = 1000;
	private final int rdbDumpMaxWaitMilli = Integer.parseInt(System.getProperty(KEY_RDB_DUMP_MAX_WAIT_MILLI, "1800000"));//half an hour
	
	private volatile boolean putOnLineOnAck = false; 

	private ExecutorService psyncExecutor;

	private RedisClient redisClient;
	
	private ChannelFutureListener writeExceptionListener = new ChannelFutureListener() {
		
		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			
			if(!future.isSuccess()){
				logger.error("[operationComplete][write fail]" + DefaultRedisSlave.this, future.cause());
			}
		}
	};
	
	private AtomicBoolean closed = new AtomicBoolean(false);
	
	public DefaultRedisSlave(RedisClient redisClient){
		this.redisClient = redisClient;
		this.setSlaveListeningPort(redisClient.getSlaveListeningPort());
		this.redisClient.addChannelCloseReleaseResources(this);
		initExecutor(((DefaultRedisClient)redisClient).channel);
	}

	private void initExecutor(Channel channel) {
		
		String getRemoteIpLocalPort = ChannelUtil.getRemoteAddr(channel);
		String threadPrefix = "RedisClientPsync-" + getRemoteIpLocalPort;
		String clusterId = redisClient.getRedisKeeperServer().getClusterId();
		String shardId = redisClient.getRedisKeeperServer().getShardId();
		psyncExecutor = Executors.newSingleThreadExecutor(ClusterShardAwareThreadFactory.create(clusterId, shardId, threadPrefix));
		scheduled = Executors.newScheduledThreadPool(1, ClusterShardAwareThreadFactory.create(clusterId, shardId, threadPrefix));
	}

	@Override
	public void waitForRdbDumping() {
		
		if(this.slaveState == SLAVE_STATE.REDIS_REPL_WAIT_RDB_DUMPING){
			logger.info("[waitForRdbDumping][already waiting]{}", this);
			return;
		}
		
		this.slaveState = SLAVE_STATE.REDIS_REPL_WAIT_RDB_DUMPING;
		
		logger.info("[waitForRdbDumping][begin ping]{}", this);
		pingFuture = scheduled.scheduleAtFixedRate(new Runnable() {
			
			@Override
			public void run() {
				try{
					sendMessage("\n".getBytes());
				}catch(Exception e){
					logger.error("[run][sendPing]" + redisClient, e);
				}
			}
		}, pingIntervalMilli, pingIntervalMilli, TimeUnit.MILLISECONDS);
		
		waitDumpTimeoutFuture = scheduled.schedule(new AbstractExceptionLogTask() {
			
			@Override
			protected void doRun() throws IOException {
				logger.info("[waitForRdbDumping][timeout][close slave]{}", DefaultRedisSlave.this);
				close();
			}
		}, rdbDumpMaxWaitMilli, TimeUnit.MILLISECONDS);
	}

	@Override
	public SLAVE_STATE getSlaveState() {
		return this.slaveState;
	}

	@Override
	public void ack(Long ackOff) {
		
		if(logger.isDebugEnabled()){
			logger.debug("[ack]{}, {}", this , ackOff);
		}
		
		if(putOnLineOnAck){
			
			putOnLineOnAck = false;
			logger.info("[ack][put slave online]{}", this);
			sendCommandForFullSync();
		}
		
		this.replAckOff = ackOff;
		this.replAckTime = System.currentTimeMillis();
	}

	@Override
	public ChannelFuture writeFile(ReferenceFileRegion referenceFileRegion) {
		
		return doWriteFile(referenceFileRegion);
	}

	private ChannelFuture doWriteFile(ReferenceFileRegion referenceFileRegion) {
		
		ChannelFuture future = channel().writeAndFlush(referenceFileRegion);
		future.addListener(writeExceptionListener);
		return future;
	}

	@Override
	public Long getAck() {
		return this.replAckOff;
	}

	@Override
	public Long getAckTime() {
		return this.replAckTime;
	}
	
	@Override
	public void beginWriteRdb(EofType eofType, long rdbFileOffset) {
		
		logger.info("[beginWriteRdb]{}, {}", eofType, rdbFileOffset);
		
		if(!eofType.support(getCapas())){
			logger.warn("[beginWriteRdb][eoftype not supported]{}, {}, {}", this, eofType, getCapas());
		}
		
		partialState = PARTIAL_STATE.FULL;
		slaveState = SLAVE_STATE.REDIS_REPL_SEND_BULK;
		
		this.rdbFileOffset = rdbFileOffset;
		this.eofType = eofType;
		
		putOnLineOnAck = eofType.putOnLineOnAck();
		
		cancelWaitRdb();
		
    	channel().writeAndFlush(eofType.getStart());
	}

	
	@Override
	public void rdbWriteComplete() {
		
		logger.info("[rdbWriteComplete]{}", this);
		
		ByteBuf end = eofType.getEnd();
		if(end != null){
			channel().writeAndFlush(end);
		}
		

		if(slaveState == SLAVE_STATE.REDIS_REPL_SEND_BULK){
			if(logger.isInfoEnabled()){
				logger.info("[writeComplete][rdbWriteComplete]" + this);
			}
		}
		this.slaveState = SLAVE_STATE.REDIS_REPL_ONLINE;
		
		if(!putOnLineOnAck){
			sendCommandForFullSync();
		}
	}

	
	private void cancelWaitRdb() {
		
		if(pingFuture != null){
			logger.info("[cancelWaitRdb][cancel ping]{}", this);
			pingFuture.cancel(true);
		}
		if(waitDumpTimeoutFuture != null){
			logger.info("[cancelWaitRdb][cancel wait dump rdb]{}", this);
			waitDumpTimeoutFuture.cancel(true);
		}
	}

	@Override
	public void beginWriteCommands(long beginOffset) {
		
		try {
			if(partialState == PARTIAL_STATE.UNKNOWN){
				partialState = PARTIAL_STATE.PARTIAL;
			}
			logger.info("[beginWriteCommands]{}, {}", this, beginOffset);
			slaveState = SLAVE_STATE.REDIS_REPL_ONLINE;
			getRedisKeeperServer().getReplicationStore().addCommandsListener(beginOffset, this);
		} catch (IOException e) {
			throw new RedisKeeperRuntimeException("[beginWriteCommands]" + beginOffset + "," + this, e);
		}
	}


	protected void sendCommandForFullSync() {
		
		logger.info("[sendCommandForFullSync]{}, {}", this, rdbFileOffset +1);
		
		processPsyncSequentially(new AbstractExceptionLogTask() {
			
			@Override
			protected void doRun() throws Exception {
				beginWriteCommands(rdbFileOffset + 1);
			}
		});
	}

	@Override
	public ChannelFuture onCommand(ReferenceFileRegion referenceFileRegion) {
		
		logger.debug("[onCommand]{}, {}", this, referenceFileRegion);
		return doWriteFile(referenceFileRegion);
	}

	@Override
	public String info() {
		
		String info = "";
		long lag = System.currentTimeMillis() - replAckTime;
		info = String.format(
				"ip=%s,port=%d,state=%s,offset=%d,lag=%d,remotePort=%d" ,
				IpUtils.getIp(channel().remoteAddress()), getSlaveListeningPort(), 
				slaveState != null ? slaveState.getDesc() : "null",
				replAckOff, lag/1000, ((InetSocketAddress)channel().remoteAddress()).getPort());
		return info;
	}

	@Override
	public PARTIAL_STATE partialState() {
		return partialState;
	}

	@Override
	public void partialSync() {
		partialState = PARTIAL_STATE.PARTIAL;
	}

	@Override
	public void processPsyncSequentially(Runnable runnable) {
		psyncExecutor.execute(runnable);
	}

	@Override
	public boolean isOpen() {
		return !closed.get();
	}
	
	public void close() throws IOException {
		
		logger.info("[close]{}", this);
		closed.set(true);
		redisClient.close();
		psyncExecutor.shutdownNow();
		scheduled.shutdownNow();
	}
	
	@Override
	public void beforeCommand() {
	}
	
	
	// delegate methods start
	public void addObserver(Observer observer) {
		redisClient.addObserver(observer);
	}

	public void removeObserver(Observer observer) {
		redisClient.removeObserver(observer);
	}

	public RedisSlave becomeSlave() {
		return redisClient.becomeSlave();
	}

	public RedisKeeperServer getRedisKeeperServer() {
		return redisClient.getRedisKeeperServer();
	}

	public void setSlaveListeningPort(int port) {
		redisClient.setSlaveListeningPort(port);
	}

	public int getSlaveListeningPort() {
		return redisClient.getSlaveListeningPort();
	}

	public void capa(CAPA capa) {
		redisClient.capa(capa);
	}
	
	@Override
	public Set<CAPA> getCapas() {
		return redisClient.getCapas();
	}
	
	public String[] readCommands(ByteBuf byteBuf) {
		return redisClient.readCommands(byteBuf);
	}

	public Channel channel() {
		return redisClient.channel();
	}

	public void sendMessage(ByteBuf byteBuf) {
		redisClient.sendMessage(byteBuf);
	}

	public void sendMessage(byte[] bytes) {
		redisClient.sendMessage(bytes);
	}

	public void addChannelCloseReleaseResources(Releasable releasable) {
		redisClient.addChannelCloseReleaseResources(releasable);
	}

	public void processCommandSequentially(Runnable runnable) {
		redisClient.processCommandSequentially(runnable);
	}
	// delegate methods end
	
	@Override
	public String toString() {
		return this.redisClient.toString();
	}

	@Override
	public void release() throws Exception {
		logger.info("[release]{}", this);
		closed.set(true);
		psyncExecutor.shutdownNow();
	}

	@Override
	public boolean capaOf(CAPA capa) {
		return redisClient.capaOf(capa);
	}

	@Override
	public boolean isKeeper() {
		return redisClient.isKeeper();
	}

	@Override
	public void setKeeper() {
		redisClient.setKeeper();
	}

}
