package httpman.ui;

import httpman.model.FolderModel;
import httpman.model.RequestModel;
import httpman.store.RequestStore;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tree of the saved requests: folders (which can be nested) first, then requests, in the order kept by the
 * {@link RequestStore}. Items can be reordered / moved into folders by drag and drop.
 */
final class RequestTree extends JTree {

    /** What is being dragged: a request or a folder, identified by id. */
    private static final class NodeRef {
        final String id;
        final boolean folder;

        NodeRef(String id, boolean folder) {
            this.id = id;
            this.folder = folder;
        }
    }

    private static final DataFlavor NODE_FLAVOR = nodeFlavor();

    private static DataFlavor nodeFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + NodeRef.class.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private final RequestStore store;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final Consumer<RequestModel> onRequestSelected;
    private final Runnable onMoved;
    private final Consumer<Exception> onError;
    /** True while the model is rebuilt: selection / expansion events are then not user actions. */
    private boolean rebuilding;
    private boolean filtering;

    RequestTree(RequestStore store, Consumer<RequestModel> onRequestSelected, Runnable onMoved,
                Consumer<Exception> onError) {
        super();
        setModel(model);
        this.store = store;
        this.onRequestSelected = onRequestSelected;
        this.onMoved = onMoved;
        this.onError = onError;

        setRootVisible(false);
        setShowsRootHandles(true);
        setRowHeight(0); // let the renderer decide: requests use two lines
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setCellRenderer(new Renderer());
        ToolTipManager.sharedInstance().registerComponent(this);

        addTreeSelectionListener(e -> {
            if (!rebuilding) {
                RequestModel r = selectedRequest();
                if (r != null) {
                    onRequestSelected.accept(r);
                }
            }
        });
        addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                remember(e.getPath(), true);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                remember(e.getPath(), false);
            }
        });

        setDragEnabled(true);
        setDropMode(DropMode.ON_OR_INSERT);
        setTransferHandler(new Mover());
    }

    // ------------------------------------------------------------------ content

    /**
     * Rebuilds the tree from the store.
     *
     * @param selectId id of the request to select (its folders are expanded), or null
     * @param filter   text matched against request name / URL / method; folders whose name matches show all
     *                 their content. Empty = no filter.
     */
    void reload(String selectId, String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        filtering = !f.isEmpty();
        rebuilding = true;
        try {
            if (selectId != null && !filtering) {
                try {
                    store.expandPathTo(selectId);
                } catch (Exception ex) {
                    onError.accept(ex);
                }
            }
            Map<String, List<FolderModel>> subFolders = new HashMap<>();
            for (FolderModel fo : store.folders()) {
                subFolders.computeIfAbsent(fo.parentId, k -> new ArrayList<>()).add(fo);
            }
            Map<String, List<RequestModel>> requests = new HashMap<>();
            for (RequestModel r : store.all()) {
                requests.computeIfAbsent(r.folderId, k -> new ArrayList<>()).add(r);
            }
            root.removeAllChildren();
            build(root, "", f, false, subFolders, requests);
            model.reload();
            expandRemembered(root);

            DefaultMutableTreeNode sel = selectId == null ? null : findRequestNode(root, selectId);
            if (sel != null) {
                TreePath path = new TreePath(sel.getPath());
                setSelectionPath(path);
                scrollPathToVisible(path);
            } else {
                clearSelection();
            }
        } finally {
            rebuilding = false;
        }
    }

    /** @return true when something was added under {@code parent} */
    private boolean build(DefaultMutableTreeNode parent, String parentId, String filter, boolean showAll,
                          Map<String, List<FolderModel>> subFolders, Map<String, List<RequestModel>> requests) {
        boolean any = false;
        for (FolderModel fo : subFolders.getOrDefault(parentId, Collections.emptyList())) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(fo);
            boolean all = showAll || filter.isEmpty() || fo.name.toLowerCase(Locale.ROOT).contains(filter);
            boolean hasContent = build(node, fo.id, filter, all, subFolders, requests);
            if (hasContent || all) {
                parent.add(node);
                any = true;
            }
        }
        for (RequestModel r : requests.getOrDefault(parentId, Collections.emptyList())) {
            if (showAll || filter.isEmpty() || matches(r, filter)) {
                parent.add(new DefaultMutableTreeNode(r));
                any = true;
            }
        }
        return any;
    }

    private static boolean matches(RequestModel r, String f) {
        return r.name.toLowerCase(Locale.ROOT).contains(f)
                || r.url.toLowerCase(Locale.ROOT).contains(f)
                || r.method.toLowerCase(Locale.ROOT).startsWith(f);
    }

    private void expandRemembered(DefaultMutableTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof FolderModel) {
                if (filtering || ((FolderModel) child.getUserObject()).expanded) {
                    expandPath(new TreePath(child.getPath()));
                }
                expandRemembered(child);
            }
        }
    }

    private void remember(TreePath path, boolean expanded) {
        if (rebuilding || filtering) {
            return;
        }
        Object o = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (o instanceof FolderModel) {
            try {
                store.setExpanded(((FolderModel) o).id, expanded);
            } catch (Exception ex) {
                onError.accept(ex);
            }
        }
    }

    private static DefaultMutableTreeNode findRequestNode(DefaultMutableTreeNode from, String id) {
        @SuppressWarnings("unchecked")
        Enumeration<TreeNode> en = from.depthFirstEnumeration();
        while (en.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) en.nextElement();
            if (n.getUserObject() instanceof RequestModel && ((RequestModel) n.getUserObject()).id.equals(id)) {
                return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ queries

    private Object selectedObject() {
        TreePath p = getSelectionPath();
        return p == null ? null : ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
    }

    RequestModel selectedRequest() {
        Object o = selectedObject();
        return o instanceof RequestModel ? (RequestModel) o : null;
    }

    FolderModel selectedFolder() {
        Object o = selectedObject();
        return o instanceof FolderModel ? (FolderModel) o : null;
    }

    /** Folder where a new item should go: the selected folder, or the folder of the selected request. */
    String contextFolderId() {
        Object o = selectedObject();
        if (o instanceof FolderModel) {
            return ((FolderModel) o).id;
        }
        if (o instanceof RequestModel) {
            return ((RequestModel) o).folderId;
        }
        return "";
    }

    /** The requests currently shown, in display order (top to bottom, expanded or not). */
    List<RequestModel> requestsInOrder() {
        List<RequestModel> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(DefaultMutableTreeNode node, List<RequestModel> out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) node.getChildAt(i);
            if (c.getUserObject() instanceof RequestModel) {
                out.add((RequestModel) c.getUserObject());
            } else {
                collect(c, out);
            }
        }
    }

    /** Selects the row under the mouse (used before showing a context menu). */
    Object selectRowAt(int x, int y) {
        TreePath p = getPathForLocation(x, y);
        if (p == null) {
            // the row's full width is clickable, not only the label
            p = getClosestPathForLocation(x, y);
            if (p != null && (getPathBounds(p) == null || y < getPathBounds(p).y
                    || y > getPathBounds(p).y + getPathBounds(p).height)) {
                p = null;
            }
        }
        if (p == null) {
            return null;
        }
        if (!p.equals(getSelectionPath())) {
            setSelectionPath(p);
        }
        return ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
    }

    // ------------------------------------------------------------------ rendering

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof RequestModel) {
                RequestModel r = (RequestModel) o;
                String color = methodColor(r.method);
                String name = esc(r.name);
                String url = r.url;
                if (url.length() > 60) {
                    url = url.substring(0, 57) + "…";
                }
                url = esc(url);
                setIcon(null);
                setText("<html><b><font color='" + (sel ? "white" : color) + "'>" + esc(r.method)
                        + "</font></b>&nbsp;" + name + "<br><font size='-2' color='" + (sel ? "white" : "gray") + "'>"
                        + url + "</font></html>");
                setToolTipText(r.method + " " + r.url);
            } else if (o instanceof FolderModel) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) value;
                setText("<html><b>" + esc(((FolderModel) o).name) + "</b>"
                        + (n.getChildCount() > 0 ? "&nbsp;<font color='gray'>(" + countRequests(n) + ")</font>" : "")
                        + "</html>");
                setIcon(expanded ? getOpenIcon() : getClosedIcon());
                setToolTipText("Folder " + ((FolderModel) o).name + " — drag requests here");
            }
            setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 2, 3, 4));
            return this;
        }

        private static int countRequests(DefaultMutableTreeNode n) {
            int c = 0;
            for (int i = 0; i < n.getChildCount(); i++) {
                DefaultMutableTreeNode ch = (DefaultMutableTreeNode) n.getChildAt(i);
                c += ch.getUserObject() instanceof RequestModel ? 1 : countRequests(ch);
            }
            return c;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String methodColor(String m) {
            switch (m == null ? "" : m.toUpperCase(Locale.ROOT)) {
                case "GET": return "#1e8c3c";
                case "POST": return "#c87800";
                case "PUT": return "#1e64c8";
                case "PATCH": return "#8c3cb4";
                case "DELETE": return "#c82828";
                default: return "#666666";
            }
        }
    }

    // ------------------------------------------------------------------ drag and drop

    /** Where a drop would put the dragged item. */
    private static final class Target {
        final String parentId;
        final int index; // among siblings of the dragged kind, -1 = end

        Target(String parentId, int index) {
            this.parentId = parentId;
            this.index = index;
        }
    }

    private final class Mover extends TransferHandler {
        private NodeRef dragged;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            Object o = selectedObject();
            if (o instanceof RequestModel) {
                dragged = new NodeRef(((RequestModel) o).id, false);
            } else if (o instanceof FolderModel) {
                dragged = new NodeRef(((FolderModel) o).id, true);
            } else {
                dragged = null;
                return null;
            }
            final NodeRef ref = dragged;
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[]{NODE_FLAVOR};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return NODE_FLAVOR.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) {
                        throw new UnsupportedFlavorException(flavor);
                    }
                    return ref;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || !support.isDataFlavorSupported(NODE_FLAVOR) || dragged == null
                    || filtering) { // while filtering, positions in the tree are not the real ones
                return false;
            }
            support.setShowDropLocation(true);
            return target((JTree.DropLocation) support.getDropLocation()) != null;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Target t = target((JTree.DropLocation) support.getDropLocation());
            NodeRef ref = dragged;
            try {
                if (!t.parentId.isEmpty()) {
                    store.setExpanded(t.parentId, true);
                }
                boolean moved = store.move(ref.id, ref.folder, t.parentId, t.index);
                if (moved) {
                    onMoved.run();
                }
                return moved;
            } catch (Exception ex) {
                onError.accept(ex);
                return false;
            } finally {
                dragged = null;
            }
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            dragged = null; // the store was already updated by importData
        }

        /** Translates a tree drop location into (new parent folder, index among same-kind siblings). */
        private Target target(JTree.DropLocation dl) {
            TreePath path = dl.getPath();
            int child = dl.getChildIndex();
            DefaultMutableTreeNode node = path == null ? root : (DefaultMutableTreeNode) path.getLastPathComponent();

            if (node.getUserObject() instanceof RequestModel) {
                // dropped on a request: put the dragged item just before it
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
                child = parent.getIndex(node);
                node = parent;
            }
            String parentId = node == root ? "" : ((FolderModel) node.getUserObject()).id;

            // never drop a folder into itself or one of its descendants
            if (dragged.folder) {
                for (DefaultMutableTreeNode n = node; n != null && n != root; n = (DefaultMutableTreeNode) n.getParent()) {
                    if (n.getUserObject() instanceof FolderModel && ((FolderModel) n.getUserObject()).id.equals(dragged.id)) {
                        return null;
                    }
                }
            }
            if (child < 0) { // dropped on a folder (or on the empty area): append
                return new Target(parentId, -1);
            }
            int folders = 0;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof FolderModel) {
                    folders++;
                }
            }
            int index = dragged.folder ? Math.min(child, folders) : Math.max(0, child - folders);
            return new Target(parentId, index);
        }
    }
}
