package httpman.store;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists saved requests to a single JSON file
 * (default: ~/.httpman/requests.json, override with -Dhttpman.home=/some/dir).
 */
public class RequestStore {

    private final Path file;
    private final List<RequestModel> requests = new ArrayList<>();

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
        sort();
    }

    public synchronized void persist() throws IOException {
        Files.createDirectories(file.getParent());
        List<Object> list = new ArrayList<>();
        for (RequestModel r : requests) {
            list.add(r.toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
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

    /** Inserts or replaces (by id) a copy of the request, then writes the file. */
    public synchronized void save(RequestModel r) throws IOException {
        RequestModel copy = r.copy();
        boolean replaced = false;
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).id.equals(r.id)) {
                requests.set(i, copy);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            requests.add(copy);
        }
        sort();
        persist();
    }

    public synchronized void delete(String id) throws IOException {
        requests.removeIf(r -> r.id.equals(id));
        persist();
    }

    private void sort() {
        requests.sort(Comparator.comparing((RequestModel r) -> r.name.toLowerCase()).thenComparing(r -> r.id));
    }
}
