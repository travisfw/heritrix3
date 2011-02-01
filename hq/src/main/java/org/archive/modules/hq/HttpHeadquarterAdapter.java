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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.util.EntityUtils;
import org.archive.modules.CrawlMetadata;
import org.archive.modules.CrawlURI;
import org.archive.modules.extractor.HTMLLinkContext;
import org.archive.modules.extractor.LinkContext;
import org.archive.modules.recrawl.RecrawlAttributeConstants;
import org.archive.net.UURI;
import org.archive.net.UURIFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * remote crawl headquarters client talking in HTTP.
 * <p>This implementation uses Aache HTTP Component 4.0 instead of
 * Commons HttpClient, because heritrix-commons project includes a modified
 * copy of Commons HttpClient in which recording of request/response is hard-coded.
 * Using Commons HttpClient for Headquarter communication resulted in an error
 * because {@linkplain org.archive.util.Recorder recorder} was double-opened
 * in a thread. Of course it is just a waste of CPU cycle and IO to record Headquarter
 * communication.</p>
 * <p>Alternative approach would be to have a copy of original HttpConnection
 * with different class name and use modified version of MultiThreadedConnectionManager
 * which uses that original HttpConnection. Having a copy doesn't sound good.</p> 
 * 
 * @contributor kenji
 */
public class HttpHeadquarterAdapter {
    private static final Logger logger = Logger.getLogger(HttpHeadquarterAdapter.class.getName());

    // for feed
    private HttpClient httpClient1;
    // for finished and discovered
    private HttpClient httpClient2;
    
    // for getting job name
    protected CrawlMetadata crawlInfo;
    
    // URL cache
    private String finishedURL = null;
    private String discoveredURL = null;
    private String multiFinishedURL = null;
    private String multiDiscoveredURL = null;
    
    protected boolean useUrlEncodedForDiscovered = false;

    public HttpHeadquarterAdapter() {
        this.httpClient1 = new DefaultHttpClient();
        setupProtocolParams(this.httpClient1);
        this.httpClient2 = new DefaultHttpClient();
        setupProtocolParams(this.httpClient2);
    }
    
