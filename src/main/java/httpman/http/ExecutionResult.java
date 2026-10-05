package httpman.http;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one request execution, successful or not. */
public class ExecutionResult {
    /** Textual representation of what was (or would have been) sent. Always filled. */
    public String rawRequest = "";
    /** Textual representation of the received response (status line + headers + body). */
    public String rawResponse = "";

    public int statusCode = -1;
    public String reasonPhrase = "";
    public String protocol = "";
    public List<String[]> responseHeaders = new ArrayList<>();
    public byte[] bodyBytes = new byte[0];
    /** Decoded body (null for binary content). */
    public String bodyText;
    public String contentType = "";
    public String finalUri = "";

    public long durationMs;
    public Throwable error;
    public boolean cancelled;

    /** Human readable execution log (settings used, redirects, errors...). */
    public final StringBuilder log = new StringBuilder();

    public boolean isSuccess() {
        return error == null && statusCode >= 0;
    }

    void log(String line) {
        log.append(line).append('\n');
    }
}
