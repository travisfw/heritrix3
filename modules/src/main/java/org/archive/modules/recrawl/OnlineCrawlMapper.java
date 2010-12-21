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
package org.archive.modules.recrawl;

import java.io.File;
import java.util.logging.Logger;

import org.archive.crawler.datamodel.UriUniqFilter;
import org.archive.crawler.datamodel.UriUniqFilter.CrawlUriReceiver;
import org.archive.crawler.framework.Frontier;
import org.archive.modules.CrawlURI;

/**
 * {@link OnlineCrawlMapper} communicates with central <i>crawl headquarter</i>
 * server to pass around URIs among crawl nodes working on the same crawl job.
 * <p>combined with {@link HttpPersistProcessor}, {@link OnlineCrawlMapper} can support de-duplication
 * based on last-modified and content-digest.</p>
 * <p>{@link OnlineCrawlMapper} route all {@link CrawlURI} coming through {@link #add(String, CrawlURI)}
 * to central server. central server performs already-seen check and crawl mapping and dispatches URI
 * to each nodes participating in the crawl job.</p>
 * <p>URI is not dispensed to {@link CrawlUriReceiver} ({@link Frontier}) until explicitly requested with
 * {@link #requestFlush()}.</p>
 * <p>server communication is delegated to {@link HttpHeadquarterAdapter} and it talks to server over HTTP.
 * I should define an interface so that different (more efficient) base protocol may be used and for unit
 * test purposes.</p>
 * @see HttpPersistProcessor
 * @contributor kenji
 */
public class OnlineCrawlMapper implements UriUniqFilter {
    private static final Logger logger = Logger.getLogger(OnlineCrawlMapper.class.getName());
    
    private HttpHeadquarterAdapter client;
    
    private String localName;
    
    protected CrawlUriReceiver receiver;

    public OnlineCrawlMapper() {
    }
    
    public void setClient(HttpHeadquarterAdapter client) {
        this.client = client;
    }
    public void setDestination(CrawlUriReceiver receiver) {
        this.receiver = receiver;
    }
    public String getLocalName() {
        return localName;
    }
    /**
     * name that uniquely identify this node within crawl job cluster.
     * <p>most often a number, but can be arbitrary text</p>
     * @param localName string
     */
    public void setLocalName(String localName) {
        this.localName = localName;
    }

    /**
     * {@inheritDoc}
     * this implementation makes no use of <code>key</code>.
     * this method sends all CrawlURI to headquarters except prerequisites.
     */
    @Override
    public void add(String key, CrawlURI curi) {
        // send all prerequisites (such as DNS lookup) to Frontier.
        if (curi.isPrerequisite()) {
            receiver.receive(curi);
        }
        // send all CrawlURI to the central server
        client.discovered(curi);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public long requestFlush() {
        CrawlURI[] uris = client.getCrawlURIs(localName);
        long count = 0;
        for (CrawlURI uri : uris) {
            if (uri != null) {
                receiver.receive(uri);
                ++count;
            }
        }
        return count;
    }

    @Override
    public void addForce(String key, CrawlURI value) {
        add(key, value);
    }

    @Override
    public void addNow(String key, CrawlURI value) {
        add(key, value);
    }

    @Override
    public void close() {
        // nothing's necessary.
    }

    /**
     * not really implemented. always returns 0.
     */
    @Override
    public long count() {
        // TODO Auto-generated method stub
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forget(String key, CrawlURI value) {
        // TODO Auto-generated method stub
        // this method is called by WorkQueueFrontier#forget(CrawlURI), but that method
        // is not used anywhere in java code. probably for use via bean shell?
        // anyway no implementation since headquarter is supposed to provide this functionality.
    }

    @Override
    public void note(String key) {
        // TODO Auto-generated method stub
        // used in WorkQueueFrontier#considerIncluded(CrawlURI), which is used in
        // AbstractFrontier#importRecoverFormat(File, boolean, boolean, String).
        // considerIncluded() passes CrawlURI.getCanonicalString() as key.
        // For it's supposed we no longer need local recovery file in cluster configuration with
        // headquarter, this method is unimplemented.
    }

    /**
     * {@inheritDoc}
     * always returns 0. used by org.archive.crawler.frontier.WorkQueueFrontier to
     * determine if crawl reached its end.
     */
    @Override
    public long pending() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setProfileLog(File logfile) {
        // TODO Auto-generated method stub
        // currently unimplemented.
    }
    
}
