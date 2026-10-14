package httpman.store;

import httpman.model.FolderModel;
import httpman.model.RequestModel;
import httpman.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists saved requests and the folders that organise them to a single JSON file
 * (default: ~/.httpman/requests.json, override with -Dhttpman.home=/some/dir).
 */
public class RequestStore {

    private final Path file;
    /** Display order: the relative order of the requests (resp. folders) of one parent is their order in the list. */
    private final List<RequestModel> requests = new ArrayList<>();
    private final List<FolderModel> folders = new ArrayList<>();

    public RequestStore() {
        this(defaultFile());
    }

    public RequestStore(Path file) {
        this.file = file;
    }

    public static Path defaultFile() {
        String home = System.getProperty("httpman.home");
        Path dir = home != null && !home.isBlank()
                ? Paths.get(home)
                : Paths.get(System.getProperty("user.home"), ".httpman");
        return dir.resolve("requests.json");
    }

    public Path getFile() {
        return file;
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() throws IOException {
        requests.clear();
        folders.clear();
        if (!Files.exists(file)) {
            return;
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return;
        }
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException e) {
            // keep a copy of the unreadable file so that the next save does not lose it
            Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            Files.copy(file, backup);
            throw new IOException("Invalid JSON (" + e.getMessage() + "). A copy was kept in " + backup, e);
        }
        Object list = root instanceof Map ? ((Map<String, Object>) root).get("requests") : root;
        if (list instanceof List) {
            for (Object o : (List<Object>) list) {
                if (o instanceof Map) {
                    requests.add(RequestModel.fromMap((Map<String, Object>) o));
                }
            }
        }
        Object flist = root instanceof Map ? ((Map<String, Object>) root).get("folders") : null;
        if (flist instanceof List) {
            for (Object o : (List<Object>) flist) {
                if (o instanceof Map) {
                    folders.add(FolderModel.fromMap((Map<String, Object>) o));
                }
            }
        }
        repair();
    }

    /** Makes a hand-edited / partly broken file safe: unknown parents go to the top level, cycles are cut. */
    private void repair() {
        Set<String> ids = new HashSet<>();
        for (FolderModel f : folders) {
            ids.add(f.id);
        }
        for (FolderModel f : folders) {
            if (!ids.contains(f.parentId) || f.parentId.equals(f.id)) {
                f.parentId = "";
            }
        }
        for (FolderModel f : folders) {
            Set<String> seen = new HashSet<>();
            FolderModel cur = f;
            while (cur != null && !cur.parentId.isEmpty()) {
                if (!seen.add(cur.id)) {
                    f.parentId = ""; // cycle
                    break;
                }
                cur = findFolderInternal(cur.parentId);
            }
        }
        for (RequestModel r : requests) {
            if (r.folderId == null || !ids.contains(r.folderId)) {
                r.folderId = "";
            }
        }
    }

