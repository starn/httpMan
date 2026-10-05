package httpman.ui;

import httpman.model.Header;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/** Editable table model for request headers: [enabled, name, value]. */
class HeadersTableModel extends AbstractTableModel {

    private final List<Header> headers = new ArrayList<>();

    void setHeaders(List<Header> list) {
        headers.clear();
        for (Header h : list) {
            headers.add(h.copy());
        }
        fireTableDataChanged();
    }

    List<Header> getHeaders() {
        List<Header> out = new ArrayList<>();
        for (Header h : headers) {
            if (!h.name.trim().isEmpty() || !h.value.isEmpty()) {
                out.add(h.copy());
            }
        }
        return out;
    }

    int add(Header h) {
        headers.add(h);
        int row = headers.size() - 1;
        fireTableRowsInserted(row, row);
        return row;
    }

    void remove(int row) {
        if (row >= 0 && row < headers.size()) {
            headers.remove(row);
            fireTableRowsDeleted(row, row);
        }
    }

    int enabledCount() {
        int n = 0;
        for (Header h : headers) {
            if (h.enabled && !h.name.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int getRowCount() {
        return headers.size();
    }

    @Override
    public int getColumnCount() {
        return 3;
    }

    @Override
    public String getColumnName(int c) {
        return c == 0 ? "On" : c == 1 ? "Name" : "Value";
    }

    @Override
    public Class<?> getColumnClass(int c) {
        return c == 0 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int r, int c) {
        return true;
    }

    @Override
    public Object getValueAt(int r, int c) {
        Header h = headers.get(r);
        return c == 0 ? h.enabled : c == 1 ? h.name : h.value;
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        Header h = headers.get(r);
        if (c == 0) {
            h.enabled = Boolean.TRUE.equals(v);
        } else if (c == 1) {
            h.name = v == null ? "" : v.toString();
        } else {
            h.value = v == null ? "" : v.toString();
        }
        fireTableCellUpdated(r, c);
    }
}