    private void setupProtocolParams(HttpClient client) {
        client.getParams().setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, false);
        client.getParams().setBooleanParameter(CoreProtocolPNames.WAIT_FOR_CONTINUE, false);
    }

    private String baseURL = "http://localhost/hq";
    public void setBaseURL(String baseURL) {
        this.baseURL = baseURL;
        // clear
        finishedURL = null;
        discoveredURL = null;
        multiFinishedURL = null;
        multiDiscoveredURL = null;
    }
    public String getBaseURL() {
        return baseURL;
    }
    @Autowired(required=true)
    public void setCrawlMetadata(CrawlMetadata crawlInfo) {
        this.crawlInfo = crawlInfo;
    }
    
    protected StringBuilder getURL(String action) {
        StringBuilder sb = new StringBuilder(baseURL);
        if (!baseURL.endsWith("/"))
            sb.append("/");
        sb.append(crawlInfo.getJobName());
        sb.append("/");
        sb.append(action);
        return sb;
    }
    protected String getFinishedURL() {
        if (finishedURL == null) {
            finishedURL = getURL("finished").toString();
        }
        return finishedURL;
    }
    protected String getDiscoveredURL() {
        if (discoveredURL == null) {
            discoveredURL = getURL("discovered").toString();
        }
        return discoveredURL;
    }
    protected String getMultiFinishedURL() {
        if (multiFinishedURL == null) {
            multiFinishedURL = getURL("mfinished").toString();
        }
        return multiFinishedURL;
    }
    protected String getMultiDiscoveredURL() {
        if (multiDiscoveredURL == null) {
            multiDiscoveredURL = getURL("mdiscovered").toString();
        }
        return multiDiscoveredURL;
    }
    protected String getFeedURL(int nodeNo, int totalNodes, int nuris) {
        StringBuilder sb = getURL("feed");
        sb.append("?name=");
        sb.append(nodeNo);
        sb.append("&nodes=");
        sb.append(totalNodes);
        sb.append("&n=" + nuris);
        return sb.toString();
    }
    protected String getResetURL(int nodeNo, int totalNodes) {
        StringBuilder sb = getURL("reset");
        sb.append("?name=").append(nodeNo).append("&nodes=").append(totalNodes);
        return sb.toString();
    }
    
    private static String getHeaderValue(org.apache.commons.httpclient.HttpMethod method, String name) {
        org.apache.commons.httpclient.Header header = method.getResponseHeader(name);
        return header != null ? header.getValue() : null;
    }
    
    public void mfinished(CrawlURI[] uris) {
        HttpPost post = new HttpPost(getMultiFinishedURL());
        try {
            JSONArray juris = new JSONArray();
            for (CrawlURI uri : uris) {
                // allow partially filled array
                if (uri == null) continue;
                JSONObject juri = new JSONObject();
                juri.put("uri", uri.getURI());
                juri.put("data", getFinishedData(uri));
                juris.put(juri);
            }
            // sending JSON text as entity for compactness
            // TODO: consider using BSON for even smaller payload
            // TODO: reuse ByteArrayOutputStream instead of creating anew every time?
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Writer w = new OutputStreamWriter(bytes, "UTF-8");
            juris.write(w);
            w.flush();
            HttpEntity entity = new ByteArrayEntity(bytes.toByteArray());
            post.setEntity(entity);
            post.addHeader("content-type", "text/json");
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("submitting " + juris.length() + " URIs to mfinished");
            }
            String responseText = null;
            synchronized (httpClient2) {
                HttpResponse response = httpClient2.execute(post);
                HttpEntity re = response.getEntity();
                responseText = EntityUtils.toString(re);
            }
            if (logger.isLoggable(Level.FINE))
                logger.fine("mfinished([...])->" + responseText);
            try {
                JSONObject jres = new JSONObject(new JSONTokener(responseText));
                int processed = jres.optInt("processed", 0);
                //int scheduled = jres.optInt("scheduled", 0);
                if (processed < juris.length()) {
                    logger.warning((uris.length - processed) + " of " + juris.length() + " not processed");
                }
            } catch (JSONException ex) {
                logger.warning("failed to parse mfinished response as JSON:" + responseText);
            } 
        } catch (JSONException ex) {
            logger.warning(uris.length + " URIs not stored due to an error: " + ex);
            for (CrawlURI uri : uris) {
                logger.warning("  " + uri.toString());
            }
        } catch (ClientProtocolException ex) {
            logger.warning(uris.length + " URIs not stored due to an error: " + ex);
            for (CrawlURI uri : uris) {
                logger.warning("  " + uri.toString());
            }
        } catch (IOException ex) {
            logger.warning(uris.length + " URIs not stored due to an error: " + ex);
            for (CrawlURI uri : uris) {
                logger.warning("  " + uri.toString());
            }            
        }
    }
    private JSONObject getFinishedData(CrawlURI uri) throws JSONException {
        JSONObject data = new JSONObject();
        // not using CrawlURI#getPersistentDataMap() for efficiency
        // it does not include content-digest and last-modified anyway,
        // unless FetchHistoryProcessor is applied prior to this processor.
        // as we're only interested in content-digest, etag, and last-modified, 
        // use of FetchHistoryProcessor seems expensive.
        data.put("status", uri.getFetchStatus());
        String digest = uri.getContentDigestSchemeString();
        if (digest != null) {
            data.put(RecrawlAttributeConstants.A_CONTENT_DIGEST, digest);
        }
        org.apache.commons.httpclient.HttpMethod method = uri.getHttpMethod();
        String etag = getHeaderValue(method, RecrawlAttributeConstants.A_ETAG_HEADER);
        if (etag != null) {
            // Etag is usually quoted
            if (etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\""))
                etag = etag.substring(1, etag.length() - 1);
            data.put(RecrawlAttributeConstants.A_ETAG_HEADER, etag);
        }
        String lastmod = getHeaderValue(method, RecrawlAttributeConstants.A_LAST_MODIFIED_HEADER);
        if (lastmod != null) {
            data.put(RecrawlAttributeConstants.A_LAST_MODIFIED_HEADER, lastmod);
        }
        return data;
    }
    /**
     * send finished URI to the Headquaters.
     * {@code content-digest}, {@code etag}, {@code last-modified} are sent along with the URI.
     * As original values are obtained from {@link CrawlURI} for these, it is not necessary to
     * copy these values in persistentDataMap before calling this method.
     * @param uri CrawlURI to send
     */
    public void finished(CrawlURI uri) {
        HttpPost post = new HttpPost(getFinishedURL());
        try {
            JSONObject data = getFinishedData(uri);
            List<NameValuePair> params = new ArrayList<NameValuePair>();
            params.add(new BasicNameValuePair("uri", uri.getURI()));
            params.add(new BasicNameValuePair("data", data.toString()));
            UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params);
            post.setEntity(entity);
            String responseText = null;
            synchronized (httpClient2) {
                if (logger.isLoggable(Level.FINE))
                    logger.fine("invoking PUT " + post.getURI());
                HttpResponse response = httpClient2.execute(post);
                HttpEntity re = response.getEntity();
                responseText = EntityUtils.toString(re);
            }
            if (logger.isLoggable(Level.FINE))
                logger.fine("finished(" + uri + ")->" + responseText);
        } catch (JSONException ex) {
            logger.warning(uri + " not stored due to an error: " + ex);
        } catch (ClientProtocolException ex) {
            logger.warning(uri + " not stored due to an error: " + ex);
        } catch (IOException ex) {
            logger.warning("error storing " + uri + ":" + ex);
        }
    }
    /**
     * send multiple discovered URIs to Headquarters in a batch
     * @param uris array of CrawlURIs. null elements are allowed and simply ignored.
     */
    public void mdiscovered(CrawlURI[] uris) {
        HttpPost post = new HttpPost(getMultiDiscoveredURL());
        try {
            JSONArray juris = new JSONArray();
            for (CrawlURI uri : uris) {
                // allow partially filled array
                if (uri == null) continue;
                JSONObject juri = new JSONObject();
                juri.put("uri", uri.getURI());
                juri.put("path", uri.getPathFromSeed());
                UURI via = uri.getVia();
                if (via != null) {
                    juri.put("via", via.getURI());
                }
                LinkContext context = uri.getViaContext();
                if (context != null) {
                    // only HTMLLinkContext is supported for now
                    if (context instanceof HTMLLinkContext) {
                        juri.put("context", context.toString());
                    }
                }
                juris.put(juri);
            }
            if (useUrlEncodedForDiscovered) {
                List<NameValuePair> params = new ArrayList<NameValuePair>();
                params.add(new BasicNameValuePair("u", juris.toString()));
                post.setEntity(new UrlEncodedFormEntity(params));
            } else {
                // sending JSON text as entity - more compact.
                // TODO: consider using BSON for even smaller payload
                // TODO: reuse ByteArrayOutputStream instead of creating anew every time?
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                Writer w = new OutputStreamWriter(bytes, "UTF-8");
                juris.write(w);
                w.flush();
                //System.out.println("entity=" + bytes.toByteArray());
                HttpEntity entity = new ByteArrayEntity(bytes.toByteArray());
                post.setEntity(entity);
                post.addHeader("content-type", "text/json");
            }
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("submitting " + juris.length() + " URIs to discovered");
            }
            String responseText = null;
            synchronized (httpClient2) {
                HttpResponse response = httpClient2.execute(post);
                HttpEntity re = response.getEntity();
                responseText = EntityUtils.toString(re);
            }
            if (logger.isLoggable(Level.FINE))
                logger.fine("discovered([...])->" + responseText);
            try {
                JSONObject jres = new JSONObject(new JSONTokener(responseText));
                int processed = jres.optInt("processed", 0);
                //int scheduled = jres.optInt("scheduled", 0);
                if (processed < juris.length()) {
                    logger.warning((uris.length - processed) + " of " + juris.length() + " not processed");
                }
            } catch (JSONException ex) {
                logger.warning("failed to parse mdiscovered response as JSON:" + responseText);
            } 
        } catch (JSONException ex) {
            logger.warning(uris.length + " URIs not stored due to an error: " + ex);
            for (CrawlURI uri : uris) {
                logger.warning("  " + uri.toString());
            }
        } catch (ClientProtocolException ex) {
            logger.warning(uris.length + " URIs not stored due to an error: " + ex);
            for (CrawlURI uri : uris) {
                logger.warning("  " + uri.toString());
            }
        } catch (IOException ex) {
            logger.warning(uris.length + " URIs not stored due to an error: " + ex);
            for (CrawlURI uri : uris) {
                logger.warning("  " + uri.toString());
            }            
        }
    }
    
    /**
     * send URIs discovered by link extraction to the Headquarters.
     * Note: this single-submission interface is too slow for practical use and
     * abandoned.
     * @param uri
     */
    public void discovered(CrawlURI uri) {
        HttpPost post = new HttpPost(getDiscoveredURL());
        try {
            // the same set of parameters as output of HashCrawlMapper
            List<NameValuePair> params = new ArrayList<NameValuePair>();
            params.add(new BasicNameValuePair("uri", uri.getURI()));
            params.add(new BasicNameValuePair("path", uri.getPathFromSeed()));
            UURI via = uri.getVia();
            if (via != null) {
                params.add(new BasicNameValuePair("via", via.getURI()));
            }
            LinkContext context = uri.getViaContext();
            if (context != null) {
                // only HTMLLinkContext is supported for now
                if (context instanceof HTMLLinkContext) {
                    params.add(new BasicNameValuePair("context", context.toString()));
                }
            }
            post.setEntity(new UrlEncodedFormEntity(params));
            if (logger.isLoggable(Level.FINE)) {
                StringBuilder sb = new StringBuilder();
                for (NameValuePair nvp : params) {
                    sb.append(nvp.getName());
                    sb.append("=");
                    sb.append(nvp.getValue());
                    sb.append(",");
                }
                logger.fine("params={" + sb.toString() + "}");
            }
            String responseText = null;
            synchronized (httpClient2) {
                HttpResponse response = httpClient2.execute(post);
                HttpEntity re = response.getEntity();
                responseText = EntityUtils.toString(re);
            }
            if (logger.isLoggable(Level.FINE))
                logger.fine("discovered(" + uri + ")->" + responseText);
        } catch (ClientProtocolException ex) {
            logger.warning(uri + " not stored due to an error: " + ex);
        } catch (IOException ex) {
            logger.warning("error storing " + uri + ":" + ex);
        }
    }
    
    private static String jget(JSONObject o, String name) {
        try {
            return o.has(name) && !o.isNull(name) ? o.getString(name) : null;
        } catch (JSONException ex) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("getString(" + name + ") failed on " + o);
            }
            return null;
        }
    }
    
    public void reset(int nodeNo, int totalNodes) {
        String url = getResetURL(nodeNo, totalNodes);
        HttpGet get = new HttpGet(url);
        try {
            String responseText = null;
            synchronized (httpClient1) {
                HttpResponse response = httpClient1.execute(get);
                StatusLine sl = response.getStatusLine();
                if (sl.getStatusCode() == 200) {
                    HttpEntity re = response.getEntity();
                    responseText = EntityUtils.toString(re);
                } else {
                    HttpEntity re = response.getEntity();
                    if (re != null)
                        re.consumeContent();
                    logger.warning("Get " + url + " returned " + sl);
                }
            }
        } catch (Exception ex) {
            logger.warning("error getting CrawlURIs: " + ex);
            ex.printStackTrace();
        }
    }
    

    public CrawlURI[] getCrawlURIs(int nodeNo, int totalNodes, int nuris) {
        String url = getFeedURL(nodeNo, totalNodes, nuris);
        HttpGet get = new HttpGet(url);
        try {
            String responseText = null;
            synchronized (httpClient1) {
                HttpResponse response = httpClient1.execute(get);
                StatusLine sl = response.getStatusLine();
                if (sl.getStatusCode() == 200) {
                    HttpEntity re = response.getEntity();
                    responseText = EntityUtils.toString(re);
                } else {
                    HttpEntity re = response.getEntity();
                    if (re != null)
                        re.consumeContent();
                    logger.warning("GET " + url + " returned " + sl);
                }
            }
            if (responseText == null) {
                return new CrawlURI[0];
            }
            JSONArray array = new JSONArray(new JSONTokener(responseText));
            CrawlURI[] result = new CrawlURI[array.length()];
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String uri = o.getString("uri");
                String path = o.getString("path");
                String via = jget(o, "via");
                String context = jget(o, "context");
                UURI uuri = UURIFactory.getInstance(uri);
                UURI viaUuri = null;
                if (via != null && via.length() > 0) {
                    try {
                        viaUuri = UURIFactory.getInstance(via);
                    } catch (URIException ex) {
                        logger.warning("via ignored:" + via + ":" + ex);
                    }
                }
                LinkContext viaContext = null;
                if (context != null && context.length() > 0) {
                    // ref. CrawlURI#fromHopsViaString(String)
                    viaContext = HTMLLinkContext.get(context);
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
        } catch (Exception ex) {
            logger.warning("error getting CrawlURIs: " + ex);
            ex.printStackTrace();
            return new CrawlURI[0];
        }
    }
    
}
