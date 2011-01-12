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
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.archive.modules.CrawlURI;
import org.archive.modules.extractor.HTMLLinkContext;
import org.archive.modules.extractor.LinkContext;
import org.archive.net.UURI;
import org.archive.net.UURIFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * remote crawl headquarters client talking in HTTP.
 * @contributor kenji
 */
public class HttpHeadquarterAdapter {
    private static final Logger logger = Logger.getLogger(HttpHeadquarterAdapter.class.getName());

    private HttpClient httpClient;
    
    public HttpHeadquarterAdapter() {
        MultiThreadedHttpConnectionManager hcm = new MultiThreadedHttpConnectionManager();
        this.httpClient = new HttpClient(hcm);
    }

    private String baseURL = "http://localhost/hq";
    public void setBaseURL(String baseURL) {
        this.baseURL = baseURL;
    }
    public String getBaseURL() {
        return baseURL;
    }
    
    protected String getFinishedURL() {
        StringBuilder sb = new StringBuilder(baseURL);
        if (!baseURL.endsWith("/"))
            sb.append("/");
        sb.append("finished");
        return sb.toString();
    }
    protected String getDiscoveredURL() {
        StringBuilder sb = new StringBuilder(baseURL);
        if (!baseURL.endsWith("/"))
            sb.append("/");
        sb.append("discovered");
        return sb.toString();
    }
    protected String getFeedURL(int nodeNo, int totalNodes) {
        StringBuilder sb = new StringBuilder(baseURL);
        if (!baseURL.endsWith("/"))
            sb.append("/");
        sb.append("feed");
        sb.append("?name=");
        sb.append(nodeNo);
        sb.append("&nodes=");
        sb.append(totalNodes);
        return sb.toString();
    }
    /**
     * send finished URI to the Headquaters.
     * {@code content-digest}, {@code etag}, {@code last-modified} are sent along with the URI.
     * As original values are obtained from {@link CrawlURI} for these, it is not necessary to
     * copy these values in persistentDataMap before calling this method.
     * @param uri CrawlURI to send
     */
    public void finished(CrawlURI uri) {
        PostMethod post = new PostMethod(getFinishedURL());
        try {
            JSONObject data = new JSONObject();
            // not using CrawlURI#getPersistentDataMap() for efficiency
            // it does not include content-digest and last-modified anyway,
            // unless FetchHistoryProcessor is applied prior to this processor.
            // as we're only interested in content-digest, etag, and last-modified, 
            // use of FetchHistoryProcessor seems expensive.
            String digest = uri.getContentDigestSchemeString();
            if (digest != null) {
                data.put(RecrawlAttributeConstants.A_CONTENT_DIGEST, digest);
            }
            HttpMethod method = uri.getHttpMethod();
            Header etagHeader = method.getResponseHeader(RecrawlAttributeConstants.A_ETAG_HEADER);
            if (etagHeader != null) {
                data.put(RecrawlAttributeConstants.A_ETAG_HEADER, etagHeader.getValue());
            }
            Header lastmodHeader = method.getResponseHeader(RecrawlAttributeConstants.A_LAST_MODIFIED_HEADER);
            if (lastmodHeader != null) {
                data.put(RecrawlAttributeConstants.A_LAST_MODIFIED_HEADER, lastmodHeader.getValue());
            }
            post.addParameter("uri", uri.getURI());
            post.addParameter("data", data.toString());
            httpClient.executeMethod(post);
            String response = post.getResponseBodyAsString();
            if (logger.isLoggable(Level.FINE))
                logger.fine("finished(" + uri + ")->" + response);
        } catch (JSONException ex) {
            logger.warning(uri + " not stored due to an error: " + ex);
        } catch (HttpException ex) {
            logger.warning(uri + " not stored due to an error: " + ex);
        } catch (IOException ex) {
            logger.warning("error storing " + uri + ":" + ex);
        } finally {
            post.releaseConnection();
        }
    }
    /**
     * send URIs discovered by link extraction to the Headquarters.
     * @param uri
     */
    public void discovered(CrawlURI uri) {
        PostMethod post = new PostMethod(getDiscoveredURL());
        try {
            // the same set of parameters as output of HashCrawlMapper
            post.addParameter("uri", uri.getURI());
            post.addParameter("path", uri.getPathFromSeed());
            UURI via = uri.getVia();
            if (via != null) {
                post.addParameter("via", via.getURI());
            }
            LinkContext context = uri.getViaContext();
            if (context != null) {
                // only HTMLLinkContext is supported for now
                if (context instanceof HTMLLinkContext) {
                    post.addParameter("context", context.toString());
                }
            }
            httpClient.executeMethod(post);
            String response = post.getResponseBodyAsString();
            if (logger.isLoggable(Level.FINE))
                logger.fine("discovered(" + uri + ")->" + response);
        } catch (HttpException ex) {
            logger.warning(uri + " not stored due to an error: " + ex);
        } catch (IOException ex) {
            logger.warning("error storing " + uri + ":" + ex);
        } finally {
            post.releaseConnection();
        }
    }
    
    public CrawlURI[] getCrawlURIs(int nodeNo, int totalNodes) {
        GetMethod get = new GetMethod(getFeedURL(nodeNo, totalNodes));
        try {
            httpClient.executeMethod(get);
            if (get.getStatusCode() == 200) {
                JSONArray array = new JSONArray(new JSONTokener(get.getResponseBodyAsString()));
                CrawlURI[] result = new CrawlURI[array.length()];
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    String uri = o.getString("uri");
                    String path = o.getString("path");
                    String via = o.optString("via");
                    String context = o.optString("context");
                    UURI uuri = UURIFactory.getInstance(uri);
                    UURI viaUuri = null;
                    if (via != null && via.length() > 0) {
                        viaUuri = UURIFactory.getInstance(via);
                    }
                    LinkContext viaContext = null;
                    if (context != null && context.length() > 0) {
                        // ref. CrawlURI#fromHopsViaString(String)
                        viaContext = new HTMLLinkContext(context);
                    }
                    CrawlURI curi = new CrawlURI(uuri, path, viaUuri, viaContext);
                    // content-digest and last-modified comes in data object.
                    JSONObject data = o.optJSONObject("data");
                    if (data != null) {
                        @SuppressWarnings("unchecked")
                        Iterator<String> keys = data.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            curi.getData().put(key, data.optString(key));
                        }
                    }
                    result[i] = curi;
                }
                return result;
            } else {
                return new CrawlURI[0];
            }
        } catch (Exception ex) {
            logger.warning("error getting CrawlURIs: " + ex);
            return new CrawlURI[0];
        }
    }
    
}
