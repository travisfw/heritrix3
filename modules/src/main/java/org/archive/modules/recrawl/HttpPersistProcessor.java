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

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.PostMethod;
import org.archive.modules.CrawlURI;
import org.archive.modules.Processor;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * HttpPersistProcessor notifies remote crawl manager of the completion of 
 * crawling one URI, along with metadata fetched, such as last-modified and
 * content-digest, throuh HTTP.
 * @contributor kenji
 */
public class HttpPersistProcessor extends Processor {
    private static final Logger logger = Logger.getLogger(HttpPersistProcessor.class.getName());

    HttpHeadquarterAdapter client;
    
    public HttpPersistProcessor() {
    }
    
    @Autowired(required=true)
    public void setClient(HttpHeadquarterAdapter client) {
        this.client = client;
    }

    @Override
    protected void innerProcess(CrawlURI uri) throws InterruptedException {
        client.finished(uri);
    }

    /**
     * {@inheritDoc}
     * 
     */
    @Override
    protected boolean shouldProcess(CrawlURI curi) {
        // TODO do we want to store other non-success codes, such as 304?
        String scheme = curi.getUURI().getScheme();
        return (scheme.equals("http") || scheme.equals("https")) && curi.isSuccess();
    }

}
