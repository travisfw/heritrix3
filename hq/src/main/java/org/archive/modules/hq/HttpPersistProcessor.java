/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.modules.hq;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.archive.modules.CrawlURI;
import org.archive.modules.Processor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

/**
 * HttpPersistProcessor notifies remote crawl manager of the completion of 
 * crawling one URI, along with metadata fetched, such as last-modified and
 * content-digest, through HTTP.
 * @contributor kenji
 */
public class HttpPersistProcessor extends Processor implements Lifecycle {
    private static final Logger logger = Logger.getLogger(HttpPersistProcessor.class.getName());
    
    public static final long RETRY_INTERVAL_MS = 30*1000;

    HttpHeadquarterAdapter client;
    
    protected final BlockingQueue<CrawlURI> finishedQueue;
    protected int finishedBatchSize = 40;
    protected int finishedBatchSizeMargin = 30;
    protected Thread finishedFlushThread;
    
    boolean running;
    
    public void setFinishedBatchSize(int finishedBatchSize) {
        this.finishedBatchSize = finishedBatchSize;
    }
    public int getFinishedBatchSize() {
        return finishedBatchSize;
    }
    public void setFinishedBatchSizeMargin(int finishedBatchSizeMargin) {
        this.finishedBatchSizeMargin = finishedBatchSizeMargin;
    }
    public int getFinishedBatchSizeMargin() {
        return finishedBatchSizeMargin;
    }
    
    public class Submitter implements Runnable {
        @Override
        public void run() {
            CrawlURI[] bucket = new CrawlURI[finishedBatchSize + finishedBatchSizeMargin];
            while (running || !finishedQueue.isEmpty()) {
                Arrays.fill(bucket, null);
                for (int i = 0; i < bucket.length; i++) {
                    try {
                        long wait = i < finishedBatchSize ? 2 : 0;
                        CrawlURI curi = finishedQueue.poll(wait, TimeUnit.SECONDS);
                        if (curi == null) break;
                        bucket[i] = curi;
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
                if (bucket[0] != null) {
                    int retries = 0;
                    while (true) {
                        if (logger.isLoggable(Level.FINE)) {
                            if (retries > 0)
                                logger.fine("submitting finished (retry " + retries + ")");
                            else
                                logger.fine("submitting finished");
                        }
                        long t0 = System.currentTimeMillis();
                        try {
                            client.mfinished(bucket);
                            if (logger.isLoggable(Level.FINE))
                                logger.fine("submission done in "
                                        + (System.currentTimeMillis() - t0)
                                        + "ms, finishedQueue.size="
                                        + finishedQueue.size());
                            break;
                        } catch (IOException ex) {
                            logger.warning("submitting finished failed: " + ex);
                        }
                        if (!running) {
                            logger.severe("finished did not complete, lost URLs:");
                            int i = 0;
                            for (CrawlURI curi : bucket) {
                                if (curi == null) continue;
                                i++;
                                logger.severe("  " + i + ":" + curi.getURI());
                            }
                            break;
                        }
                        retries++;
                        try {
                            Thread.sleep(RETRY_INTERVAL_MS);
                        } catch (InterruptedException ex) {
                        }
                    }
                }
            }
        }
    }
    
    public HttpPersistProcessor() {
        this.finishedQueue = new LinkedBlockingQueue<CrawlURI>(500);
    }
    
    @Autowired(required=true)
    public void setClient(HttpHeadquarterAdapter client) {
        this.client = client;
    }

    @Override
    protected void innerProcess(CrawlURI uri) throws InterruptedException {
        if (running) {
            // flush thread is ative. queue and leave.
            try {
                finishedQueue.put(uri);
            } catch (InterruptedException ex) {
                // TODO: do not lose CrawlURI!
            }
        } else {
            // submit CrawlURI one by one during shutdown.
            client.finished(uri);
        }
    }

    /**
     * {@inheritDoc}
     * 
     */
    @Override
    protected boolean shouldProcess(CrawlURI curi) {
        // prerequisite URIs such as DNS, robots.txt are internal. they need not be reported
        // to HQ.
        if (curi.isPrerequisite()) return false;
        // TODO do we want to store other non-success codes, such as 304?
        String scheme = curi.getUURI().getScheme();
        if (!(scheme.equals("http") || scheme.equals("https"))) return false;
        if (curi.getHttpMethod() == null) return false;
        // && curi.isSuccess();
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
    
    public void start() {
        if (running) return;
        running = true;
        finishedFlushThread = new Thread(new Submitter());
        finishedFlushThread.start();
    };
    
    @Override
    public void stop() {
        running = false;
        finishedFlushThread = null;
    }
}
