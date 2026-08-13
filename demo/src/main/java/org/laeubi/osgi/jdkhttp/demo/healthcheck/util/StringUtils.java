/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.laeubi.osgi.jdkhttp.demo.healthcheck.util;

/**
 * Copied unchanged (only the package was adjusted) from
 * {@code org.apache.felix.hc.core.impl.util.lang.StringUtils} in the Apache
 * Felix Health Check core bundle - it has no dependency on the Servlet API.
 */
public class StringUtils {

    public static boolean isBlank(final CharSequence cs) {
        return cs == null || cs.chars().allMatch(Character::isWhitespace);
    }

    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }

    public static String defaultIfBlank(final String cs, String defaultStr) {
        return isBlank(cs) ? defaultStr : cs;
    }

    public static String repeat(String s, int n) {
        if (s == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

}
