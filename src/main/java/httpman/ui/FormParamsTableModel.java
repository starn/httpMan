package httpman.ui;

import httpman.model.FormParam;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable table of form fields.
 * urlencoded: [On, Name, Value]; multipart: [On, Name, Type, Value / file, Content-Type].
 */
class FormParamsTableModel extends AbstractTableModel {

    static final String TEXT = "Text";
    static final String FILE = "File";

    private final boolean multipart;
    private final List<FormParam> params = new ArrayList<>();

    FormParamsTableModel(boolean multipart) {
        this.multipart = multipart;
    }

    void setParams(List<FormParam> list) {
        params.clear();
        for (FormParam p : list) {
            params.add(p.copy());
        }
        fireTableDataChanged();
    }

    List<FormParam> getParams() {
        List<FormParam> out = new ArrayList<>();
        for (FormParam p : params) {
            if (!p.name.trim().isEmpty() || !p.value.isEmpty()) {
                FormParam c = p.copy();
                if (!multipart) {
                    c.file = false;
                    c.contentType = "";
                }
                out.add(c);
            }
        }
        return out;
    }

    FormParam get(int row) {
        return params.get(row);
    }

    int add(FormParam p) {
        params.add(p);
        int row = params.size() - 1;
        fireTableRowsInserted(row, row);
        return row;
    }

    void remove(int row) {
        if (row >= 0 && row < params.size()) {
            params.remove(row);
            fireTableRowsDeleted(row, row);
        }
    }

    int valueColumn() {
        return multipart ? 3 : 2;
    }

    int typeColumn() {
        return multipart ? 2 : -1;
    }

    @Override
    public int getRowCount() {
        return params.size();
    }

    @Override
    public int getColumnCount() {
        return multipart ? 5 : 3;
    }

    @Override
    public String getColumnName(int c) {
        if (!multipart) {
            return c == 0 ? "On" : c == 1 ? "Name" : "Value";
        }
        switch (c) {
            case 0: return "On";
            case 1: return "Name";
            case 2: return "Type";
            case 3: return "Value / file path";
            default: return "Content-Type (optional)";
        }
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
        FormParam p = params.get(r);
        switch (c) {
            case 0: return p.enabled;
            case 1: return p.name;
            case 2: return multipart ? (p.file ? FILE : TEXT) : p.value;
            case 3: return p.value;
            default: return p.contentType;
        }
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        FormParam p = params.get(r);
        String s = v == null ? "" : v.toString();
        if (c == 0) {
            p.enabled = Boolean.TRUE.equals(v);
        } else if (c == 1) {
            p.name = s;
        } else if (c == valueColumn()) {
            p.value = s;
        } else if (c == typeColumn()) {
            p.file = FILE.equals(s);
        } else {
            p.contentType = s;
        }
        fireTableCellUpdated(r, c);
    }
}
