/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.valves;

import java.io.IOException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ResourceBundle;
import java.util.MissingResourceException;

import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.http.fileupload.IOUtils;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * <p>Implementation of a Valve that proxies error reporting to other urls.</p>
 *
 * <p>This Valve should be attached at the Host level, although it will work
 * if attached to a Context.</p>
 *
 */
public class ProxyErrorReportValve extends ErrorReportValve {
	private static final Log log = LogFactory.getLog(ProxyErrorReportValve.class);

	public ProxyErrorReportValve() {
		super();
	}

	@Override
	protected void report(Request request, Response response, Throwable throwable) {
		try {
			reportImpl(request, response, throwable);
		} catch(Throwable t) {
			ExceptionUtils.handleThrowable(t);
			log.warn("Returning error reporting to "+super.getClass().getName()+".", t);
			super.report(request, response, throwable);
		}
	}

	private String getProxyUrl(Response response) {
		int statusCode = response.getStatus();

		ResourceBundle resourceBundle = ResourceBundle.getBundle(this.getClass().getSimpleName(), response.getLocale());

		String redirectUrl = null;
		try {
			redirectUrl = resourceBundle.getString(String.valueOf(statusCode));
		} catch(MissingResourceException e) {
			redirectUrl = resourceBundle.getString("0");
		}

		return redirectUrl;
	}

	private void reportImpl(Request request, Response response, Throwable throwable) throws Throwable {

		int statusCode = response.getStatus();

		// Do nothing on a 1xx, 2xx and 3xx status
		// Do nothing if anything has been written already
		// Do nothing if the response hasn't been explicitly marked as in error
		//	and that error has not been reported.
		if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
			return;
		}

		String urlString = getProxyUrl(response);
		StringBuilder stringBuilder = new StringBuilder(urlString);
		if(urlString.indexOf("?") > -1) {
			stringBuilder.append("&");
		} else {
			stringBuilder.append("?");
		}
		stringBuilder.append("requestUri=");
		stringBuilder.append(URLEncoder.encode(request.getRequestURI()));
		stringBuilder.append("&statusCode=");
		stringBuilder.append(URLEncoder.encode(String.valueOf(statusCode)));

		try {
			StringManager smClient = StringManager.getManager( Constants.Package, request.getLocales());
			String statusDescription = smClient.getString("http." + statusCode);
			stringBuilder.append("&statusDescription=");
			stringBuilder.append(URLEncoder.encode(statusDescription));
		} catch(Exception e) {
			log.warn("Failed to get status description for "+statusCode, e);
		}

		if(null != throwable) {
			stringBuilder.append("&throwable=");
			stringBuilder.append(URLEncoder.encode(throwable.toString()));
		}

		urlString = stringBuilder.toString();
		if(log.isTraceEnabled()) {
			log.trace("Proxying error reporting to "+urlString);
		}
		URL url = new URL(urlString);
		HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
		httpURLConnection.connect();
		int responseCode = httpURLConnection.getResponseCode();
		response.setContentType(httpURLConnection.getContentType()); 
		response.setContentLength(httpURLConnection.getContentLength()); 
		OutputStream outputStream = response.getOutputStream();
		InputStream inputStream = null;
		try {
			inputStream = url.openStream(); 
			IOUtils.copy(inputStream, outputStream);
		} finally {
			inputStream.close();
		}
	}	
}