    public synchronized void persist() throws IOException {
        Files.createDirectories(file.getParent());
        List<Object> list = new ArrayList<>();
        for (RequestModel r : requests) {
            list.add(r.toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> flist = new ArrayList<>();
        for (FolderModel f : folders) {
            flist.add(f.toMap());
        }
        root.put("version", 2);
        root.put("folders", flist);
        root.put("requests", list);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized List<RequestModel> all() {
        return new ArrayList<>(requests);
    }

    public synchronized RequestModel find(String id) {
        for (RequestModel r : requests) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Inserts or replaces (by id) a copy of the request, then writes the file. A replaced request keeps its
     * position and its folder; a new one is added at the end of {@code r.folderId} (top level if unknown).
     */
    public synchronized void save(RequestModel r) throws IOException {
        RequestModel copy = r.copy();
        boolean replaced = false;
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).id.equals(r.id)) {
                copy.folderId = requests.get(i).folderId;
                requests.set(i, copy);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (copy.folderId == null || findFolderInternal(copy.folderId) == null) {
                copy.folderId = "";
            }
            requests.add(copy);
        }
        persist();
    }

    public synchronized void delete(String id) throws IOException {
        requests.removeIf(r -> r.id.equals(id));
        persist();
    }

    // ------------------------------------------------------------------ folders

    /** Copies of all folders, in display order. */
    public synchronized List<FolderModel> folders() {
        List<FolderModel> out = new ArrayList<>();
        for (FolderModel f : folders) {
            out.add(f.copy());
        }
        return out;
    }

    public synchronized FolderModel findFolder(String id) {
        FolderModel f = findFolderInternal(id);
        return f == null ? null : f.copy();
    }

    private FolderModel findFolderInternal(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (FolderModel f : folders) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }

    /** Folder id of a saved request ("" = top level or unknown request). */
    public synchronized String folderOf(String requestId) {
        RequestModel r = find(requestId);
        return r == null ? "" : r.folderId;
    }

    /** Creates a folder at the end of {@code parentId} (top level if unknown) and returns a copy of it. */
    public synchronized FolderModel addFolder(String name, String parentId) throws IOException {
        FolderModel f = new FolderModel(name, findFolderInternal(parentId) == null ? "" : parentId);
        f.expanded = true;
        folders.add(f);
        persist();
        return f.copy();
    }

    public synchronized void renameFolder(String id, String name) throws IOException {
        FolderModel f = findFolderInternal(id);
        if (f != null) {
            f.name = name;
            persist();
        }
    }

    /** Remembers whether a folder is expanded in the tree (written to disk only when it changes). */
    public synchronized void setExpanded(String id, boolean expanded) throws IOException {
        FolderModel f = findFolderInternal(id);
        if (f != null && f.expanded != expanded) {
            f.expanded = expanded;
            persist();
        }
    }

    /** Expands every folder containing the given request. */
    public synchronized void expandPathTo(String requestId) throws IOException {
        RequestModel r = find(requestId);
        boolean changed = false;
        FolderModel f = r == null ? null : findFolderInternal(r.folderId);
        while (f != null) {
            if (!f.expanded) {
                f.expanded = true;
                changed = true;
            }
            f = findFolderInternal(f.parentId);
        }
        if (changed) {
            persist();
        }
    }

    /** Ids of {@code folderId} and of all the folders below it. */
    private Set<String> subtreeIds(String folderId) {
        Set<String> ids = new HashSet<>();
        ids.add(folderId);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (FolderModel f : folders) {
                if (!ids.contains(f.id) && ids.contains(f.parentId)) {
                    ids.add(f.id);
                    grew = true;
                }
            }
        }
        return ids;
    }

    /** Number of requests in the folder, sub-folders included. */
    public synchronized int countRequests(String folderId) {
        Set<String> ids = subtreeIds(folderId);
        int n = 0;
        for (RequestModel r : requests) {
            if (ids.contains(r.folderId)) {
                n++;
            }
        }
        return n;
    }

    /** Deletes the folder with its sub-folders and every request they contain. */
    public synchronized void deleteFolder(String id) throws IOException {
        Set<String> ids = subtreeIds(id);
        folders.removeIf(f -> ids.contains(f.id));
        requests.removeIf(r -> ids.contains(r.folderId));
        persist();
    }

    // ------------------------------------------------------------------ ordering

    /**
     * Moves a request or a folder under {@code newParentId} ("" = top level) at position {@code index} among
     * the siblings of the same kind (folders are compared with folders, requests with requests). The index
     * refers to the sibling list as it was <em>before</em> the move; -1 (or too large) means "at the end".
     *
     * @return false when nothing was moved (unknown id, or a folder dropped into itself / its own sub-folder)
     */
    public synchronized boolean move(String id, boolean isFolder, String newParentId, int index) throws IOException {
        String parent = newParentId == null ? "" : newParentId;
        if (!parent.isEmpty() && findFolderInternal(parent) == null) {
            return false;
        }
        if (isFolder) {
            FolderModel f = findFolderInternal(id);
            if (f == null || subtreeIds(id).contains(parent)) {
                return false;
            }
            index = adjustedIndex(folders, f, f.parentId, parent, index, x -> x.parentId);
            folders.remove(f);
            f.parentId = parent;
            insertAt(folders, f, index, x -> x.parentId);
        } else {
            RequestModel r = find(id);
            if (r == null) {
                return false;
            }
            index = adjustedIndex(requests, r, r.folderId, parent, index, x -> x.folderId);
            requests.remove(r);
            r.folderId = parent;
            insertAt(requests, r, index, x -> x.folderId);
        }
        persist();
        return true;
    }

    /** Converts an index in the old sibling list into one in the list without the moved item. */
    private static <T> int adjustedIndex(List<T> all, T item, String oldParent, String newParent, int index,
                                         java.util.function.Function<T, String> parentOf) {
        if (index >= 0 && oldParent.equals(newParent)) {
            int pos = 0;
            for (T t : all) {
                if (t == item) {
                    break;
                }
                if (parentOf.apply(t).equals(oldParent)) {
                    pos++;
                }
            }
            if (pos < index) {
                return index - 1;
            }
        }
        return index;
    }

    /** Inserts {@code item} (already removed from {@code all}, parent already set) at a sibling index. */
    private static <T> void insertAt(List<T> all, T item, int index, java.util.function.Function<T, String> parentOf) {
        String parent = parentOf.apply(item);
        List<T> siblings = new ArrayList<>();
        for (T t : all) {
            if (parentOf.apply(t).equals(parent)) {
                siblings.add(t);
            }
        }
        if (index < 0 || index >= siblings.size()) {
            all.add(item);
        } else {
            all.add(all.indexOf(siblings.get(index)), item);
        }
    }
}
