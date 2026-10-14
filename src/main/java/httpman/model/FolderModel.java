package httpman.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A folder used to organise saved requests. Folders can be nested ({@link #parentId} empty = top level). */
public class FolderModel {

    public String id = UUID.randomUUID().toString();
    public String name = "New folder";
    /** Id of the parent folder, "" for the top level. */
    public String parentId = "";
    /** Whether the folder is shown expanded in the request tree. */
    public boolean expanded = false;

    public FolderModel() {
    }

    public FolderModel(String name, String parentId) {
        this.name = name;
        this.parentId = parentId == null ? "" : parentId;
    }

    public FolderModel copy() {
        FolderModel c = new FolderModel(name, parentId);
        c.id = id;
        c.expanded = expanded;
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("parentId", parentId);
        m.put("expanded", expanded);
        return m;
    }

    public static FolderModel fromMap(Map<String, Object> m) {
        FolderModel f = new FolderModel();
        Object o = m.get("id");
        if (o != null) {
            f.id = String.valueOf(o);
        }
        o = m.get("name");
        if (o != null) {
            f.name = String.valueOf(o);
        }
        o = m.get("parentId");
        f.parentId = o == null ? "" : String.valueOf(o);
        o = m.get("expanded");
        f.expanded = o instanceof Boolean && (Boolean) o;
        return f;
    }

    @Override
    public String toString() {
        return name;
    }
}
