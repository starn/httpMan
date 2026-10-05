package httpman;

import httpman.store.RequestStore;
import httpman.ui.MainFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entry point of HttpMan, a small Postman / Bruno like HTTP client written in plain Java + Swing. */
public final class HttpMan {

    private HttpMan() {
    }

    public static void main(String[] args) {
        // These must be set before the networking classes are initialised.
        System.setProperty("java.net.useSystemProxies", "true");          // "System proxy" mode
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", ""); // allow Basic auth for HTTPS proxies
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host"); // let the user override Host (JDK 12+)

        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "HttpMan");

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel is fine
            }
            RequestStore store = new RequestStore();
            try {
                store.load();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Could not read saved requests from\n" + store.getFile()
                        + "\n\n" + e.getMessage() + "\n\nStarting with an empty list.",
                        "HttpMan", JOptionPane.WARNING_MESSAGE);
            }
            new MainFrame(store).setVisible(true);
        });
    }
}
