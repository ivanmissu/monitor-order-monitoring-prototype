package com.monitor.sdk;

/** Reporter 运行状态快照；所有计数均自 reporter 创建后累加。 */
public final class ReporterStats {

    private final long submitted;
    private final long queued;
    private final long delivered;
    private final long retried;
    private final long permanentlyFailed;
    private final long dropped;
    private final int queueDepth;

    public ReporterStats(long submitted, long queued, long delivered, long retried,
                         long permanentlyFailed, long dropped, int queueDepth) {
        this.submitted = submitted;
        this.queued = queued;
        this.delivered = delivered;
        this.retried = retried;
        this.permanentlyFailed = permanentlyFailed;
        this.dropped = dropped;
        this.queueDepth = queueDepth;
    }

    public long getSubmitted() { return submitted; }
    public long getQueued() { return queued; }
    public long getDelivered() { return delivered; }
    public long getRetried() { return retried; }
    public long getPermanentlyFailed() { return permanentlyFailed; }
    public long getDropped() { return dropped; }
    public int getQueueDepth() { return queueDepth; }
}
