package httpman.ui;

import httpman.curl.CurlConverter;
import httpman.http.ExecutionResult;
import httpman.http.HttpExecutor;
import httpman.model.Header;
import httpman.model.RequestModel;
import httpman.store.RequestStore;
import httpman.util.Json;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;
import java.util.Locale;

/** Main window of HttpMan. */
public class MainFrame extends JFrame {

    private static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"};
    /** Labels shown in the "TLS version" combo and the matching JSSE protocol names ("" = JVM default / negotiated). */
    private static final String[] TLS_LABELS = {"Default (negotiated)", "TLS 1.0", "TLS 1.1", "TLS 1.2", "TLS 1.3"};
    private static final String[] TLS_VALUES = {"", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"};

    private final RequestStore store;
    private final HttpExecutor executor = new HttpExecutor();

    // ---- left panel
    private final JTextField filterField = new JTextField();
    private final DefaultListModel<RequestModel> listModel = new DefaultListModel<>();
    private final JList<RequestModel> requestList = new JList<>(listModel);
    private boolean ignoreSelection;

    // ---- request line
    private final JTextField nameField = new JTextField();
    private final JComboBox<String> methodCombo = new JComboBox<>(METHODS);
    private final JTextField urlField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton saveButton = new JButton("Save");
    private final JButton revertButton = new JButton("Revert");

    // ---- request tabs
    private final JTabbedPane requestTabs = new JTabbedPane();
    private final HeadersTableModel headersModel = new HeadersTableModel();
    private final JTable headersTable = new JTable(headersModel);
    private final JTextArea bodyArea = UiUtil.codeArea(true);
    private final JComboBox<RequestModel.BodyType> bodyTypeCombo = new JComboBox<>(RequestModel.BodyType.values());
    private final java.awt.CardLayout bodyCards = new java.awt.CardLayout();
    private final JPanel bodyCardPanel = new JPanel(bodyCards);
    private final FormParamsTableModel urlencodedModel = new FormParamsTableModel(false);
    private final JTable urlencodedTable = new JTable(urlencodedModel);
    private final FormParamsTableModel multipartModel = new FormParamsTableModel(true);
    private final JTable multipartTable = new JTable(multipartModel);
    private final JTextField binaryFileField = new JTextField();
    private final JTextField certPathField = new JTextField();
    private final JPasswordField certPasswordField = new JPasswordField();
    private final JTextArea certInfoArea = UiUtil.codeArea(false);
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 3600, 1));
    private final JCheckBox followRedirectsBox = new JCheckBox("Follow redirects");
    private final JCheckBox insecureBox = new JCheckBox("Disable TLS certificate verification (insecure)");
    private final JComboBox<RequestModel.HttpVersion> httpVersionCombo = new JComboBox<>(RequestModel.HttpVersion.values());
    private final JComboBox<String> tlsVersionCombo = new JComboBox<>(TLS_LABELS);
    private final JTextField hostOverrideField = new JTextField(24);
    private final JRadioButton proxyNone = new JRadioButton("No proxy");
    private final JRadioButton proxySystem = new JRadioButton("System proxy");
    private final JRadioButton proxyCustom = new JRadioButton("Custom proxy");
    private final JTextField proxyHostField = new JTextField();
    private final JSpinner proxyPortSpinner = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
    private final JTextField proxyUserField = new JTextField();
    private final JPasswordField proxyPasswordField = new JPasswordField();
    private final JTextArea curlArea = UiUtil.codeArea(true);
    private final JLabel curlMessage = new JLabel(" ");

    // ---- response
    private final ResponsePanel responsePanel = new ResponsePanel();

    // ---- state
    private String currentId;
    private String savedSnapshot = "";
    private HttpExecutor.Execution running;

    public MainFrame(RequestStore store) {
        super("HttpMan");
        this.store = store;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildRequestPanel(), responsePanel);
        right.setResizeWeight(0.45);
        right.setContinuousLayout(true);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeftPanel(), right);
        main.setDividerLocation(260);
        main.setContinuousLayout(true);
        getContentPane().add(main, BorderLayout.CENTER);
        setJMenuBar(buildMenuBar());

        setSize(1280, 860);
        setLocationByPlatform(true);

        refreshList(null);
        if (!listModel.isEmpty()) {
            RequestModel first = listModel.get(0);
            loadEditor(first);
            refreshList(first.id);
        } else {
            loadEditor(new RequestModel());
        }

        // periodically refresh "dirty" indicators (cheap, avoids listeners everywhere)
        new Timer(400, e -> updateDirtyState()).start();
    }

    // ================================================================== layout

    private JComponent buildLeftPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 0));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.add(new JLabel("Filter:"), BorderLayout.WEST);
        top.add(filterField, BorderLayout.CENTER);
        filterField.setToolTipText("Filter by name, method or URL");
        filterField.getDocument().addDocumentListener(onChange(() -> refreshList(currentId)));
        p.add(top, BorderLayout.NORTH);

        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestList.setCellRenderer(new RequestRenderer());
        requestList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !ignoreSelection) {
                onListSelection();
            }
        });
        JPopupMenu popup = new JPopupMenu();
        popup.add(item("Revert to last save", this::revertRequest));
        popup.add(item("Duplicate", this::duplicateRequest));
        popup.add(item("Copy as cURL", this::copyAsCurl));
        popup.addSeparator();
        popup.add(item("Delete", this::deleteRequest));
        requestList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int idx = requestList.locationToIndex(e.getPoint());
                    if (idx >= 0 && idx != requestList.getSelectedIndex()) {
                        requestList.setSelectedIndex(idx);
                    }
                    if (idx >= 0) {
                        popup.show(requestList, e.getX(), e.getY());
                    }
                }
            }
        });
        p.add(new JScrollPane(requestList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(button("New", "New request", this::newRequest));
        buttons.add(button("Duplicate", "Duplicate the selected request", this::duplicateRequest));
        buttons.add(button("Delete", "Delete the selected request", this::deleteRequest));
        p.add(buttons, BorderLayout.SOUTH);
        p.setMinimumSize(new Dimension(180, 100));
        return p;
    }

    private JComponent buildRequestPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        top.add(new JLabel("Name"), c);
        c.gridx = 1;
        c.gridwidth = 5;
        c.weightx = 1;
        top.add(nameField, c);

        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        methodCombo.setEditable(true);
        methodCombo.setPrototypeDisplayValue("OPTIONS__");
        top.add(methodCombo, c);
        c.gridx = 1;
        c.weightx = 1;
        c.gridwidth = 2;
        urlField.setFont(UiUtil.monoFont());
        urlField.setToolTipText("Request URL (press Enter to send)");
        urlField.addActionListener(e -> sendOrCancel());
        top.add(urlField, c);
        c.gridx = 3;
        c.weightx = 0;
        c.gridwidth = 1;
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        sendButton.setToolTipText("Execute the request (" + shortcutText(KeyEvent.VK_ENTER) + ")");
        sendButton.addActionListener(e -> sendOrCancel());
        top.add(sendButton, c);
        c.gridx = 4;
        saveButton.setToolTipText("Save the request (" + shortcutText(KeyEvent.VK_S) + ")");
        saveButton.addActionListener(e -> saveRequest());
        top.add(saveButton, c);
        c.gridx = 5;
        revertButton.setToolTipText("Discard the modifications and reload the last saved version");
        revertButton.addActionListener(e -> revertRequest());
        top.add(revertButton, c);
        p.add(top, BorderLayout.NORTH);

        requestTabs.addTab("Headers", buildHeadersTab());
        requestTabs.addTab("Body", buildBodyTab());
        requestTabs.addTab("Client certificate", buildCertTab());
        requestTabs.addTab("Settings", buildSettingsTab());
        requestTabs.addTab("cURL", buildCurlTab());
        p.add(requestTabs, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildHeadersTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        headersTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        headersTable.setRowHeight(Math.max(22, headersTable.getRowHeight()));
        headersTable.getColumnModel().getColumn(0).setMaxWidth(40);
        headersTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        headersTable.getColumnModel().getColumn(2).setPreferredWidth(500);
        headersTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        headersTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeHeader");
        headersTable.getActionMap().put("removeHeader", action(this::removeHeaders));
        p.add(new JScrollPane(headersTable), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(button("Add", "Add a header", this::addHeader));
        buttons.add(button("Remove", "Remove the selected headers", this::removeHeaders));
        JComboBox<String> common = new JComboBox<>(new String[]{
                "Add common header…",
                "Content-Type: application/json",
                "Content-Type: application/xml",
                "Content-Type: application/x-www-form-urlencoded",
                "Content-Type: text/plain",
                "Accept: application/json",
                "Accept: */*",
                "Accept-Encoding: gzip, deflate",
                "Authorization: Bearer ",
                "Authorization: Basic ",
                "Cache-Control: no-cache"});
        common.addActionListener(e -> {
            int i = common.getSelectedIndex();
            if (i > 0) {
                String s = (String) common.getSelectedItem();
                int colon = s.indexOf(':');
                stopHeaderEditing();
                setOrAddHeader(s.substring(0, colon), s.substring(colon + 1).trim());
                common.setSelectedIndex(0);
            }
        });
        buttons.add(common);
        p.add(buttons, BorderLayout.SOUTH);
        headersModel.addTableModelListener(e -> updateHeadersTitle());
        return p;
    }

    private JComponent buildBodyTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel typeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeBar.add(new JLabel("Body type"));
        typeBar.add(bodyTypeCombo);
        bodyTypeCombo.addActionListener(e -> {
            RequestModel.BodyType t = (RequestModel.BodyType) bodyTypeCombo.getSelectedItem();
            bodyCards.show(bodyCardPanel, t.name());
            updateBodyTitle();
        });
        p.add(typeBar, BorderLayout.NORTH);

        // none
        JLabel none = new JLabel("This request has no body.", JLabel.CENTER);
        none.setForeground(Color.GRAY);
        bodyCardPanel.add(none, RequestModel.BodyType.NONE.name());

        // raw
        JPanel raw = new JPanel(new BorderLayout(0, 4));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JComboBox<String> ct = new JComboBox<>(new String[]{"Set Content-Type…", "application/json", "application/xml",
                "text/plain", "text/html", "application/javascript", "application/yaml"});
        ct.addActionListener(e -> {
            if (ct.getSelectedIndex() > 0) {
                stopHeaderEditing();
                setOrAddHeader("Content-Type", (String) ct.getSelectedItem());
                ct.setSelectedIndex(0);
            }
        });
        tools.add(ct);
        tools.add(button("Format JSON", "Pretty print the JSON body", () -> {
            String pretty = Json.prettyPrint(bodyArea.getText());
            if (pretty == null) {
                JOptionPane.showMessageDialog(this, "The body is not valid JSON.", "Format JSON", JOptionPane.WARNING_MESSAGE);
            } else {
                bodyArea.setText(pretty);
            }
        }));
        tools.add(button("Load from file…", "Replace the body with the content of a text file", this::loadBodyFromFile));
        JLabel hint = new JLabel("  Sent as UTF-8 text.");
        hint.setForeground(Color.GRAY);
        tools.add(hint);
        raw.add(tools, BorderLayout.NORTH);
        raw.add(new JScrollPane(bodyArea), BorderLayout.CENTER);
        bodyArea.getDocument().addDocumentListener(onChange(this::updateBodyTitle));
        bodyCardPanel.add(raw, RequestModel.BodyType.RAW.name());

        // x-www-form-urlencoded
        bodyCardPanel.add(buildFormPanel(urlencodedTable, urlencodedModel,
                        "Fields are URL-encoded; Content-Type application/x-www-form-urlencoded is added if not set."),
                RequestModel.BodyType.FORM_URLENCODED.name());

        // multipart
        bodyCardPanel.add(buildFormPanel(multipartTable, multipartModel,
                        "Content-Type multipart/form-data with boundary is generated. Type 'File' = value is a file path."),
                RequestModel.BodyType.MULTIPART.name());

        // binary
        JPanel bin = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        c.gridx = 0;
        c.gridy = 0;
        bin.add(new JLabel("File"), c);
        c.gridx = 1;
        c.weightx = 1;
        bin.add(binaryFileField, c);
        c.gridx = 2;
        c.weightx = 0;
        bin.add(button("Browse…", "Select the file to send", () -> {
            String f = chooseFile(binaryFileField.getText());
            if (f != null) {
                binaryFileField.setText(f);
            }
        }), c);
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        JLabel bh = new JLabel("The file content is sent as is. Content-Type is guessed from the file name unless set in Headers.");
        bh.setForeground(Color.GRAY);
        bin.add(bh, c);
        c.gridy = 2;
        c.weighty = 1;
        bin.add(new JPanel(), c);
        bodyCardPanel.add(bin, RequestModel.BodyType.BINARY.name());

        p.add(bodyCardPanel, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildFormPanel(JTable table, FormParamsTableModel model, String hintText) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setRowHeight(Math.max(22, table.getRowHeight()));
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        boolean multipart = model.typeColumn() >= 0;
        if (multipart) {
            JComboBox<String> types = new JComboBox<>(new String[]{FormParamsTableModel.TEXT, FormParamsTableModel.FILE});
            table.getColumnModel().getColumn(2).setCellEditor(new javax.swing.DefaultCellEditor(types));
            table.getColumnModel().getColumn(2).setMaxWidth(70);
            table.getColumnModel().getColumn(3).setPreferredWidth(400);
            table.getColumnModel().getColumn(4).setPreferredWidth(150);
        } else {
            table.getColumnModel().getColumn(2).setPreferredWidth(450);
        }
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        p.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(button(multipart ? "Add text" : "Add", "Add a field", () -> {
            stopEditing(table);
            int row = model.add(new httpman.model.FormParam("", ""));
            table.changeSelection(row, 1, false, false);
            table.editCellAt(row, 1);
            Component ed = table.getEditorComponent();
            if (ed != null) {
                ed.requestFocusInWindow();
            }
        }));
        if (multipart) {
            buttons.add(button("Add file…", "Add a file field", () -> {
                stopEditing(table);
                String f = chooseFile(null);
                if (f != null) {
                    int row = model.add(httpman.model.FormParam.file("file", f));
                    table.changeSelection(row, 1, false, false);
                }
            }));
            buttons.add(button("Choose file…", "Select the file of the selected field", () -> {
                stopEditing(table);
                int row = table.getSelectedRow();
                if (row < 0) {
                    return;
                }
                httpman.model.FormParam fp = model.get(row);
                String f = chooseFile(fp.file ? fp.value : null);
                if (f != null) {
                    model.setValueAt(FormParamsTableModel.FILE, row, 2);
                    model.setValueAt(f, row, 3);
                }
            }));
        }
        buttons.add(button("Remove", "Remove the selected fields", () -> {
            stopEditing(table);
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                model.remove(table.convertRowIndexToModel(rows[i]));
            }
        }));
        JLabel hint = new JLabel("  " + hintText);
        hint.setForeground(Color.GRAY);
        buttons.add(hint);
        p.add(buttons, BorderLayout.SOUTH);
        model.addTableModelListener(e -> updateBodyTitle());
        return p;
    }

    private String chooseFile(String current) {
        JFileChooser fc = new JFileChooser();
        if (current != null && !current.isBlank()) {
            fc.setSelectedFile(new File(current.trim()));
        }
        return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile().getAbsolutePath() : null;
    }

    private static void stopEditing(JTable t) {
        if (t.isEditing()) {
            t.getCellEditor().stopCellEditing();
        }
    }

    private JComponent buildCertTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = gbc();

        c.gridx = 0;
        c.gridy = 0;
        p.add(new JLabel("PKCS#12 file (.p12 / .pfx)"), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(certPathField, c);
        c.gridx = 2;
        c.weightx = 0;
        p.add(button("Browse…", "Select a .p12 file", this::browseCert), c);
        c.gridx = 3;
        p.add(button("Clear", "Do not use a client certificate", () -> {
            certPathField.setText("");
            certPasswordField.setText("");
            certInfoArea.setText("");
        }), c);

        c.gridy = 1;
        c.gridx = 0;
        p.add(new JLabel("Password"), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(certPasswordField, c);
        c.gridx = 2;
        c.weightx = 0;
        JCheckBox show = new JCheckBox("Show");
        char echo = certPasswordField.getEchoChar();
        show.addActionListener(e -> certPasswordField.setEchoChar(show.isSelected() ? (char) 0 : echo));
        p.add(show, c);
        c.gridx = 3;
        p.add(button("Check", "Open the keystore with this password and show its content", this::checkCert), c);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 4;
        JLabel hint = new JLabel("Optional. Used for mutual TLS (client authentication) on https URLs.");
        hint.setForeground(Color.GRAY);
        p.add(hint, c);

        c.gridy = 3;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        certInfoArea.setBackground(p.getBackground());
        p.add(new JScrollPane(certInfoArea), c);
        return p;
    }

    private JComponent buildSettingsTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = gbc();

        c.gridx = 0;
        c.gridy = 0;
        p.add(new JLabel("Request timeout (seconds, 0 = none)"), c);
        c.gridx = 1;
        ((JSpinner.DefaultEditor) timeoutSpinner.getEditor()).getTextField().setColumns(6);
        p.add(timeoutSpinner, c);

        c.gridy++;
        c.gridx = 0;
        p.add(new JLabel("HTTP version"), c);
        c.gridx = 1;
        p.add(httpVersionCombo, c);

        c.gridy++;
        c.gridx = 0;
        p.add(new JLabel("TLS version"), c);
        c.gridx = 1;
        tlsVersionCombo.setToolTipText("Force the TLS protocol version used for https URLs (only this version is offered)."
                + " TLS 1.0 / 1.1 may also have to be re-enabled in the JVM (jdk.tls.disabledAlgorithms).");
        p.add(tlsVersionCombo, c);

        c.gridy++;
        c.gridx = 0;
        p.add(new JLabel("Override host"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        hostOverrideField.setToolTipText("IP address (or host name) to connect to instead of resolving the URL's host."
                + " Empty = use the OS DNS. The Host header and TLS SNI still use the URL's host.");
        p.add(hostOverrideField, c);
        c.gridwidth = 1;

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        p.add(followRedirectsBox, c);
        c.gridy++;
        p.add(insecureBox, c);

        // proxy group
        JPanel proxy = new JPanel(new GridBagLayout());
        proxy.setBorder(BorderFactory.createTitledBorder("Proxy"));
        GridBagConstraints pc = gbc();
        ButtonGroup g = new ButtonGroup();
        g.add(proxyNone);
        g.add(proxySystem);
        g.add(proxyCustom);
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        radios.add(proxyNone);
        radios.add(proxySystem);
        radios.add(proxyCustom);
        pc.gridx = 0;
        pc.gridy = 0;
        pc.gridwidth = 4;
        proxy.add(radios, pc);
        pc.gridwidth = 1;
        pc.gridy = 1;
        proxy.add(new JLabel("Host"), pc);
        pc.gridx = 1;
        pc.weightx = 1;
        proxy.add(proxyHostField, pc);
        pc.gridx = 2;
        pc.weightx = 0;
        proxy.add(new JLabel("Port"), pc);
        pc.gridx = 3;
        proxyPortSpinner.setEditor(new JSpinner.NumberEditor(proxyPortSpinner, "#"));
        ((JSpinner.DefaultEditor) proxyPortSpinner.getEditor()).getTextField().setColumns(6);
        proxy.add(proxyPortSpinner, pc);
        pc.gridy = 2;
        pc.gridx = 0;
        proxy.add(new JLabel("Username"), pc);
        pc.gridx = 1;
        pc.weightx = 1;
        proxy.add(proxyUserField, pc);
        pc.gridx = 2;
        pc.weightx = 0;
        proxy.add(new JLabel("Password"), pc);
        pc.gridx = 3;
        proxyPasswordField.setColumns(10);
        proxy.add(proxyPasswordField, pc);
        pc.gridy = 3;
        pc.gridx = 0;
        pc.gridwidth = 4;
        JLabel hint = new JLabel("HTTP proxy only (also used for https via CONNECT). Credentials are optional.");
        hint.setForeground(Color.GRAY);
        proxy.add(hint, pc);
        proxyNone.addActionListener(e -> updateProxyFields());
        proxySystem.addActionListener(e -> updateProxyFields());
        proxyCustom.addActionListener(e -> updateProxyFields());

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        p.add(proxy, c);

        c.gridy++;
        c.weighty = 1;
        p.add(new JPanel(), c);
        return p;
    }

    private JComponent buildCurlTab() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tools.add(button("Export to cURL", "Generate a curl command from the current request", this::exportCurl));
        tools.add(button("Import from cURL", "Replace the current request with the curl command below", this::importCurl));
        tools.add(button("Copy", "Copy the command to the clipboard", () -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(curlArea.getText()), null);
            curlMessage.setText("Copied to clipboard.");
        }));
        tools.add(button("Paste & import", "Import the curl command found in the clipboard", this::pasteAndImportCurl));
        p.add(tools, BorderLayout.NORTH);
        curlArea.setLineWrap(true);
        p.add(new JScrollPane(curlArea), BorderLayout.CENTER);
        p.add(curlMessage, BorderLayout.SOUTH);
        return p;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("New request", KeyEvent.VK_N, this::newRequest));
        file.add(item("Save request", KeyEvent.VK_S, this::saveRequest));
        file.add(item("Revert to last save", 0, this::revertRequest));
        file.add(item("Duplicate request", KeyEvent.VK_D, this::duplicateRequest));
        file.add(item("Delete request", 0, this::deleteRequest));
        file.addSeparator();
        file.add(item("Show storage file location", 0, () -> JOptionPane.showMessageDialog(this,
                "Saved requests are stored in:\n" + store.getFile().toAbsolutePath(), "Storage", JOptionPane.INFORMATION_MESSAGE)));
        if (!isMac()) {
            file.addSeparator();
            file.add(item("Quit", KeyEvent.VK_Q, this::quit));
        }
        JMenu req = new JMenu("Request");
        req.add(item("Send / Cancel", KeyEvent.VK_ENTER, this::sendOrCancel));
        req.add(item("Focus URL", KeyEvent.VK_L, () -> {
            urlField.requestFocusInWindow();
            urlField.selectAll();
        }));
        req.addSeparator();
        req.add(item("Copy as cURL", 0, this::copyAsCurl));
        req.add(item("Import cURL from clipboard", KeyEvent.VK_I, this::pasteAndImportCurl));
        JMenu view = new JMenu("View");
        view.add(item("Filter requests", KeyEvent.VK_F, () -> {
            filterField.requestFocusInWindow();
            filterField.selectAll();
        }));
        bar.add(file);
        bar.add(req);
        bar.add(view);
        return bar;
    }

    // ================================================================== editor <-> model

    private void loadEditor(RequestModel r) {
        currentId = r.id;
        nameField.setText(r.name);
        methodCombo.setSelectedItem(r.method);
        urlField.setText(r.url);
        headersModel.setHeaders(r.headers);
        bodyArea.setText(r.body);
        bodyArea.setCaretPosition(0);
        urlencodedModel.setParams(r.bodyType == RequestModel.BodyType.FORM_URLENCODED ? r.formParams : java.util.Collections.emptyList());
        multipartModel.setParams(r.bodyType == RequestModel.BodyType.MULTIPART ? r.formParams : java.util.Collections.emptyList());
        binaryFileField.setText(r.binaryFile);
        bodyTypeCombo.setSelectedItem(r.bodyType);
        certPathField.setText(r.certPath);
        certPasswordField.setText(r.certPassword);
        certInfoArea.setText("");
        timeoutSpinner.setValue(Math.max(0, Math.min(3600, r.timeoutSeconds)));
        followRedirectsBox.setSelected(r.followRedirects);
        insecureBox.setSelected(r.insecure);
        httpVersionCombo.setSelectedItem(r.httpVersion);
        tlsVersionCombo.setSelectedIndex(tlsIndex(r.tlsVersion));
        hostOverrideField.setText(r.hostOverride == null ? "" : r.hostOverride);
        switch (r.proxyMode) {
            case NONE: proxyNone.setSelected(true); break;
            case CUSTOM: proxyCustom.setSelected(true); break;
            default: proxySystem.setSelected(true);
        }
        proxyHostField.setText(r.proxyHost);
        proxyPortSpinner.setValue(r.proxyPort > 0 && r.proxyPort <= 65535 ? r.proxyPort : 8080);
        proxyUserField.setText(r.proxyUser);
        proxyPasswordField.setText(r.proxyPassword);
        updateProxyFields();
        updateHeadersTitle();
        updateBodyTitle();
        savedSnapshot = snapshot(readEditor());
        updateDirtyState();
    }

    private RequestModel readEditor() {
        stopHeaderEditing();
        commitSpinner(timeoutSpinner);
        commitSpinner(proxyPortSpinner);
        RequestModel r = new RequestModel();
        r.id = currentId;
        r.name = nameField.getText().trim();
        Object m = methodCombo.getEditor().getItem();
        r.method = (m == null ? "GET" : m.toString().trim().toUpperCase(Locale.ROOT));
        if (r.method.isEmpty()) {
            r.method = "GET";
        }
        r.url = urlField.getText().trim();
        r.headers = headersModel.getHeaders();
        stopEditing(urlencodedTable);
        stopEditing(multipartTable);
        r.bodyType = (RequestModel.BodyType) bodyTypeCombo.getSelectedItem();
        r.body = bodyArea.getText();
        if (r.bodyType == RequestModel.BodyType.FORM_URLENCODED) {
            r.formParams = urlencodedModel.getParams();
        } else if (r.bodyType == RequestModel.BodyType.MULTIPART) {
            r.formParams = multipartModel.getParams();
        }
        r.binaryFile = binaryFileField.getText().trim();
        r.certPath = certPathField.getText().trim();
        r.certPassword = new String(certPasswordField.getPassword());
        r.timeoutSeconds = (Integer) timeoutSpinner.getValue();
        r.followRedirects = followRedirectsBox.isSelected();
        r.insecure = insecureBox.isSelected();
        r.httpVersion = (RequestModel.HttpVersion) httpVersionCombo.getSelectedItem();
        r.tlsVersion = TLS_VALUES[Math.max(0, tlsVersionCombo.getSelectedIndex())];
        r.hostOverride = hostOverrideField.getText().trim();
        r.proxyMode = proxyNone.isSelected() ? RequestModel.ProxyMode.NONE
                : proxyCustom.isSelected() ? RequestModel.ProxyMode.CUSTOM : RequestModel.ProxyMode.SYSTEM;
        r.proxyHost = proxyHostField.getText().trim();
        r.proxyPort = (Integer) proxyPortSpinner.getValue();
        r.proxyUser = proxyUserField.getText().trim();
        r.proxyPassword = new String(proxyPasswordField.getPassword());
        return r;
    }

    private static String snapshot(RequestModel r) {
        return Json.write(r.toMap());
    }

    private boolean isDirty() {
        return !snapshot(readEditor()).equals(savedSnapshot);
    }

    private boolean isSaved() {
        return currentId != null && store.find(currentId) != null;
    }

    private void updateDirtyState() {
        // don't interfere while the user edits a header cell
        if (headersTable.isEditing() || urlencodedTable.isEditing() || multipartTable.isEditing()) {
            return;
        }
        boolean dirty = isDirty();
        String n = nameField.getText().trim();
        setTitle("HttpMan — " + (n.isEmpty() ? "untitled" : n) + (dirty || !isSaved() ? " •" : ""));
        saveButton.setText(dirty || !isSaved() ? "Save*" : "Save");
        revertButton.setEnabled(dirty && isSaved());
    }

    // ================================================================== list

    private void refreshList(String selectId) {
        String f = filterField.getText().trim().toLowerCase(Locale.ROOT);
        ignoreSelection = true;
        try {
            listModel.clear();
            int sel = -1;
            for (RequestModel r : store.all()) {
                if (f.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(f)
                        || r.url.toLowerCase(Locale.ROOT).contains(f)
                        || r.method.toLowerCase(Locale.ROOT).startsWith(f)) {
                    listModel.addElement(r);
                    if (r.id.equals(selectId)) {
                        sel = listModel.size() - 1;
                    }
                }
            }
            if (sel >= 0) {
                requestList.setSelectedIndex(sel);
                requestList.ensureIndexIsVisible(sel);
            } else {
                requestList.clearSelection();
            }
        } finally {
            ignoreSelection = false;
        }
    }

    private void onListSelection() {
        RequestModel selected = requestList.getSelectedValue();
        if (selected == null || selected.id.equals(currentId)) {
            return;
        }
        if (!confirmDiscard()) {
            // revert the selection
            refreshList(currentId);
            return;
        }
        RequestModel fresh = store.find(selected.id);
        RequestModel toLoad = fresh != null ? fresh : selected;
        loadEditor(toLoad);
        refreshList(toLoad.id);
    }

    /** @return true when it is OK to replace the editor content. */
    private boolean confirmDiscard() {
        if (currentId == null || !isDirty()) {
            return true;
        }
        String n = nameField.getText().trim();
        int choice = JOptionPane.showConfirmDialog(this,
                "Save changes to \"" + (n.isEmpty() ? "untitled" : n) + "\"?", "Unsaved changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            return saveRequest();
        }
        return choice == JOptionPane.NO_OPTION;
    }

    // ================================================================== actions

    private void newRequest() {
        if (!confirmDiscard()) {
            return;
        }
        RequestModel requestModel = new RequestModel();
        requestModel.insecure = true;
        loadEditor(requestModel);
        refreshList(null);
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private boolean saveRequest() {
        RequestModel r = readEditor();
        if (r.name.isEmpty()) {
            String n = JOptionPane.showInputDialog(this, "Request name:", r.method + " " + r.url);
            if (n == null || n.trim().isEmpty()) {
                return false;
            }
            r.name = n.trim();
            nameField.setText(r.name);
        }
        try {
            store.save(r);
        } catch (Exception ex) {
            error("Could not save the request", ex);
            return false;
        }
        savedSnapshot = snapshot(readEditor());
        refreshList(r.id);
        updateDirtyState();
        return true;
    }

    private void duplicateRequest() {
        if (!confirmDiscard()) {
            return;
        }
        RequestModel r = readEditor();
        RequestModel copy = r.copy();
        copy.id = java.util.UUID.randomUUID().toString();
        copy.name = r.name + " (copy)";
        try {
            store.save(copy);
        } catch (Exception ex) {
            error("Could not save the copy", ex);
            return;
        }
        loadEditor(copy);
        refreshList(copy.id);
    }

    private void revertRequest() {
        RequestModel saved = currentId == null ? null : store.find(currentId);
        if (saved == null) {
            JOptionPane.showMessageDialog(this, "This request has never been saved.", "Revert", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!isDirty()) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Discard your modifications and reload \"" + saved.name + "\" from the last save?",
                "Revert to last save", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            loadEditor(saved);
            refreshList(saved.id);
        }
    }

    private void deleteRequest() {
        RequestModel sel = requestList.getSelectedValue();
        String id = sel != null ? sel.id : currentId;
        RequestModel target = id == null ? null : store.find(id);
        if (target == null) {
            // unsaved request: just reset the editor
            loadEditor(new RequestModel());
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Delete \"" + target.name + "\"?", "Delete request",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            store.delete(target.id);
        } catch (Exception ex) {
            error("Could not delete the request", ex);
            return;
        }
        int idx = Math.max(0, requestList.getSelectedIndex() - 1);
        refreshList(null);
        if (target.id.equals(currentId)) {
            savedSnapshot = ""; // allow switching without prompt
            if (!listModel.isEmpty()) {
                RequestModel next = listModel.get(Math.min(idx, listModel.size() - 1));
                loadEditor(next);
                refreshList(next.id);
            } else {
                loadEditor(new RequestModel());
            }
        } else {
            refreshList(currentId);
        }
    }

    private void sendOrCancel() {
        if (running != null) {
            running.cancel();
            return;
        }
        RequestModel r = readEditor();
        responsePanel.showRunning(safeRawRequest(r));
        sendButton.setText("Cancel");
        HttpExecutor.Execution exec = executor.start(r);
        running = exec;
        exec.result.thenAccept(res -> SwingUtilities.invokeLater(() -> onFinished(exec, res)));
    }

    private void onFinished(HttpExecutor.Execution exec, ExecutionResult res) {
        if (running != exec) {
            return;
        }
        running = null;
        sendButton.setText("Send");
        responsePanel.showResult(res);
    }

    private static String safeRawRequest(RequestModel r) {
        try {
            return HttpExecutor.buildRawRequest(r, HttpExecutor.parseUri(r.url));
        } catch (RuntimeException e) {
            return HttpExecutor.buildRawRequest(r, null);
        }
    }

    private void addHeader() {
        stopHeaderEditing();
        int row = headersModel.add(new Header("", ""));
        headersTable.changeSelection(row, 1, false, false);
        headersTable.editCellAt(row, 1);
        Component ed = headersTable.getEditorComponent();
        if (ed != null) {
            ed.requestFocusInWindow();
        }
    }

    private void removeHeaders() {
        stopHeaderEditing();
        int[] rows = headersTable.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) {
            headersModel.remove(headersTable.convertRowIndexToModel(rows[i]));
        }
    }

    private void setOrAddHeader(String name, String value) {
        for (int i = 0; i < headersModel.getRowCount(); i++) {
            if (name.equalsIgnoreCase(String.valueOf(headersModel.getValueAt(i, 1)).trim())) {
                headersModel.setValueAt(value, i, 2);
                headersModel.setValueAt(Boolean.TRUE, i, 0);
                headersTable.changeSelection(i, 2, false, false);
                return;
            }
        }
        int row = headersModel.add(new Header(name, value));
        headersTable.changeSelection(row, 2, false, false);
    }

    private void stopHeaderEditing() {
        if (headersTable.isEditing()) {
            headersTable.getCellEditor().stopCellEditing();
        }
    }

    private void loadBodyFromFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                bodyArea.setText(new String(java.nio.file.Files.readAllBytes(fc.getSelectedFile().toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                bodyArea.setCaretPosition(0);
            } catch (Exception ex) {
                error("Could not read the file", ex);
            }
        }
    }

    private void browseCert() {
        JFileChooser fc = new JFileChooser();
        String cur = certPathField.getText().trim();
        if (!cur.isEmpty()) {
            fc.setSelectedFile(new File(cur));
        }
        fc.setFileFilter(new FileNameExtensionFilter("PKCS#12 keystores (*.p12, *.pfx)", "p12", "pfx"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            certPathField.setText(fc.getSelectedFile().getAbsolutePath());
            certInfoArea.setText("");
        }
    }

    private void checkCert() {
        String path = certPathField.getText().trim();
        if (path.isEmpty()) {
            certInfoArea.setText("No certificate file selected.");
            return;
        }
        try {
            certInfoArea.setText("Keystore opened successfully.\n\n"
                    + HttpExecutor.describeKeyStore(path, new String(certPasswordField.getPassword())));
        } catch (Exception ex) {
            String msg = ex.getMessage();
            if (ex instanceof java.io.IOException && ex.getCause() instanceof java.security.UnrecoverableKeyException) {
                msg = "wrong password";
            }
            certInfoArea.setText("Could not open the keystore: " + (msg == null ? ex.getClass().getSimpleName() : msg));
        }
        certInfoArea.setCaretPosition(0);
    }

    private void exportCurl() {
        curlArea.setText(CurlConverter.toCurl(readEditor()));
        curlArea.setCaretPosition(0);
        curlMessage.setText("Exported from the current request.");
    }

    private void importCurl() {
        String text = curlArea.getText().trim();
        if (text.isEmpty()) {
            curlMessage.setText("Paste a curl command in the text area first.");
            return;
        }
        CurlConverter.ImportResult res;
        try {
            res = CurlConverter.fromCurl(text);
        } catch (RuntimeException ex) {
            curlMessage.setText("Import failed: " + ex.getMessage());
            return;
        }
        // keep identity of the edited request; the user saves explicitly
        RequestModel imported = res.request;
        imported.id = currentId;
        String currentName = nameField.getText().trim();
        if (!currentName.isEmpty() && !currentName.equals(new RequestModel().name)) {
            imported.name = currentName;
        }
        String oldSnapshot = savedSnapshot;
        loadEditor(imported);
        savedSnapshot = oldSnapshot; // the editor now has unsaved changes
        curlArea.setText(text);
        updateDirtyState();
        if (res.warnings.isEmpty()) {
            curlMessage.setText("Imported. Review the request and click Save to keep it.");
        } else {
            curlMessage.setText("<html>Imported with warnings:<br>" + String.join("<br>", escape(res.warnings)) + "</html>");
        }
    }

    private void pasteAndImportCurl() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            curlArea.setText(String.valueOf(data));
        } catch (Exception ex) {
            curlMessage.setText("The clipboard does not contain text.");
            return;
        }
        requestTabs.setSelectedIndex(4);
        importCurl();
    }

    private void copyAsCurl() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(CurlConverter.toCurl(readEditor())), null);
        curlMessage.setText("cURL command copied to clipboard.");
    }

    private void quit() {
        if (!confirmDiscard()) {
            return;
        }
        if (running != null) {
            running.cancel();
        }
        dispose();
        System.exit(0);
    }

    // ================================================================== small helpers

    private static int tlsIndex(String value) {
        for (int i = 0; i < TLS_VALUES.length; i++) {
            if (TLS_VALUES[i].equalsIgnoreCase(value == null ? "" : value.trim())) {
                return i;
            }
        }
        return 0;
    }

    private void updateProxyFields() {
        boolean custom = proxyCustom.isSelected();
        proxyHostField.setEnabled(custom);
        proxyPortSpinner.setEnabled(custom);
        proxyUserField.setEnabled(custom);
        proxyPasswordField.setEnabled(custom);
    }

    private void updateHeadersTitle() {
        int n = headersModel.enabledCount();
        requestTabs.setTitleAt(0, n == 0 ? "Headers" : "Headers (" + n + ")");
    }

    private void updateBodyTitle() {
        RequestModel.BodyType t = (RequestModel.BodyType) bodyTypeCombo.getSelectedItem();
        String title;
        switch (t == null ? RequestModel.BodyType.NONE : t) {
            case RAW: title = bodyArea.getDocument().getLength() == 0 ? "Body" : "Body (raw)"; break;
            case FORM_URLENCODED: title = "Body (urlencoded)"; break;
            case MULTIPART: title = "Body (form-data)"; break;
            case BINARY: title = "Body (file)"; break;
            default: title = "Body";
        }
        requestTabs.setTitleAt(1, title);
    }

    private void error(String msg, Exception ex) {
        JOptionPane.showMessageDialog(this, msg + ":\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private static List<String> escape(List<String> list) {
        List<String> out = new java.util.ArrayList<>();
        for (String s : list) {
            out.add(s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
        }
        return out;
    }

    private static void commitSpinner(JSpinner s) {
        try {
            s.commitEdit();
        } catch (java.text.ParseException ignored) {
            // keep last valid value
        }
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private static JButton button(String text, String tooltip, Runnable r) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.addActionListener(e -> r.run());
        return b;
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    private static JMenuItem item(String text, int key, Runnable r) {
        JMenuItem i = item(text, r);
        if (key != 0) {
            i.setAccelerator(KeyStroke.getKeyStroke(key, UiUtil.menuMask()));
        }
        return i;
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                r.run();
            }
        };
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String shortcutText(int key) {
        return (isMac() ? "⌘" : "Ctrl+") + KeyEvent.getKeyText(key);
    }

    /** List renderer showing a colored method badge before the request name. */
    private static final class RequestRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            RequestModel r = (RequestModel) value;
            String color = methodColor(r.method);
            String name = r.name.replace("&", "&amp;").replace("<", "&lt;");
            String url = r.url.replace("&", "&amp;").replace("<", "&lt;");
            if (url.length() > 60) {
                url = url.substring(0, 57) + "…";
            }
            setText("<html><b><font color='" + (selected ? "white" : color) + "'>" + r.method
                    + "</font></b>&nbsp;" + name + "<br><font size='-2' color='" + (selected ? "white" : "gray") + "'>"
                    + url + "</font></html>");
            setToolTipText(r.method + " " + r.url);
            setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
            return this;
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
}
