package org.krmdemo.plansinstr.equilib;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AWS Lambda behind a Function URL: {@code GET /?thresholdMin=…&…} answers with the SVG of
 * {@link SvgRenderer}. No libraries: the Java runtime calls {@link #handleRequest(InputStream, OutputStream)} with
 * the raw event, and only {@code rawQueryString} is read from it.
 * <p>
 * Query parameters, all optional, in whole dollars (the names of {@code ThresholdsInfo} in plans-core):
 * {@code thresholdMin}, {@code thresholdMax}, {@code nominalMin}, {@code nominalMax}, {@code sweptInMax} (A),
 * {@code sweptOutMax} (L); and {@code size} (L, M, S) and {@code frames} (12, 24, 48, 96). Amounts are rounded to two
 * significant digits ({@link Sweep#snapped()}), so the number of different answers is finite and they cache well.
 */
public class EquilibHandler {

    private static final Pattern RAW_QUERY = Pattern.compile("\"rawQueryString\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public void handleRequest(InputStream input, OutputStream output) throws IOException {
        String event = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = RAW_QUERY.matcher(event);
        String query = m.find() ? m.group(1).replace("\\u0026", "&").replace("\\/", "/") : "";
        output.write(respond(query).getBytes(StandardCharsets.UTF_8));
    }

    /** The whole Function URL response (JSON) for one query string. */
    static String respond(String query) {
        Map<String, String> q = parse(query);
        try {
            Sweep d = Sweep.DEMO;
            Sweep sweep = new Sweep(
                amount(q, "thresholdMin", d.thresholdMin()), amount(q, "thresholdMax", d.thresholdMax()),
                amount(q, "nominalMin", d.nominalMin()), amount(q, "nominalMax", d.nominalMax()),
                amount(q, "sweptInMax", d.sweptInMax()), amount(q, "sweptOutMax", d.sweptOutMax())).snapped();
            int frames = (int) amount(q, "frames", SvgRenderer.DEFAULT_FRAMES);
            String svg = SvgRenderer.render(sweep, q.getOrDefault("size", "L"), frames);
            return response(200, "image/svg+xml; charset=utf-8", svg);
        } catch (IllegalArgumentException e) {
            return response(400, "text/plain; charset=utf-8", e.getMessage() + "\n");
        }
    }

    private static long amount(Map<String, String> q, String name, long dflt) {
        String v = q.get(name);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Math.round(Double.parseDouble(v.replace(",", "").replace("_", "").replace("$", "")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": not a number: " + v);
        }
    }

    static Map<String, String> parse(String query) {
        Map<String, String> q = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            q.put(k, v);
        }
        return q;
    }

    private static String response(int status, String type, String body) {
        return "{\"statusCode\":" + status + ",\"headers\":{\"content-type\":\"" + type + "\","
            + "\"cache-control\":\"" + (status == 200 ? "public, max-age=86400" : "no-store") + "\","
            + "\"access-control-allow-origin\":\"*\"},"
            + "\"isBase64Encoded\":false,\"body\":\"" + json(body) + "\"}";
    }

    static String json(String s) {
        StringBuilder o = new StringBuilder(s.length() + 1024);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> o.append("\\\"");
                case '\\' -> o.append("\\\\");
                case '\n' -> o.append("\\n");
                case '\r' -> o.append("\\r");
                case '\t' -> o.append("\\t");
                default -> {
                    if (c < 0x20) o.append(String.format("\\u%04x", (int) c));
                    else o.append(c);
                }
            }
        }
        return o.toString();
    }
}
