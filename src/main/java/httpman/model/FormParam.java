package httpman.model;

/**
 * One field of a form body: used for x-www-form-urlencoded (text only)
 * and multipart/form-data (text or file parts).
 */
public class FormParam {
    public boolean enabled = true;
    public String name = "";
    /** Text value, or the file path when {@link #file} is true. */
    public String value = "";
    /** Multipart only: the value is a path to a file to upload. */
    public boolean file;
    /** Multipart only: optional Content-Type of the part. */
    public String contentType = "";

    public FormParam() {
    }

    public FormParam(String name, String value) {
        this.name = name == null ? "" : name;
        this.value = value == null ? "" : value;
    }

    public static FormParam file(String name, String path) {
        FormParam p = new FormParam(name, path);
        p.file = true;
        return p;
    }

    public FormParam copy() {
        FormParam p = new FormParam(name, value);
        p.enabled = enabled;
        p.file = file;
        p.contentType = contentType;
        return p;
    }
}
