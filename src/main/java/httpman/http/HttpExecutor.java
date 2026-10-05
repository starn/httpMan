package httpman.http;

import httpman.model.Header;
import httpman.model.RequestModel;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Executes a {@link RequestModel} with java.net.http.HttpClient. */
public class HttpExecutor {

    public static final String USER_AGENT = "HttpMan/1.0";

    /** Headers that java.net.http manages itself and refuses to set. */
    private static final Set<String> CLIENT_MANAGED = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        Collections.addAll(CLIENT_MANAGED, "content-length", "connection", "upgrade", "expect");
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "httpman-http");
        t.setDaemon(true);
        return t;
    });

    /** A running request: its (never failing) result future and a way to cancel it. */
    public static final class Execution {
        public final CompletableFuture<ExecutionResult> result;
        private final CompletableFuture<?> inner;

        Execution(CompletableFuture<ExecutionResult> result, CompletableFuture<?> inner) {
            this.result = result;
            this.inner = inner;
        }

        public void cancel() {
            if (inner != null) {
                inner.cancel(true);
            }
        }
    }

    /**
     * Starts the request asynchronously. The result future never completes exceptionally:
     * errors are reported inside the {@link ExecutionResult}.
     */
    public Execution start(RequestModel req) {
        CompletableFuture<ExecutionResult> out = new CompletableFuture<>();
        CompletableFuture<?> inner = execute(req, out);
        return new Execution(out, inner);
    }

    /** Convenience: start and return the result future only. */
    public CompletableFuture<ExecutionResult> execute(RequestModel req) {
        return start(req).result;
    }

    private CompletableFuture<?> execute(RequestModel req, CompletableFuture<ExecutionResult> out) {
        ExecutionResult res = new ExecutionResult();
        res.log("Started    : " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        long start = System.nanoTime();

        URI uri;
        HttpClient client;
        HttpRequest httpRequest;
        try {
            uri = parseUri(req.url);
            BodyBuilder.Body body;
            try {
                body = BodyBuilder.build(req, true);
            } catch (IOException e) {
                res.rawRequest = buildRawRequest(req, uri);
                throw new IOException("Cannot read body file: " + e.getMessage(), e);
            }
            res.rawRequest = buildRawRequest(req, uri, body);
            client = buildClient(req, res);
            httpRequest = buildRequest(req, uri, res, body);
        } catch (Throwable e) {
            if (res.rawRequest.isEmpty()) {
                res.rawRequest = buildRawRequest(req, null);
            }
            fail(res, e, start);
            out.complete(res);
            return null;
        }

        res.log("Request    : " + httpRequest.method() + " " + uri);
        CompletableFuture<HttpResponse<byte[]>> inner = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        inner.whenComplete((response, err) -> {
            if (err != null) {
                fail(res, err, start);
            } else {
                try {
                    fillResponse(res, response, start);
                } catch (Throwable t) {
                    fail(res, t, start);
                }
            }
            out.complete(res);
        });
        return inner;
    }

    // ------------------------------------------------------------------ building

    public static URI parseUri(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            throw new IllegalArgumentException("The request URL is empty");
        }
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            u = "http://" + u;
        }
        // tolerate a few characters users often paste unencoded
        u = u.replace(" ", "%20").replace("|", "%7C").replace("\"", "%22");
        URI uri = URI.create(u);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Unsupported scheme '" + scheme + "' (only http and https)");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("The URL has no host: " + url);
        }
        return uri;
    }

    private HttpClient buildClient(RequestModel req, ExecutionResult res) throws Exception {
        HttpClient.Builder b = HttpClient.newBuilder().executor(POOL);

        if (req.timeoutSeconds > 0) {
            b.connectTimeout(Duration.ofSeconds(req.timeoutSeconds));
        }
        b.followRedirects(req.followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
        if (req.httpVersion == RequestModel.HttpVersion.HTTP_1_1) {
            b.version(HttpClient.Version.HTTP_1_1);
        } else if (req.httpVersion == RequestModel.HttpVersion.HTTP_2) {
            b.version(HttpClient.Version.HTTP_2);
        }

        // ---- proxy
        switch (req.proxyMode) {
            case NONE:
                b.proxy(HttpClient.Builder.NO_PROXY);
                res.log("Proxy      : none");
                break;
            case SYSTEM:
                ProxySelector sel = ProxySelector.getDefault();
                if (sel != null) {
                    b.proxy(sel);
                }
                res.log("Proxy      : system settings" + describeSystemProxy(req.url));
                break;
            case CUSTOM:
                if (req.proxyHost == null || req.proxyHost.isBlank()) {
                    throw new IllegalArgumentException("Custom proxy selected but no proxy host given");
                }
                if (req.proxyPort <= 0 || req.proxyPort > 65535) {
                    throw new IllegalArgumentException("Invalid proxy port " + req.proxyPort);
                }
                b.proxy(ProxySelector.of(new InetSocketAddress(req.proxyHost.trim(), req.proxyPort)));
                res.log("Proxy      : " + req.proxyHost.trim() + ":" + req.proxyPort
                        + (req.proxyUser.isBlank() ? "" : " (user " + req.proxyUser + ")"));
                if (!req.proxyUser.isBlank()) {
                    final String user = req.proxyUser;
                    final char[] pwd = req.proxyPassword == null ? new char[0] : req.proxyPassword.toCharArray();
                    b.authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            if (getRequestorType() == RequestorType.PROXY) {
                                return new PasswordAuthentication(user, pwd);
                            }
                            return null;
                        }
                    });
                }
                break;
            default:
                break;
        }

        // ---- TLS
        boolean hasCert = req.certPath != null && !req.certPath.isBlank();
        if (hasCert || req.insecure) {
            KeyManager[] kms = null;
            if (hasCert) {
                KeyStore ks = loadKeyStore(req.certPath, req.certPassword);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pwd(req.certPassword));
                kms = kmf.getKeyManagers();
                res.log("Client cert: " + req.certPath);
            }
            TrustManager[] tms = req.insecure ? new TrustManager[]{new TrustAllManager()} : null;
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kms, tms, null);
            b.sslContext(ctx);
        }
        if (req.insecure) {
            res.log("TLS        : certificate and hostname verification DISABLED");
        }
        res.log("Timeout    : " + (req.timeoutSeconds > 0 ? req.timeoutSeconds + " s" : "none"));
        res.log("Redirects  : " + (req.followRedirects ? "follow" : "do not follow"));
        res.log("HTTP ver.  : " + req.httpVersion);
        return b.build();
    }

    /** Headers really sent (user headers + body Content-Type), without Host / User-Agent / Content-Length logic. */
    private static java.util.List<String[]> effectiveHeaders(RequestModel req, BodyBuilder.Body body) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        boolean userHasCt = req.headerValue("Content-Type") != null;
        for (Header h : req.headers) {
            if (!h.enabled || h.name.trim().isEmpty()) {
                continue;
            }
            String name = h.name.trim();
            if (name.equalsIgnoreCase("content-type") && body != null && body.forceContentType) {
                continue; // replaced by the generated multipart content type (boundary)
            }
            out.add(new String[]{name, h.value});
        }
        if (body != null && body.contentType != null && (body.forceContentType || !userHasCt)) {
            out.add(new String[]{"Content-Type", body.contentType});
        }
        return out;
    }

    private HttpRequest buildRequest(RequestModel req, URI uri, ExecutionResult res, BodyBuilder.Body body) {
        String method = req.method == null || req.method.isBlank() ? "GET" : req.method.trim().toUpperCase(Locale.ROOT);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        if (req.timeoutSeconds > 0) {
            b.timeout(Duration.ofSeconds(req.timeoutSeconds));
        }
        boolean hasUserAgent = false;
        for (String[] h : effectiveHeaders(req, body)) {
            String name = h[0];
            if (CLIENT_MANAGED.contains(name)) {
                res.log("Note       : header '" + name + "' is managed by the HTTP client and was not sent as-is");
                continue;
            }
            if (name.equalsIgnoreCase("user-agent")) {
                hasUserAgent = true;
            }
            try {
                b.header(name, h[1]);
            } catch (IllegalArgumentException e) {
                res.log("Warning    : header '" + name + "' skipped: " + e.getMessage());
            }
        }
        if (!hasUserAgent) {
            b.header("User-Agent", USER_AGENT);
        }
        HttpRequest.BodyPublisher pub = body.bytes.length > 0 || req.hasBody()
                ? HttpRequest.BodyPublishers.ofByteArray(body.bytes)
                : HttpRequest.BodyPublishers.noBody();
        b.method(method, pub);
        if (req.hasBody()) {
            res.log("Body       : " + req.bodyType.name() + ", " + body.bytes.length + " bytes");
        }
        return b.build();
    }

    /** Builds the body without failing: unreadable files are only shown by name. */
    public static BodyBuilder.Body previewBody(RequestModel req) {
        try {
            return BodyBuilder.build(req, true);
        } catch (Exception e) {
            try {
                return BodyBuilder.build(req, false);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** Builds an HTTP/1.1 style text of the request (best effort, works with invalid input too). */
    public static String buildRawRequest(RequestModel req, URI uri) {
        return buildRawRequest(req, uri, previewBody(req));
    }

    public static String buildRawRequest(RequestModel req, URI uri, BodyBuilder.Body body) {
        StringBuilder sb = new StringBuilder();
        String method = req.method == null || req.method.isBlank() ? "GET" : req.method.trim().toUpperCase(Locale.ROOT);
        String target;
        String host = null;
        if (uri != null) {
            target = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                target += "?" + uri.getRawQuery();
            }
            host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } else {
            target = req.url == null ? "" : req.url.trim();
        }
        String version = req.httpVersion == RequestModel.HttpVersion.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
        sb.append(method).append(' ').append(target).append(' ').append(version).append('\n');
        boolean hasHost = req.headerValue("Host") != null;
        if (host != null && !hasHost) {
            sb.append("Host: ").append(host).append('\n');
        }
        boolean hasUa = false;
        boolean hasCl = false;
        for (String[] h : effectiveHeaders(req, body)) {
            if (h[0].equalsIgnoreCase("user-agent")) {
                hasUa = true;
            }
            if (h[0].equalsIgnoreCase("content-length")) {
                hasCl = true;
                continue; // replaced by the computed one below
            }
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        if (!hasUa) {
            sb.append("User-Agent: ").append(USER_AGENT).append('\n');
        }
        boolean hasBody = req.hasBody() && body != null;
        if (hasBody || hasCl) {
            sb.append("Content-Length: ").append(hasBody ? body.bytes.length : 0).append('\n');
        }
        sb.append('\n');
        if (hasBody) {
            sb.append(body.display);
        } else if (req.hasBody()) {
            sb.append("[body could not be built]");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ response

    private void fillResponse(ExecutionResult res, HttpResponse<byte[]> response, long start) {
        res.durationMs = (System.nanoTime() - start) / 1_000_000;
        res.statusCode = response.statusCode();
        res.reasonPhrase = reasonPhrase(res.statusCode);
        res.protocol = response.version() == HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
        res.finalUri = response.uri().toString();

        // redirect chain
        java.util.ArrayDeque<HttpResponse<byte[]>> chain = new java.util.ArrayDeque<>();
        HttpResponse<byte[]> prev = response.previousResponse().orElse(null);
        while (prev != null) {
            chain.push(prev);
            prev = prev.previousResponse().orElse(null);
        }
        for (HttpResponse<byte[]> r : chain) {
            res.log("Redirect   : " + r.statusCode() + " " + r.uri() + " -> " + r.headers().firstValue("location").orElse("?"));
        }

        HttpHeaders headers = response.headers();
        StringBuilder raw = new StringBuilder();
        raw.append(res.protocol).append(' ').append(res.statusCode);
        if (!res.reasonPhrase.isEmpty()) {
            raw.append(' ').append(res.reasonPhrase);
        }
        raw.append('\n');
        for (Map.Entry<String, List<String>> e : headers.map().entrySet()) {
            if (e.getKey().startsWith(":")) {
                continue; // HTTP/2 pseudo headers
            }
            for (String v : e.getValue()) {
                res.responseHeaders.add(new String[]{e.getKey(), v});
                raw.append(e.getKey()).append(": ").append(v).append('\n');
            }
        }
        raw.append('\n');

        byte[] body = response.body() == null ? new byte[0] : response.body();
        res.contentType = headers.firstValue("content-type").orElse("");
        String encoding = headers.firstValue("content-encoding").orElse("").trim().toLowerCase(Locale.ROOT);
        byte[] decoded = body;
        if (!encoding.isEmpty() && !encoding.equals("identity") && body.length > 0) {
            try {
                decoded = decompress(body, encoding);
                res.log("Body       : " + body.length + " bytes (" + encoding + "), " + decoded.length + " bytes decoded");
            } catch (IOException e) {
                res.log("Body       : could not decode content-encoding '" + encoding + "': " + e.getMessage());
                decoded = body;
            }
        }
        res.bodyBytes = decoded;
        if (isText(res.contentType, decoded)) {
            res.bodyText = new String(decoded, charsetOf(res.contentType));
            raw.append(res.bodyText);
        } else {
            res.bodyText = null;
            raw.append("[binary content: ").append(decoded.length).append(" bytes]");
        }
        res.rawResponse = raw.toString();
        res.log("Status     : " + res.statusCode + " " + res.reasonPhrase);
        res.log("Duration   : " + res.durationMs + " ms");
        res.log("Size       : " + decoded.length + " bytes");
    }

    private void fail(ExecutionResult res, Throwable err, long start) {
        res.durationMs = (System.nanoTime() - start) / 1_000_000;
        Throwable e = err;
        while ((e instanceof CompletionException || e instanceof java.util.concurrent.ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        res.error = e;
        res.cancelled = e instanceof CancellationException;
        String msg = describeError(e);
        res.log("ERROR      : " + msg);
        res.log("Duration   : " + res.durationMs + " ms");
        res.rawResponse = "No response received.\n\n" + msg;
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        res.log("\n" + sw);
    }

    public static String describeError(Throwable e) {
        if (e instanceof CancellationException) {
            return "Request cancelled by the user";
        }
        if (e instanceof HttpConnectTimeoutException) {
            return "Connection timed out (" + e.getMessage() + ")";
        }
        if (e instanceof HttpTimeoutException) {
            return "Request timed out (" + e.getMessage() + ")";
        }
        if (e instanceof java.net.ConnectException) {
            return "Connection refused / failed" + (e.getMessage() != null ? " (" + e.getMessage() + ")" : "");
        }
        if (e instanceof java.nio.channels.UnresolvedAddressException || e instanceof java.net.UnknownHostException) {
            return "Unknown host (DNS resolution failed)";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "TLS/SSL error: " + e.getMessage();
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    // ------------------------------------------------------------------ helpers

    public static KeyStore loadKeyStore(String path, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            ks.load(in, pwd(password));
        }
        return ks;
    }

    /** Short description of a PKCS#12 file content, used by the "Check" button. */
    public static String describeKeyStore(String path, String password) throws Exception {
        KeyStore ks = loadKeyStore(path, password);
        StringBuilder sb = new StringBuilder();
        Enumeration<String> aliases = ks.aliases();
        int count = 0;
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            count++;
            sb.append("Alias: ").append(a).append(ks.isKeyEntry(a) ? " (private key)" : " (certificate)").append('\n');
            Certificate c = ks.getCertificate(a);
            if (c instanceof X509Certificate) {
                X509Certificate x = (X509Certificate) c;
                sb.append("  Subject : ").append(x.getSubjectX500Principal().getName()).append('\n');
                sb.append("  Issuer  : ").append(x.getIssuerX500Principal().getName()).append('\n');
                sb.append("  Valid   : ").append(x.getNotBefore()).append("  ->  ").append(x.getNotAfter()).append('\n');
            }
        }
        if (count == 0) {
            sb.append("The keystore is empty.");
        }
        return sb.toString();
    }

    private static char[] pwd(String p) {
        return p == null ? new char[0] : p.toCharArray();
    }

    private static String describeSystemProxy(String url) {
        try {
            ProxySelector sel = ProxySelector.getDefault();
            if (sel == null) {
                return " (none configured)";
            }
            List<java.net.Proxy> list = sel.select(parseUri(url));
            if (list.isEmpty() || list.get(0).type() == java.net.Proxy.Type.DIRECT) {
                return " -> direct connection";
            }
            return " -> " + list.get(0).address();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static byte[] decompress(byte[] data, String encoding) throws IOException {
        InputStream in;
        if (encoding.contains("gzip")) {
            in = new GZIPInputStream(new ByteArrayInputStream(data));
        } else if (encoding.contains("deflate")) {
            in = new InflaterInputStream(new ByteArrayInputStream(data));
        } else {
            throw new IOException("unsupported encoding");
        }
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            is.transferTo(out);
            return out.toByteArray();
        }
    }

    static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    static boolean isText(String contentType, byte[] body) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")
                || ct.contains("x-www-form-urlencoded") || ct.contains("html") || ct.contains("yaml")
                || ct.contains("csv") || ct.contains("graphql")) {
            return true;
        }
        if (ct.startsWith("image/") || ct.startsWith("audio/") || ct.startsWith("video/")
                || ct.contains("octet-stream") || ct.contains("zip") || ct.contains("pdf")) {
            return false;
        }
        int n = Math.min(body.length, 2048);
        for (int i = 0; i < n; i++) {
            if (body[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static final Map<Integer, String> REASONS = new HashMap<>();

    static {
        String[] r = {
                "100", "Continue", "101", "Switching Protocols", "200", "OK", "201", "Created", "202", "Accepted",
                "203", "Non-Authoritative Information", "204", "No Content", "205", "Reset Content",
                "206", "Partial Content", "300", "Multiple Choices", "301", "Moved Permanently", "302", "Found",
                "303", "See Other", "304", "Not Modified", "307", "Temporary Redirect", "308", "Permanent Redirect",
                "400", "Bad Request", "401", "Unauthorized", "402", "Payment Required", "403", "Forbidden",
                "404", "Not Found", "405", "Method Not Allowed", "406", "Not Acceptable",
                "407", "Proxy Authentication Required", "408", "Request Timeout", "409", "Conflict", "410", "Gone",
                "411", "Length Required", "412", "Precondition Failed", "413", "Payload Too Large",
                "414", "URI Too Long", "415", "Unsupported Media Type", "416", "Range Not Satisfiable",
                "417", "Expectation Failed", "418", "I'm a teapot", "422", "Unprocessable Entity",
                "425", "Too Early", "426", "Upgrade Required", "428", "Precondition Required",
                "429", "Too Many Requests", "431", "Request Header Fields Too Large",
                "451", "Unavailable For Legal Reasons", "500", "Internal Server Error", "501", "Not Implemented",
                "502", "Bad Gateway", "503", "Service Unavailable", "504", "Gateway Timeout",
                "505", "HTTP Version Not Supported"};
        for (int i = 0; i < r.length; i += 2) {
            REASONS.put(Integer.parseInt(r[i]), r[i + 1]);
        }
    }

    public static String reasonPhrase(int code) {
        return REASONS.getOrDefault(code, "");
    }

    /** Trust manager accepting everything, including hostname mismatches (used for "insecure"). */
    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, java.net.Socket s) { }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
