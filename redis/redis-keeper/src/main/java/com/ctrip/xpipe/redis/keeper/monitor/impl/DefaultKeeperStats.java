package com.ctrip.xpipe.redis.keeper.monitor.impl;

import java.util.concurrent.atomic.AtomicLong;

import com.ctrip.xpipe.redis.keeper.monitor.KeeperStats;

/**
 * @author wenchao.meng
 *
 * Feb 20, 2017
 */
public class DefaultKeeperStats implements KeeperStats{
	
	private AtomicLong fullSyncCount = new AtomicLong();
	
	private AtomicLong partialSyncCount = new AtomicLong();

	private AtomicLong partialSyncErrorCount = new AtomicLong();

	@Override
	public void increaseFullSync() {
		fullSyncCount.incrementAndGet();
	}

	@Override
	public long getFullSyncCount() {
		return fullSyncCount.get();
	}

	@Override
	public void increatePartialSync() {
		partialSyncCount.incrementAndGet();
	}

	@Override
	public long getPartialSyncCount() {
		return partialSyncCount.get();
	}

	@Override
	public void increatePartialSyncError() {
		partialSyncErrorCount.incrementAndGet();
	}

	@Override
	public long getPartialSyncErrorCount() {
		return partialSyncErrorCount.get();
	}
}
