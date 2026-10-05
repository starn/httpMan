package httpman.curl;

import httpman.model.FormParam;
import httpman.model.Header;
import httpman.model.RequestModel;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Converts between {@link RequestModel} and curl command lines. */
public final class CurlConverter {

    private CurlConverter() {
    }

    /** Result of an import: the request plus warnings about ignored options. */
    public static final class ImportResult {
        public final RequestModel request;
        public final List<String> warnings;

        ImportResult(RequestModel request, List<String> warnings) {
            this.request = request;
            this.warnings = warnings;
        }
    }

    // ================================================================== export

    public static String toCurl(RequestModel r) {
        List<String> parts = new ArrayList<>();
        String method = r.method == null || r.method.isBlank() ? "GET" : r.method.trim().toUpperCase(Locale.ROOT);
        StringBuilder first = new StringBuilder("curl");
        if (!(method.equals("GET") && !r.hasBody()) && !(method.equals("POST") && r.hasBody())) {
            if (method.equals("HEAD") && !r.hasBody()) {
                first.append(" --head");
            } else {
                first.append(" -X ").append(method);
            }
        }
        first.append(' ').append(quote(r.url == null ? "" : r.url.trim()));
        parts.add(first.toString());

        boolean multipart = r.bodyType == RequestModel.BodyType.MULTIPART && r.hasBody();
        for (Header h : r.headers) {
            if (h.enabled && !h.name.trim().isEmpty()) {
                if (multipart && h.name.trim().equalsIgnoreCase("content-type")
                        && h.value.trim().toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                    continue; // curl generates it with the boundary
                }
                parts.add("-H " + quote(h.name.trim() + ": " + h.value));
            }
        }
        if (r.hasBody()) {
            switch (r.bodyType) {
                case RAW:
                    parts.add("--data-raw " + quote(r.body));
                    break;
                case FORM_URLENCODED:
                    for (FormParam p : r.enabledFormParams()) {
                        parts.add("--data-urlencode " + quote(URLEncoder.encode(p.name.trim(), StandardCharsets.UTF_8) + "=" + p.value));
                    }
                    break;
                case MULTIPART:
                    for (FormParam p : r.enabledFormParams()) {
                        parts.add("--form " + quote(formArg(p)));
                    }
                    break;
                case BINARY:
                    parts.add("--data-binary " + quote("@" + r.binaryFile.trim()));
                    break;
                default:
                    break;
            }
        }
        if (r.certPath != null && !r.certPath.isBlank()) {
            parts.add("--cert-type P12 --cert " + quote(r.certPath));
            if (r.certPassword != null && !r.certPassword.isEmpty()) {
                parts.add("--pass " + quote(r.certPassword));
            }
        }
        if (r.insecure) {
            parts.add("--insecure");
        }
        if (r.followRedirects) {
            parts.add("--location");
        }
        if (r.timeoutSeconds > 0) {
            parts.add("--max-time " + r.timeoutSeconds);
        }
        if (r.httpVersion == RequestModel.HttpVersion.HTTP_1_1) {
            parts.add("--http1.1");
        } else if (r.httpVersion == RequestModel.HttpVersion.HTTP_2) {
            parts.add("--http2");
        }
        switch (r.proxyMode) {
            case NONE:
                parts.add("--noproxy " + quote("*"));
                break;
            case CUSTOM:
                if (r.proxyHost != null && !r.proxyHost.isBlank()) {
                    parts.add("--proxy " + quote("http://" + r.proxyHost.trim() + ":" + r.proxyPort));
                    if (r.proxyUser != null && !r.proxyUser.isBlank()) {
                        parts.add("--proxy-user " + quote(r.proxyUser + ":" + (r.proxyPassword == null ? "" : r.proxyPassword)));
                    }
                }
                break;
            default:
                break; // SYSTEM: curl uses the http(s)_proxy environment variables by default
        }
        return String.join(" \\\n  ", parts);
    }

