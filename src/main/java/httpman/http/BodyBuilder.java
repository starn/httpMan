package httpman.http;

import httpman.model.FormParam;
import httpman.model.RequestModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** Turns the body part of a {@link RequestModel} into bytes + content type. */
public final class BodyBuilder {

    /** The encoded body. */
    public static final class Body {
        public final byte[] bytes;
        /** Content-Type the body needs (null = leave headers alone). */
        public final String contentType;
        /** Whether that content type must replace one set by the user (multipart boundary). */
        public final boolean forceContentType;
        /** Human readable version for the "raw request" view. */
        public final String display;

        Body(byte[] bytes, String contentType, boolean forceContentType, String display) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.forceContentType = forceContentType;
            this.display = display;
        }
    }

    private BodyBuilder() {
    }

    /**
     * @param readFiles when false, files are not read (used to preview the raw request when a file is missing)
     */
    public static Body build(RequestModel r, boolean readFiles) throws IOException {
        if (!r.hasBody()) {
            return new Body(new byte[0], null, false, "");
        }
        switch (r.bodyType) {
            case RAW: {
                return new Body(r.body.getBytes(StandardCharsets.UTF_8), null, false, r.body);
            }
            case FORM_URLENCODED: {
                StringBuilder sb = new StringBuilder();
                for (FormParam p : r.enabledFormParams()) {
                    if (sb.length() > 0) {
                        sb.append('&');
                    }
                    sb.append(encode(p.name.trim())).append('=').append(encode(p.value));
                }
                String s = sb.toString();
                return new Body(s.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", false, s);
            }
            case MULTIPART:
                return multipart(r, readFiles);
            case BINARY: {
                Path p = Paths.get(r.binaryFile.trim());
                byte[] data = readFiles ? Files.readAllBytes(p) : new byte[0];
                String display = "[binary file " + p + (readFiles ? ", " + data.length + " bytes]" : "]");
                return new Body(data, guessType(p.getFileName().toString()), false, display);
            }
            default:
                return new Body(new byte[0], null, false, "");
        }
    }

    private static Body multipart(RequestModel r, boolean readFiles) throws IOException {
        String boundary = "----HttpManBoundary" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder display = new StringBuilder();
        for (FormParam p : r.enabledFormParams()) {
            StringBuilder head = new StringBuilder();
            head.append("--").append(boundary).append("\r\n");
            head.append("Content-Disposition: form-data; name=\"").append(escapeQuoted(p.name.trim())).append('"');
            byte[] content;
            String shown;
            String ct = p.contentType == null ? "" : p.contentType.trim();
            if (p.file) {
                Path path = Paths.get(p.value.trim());
                String fileName = path.getFileName() == null ? "file" : path.getFileName().toString();
                head.append("; filename=\"").append(escapeQuoted(fileName)).append('"');
                if (ct.isEmpty()) {
                    ct = guessType(fileName);
                }
                content = readFiles ? Files.readAllBytes(path) : new byte[0];
                shown = "[content of file " + path + (readFiles ? ", " + content.length + " bytes]" : "]");
            } else {
                content = p.value.getBytes(StandardCharsets.UTF_8);
                shown = p.value;
            }
            head.append("\r\n");
            if (!ct.isEmpty()) {
                head.append("Content-Type: ").append(ct).append("\r\n");
            }
            head.append("\r\n");
            byte[] h = head.toString().getBytes(StandardCharsets.UTF_8);
            out.write(h);
            out.write(content);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            display.append(head.toString().replace("\r\n", "\n")).append(shown).append('\n');
        }
        String end = "--" + boundary + "--\r\n";
        out.write(end.getBytes(StandardCharsets.UTF_8));
        display.append("--").append(boundary).append("--\n");
        return new Body(out.toByteArray(), "multipart/form-data; boundary=" + boundary, true, display.toString());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escapeQuoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", " ");
    }

    static String guessType(String fileName) {
        String t = URLConnection.guessContentTypeFromName(fileName);
        if (t == null) {
            String f = fileName.toLowerCase();
            if (f.endsWith(".json")) {
                t = "application/json";
            } else if (f.endsWith(".pdf")) {
                t = "application/pdf";
            } else if (f.endsWith(".csv")) {
                t = "text/csv";
            } else if (f.endsWith(".yaml") || f.endsWith(".yml")) {
                t = "application/yaml";
            } else {
                t = "application/octet-stream";
            }
        }
        return t;
    }
}
