package httpman.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything needed to describe (and persist) one HTTP request. */
public class RequestModel {

    public enum ProxyMode { NONE, SYSTEM, CUSTOM }

    public enum HttpVersion { AUTO, HTTP_1_1, HTTP_2 }

    public String tlsVersion = "";
    public String hostOverride = "";

    public enum BodyType {
        NONE("none"), RAW("raw (text / JSON / XML…)"), FORM_URLENCODED("x-www-form-urlencoded"),
        MULTIPART("form-data (multipart)"), BINARY("binary file");

        private final String label;

        BodyType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public String id = UUID.randomUUID().toString();
    public String name = "New request";
    public String method = "GET";
    public String url = "";
    public List<Header> headers = new ArrayList<>();
    public BodyType bodyType = BodyType.NONE;
    /** RAW body text. */
    public String body = "";
    /** FORM_URLENCODED and MULTIPART fields. */
    public List<FormParam> formParams = new ArrayList<>();
    /** BINARY body: path of the file to send. */
    public String binaryFile = "";

    // client certificate (optional)
    public String certPath = "";
    public String certPassword = "";

    // settings
    public int timeoutSeconds = 15;
    public boolean followRedirects = true;
    public boolean insecure = false;
    public HttpVersion httpVersion = HttpVersion.HTTP_1_1;

    // proxy
    public ProxyMode proxyMode = ProxyMode.SYSTEM;
    public String proxyHost = "";
    public int proxyPort = 8080;
    public String proxyUser = "";
    public String proxyPassword = "";

    public RequestModel copy() {
        RequestModel c = fromMap(toMap());
        return c;
    }

    /** True when the request will carry a body. */
    public boolean hasBody() {
        switch (bodyType) {
            case RAW:
                return body != null && !body.isEmpty();
            case FORM_URLENCODED:
            case MULTIPART:
                for (FormParam p : formParams) {
                    if (p.enabled && !p.name.trim().isEmpty()) {
                        return true;
                    }
                }
                return false;
            case BINARY:
                return binaryFile != null && !binaryFile.isBlank();
            default:
                return false;
        }
    }

    public List<FormParam> enabledFormParams() {
        List<FormParam> out = new ArrayList<>();
        for (FormParam p : formParams) {
            if (p.enabled && !p.name.trim().isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    public String headerValue(String headerName) {
        for (Header h : headers) {
            if (h.enabled && h.name.trim().equalsIgnoreCase(headerName)) {
                return h.value;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ (de)serialization

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("method", method);
        m.put("url", url);
        List<Object> hs = new ArrayList<>();
        for (Header h : headers) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("enabled", h.enabled);
            hm.put("name", h.name);
            hm.put("value", h.value);
            hs.add(hm);
        }
        m.put("headers", hs);
        m.put("bodyType", bodyType.name());
        m.put("body", body);
        List<Object> fs = new ArrayList<>();
        for (FormParam p : formParams) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("enabled", p.enabled);
            pm.put("name", p.name);
            pm.put("value", p.value);
            pm.put("file", p.file);
            pm.put("contentType", p.contentType);
            fs.add(pm);
        }
        m.put("formParams", fs);
        m.put("binaryFile", binaryFile);
        m.put("certPath", certPath);
        m.put("certPassword", certPassword);
        m.put("timeoutSeconds", timeoutSeconds);
        m.put("followRedirects", followRedirects);
        m.put("insecure", insecure);
        m.put("httpVersion", httpVersion.name());
        m.put("proxyMode", proxyMode.name());
        m.put("proxyHost", proxyHost);
        m.put("proxyPort", proxyPort);
        m.put("proxyUser", proxyUser);
        m.put("proxyPassword", proxyPassword);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static RequestModel fromMap(Map<String, Object> m) {
        RequestModel r = new RequestModel();
        r.id = str(m, "id", r.id);
        r.name = str(m, "name", r.name);
        r.method = str(m, "method", r.method);
        r.url = str(m, "url", r.url);
        Object hs = m.get("headers");
        if (hs instanceof List) {
            for (Object o : (List<Object>) hs) {
                if (o instanceof Map) {
                    Map<String, Object> hm = (Map<String, Object>) o;
                    r.headers.add(new Header(bool(hm, "enabled", true), str(hm, "name", ""), str(hm, "value", "")));
                }
            }
        }
        r.body = str(m, "body", "");
        try {
            r.bodyType = BodyType.valueOf(str(m, "bodyType", r.body.isEmpty() ? "NONE" : "RAW"));
        } catch (IllegalArgumentException ignored) {
            r.bodyType = r.body.isEmpty() ? BodyType.NONE : BodyType.RAW;
        }
        Object fs = m.get("formParams");
        if (fs instanceof List) {
            for (Object o : (List<Object>) fs) {
                if (o instanceof Map) {
                    Map<String, Object> pm = (Map<String, Object>) o;
                    FormParam p = new FormParam(str(pm, "name", ""), str(pm, "value", ""));
                    p.enabled = bool(pm, "enabled", true);
                    p.file = bool(pm, "file", false);
                    p.contentType = str(pm, "contentType", "");
                    r.formParams.add(p);
                }
            }
        }
        r.binaryFile = str(m, "binaryFile", "");
        r.certPath = str(m, "certPath", "");
        r.certPassword = str(m, "certPassword", "");
        r.timeoutSeconds = integer(m, "timeoutSeconds", r.timeoutSeconds);
        r.followRedirects = bool(m, "followRedirects", r.followRedirects);
        r.insecure = bool(m, "insecure", r.insecure);
        try {
            r.httpVersion = HttpVersion.valueOf(str(m, "httpVersion", r.httpVersion.name()));
        } catch (IllegalArgumentException ignored) {
        }
        try {
            r.proxyMode = ProxyMode.valueOf(str(m, "proxyMode", r.proxyMode.name()));
        } catch (IllegalArgumentException ignored) {
        }
        r.proxyHost = str(m, "proxyHost", "");
        r.proxyPort = integer(m, "proxyPort", r.proxyPort);
        r.proxyUser = str(m, "proxyUser", "");
        r.proxyPassword = str(m, "proxyPassword", "");
        return r;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object o = m.get(k);
        return o == null ? def : String.valueOf(o);
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m.get(k);
        return o instanceof Boolean ? (Boolean) o : def;
    }

    private static int integer(Map<String, Object> m, String k, int def) {
        Object o = m.get(k);
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return def;
    }

    @Override
    public String toString() {
        return name;
    }
}
