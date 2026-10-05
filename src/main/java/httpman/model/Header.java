package httpman.model;

/** A single HTTP header line of a request. */
public class Header {
    public boolean enabled = true;
    public String name = "";
    public String value = "";

    public Header() {
    }

    public Header(String name, String value) {
        this(true, name, value);
    }

    public Header(boolean enabled, String name, String value) {
        this.enabled = enabled;
        this.name = name == null ? "" : name;
        this.value = value == null ? "" : value;
    }

    public Header copy() {
        return new Header(enabled, name, value);
    }
}