    /** curl -F argument for one multipart field. */
    static String formArg(FormParam p) {
        String name = p.name.trim();
        String ct = p.contentType == null ? "" : p.contentType.trim();
        String typeSuffix = ct.isEmpty() ? "" : ";type=" + ct;
        if (p.file) {
            return name + "=@" + curlQuote(p.value.trim()) + typeSuffix;
        }
        String v = p.value;
        boolean plain = !v.isEmpty() && ct.isEmpty() && v.charAt(0) != '@' && v.charAt(0) != '<' && v.charAt(0) != '"'
                && v.indexOf(';') < 0 && v.indexOf(',') < 0;
        return name + "=" + (plain ? v : curlQuote(v)) + typeSuffix;
    }

    /** curl's own double-quote syntax used inside -F values. */
    private static String curlQuote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** POSIX shell single-quoting. */
    static String quote(String s) {
        if (s.matches("[A-Za-z0-9_@%+=:,./-]+")) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ================================================================== import

    private static final Set<String> NO_ARG_IGNORED = new HashSet<>(List.of(
            "-s", "--silent", "-S", "--show-error", "-v", "--verbose", "-i", "--include", "-#", "--progress-bar",
            "-f", "--fail", "--fail-with-body", "-N", "--no-buffer", "-g", "--globoff", "--no-progress-meter",
            "-O", "--remote-name", "-J", "--remote-header-name", "--tr-encoding", "--path-as-is", "-q", "--disable",
            "--http2-prior-knowledge", "--http3", "--tcp-nodelay", "--ssl-no-revoke", "--raw", "-n", "--netrc",
            "-0", "--http1.0", "-1", "--tlsv1", "--tlsv1.0", "--tlsv1.1", "--tlsv1.2", "--tlsv1.3", "-4", "-6",
            "--ipv4", "--ipv6", "--proxy-insecure", "--location-trusted", "--anyauth", "--basic", "--digest",
            "--ntlm", "--negotiate"));

    private static final Set<String> ARG_IGNORED = new HashSet<>(List.of(
            "-o", "--output", "-w", "--write-out", "-c", "--cookie-jar", "-D", "--dump-header", "--resolve",
            "--connect-to", "--retry", "--retry-delay", "--retry-max-time", "--max-redirs", "--interface",
            "--cacert", "--capath", "--key", "--key-type", "--limit-rate", "-r", "--range", "-t", "--telnet-option",
            "--trace", "--trace-ascii", "--stderr", "-K", "--config", "--ciphers", "--tls-max", "-Y",
            "--speed-limit", "-y", "--speed-time", "--unix-socket", "--abstract-unix-socket", "--dns-servers",
            "--proxy-cacert", "--proxy-header", "--oauth2-bearer", "-z", "--time-cond", "--expect100-timeout",
            "--keepalive-time", "--happy-eyeballs-timeout-ms", "--doh-url"));

    public static ImportResult fromCurl(String command) {
        List<String> warnings = new ArrayList<>();
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("The cURL command is empty");
        }
        int i = 0;
        if (tokens.get(0).equals("curl") || tokens.get(0).endsWith("/curl") || tokens.get(0).equalsIgnoreCase("curl.exe")) {
            i = 1;
        } else {
            warnings.add("Command does not start with 'curl', parsing anyway");
        }

        RequestModel r = new RequestModel();
        r.name = "Imported request";
        r.followRedirects = false; // curl default
        r.timeoutSeconds = 0;      // curl default: no timeout
        r.httpVersion = RequestModel.HttpVersion.AUTO;
        r.proxyMode = RequestModel.ProxyMode.SYSTEM;
        r.headers.clear();

        String method = null;
        List<String> data = new ArrayList<>();
        boolean jsonMode = false;
        boolean getMode = false;
        boolean head = false;
        String url = null;
        int connectTimeout = 0;
        String certPass = null;
        List<FormParam> forms = new ArrayList<>();
        String uploadFile = null;
        String binaryDataFile = null;
        boolean urlencodeUsed = false;

        while (i < tokens.size()) {
            String tok = tokens.get(i++);
            String opt = tok;
            String inlineVal = null;

            if (tok.startsWith("--")) {
                int eq = tok.indexOf('=');
                if (eq > 0 && takesArg(tok.substring(0, eq))) {
                    opt = tok.substring(0, eq);
                    inlineVal = tok.substring(eq + 1);
                }
            } else if (tok.startsWith("-") && tok.length() > 2) {
                // short option cluster: -sSL, -XPOST, -H'Accept: x', -sXPOST
                String expanded = null;
                for (int k = 1; k < tok.length(); k++) {
                    String so = "-" + tok.charAt(k);
                    if (takesArg(so)) {
                        String rest = tok.substring(k + 1);
                        // process preceding flags
                        for (int p = 1; p < k; p++) {
                            applyFlag("-" + tok.charAt(p), r, warnings);
                        }
                        opt = so;
                        inlineVal = rest.isEmpty() ? null : rest;
                        expanded = so;
                        break;
                    }
                }
                if (expanded == null) {
                    // all flags without argument
                    boolean flagsHandled = false;
                    for (int k = 1; k < tok.length(); k++) {
                        String so = "-" + tok.charAt(k);
                        if (so.equals("-I")) {
                            head = true;
                        } else if (so.equals("-G")) {
                            getMode = true;
                        } else {
                            applyFlag(so, r, warnings);
                        }
                        flagsHandled = true;
                    }
                    if (flagsHandled) {
                        continue;
                    }
                }
            }

            if (!opt.startsWith("-") || opt.equals("-")) {
                if (url == null) {
                    url = opt;
                } else {
                    warnings.add("Extra URL ignored: " + opt);
                }
                continue;
            }

            String val = null;
            if (takesArg(opt)) {
                if (inlineVal != null) {
                    val = inlineVal;
                } else if (i < tokens.size()) {
                    val = tokens.get(i++);
                } else {
                    warnings.add("Option " + opt + " is missing its value");
                    continue;
                }
            }

            switch (opt) {
                case "-X":
                case "--request":
                    method = val.toUpperCase(Locale.ROOT);
                    break;
                case "--url":
                    url = val;
                    break;
                case "-H":
                case "--header": {
                    if (val.startsWith("@")) {
                        warnings.add("Headers from file not supported: " + val);
                        break;
                    }
                    int c = val.indexOf(':');
                    int sc = val.indexOf(';');
                    if (c > 0) {
                        String value = val.substring(c + 1).trim();
                        if (value.isEmpty()) {
                            warnings.add("Header removal ignored: " + val);
                        } else {
                            r.headers.add(new Header(val.substring(0, c).trim(), value));
                        }
                    } else if (sc > 0 && sc == val.length() - 1) {
                        r.headers.add(new Header(val.substring(0, sc).trim(), "")); // "X-Empty;" syntax
                    } else {
                        warnings.add("Invalid header ignored: " + val);
                    }
                    break;
                }
                case "--data-binary":
                    if (val.startsWith("@") && val.length() > 1 && !val.equals("@-")) {
                        binaryDataFile = val.substring(1);
                    }
                    data.add(readDataArg(val, true, warnings));
                    break;
                case "-d":
                case "--data":
                case "--data-ascii":
                    data.add(readDataArg(val, false, warnings));
                    break;
                case "--data-raw":
                    data.add(val);
                    break;
                case "--data-urlencode":
                    data.add(urlencodeArg(val, warnings));
                    urlencodeUsed = true;
                    break;
                case "--json":
                    data.add(readDataArg(val, true, warnings));
                    jsonMode = true;
                    break;
                case "-F":
                case "--form": {
                    FormParam fp = parseFormArg(val, false, warnings);
                    if (fp != null) {
                        forms.add(fp);
                    }
                    break;
                }
                case "--form-string": {
                    FormParam fp = parseFormArg(val, true, warnings);
                    if (fp != null) {
                        forms.add(fp);
                    }
                    break;
                }
                case "-T":
                case "--upload-file":
                    uploadFile = val;
                    break;
                case "-u":
                case "--user":
                    r.headers.add(new Header("Authorization", "Basic "
                            + Base64.getEncoder().encodeToString(val.getBytes(StandardCharsets.UTF_8))));
                    break;
                case "-A":
                case "--user-agent":
                    r.headers.add(new Header("User-Agent", val));
                    break;
                case "-e":
                case "--referer":
                    r.headers.add(new Header("Referer", val));
                    break;
                case "-b":
                case "--cookie":
                    if (val.contains("=")) {
                        r.headers.add(new Header("Cookie", val));
                    } else {
                        warnings.add("Cookie file ignored: " + val);
                    }
                    break;
                case "-m":
                case "--max-time":
                    r.timeoutSeconds = parseSeconds(val, warnings);
                    break;
                case "--connect-timeout":
                    connectTimeout = parseSeconds(val, warnings);
                    break;
                case "-x":
                case "--proxy":
                    applyProxy(val, r, warnings);
                    break;
                case "-U":
                case "--proxy-user": {
                    int c = val.indexOf(':');
                    r.proxyUser = c >= 0 ? val.substring(0, c) : val;
                    r.proxyPassword = c >= 0 ? val.substring(c + 1) : "";
                    break;
                }
                case "--noproxy":
                    if (val.trim().equals("*")) {
                        r.proxyMode = RequestModel.ProxyMode.NONE;
                    } else {
                        warnings.add("--noproxy host list ignored: " + val);
                    }
                    break;
                case "-E":
                case "--cert": {
                    // path[:password] with '\:' escaping
                    StringBuilder path = new StringBuilder();
                    String pass = null;
                    for (int k = 0; k < val.length(); k++) {
                        char ch = val.charAt(k);
                        if (ch == '\\' && k + 1 < val.length() && val.charAt(k + 1) == ':') {
                            path.append(':');
                            k++;
                        } else if (ch == ':' && !(k == 1 && Character.isLetter(val.charAt(0)))) { // keep C:\ paths
                            pass = val.substring(k + 1);
                            break;
                        } else {
                            path.append(ch);
                        }
                    }
                    r.certPath = path.toString();
                    if (pass != null) {
                        r.certPassword = pass;
                    }
                    break;
                }
                case "--pass":
                    certPass = val;
                    break;
                case "--cert-type":
                    if (!val.equalsIgnoreCase("P12")) {
                        warnings.add("Only P12 client certificates are supported (got " + val + ")");
                    }
                    break;
                case "-I":
                case "--head":
                    head = true;
                    break;
                case "-G":
                case "--get":
                    getMode = true;
                    break;
                default:
                    if (val == null) {
                        applyFlag(opt, r, warnings);
                    } else if (!ARG_IGNORED.contains(opt)) {
                        warnings.add("Unsupported option ignored: " + opt + " " + val);
                    }
            }
        }

        if (url == null) {
            throw new IllegalArgumentException("No URL found in the cURL command");
        }
        if (certPass != null) {
            r.certPassword = certPass;
        }
        if (r.timeoutSeconds == 0 && connectTimeout > 0) {
            r.timeoutSeconds = connectTimeout;
        }

        String joined = String.join("&", data);
        if (!forms.isEmpty()) {
            if (!data.isEmpty()) {
                warnings.add("Both -F and -d were given (curl refuses this); the -d data was ignored");
            }
            r.bodyType = RequestModel.BodyType.MULTIPART;
            r.formParams = forms;
        } else if (uploadFile != null) {
            r.bodyType = RequestModel.BodyType.BINARY;
            r.binaryFile = uploadFile;
            if (method == null) {
                method = "PUT";
            }
        } else if (getMode && !data.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + joined;
        } else if (!data.isEmpty()) {
            String userCt = r.headerValue("Content-Type");
            boolean formCt = userCt == null || userCt.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded");
            List<FormParam> params = jsonMode || !formCt ? null : parseUrlencoded(joined);
            if (binaryDataFile != null && data.size() == 1 && !jsonMode) {
                r.bodyType = RequestModel.BodyType.BINARY;
                r.binaryFile = binaryDataFile;
                if (userCt == null) {
                    r.headers.add(new Header("Content-Type", "application/x-www-form-urlencoded"));
                }
            } else if (params != null && (urlencodeUsed || userCt == null || formCt)) {
                r.bodyType = RequestModel.BodyType.FORM_URLENCODED;
                r.formParams = params;
            } else {
                r.bodyType = RequestModel.BodyType.RAW;
                r.body = joined;
                if (jsonMode) {
                    if (userCt == null) {
                        r.headers.add(new Header("Content-Type", "application/json"));
                    }
                    if (r.headerValue("Accept") == null) {
                        r.headers.add(new Header("Accept", "application/json"));
                    }
                } else if (userCt == null) {
                    r.headers.add(new Header("Content-Type", "application/x-www-form-urlencoded"));
                }
            }
        }

        boolean hasData = !forms.isEmpty() || !data.isEmpty();
        if (method != null) {
            r.method = method;
        } else if (head) {
            r.method = "HEAD";
        } else if (hasData && !getMode) {
            r.method = "POST";
        } else {
            r.method = "GET";
        }
        r.url = url;
        r.name = deriveName(r.method, url);
        return new ImportResult(r, warnings);
    }

