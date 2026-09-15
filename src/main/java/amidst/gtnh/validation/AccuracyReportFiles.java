package amidst.gtnh.validation;

import com.google.gson.GsonBuilder;

import java.util.List;

/** Report serialization independent of Swing, localization and file chooser state. */
public final class AccuracyReportFiles {
    private AccuracyReportFiles() {}

    public static String json(List<AccuracyReport> reports) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(reports);
    }

    public static String csv(List<AccuracyReport> reports) {
        // BOM lets spreadsheet programs recognize Chinese text as UTF-8.
        StringBuilder out =
                new StringBuilder(
                        "\ufeffrow,category,seed,dimension,session,timestamp,x,z,width,height,step,matched,mismatched,unverified,omitted,kind,predicted,recorded,status,reason\r\n");
        for (AccuracyReport report : reports) {
            append(
                    out,
                    "summary",
                    report.category,
                    report.seed,
                    report.dimension,
                    report.worldSession,
                    report.createdAt,
                    report.x,
                    report.z,
                    report.width,
                    report.height,
                    report.step,
                    report.matched,
                    report.mismatched,
                    report.unverified,
                    report.omittedDetails,
                    "",
                    "",
                    "",
                    "",
                    "");
            for (AccuracyReport.Entry entry : report.details) {
                append(
                        out,
                        "detail",
                        report.category,
                        report.seed,
                        report.dimension,
                        report.worldSession,
                        report.createdAt,
                        entry.x,
                        entry.z,
                        report.width,
                        report.height,
                        report.step,
                        "",
                        "",
                        "",
                        "",
                        entry.kind,
                        entry.predicted,
                        entry.actual,
                        entry.status,
                        entry.reason);
            }
        }
        return out.toString();
    }

    private static void append(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            String value = String.valueOf(cells[i]);
            // Escape untrusted text as text, while retaining negative numeric coordinates.
            if (cells[i] instanceof String && value.matches("^[=+@\\-].*")) value = "'" + value;
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }
}
