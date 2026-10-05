package httpman.ui;

import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.undo.UndoManager;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

final class UiUtil {

    private UiUtil() {
    }

    static int menuMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    static Font monoFont() {
        Font base = UIManager.getFont("TextArea.font");
        int size = base == null ? 13 : Math.max(12, base.getSize());
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** Monospaced text area; editable ones get undo/redo. */
    static JTextArea codeArea(boolean editable) {
        JTextArea a = new JTextArea();
        a.setFont(monoFont());
        a.setEditable(editable);
        a.setTabSize(2);
        if (editable) {
            UndoManager undo = new UndoManager();
            a.getDocument().addUndoableEditListener(e -> undo.addEdit(e.getEdit()));
            a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask()), "undo");
            a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask() | KeyEvent.SHIFT_DOWN_MASK), "redo");
            a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask()), "redo");
            a.getActionMap().put("undo", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (undo.canUndo()) {
                        undo.undo();
                    }
                }
            });
            a.getActionMap().put("redo", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (undo.canRedo()) {
                        undo.redo();
                    }
                }
            });
        }
        return a;
    }
}
