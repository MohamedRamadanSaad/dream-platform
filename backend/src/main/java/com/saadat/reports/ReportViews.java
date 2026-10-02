package com.saadat.reports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** View models of {@code templates/pdf/report.html}; every text is already localized, formatted and cleaned. */
public final class ReportViews {

    private ReportViews() {
    }

    /** A label/value line (value never null; "—" when unknown). */
    public record Row(String label, String value) {
    }

    /** One table cell: a label (muted) or a value (bold). */
    public record Cell(String text, boolean label) {
    }

    public record MessageView(String sender, String at, String body, boolean fromInterpreter) {
    }

    public record InterpretationView(String text, String at) {
    }

    /**
     * One dream: {@code number} is the short id, {@code statusKey} the DreamStatus name (CSS class),
     * {@code metaGrid} the dates/details, {@code paymentGrid} empty in user-facing documents.
     */
    public record DreamView(
            String number,
            String status,
            String statusKey,
            List<List<Cell>> metaGrid,
            String text,
            List<MessageView> messages,
            InterpretationView interpretation,
            List<List<Cell>> paymentGrid) {
    }

    /** A rendered download. */
    public record Download(String filename, byte[] bytes) {
    }

    /**
     * Lays label/value rows out as table rows of {@code perLine} pairs, already in VISUAL left-to-right order: the
     * PDF engine does not mirror table columns for right-to-left documents, so for RTL every line is reversed
     * (the first label ends up at the right edge). Short last lines are padded so columns stay aligned.
     */
    public static List<List<Cell>> grid(List<Row> rows, int perLine, boolean rtl) {
        List<List<Cell>> lines = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += perLine) {
            List<Cell> cells = new ArrayList<>();
            for (int j = i; j < i + perLine; j++) {
                Row row = j < rows.size() ? rows.get(j) : new Row("", "");
                cells.add(new Cell(row.label(), true));
                cells.add(new Cell(row.value(), false));
            }
            if (rtl) {
                Collections.reverse(cells);
            }
            lines.add(List.copyOf(cells));
        }
        return List.copyOf(lines);
    }
}