    private static void applyFlag(String opt, RequestModel r, List<String> warnings) {
        switch (opt) {
            case "-k":
            case "--insecure":
                r.insecure = true;
                break;
            case "-L":
            case "--location":
                r.followRedirects = true;
                break;
            case "--compressed":
                if (r.headerValue("Accept-Encoding") == null) {
                    r.headers.add(new Header("Accept-Encoding", "gzip, deflate"));
                }
                break;
            case "--http1.1":
                r.httpVersion = RequestModel.HttpVersion.HTTP_1_1;
                break;
            case "--http2":
                r.httpVersion = RequestModel.HttpVersion.HTTP_2;
                break;
            default:
                if (!NO_ARG_IGNORED.contains(opt)) {
                    warnings.add("Unknown option ignored: " + opt);
                }
        }
    }

    private static boolean takesArg(String opt) {
        switch (opt) {
            case "-X": case "--request": case "--url": case "-H": case "--header":
            case "-d": case "--data": case "--data-ascii": case "--data-binary": case "--data-raw":
            case "--data-urlencode": case "--json": case "-F": case "--form": case "--form-string":
            case "-u": case "--user": case "-A": case "--user-agent": case "-e": case "--referer":
            case "-b": case "--cookie": case "-m": case "--max-time": case "--connect-timeout":
            case "-x": case "--proxy": case "-U": case "--proxy-user": case "--noproxy":
            case "-E": case "--cert": case "--pass": case "--cert-type": case "-T": case "--upload-file":
                return true;
            default:
                return ARG_IGNORED.contains(opt);
        }
    }

