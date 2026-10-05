package httpman.ui;

import httpman.http.ExecutionResult;
import httpman.util.Json;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Files;

/** Bottom panel displaying the result of the last execution. */
class ResponsePanel extends JPanel {

    private static final int MAX_DISPLAY_CHARS = 2_000_000;

    private final JLabel statusLabel = new JLabel("No request sent yet");
    private final JLabel timeLabel = new JLabel();
    private final JLabel sizeLabel = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea bodyArea = UiUtil.codeArea(false);
    private final JCheckBox prettyBox = new JCheckBox("Pretty", true);
    private final JButton saveBodyButton = new JButton("Save body…");
    private final DefaultTableModel headersModel = new DefaultTableModel(new Object[]{"Name", "Value"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTextArea rawRequestArea = UiUtil.codeArea(false);
    private final JTextArea rawResponseArea = UiUtil.codeArea(false);
    private final JTextArea logArea = UiUtil.codeArea(false);

    private ExecutionResult current;

    ResponsePanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Response"));

        JPanel statusBar = new JPanel();
        statusBar.setLayout(new BoxLayout(statusBar, BoxLayout.X_AXIS));
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 4));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        statusBar.add(statusLabel);
        statusBar.add(Box.createHorizontalStrut(20));
        statusBar.add(timeLabel);
        statusBar.add(Box.createHorizontalStrut(20));
        statusBar.add(sizeLabel);
        statusBar.add(Box.createHorizontalGlue());
        add(statusBar, BorderLayout.NORTH);

        JPanel bodyPanel = new JPanel(new BorderLayout());
        JPanel bodyTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bodyTools.add(prettyBox);
        bodyTools.add(saveBodyButton);
        bodyPanel.add(bodyTools, BorderLayout.NORTH);
        bodyPanel.add(new JScrollPane(bodyArea), BorderLayout.CENTER);
        prettyBox.addActionListener(e -> renderBody());
        saveBodyButton.addActionListener(e -> saveBody());
        saveBodyButton.setEnabled(false);

        JTable headersTable = new JTable(headersModel);
        headersTable.setAutoCreateRowSorter(true);
        headersTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        headersTable.getColumnModel().getColumn(1).setPreferredWidth(600);

        tabs.addTab("Body", bodyPanel);
        tabs.addTab("Headers", new JScrollPane(headersTable));
        tabs.addTab("Raw request", new JScrollPane(rawRequestArea));
        tabs.addTab("Raw response", new JScrollPane(rawResponseArea));
        tabs.addTab("Log", new JScrollPane(logArea));
        add(tabs, BorderLayout.CENTER);
    }

    void showRunning(String rawRequest) {
        current = null;
        statusLabel.setText("Sending…");
        statusLabel.setForeground(UIManagerColor.label());
        timeLabel.setText("");
        sizeLabel.setText("");
        rawRequestArea.setText(rawRequest);
        rawRequestArea.setCaretPosition(0);
        rawResponseArea.setText("");
        bodyArea.setText("");
        logArea.setText("");
        headersModel.setRowCount(0);
        saveBodyButton.setEnabled(false);
    }

    void showResult(ExecutionResult r) {
        current = r;
        if (r.error != null) {
            statusLabel.setText(r.cancelled ? "Cancelled" : "Error: " + shorten(httpman.http.HttpExecutor.describeError(r.error), 120));
            statusLabel.setForeground(new Color(200, 40, 40));
        } else {
            statusLabel.setText(r.statusCode + " " + r.reasonPhrase);
            statusLabel.setForeground(colorFor(r.statusCode));
        }
        timeLabel.setText("Time: " + r.durationMs + " ms");
        sizeLabel.setText(r.error == null ? "Size: " + humanSize(r.bodyBytes.length)
                + (r.protocol.isEmpty() ? "" : "   " + r.protocol) : "");

        headersModel.setRowCount(0);
        for (String[] h : r.responseHeaders) {
            headersModel.addRow(new Object[]{h[0], h[1]});
        }
        tabs.setTitleAt(1, "Headers (" + r.responseHeaders.size() + ")");

        setText(rawRequestArea, r.rawRequest);
        setText(rawResponseArea, r.rawResponse);
        setText(logArea, r.log.toString());
        renderBody();
        saveBodyButton.setEnabled(r.error == null && r.bodyBytes.length > 0);
        if (r.error != null) {
            tabs.setSelectedIndex(4); // log, with the error details
        } else if (tabs.getSelectedIndex() == 4) {
            tabs.setSelectedIndex(0);
        }
    }

    private void renderBody() {
        ExecutionResult r = current;
        if (r == null) {
            return;
        }
        if (r.error != null) {
            setText(bodyArea, httpman.http.HttpExecutor.describeError(r.error));
            return;
        }
        if (r.bodyText == null) {
            setText(bodyArea, "[binary content, " + humanSize(r.bodyBytes.length) + ", content-type: "
                    + r.contentType + "]\nUse \"Save body…\" to write it to a file.");
            return;
        }
        String text = r.bodyText;
        if (prettyBox.isSelected()) {
            String pretty = Json.prettyPrint(text);
            if (pretty != null) {
                text = pretty;
            }
        }
        setText(bodyArea, text);
    }

    private void saveBody() {
        if (current == null) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(suggestFileName(current)));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.write(fc.getSelectedFile().toPath(), current.bodyBytes);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static String suggestFileName(ExecutionResult r) {
        String ct = r.contentType.toLowerCase();
        String ext = ct.contains("json") ? "json" : ct.contains("html") ? "html" : ct.contains("xml") ? "xml"
                : ct.contains("pdf") ? "pdf" : ct.contains("png") ? "png" : ct.contains("jpeg") ? "jpg"
                : ct.startsWith("text/") ? "txt" : "bin";
        return "response." + ext;
    }

    private static void setText(JTextArea area, String text) {
        String t = text == null ? "" : text;
        if (t.length() > MAX_DISPLAY_CHARS) {
            t = t.substring(0, MAX_DISPLAY_CHARS) + "\n\n[… truncated for display, " + text.length() + " characters in total]";
        }
        area.setText(t);
        area.setCaretPosition(0);
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static Color colorFor(int code) {
        if (code >= 200 && code < 300) {
            return new Color(30, 140, 60);
        }
        if (code >= 300 && code < 400) {
            return new Color(30, 100, 200);
        }
        if (code >= 400 && code < 500) {
            return new Color(210, 120, 0);
        }
        return new Color(200, 40, 40);
    }

    static String humanSize(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format("%.1f KB", n / 1024.0);
        }
        return String.format("%.2f MB", n / (1024.0 * 1024));
    }

    /** Small helper to get the L&F default label color. */
    private static final class UIManagerColor {
        static Color label() {
            Color c = javax.swing.UIManager.getColor("Label.foreground");
            return c == null ? Color.BLACK : c;
        }
    }
}
