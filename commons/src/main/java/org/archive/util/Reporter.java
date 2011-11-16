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

package org.archive.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Map;

public interface Reporter {

    /**
     * @return tabular data as key-value pairs.
     * Maps should retain order for its entries, eg by using a LinkedHashMap.
     * Map entry values should have reasonable toString() implementations.
     */
    public Iterable<Map<String,Object>> report();

    /**
     * @return tabular data as key-value pairs.
     * Maps should retain order for its entries, eg by using a LinkedHashMap.
     * Map entry values should have reasonable toString() implementations.
     */
    public Iterable<Map<String,Object>> shortReport();

    /**
     * Formatters turn Reporter output into text, xml, json, or anything else.
     */
    public interface Formatter {
        public InputStream format(Iterable<Map<String, Object>> report);
    }
    
    
    /**
     * Make a default report to the passed-in Writer.
     * New implementations should throw UnsupportedOperationException due to deprecation.
     * 
     * @param writer to receive report
     */
    @Deprecated
    public void reportTo(PrintWriter writer) throws IOException;
    
    /**
     * Write a short single-line summary report 
     * New implementations should throw UnsupportedOperationException due to deprecation.
     * 
     * @param writer to receive report
     */
    @Deprecated
    public void shortReportLineTo(PrintWriter pw) throws IOException;
    
    
    /**
     * Like report() but returns only one row.
     * Deprecated in favor of shortReport() which works with Reporter.Formatter.format()
     */
    @Deprecated
    public Map<String,Object> shortReportMap();


    /**
     * Return a legend for the single-line summary report as a String.
     * New implementations should throw UnsupportedOperationException due to deprecation.
     * 
     * @return String single-line summary legend
     */
    @Deprecated
    public String shortReportLegend();
}
