package com.kellen.agent.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/** 验签读取有界原始字节后，向 MVC 原样重放；URL encoded form 参数与 query 参数保持可读。 */
final class ReplayableAgentRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    private Map<String, String[]> formParameters;

    ReplayableAgentRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream stream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return stream.read(); }
            @Override public int read(byte[] bytes, int offset, int length) { return stream.read(bytes, offset, length); }
            @Override public boolean isFinished() { return stream.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                if (listener == null) { throw new IllegalArgumentException("listener required"); }
                try {
                    if (!isFinished()) { listener.onDataAvailable(); }
                    if (isFinished()) { listener.onAllDataRead(); }
                } catch (IOException exception) { listener.onError(exception); }
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    @Override public String getParameter(String name) {
        String[] values = getParameterValues(name);
        return values == null || values.length == 0 ? null : values[0];
    }
    @Override public String[] getParameterValues(String name) {
        if (!isForm()) { return super.getParameterValues(name); }
        String[] values = getParameterMap().get(name);
        return values == null ? null : values.clone();
    }
    @Override public Enumeration<String> getParameterNames() {
        return isForm() ? Collections.enumeration(getParameterMap().keySet()) : super.getParameterNames();
    }
    @Override public Map<String, String[]> getParameterMap() {
        if (!isForm()) { return super.getParameterMap(); }
        if (formParameters == null) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            parseParameters(getQueryString(), values);
            parseParameters(new String(body, charset()), values);
            Map<String, String[]> parsed = new LinkedHashMap<>();
            values.forEach((key, entries) -> parsed.put(key, entries.toArray(String[]::new)));
            formParameters = Collections.unmodifiableMap(parsed);
        }
        return formParameters;
    }
    private boolean isForm() {
        return getContentType() != null
                && getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }
    private Charset charset() {
        return getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
    }
    private void parseParameters(String encoded, Map<String, List<String>> values) {
        if (encoded == null || encoded.isEmpty()) { return; }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) { continue; }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], charset());
            String value = URLDecoder.decode(parts.length > 1 ? parts[1] : "", charset());
            values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
    }
}
