package org.archive.modules.fetcher;

import static org.archive.modules.fetcher.FetchErrors.LENGTH_TRUNC;
import static org.archive.modules.fetcher.FetchErrors.TIMER_TRUNC;
import static org.archive.modules.fetcher.FetchStatusCodes.S_CONNECT_FAILED;
import static org.archive.modules.fetcher.FetchStatusCodes.S_CONNECT_LOST;
import static org.archive.modules.fetcher.FetchStatusCodes.S_DOMAIN_PREREQUISITE_FAILURE;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_FETCH_HISTORY;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_REFERENCE_LENGTH;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Map;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ClientConnectionOperator;
import org.apache.http.conn.OperatedClientConnection;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.BasicClientConnectionManager;
import org.apache.http.impl.conn.DefaultClientConnection;
import org.apache.http.impl.conn.DefaultClientConnectionOperator;
import org.apache.http.impl.conn.SchemeRegistryFactory;
import org.apache.http.impl.io.AbstractSessionInputBuffer;
import org.apache.http.impl.io.AbstractSessionOutputBuffer;
import org.apache.http.impl.io.IdentityOutputStream;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.io.SessionOutputBuffer;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.archive.io.RecorderLengthExceededException;
import org.archive.io.RecorderTimeoutException;
import org.archive.modules.CrawlURI;
import org.archive.modules.CrawlURI.FetchType;
import org.archive.modules.Processor;
import org.archive.modules.net.CrawlHost;
import org.archive.modules.net.ServerCache;
import org.archive.util.Recorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

public class FetchHTTP2 extends Processor implements Lifecycle {

    protected ServerCache serverCache;
    public ServerCache getServerCache() {
        return this.serverCache;
    }
    /**
     * Used to do DNS lookups.
     */
    @Autowired
    public void setServerCache(ServerCache serverCache) {
        this.serverCache = serverCache;
    }

    {
        setDigestContent(true);
    }
    public boolean getDigestContent() {
        return (Boolean) kp.get("digestContent");
    }
    /**
     * Whether or not to perform an on-the-fly digest hash of retrieved
     * content-bodies.
     */
    public void setDigestContent(boolean digest) {
        kp.put("digestContent",digest);
    }
 
    protected String digestAlgorithm = "sha1"; 
    public String getDigestAlgorithm() {
        return digestAlgorithm;
    }
    /**
     * Which algorithm (for example MD5 or SHA-1) to use to perform an
     * on-the-fly digest hash of retrieved content-bodies.
     */
    public void setDigestAlgorithm(String digestAlgorithm) {
        this.digestAlgorithm = digestAlgorithm;
    }


    /**
     * Can this processor fetch the given CrawlURI. May set a fetch status
     * if this processor would usually handle the CrawlURI, but cannot in
     * this instance.
     * 
     * @param curi
     * @return True if processor can fetch.
     */
    @Override
    protected boolean shouldProcess(CrawlURI curi) {
        String scheme = curi.getUURI().getScheme();
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            // handles only plain http and https
            return false;
        }

        CrawlHost host = serverCache.getHostFor(curi.getUURI());
        if (host.getIP() == null && host.hasBeenLookedUp()) {
            curi.setFetchStatus(S_DOMAIN_PREREQUISITE_FAILURE);
            return false;
        }