    private static void applyProxy(String val, RequestModel r, List<String> warnings) {
        String v = val.trim();
        if (v.isEmpty()) {
            r.proxyMode = RequestModel.ProxyMode.NONE;
            return;
        }
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            String s = v.substring(0, scheme).toLowerCase(Locale.ROOT);
            if (s.startsWith("socks")) {
                warnings.add("SOCKS proxies are not supported by the Java HTTP client, using it as HTTP proxy");
            }
            v = v.substring(scheme + 3);
        }
        int at = v.lastIndexOf('@');
        if (at >= 0) {
            String cred = v.substring(0, at);
            v = v.substring(at + 1);
            int c = cred.indexOf(':');
            r.proxyUser = c >= 0 ? cred.substring(0, c) : cred;
            r.proxyPassword = c >= 0 ? cred.substring(c + 1) : "";
        }
        if (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        int colon = v.lastIndexOf(':');
        if (colon > 0 && !v.endsWith("]")) {
            r.proxyHost = v.substring(0, colon);
            try {
                r.proxyPort = Integer.parseInt(v.substring(colon + 1));
            } catch (NumberFormatException e) {
                warnings.add("Invalid proxy port in " + val);
            }
        } else {
            r.proxyHost = v;
            r.proxyPort = 1080; // curl default proxy port
        }
        r.proxyMode = RequestModel.ProxyMode.CUSTOM;
    }

