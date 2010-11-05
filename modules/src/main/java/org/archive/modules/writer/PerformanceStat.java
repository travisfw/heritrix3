package org.archive.modules.writer;

/**
 * PerformanceStat takes time information for each occurrence and
 * maintains min, max and average for fixed time interval.
 * <p>in current implementation, time interval is hard-coded as 5 minutes.</p>
 * <p>statistics could be out-of-date up to the time interval. better implementation
 * would record numbers in smaller time interval internally and compute output
 * statistics combining multiple records.</p>
 * @contributor kenji
 */
public class PerformanceStat {
    /**
     * fixed time interval for computing min, max and average, in milliseconds.
     */
    public static int INTERVAL = 300000; // 5-min
    public static class Record {
        public long totalWait;
        public long maxWait;
        public long minWait;
        public long totalProcess;
        public long maxProcess;
        public long minProcess;
        public int count;
        public Record() {
            clear();
        }
        public Record(Record pr) {
            this.totalWait = pr.totalWait;
            this.maxWait = pr.maxWait;
            this.minWait = pr.minWait;
            this.totalProcess = pr.totalProcess;
            this.maxProcess = pr.maxProcess;
            this.minProcess = pr.minProcess;
            this.count = pr.count;            
        }
        public void clear() {
            totalWait = 0;
            totalProcess = 0;
            maxWait = 0;
            minWait = Long.MAX_VALUE;
            maxProcess = 0;
            minProcess = Long.MAX_VALUE;
            count = 0;
        }
        public void update(long wait, long process) {
            totalWait += wait;
            totalProcess += process;
            if (wait > maxWait) maxWait = wait;
            if (wait < minWait) minWait = wait;
            if (process > maxProcess) maxProcess = process;
            if (process < minProcess) minProcess = process;
            count++;
        }
        public double getAverageWait() {
            return count > 0 ? (double)totalWait / count : 0;
        }
        public double getAverageProcess() {
            return count > 0 ? (double)totalProcess / count : 0;
        }
    }
    private Record[] stats;
    /**
     * indicates which row is currently updated.
     * (i.e. one before this one has the latest complete number).
     */
    int recordIndex = 0;
    /**
     * start time for current record (indexed by recordIndex).
     */
    long baseTime = 0;
    public PerformanceStat() {
        stats = new Record[] {
                new Record(),
                new Record()
        };
    }
    public synchronized void update(long wait, long process) {
        long now = System.currentTimeMillis();
        if (baseTime == 0) {
            baseTime = now;
            recordIndex = 0;
        } else {
            while (now > baseTime + INTERVAL) {
                if (++recordIndex >= stats.length) recordIndex = 0;
                stats[recordIndex].clear();
                baseTime += INTERVAL;
            }
        }
        stats[recordIndex].update(wait, process);
    }
    /**
     * return a copy of latest complete statistics.
     * data is old by up to the time interval.
     * @return
     */
    public synchronized Record getRecord() {
        int i = recordIndex - 1;
        if (i < 0) i = stats.length - 1;
        return new Record(stats[i]);
    }
}