        return true;
    }

    @Override
    protected void innerProcess(final CrawlURI curi) throws InterruptedException {
        // Note begin time
        curi.setFetchBeginTime(System.currentTimeMillis());

        // Get a reference to the HttpRecorder that is set into this ToeThread.
        final Recorder rec = curi.getRecorder();

        // Shall we get a digest on the content downloaded?
        boolean digestContent = getDigestContent();
        String algorithm = null;
        if (digestContent) {
            algorithm = getDigestAlgorithm();
            rec.getRecordedInput().setDigest(algorithm);
        } else {
            // clear
            rec.getRecordedInput().setDigest((MessageDigest)null);
        }

        String curiString = curi.getUURI().toString();
        HttpRequestBase request = null;
        if (curi.getFetchType() == FetchType.HTTP_POST) {
            request = new HttpPost(curiString);
            curi.setFetchType(FetchType.HTTP_POST);
        } else {
            request = new HttpGet(curiString);
            curi.setFetchType(FetchType.HTTP_GET);
        }
        HttpClient httpClient = obtainHttpClient(rec);
        HttpResponse response = null;
        try {
            response = httpClient.execute(request);
            addResponseContent(response, curi);
        } catch (ClientProtocolException e) {
            failedExecuteCleanup(request, curi, e);
            return;
        } catch (IOException e) {
            failedExecuteCleanup(request, curi, e);
            return;
        }
        
        // set softMax on bytes to get (if implied by content-length)
        long softMax = -1l;
        Header h = response.getLastHeader("content-length");
        if (h != null) {
            Long.parseLong(h.getValue());
        }
        try {
            if (!request.isAborted()) {
                // Force read-to-end, so that any socket hangs occur here,
                // not in later modules.
                
                // XXX does it matter that we're circumventing the library here? response.getEntity().getContent()
                rec.getRecordedInput().readFullyOrUntil(softMax); 
            }
        } catch (RecorderTimeoutException ex) {
            doAbort(curi, request, TIMER_TRUNC);
        } catch (RecorderLengthExceededException ex) {
            doAbort(curi, request, LENGTH_TRUNC);
        } catch (IOException e) {
            cleanup(curi, e, "readFully", S_CONNECT_LOST);
            return;
        } catch (ArrayIndexOutOfBoundsException e) {
            // For weird windows-only ArrayIndex exceptions from native code
            // see http://forum.java.sun.com/thread.jsp?forum=11&thread=378356
            // treating as if it were an IOException
            cleanup(curi, e, "readFully", S_CONNECT_LOST);
            return;
        } finally {
            rec.close();
            // ensure recording has stopped
            rec.closeRecorders();
            if (!request.isAborted()) {
                request.reset();
            }
            // Note completion time
            curi.setFetchCompletedTime(System.currentTimeMillis());
            // Set the response charset into the HttpRecord if available.
//            setCharacterEncoding(curi, rec, method);
            setSizes(curi, rec);
//            setOtherCodings(curi, rec, method); 
        }

        if (digestContent) {
            curi.setContentDigest(algorithm, 
                rec.getRecordedInput().getDigestValue());
        }
    }

    protected HttpClient obtainHttpClient(final Recorder rec) {
        DefaultHttpClient httpClient = new DefaultHttpClient() {
            @Override
            protected ClientConnectionManager createClientConnectionManager() {
                return new BasicClientConnectionManager(
                        SchemeRegistryFactory.createDefault()) {
                    @Override
                    protected ClientConnectionOperator createConnectionOperator(
                            SchemeRegistry schreg) {
                        return new RecordingClientConnectionOperator(schreg, rec);
                    }
                };
            }
        };

        // never retry (heritrix handles this elsewhere)
        httpClient.setHttpRequestRetryHandler(new HttpRequestRetryHandler() {
            @Override
            public boolean retryRequest(IOException exception, int executionCount,
                    HttpContext context) {
                return false;
            }
        });
        
        return httpClient;
    }

    protected void doAbort(CrawlURI curi, HttpRequestBase request,
            String annotation) {
        curi.getAnnotations().add(annotation);
        curi.getRecorder().close();
        request.abort();
    }

    /**
     * Update CrawlURI internal sizes based on current transaction (and
     * in the case of 304s, history) 
     * 
     * @param curi CrawlURI
     * @param rec HttpRecorder
     */
    @SuppressWarnings("unchecked")
    protected void setSizes(CrawlURI curi, Recorder rec) {
        // set reporting size
        curi.setContentSize(rec.getRecordedInput().getSize());
        // special handling for 304-not modified
        if (curi.getFetchStatus() == HttpStatus.SC_NOT_MODIFIED
                && curi.containsDataKey(A_FETCH_HISTORY)) {
            Map history[] = (Map[])curi.getData().get(A_FETCH_HISTORY);
            if (history[0] != null
                    && history[0]
                            .containsKey(A_REFERENCE_LENGTH)) {
                long referenceLength = (Long) history[0].get(A_REFERENCE_LENGTH);
                // carry-forward previous 'reference-length' for future
                curi.getData().put(A_REFERENCE_LENGTH, referenceLength);
                // increase content-size to virtual-size for reporting
                curi.setContentSize(rec.getRecordedInput().getSize()
                        + referenceLength);
            }
        }
    }

    /**
     * This method populates <code>curi</code> with response status and
     * content type.
     * 
     * @param curi
     *            CrawlURI to populate.
     * @param response
     *            Method to get response status and headers from.
     */
    protected void addResponseContent(HttpResponse response, CrawlURI curi) {
        curi.setFetchStatus(response.getStatusLine().getStatusCode());
        // Header ct = response.getResponseHeader("content-type");
        Header ct = response.getLastHeader("content-type");
        curi.setContentType(ct == null ? null : ct.getValue());
        
        for (Header h: response.getAllHeaders()) {
            curi.putHttpHeader(h.getName(), h.getValue());
        }
    }

    /**
     * Cleanup after a failed method execute.
     * 
     * @param curi
     *            CrawlURI we failed on.
     * @param request
     *            Method we failed on.
     * @param exception
     *            Exception we failed with.
     */
    protected void failedExecuteCleanup(final HttpRequestBase request,
            final CrawlURI curi, final Exception exception) {
        cleanup(curi, exception, "executeMethod", S_CONNECT_FAILED);
        request.reset();
    }
    
    /**
     * Cleanup after a failed method execute.
     * 
     * @param curi
     *            CrawlURI we failed on.
     * @param exception
     *            Exception we failed with.
     * @param message
     *            Message to log with failure. FIXME: Seems ignored
     * @param status
     *            Status to set on the fetch.
     */
    protected void cleanup(final CrawlURI curi, final Exception exception,
            final String message, final int status) {
        // message ignored!
        curi.getNonFatalFailures().add(exception);
        curi.setFetchStatus(status);
        curi.getRecorder().close();
    }

    protected static class RecordingClientConnectionOperator extends DefaultClientConnectionOperator {

        protected final Recorder rec;

        protected RecordingClientConnectionOperator(SchemeRegistry schemes, Recorder rec) {
            super(schemes);
            this.rec = rec;
        }

        @Override
        public OperatedClientConnection createConnection() {
            return new DefaultClientConnection() {
                @Override
                protected SessionInputBuffer createSessionInputBuffer(Socket socket, int buffersize, HttpParams params) throws IOException {
                    return new RecordingSocketInputBuffer(rec, socket, params);
                }
                
                @Override
                protected SessionOutputBuffer createSessionOutputBuffer(Socket socket, int buffersize, HttpParams params) throws IOException {
                    SessionOutputBuffer sob = super.createSessionOutputBuffer(socket, buffersize, params);
                    OutputStream ros = rec.outputWrap(new IdentityOutputStream(sob));
                    return new HcOutputWrapper(ros, buffersize, params);
                }
                
                @Override
                public void receiveResponseEntity(HttpResponse response)
                        throws HttpException, IOException {
                    rec.markContentBegin();
                    super.receiveResponseEntity(response);
                }
            };
        }
    }

    protected static class HcInputWrapper extends AbstractSessionInputBuffer {
        public HcInputWrapper(InputStream in, int buffersize, HttpParams params) {
            this.init(in, buffersize, params);
        }

        @Override
        public boolean isDataAvailable(int timeout) throws IOException {
            throw new IOException("not implemented");
        }
    }

    protected static class HcOutputWrapper extends AbstractSessionOutputBuffer {
        public HcOutputWrapper(OutputStream out, int buffersize, HttpParams params) {
            this.init(out, buffersize, params);
        }
    }

}