    private static int parseSeconds(String v, List<String> warnings) {
        try {
            return (int) Math.ceil(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            warnings.add("Invalid time value: " + v);
            return 0;
        }
    }

    private static String readDataArg(String val, boolean binary, List<String> warnings) {
        if (val.startsWith("@")) {
            String f = val.substring(1);
            try {
                Path p = Paths.get(f);
                String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                return binary ? content : content.replace("\r", "").replace("\n", "");
            } catch (Exception e) {
                warnings.add("Could not read data file " + f + " (" + e.getMessage() + "), kept literally");
                return val;
            }
        }
        return val;
    }

    private static String urlencodeArg(String val, List<String> warnings) {
        // forms: content | =content | name=content | @file | name@file
        int eq = val.indexOf('=');
        int at = val.indexOf('@');
        if (eq >= 0 && (at < 0 || eq < at)) {
            String name = val.substring(0, eq);
            String enc = URLEncoder.encode(val.substring(eq + 1), StandardCharsets.UTF_8).replace("+", "%20");
            return name.isEmpty() ? enc : name + "=" + enc;
        }
        if (at >= 0) {
            String name = val.substring(0, at);
            String content = readDataArg("@" + val.substring(at + 1), true, warnings);
            String enc = URLEncoder.encode(content, StandardCharsets.UTF_8).replace("+", "%20");
            return name.isEmpty() ? enc : name + "=" + enc;
        }
        return URLEncoder.encode(val, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Parses a=1&b=2 into fields; null if the text is not a proper urlencoded form. */
    static List<FormParam> parseUrlencoded(String s) {
        List<FormParam> out = new ArrayList<>();
        if (s.isEmpty()) {
            return null;
        }
        for (String pair : s.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || pair.indexOf('\n') >= 0) {
                return null;
            }
            try {
                out.add(new FormParam(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                return null; // invalid % escape: keep the body raw
            }
        }
        return out;
    }

    /**
     * Parses a curl -F argument: name=value, name="quoted \"value\"", name=@file, name=<file,
     * each optionally followed by ;type=... ;filename=... ;headers=...
     */
    static FormParam parseFormArg(String arg, boolean literal, List<String> warnings) {
        int eq = arg.indexOf('=');
        if (eq <= 0) {
            warnings.add("Invalid form field ignored: " + arg);
            return null;
        }
        FormParam p = new FormParam(arg.substring(0, eq), "");
        String rest = arg.substring(eq + 1);
        if (literal) {
            p.value = rest;
            return p;
        }
        boolean isFile = false;
        boolean fromFile = false;
        if (rest.startsWith("@")) {
            isFile = true;
            rest = rest.substring(1);
        } else if (rest.startsWith("<")) {
            fromFile = true;
            rest = rest.substring(1);
        }
        // value: quoted or up to the first ';'
        StringBuilder value = new StringBuilder();
        int i = 0;
        if (rest.startsWith("\"")) {
            i = 1;
            while (i < rest.length() && rest.charAt(i) != '"') {
                char c = rest.charAt(i);
                if (c == '\\' && i + 1 < rest.length() && (rest.charAt(i + 1) == '"' || rest.charAt(i + 1) == '\\')) {
                    value.append(rest.charAt(i + 1));
                    i += 2;
                } else {
                    value.append(c);
                    i++;
                }
            }
            i++; // closing quote
        } else {
            int sc = findParamSeparator(rest, 0);
            value.append(sc < 0 ? rest : rest.substring(0, sc));
            i = sc < 0 ? rest.length() : sc;
        }
        // parameters
        while (i < rest.length()) {
            int sc = rest.indexOf(';', i);
            if (sc < 0) {
                break;
            }
            int next = findParamSeparator(rest, sc + 1);
            String param = (next < 0 ? rest.substring(sc + 1) : rest.substring(sc + 1, next)).trim();
            i = next < 0 ? rest.length() : next;
            String lower = param.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type=")) {
                p.contentType = param.substring(5).trim();
            } else if (lower.startsWith("filename=")) {
                warnings.add("Custom file name for form field '" + p.name + "' ignored (the real file name is sent)");
            } else if (!param.isEmpty()) {
                warnings.add("Form field parameter ignored: " + param);
            }
        }
        if (isFile) {
            p.file = true;
            p.value = value.toString();
        } else if (fromFile) {
            p.value = readDataArg("@" + value, true, warnings);
        } else {
            p.value = value.toString();
        }
        return p;
    }

    /** Index of the next ';' that starts a known form parameter, or -1. */
    private static int findParamSeparator(String s, int from) {
        int i = s.indexOf(';', from);
        while (i >= 0) {
            String after = s.substring(i + 1).trim().toLowerCase(Locale.ROOT);
            if (after.startsWith("type=") || after.startsWith("filename=") || after.startsWith("headers=")
                    || after.startsWith("encoder=")) {
                return i;
            }
            i = s.indexOf(';', i + 1);
        }
        return -1;
    }

    private static String deriveName(String method, String url) {
        String u = url.replaceFirst("(?i)^https?://", "");
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        if (u.length() > 60) {
            u = u.substring(0, 57) + "...";
        }
        return method + " " + u;
    }

    /**
     * Splits a shell command line (bash style) into words: handles single quotes, double quotes,
     * $'ANSI-C' quotes, backslash escapes and line continuations (\ or ^ or ` at end of line).
     */
    public static List<String> tokenize(String cmd) {
        List<String> out = new ArrayList<>();
        String s = cmd.replace("\r\n", "\n")
                .replaceAll("\\\\\\n", " ")     // bash continuation
                .replaceAll("\\^\\n", " ")      // windows cmd continuation
                .replaceAll("`\\n", " ");       // powershell continuation
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
                i++;
            } else if (c == '\'') {
                inWord = true;
                int end = s.indexOf('\'', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Unterminated single quote");
                }
                cur.append(s, i + 1, end);
                i = end + 1;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                inWord = true;
                i += 2;
                while (true) {
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("Unterminated $'...' quote");
                    }
                    char d = s.charAt(i);
                    if (d == '\'') {
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < s.length()) {
                        char e = s.charAt(i + 1);
                        i += 2;
                        switch (e) {
                            case 'n': cur.append('\n'); break;
                            case 't': cur.append('\t'); break;
                            case 'r': cur.append('\r'); break;
                            case '0': cur.append('\0'); break;
                            case 'e': case 'E': cur.append((char) 27); break;
                            case 'x': {
                                int j = i;
                                while (j < s.length() && j < i + 2 && Character.digit(s.charAt(j), 16) >= 0) {
                                    j++;
                                }
                                if (j > i) {
                                    cur.append((char) Integer.parseInt(s.substring(i, j), 16));
                                    i = j;
                                } else {
                                    cur.append("\\x");
                                }
                                break;
                            }
                            case 'u': {
                                int j = i;
                                while (j < s.length() && j < i + 4 && Character.digit(s.charAt(j), 16) >= 0) {
                                    j++;
                                }
                                if (j > i) {
                                    cur.append((char) Integer.parseInt(s.substring(i, j), 16));
                                    i = j;
                                } else {
                                    cur.append("\\u");
                                }
                                break;
                            }
                            default: cur.append(e);
                        }
                    } else {
                        cur.append(d);
                        i++;
                    }
                }
            } else if (c == '"') {
                inWord = true;
                i++;
                while (true) {
                    if (i >= s.length()) {
                        throw new IllegalArgumentException("Unterminated double quote");
                    }
                    char d = s.charAt(i);
                    if (d == '"') {
                        i++;
                        break;
                    }
                    if (d == '\\' && i + 1 < s.length() && "\"\\$`\n".indexOf(s.charAt(i + 1)) >= 0) {
                        cur.append(s.charAt(i + 1));
                        i += 2;
                    } else {
                        cur.append(d);
                        i++;
                    }
                }
            } else if (c == '\\' && i + 1 < s.length()) {
                inWord = true;
                cur.append(s.charAt(i + 1));
                i += 2;
            } else {
                inWord = true;
                cur.append(c);
                i++;
            }
        }
        if (inWord) {
            out.add(cur.toString());
        }
        return out;
    }
}
