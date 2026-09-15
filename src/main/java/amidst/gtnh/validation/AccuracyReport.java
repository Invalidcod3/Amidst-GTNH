package amidst.gtnh.validation;

import java.util.ArrayList;
import java.util.List;

/** Wire report: unknown evidence never contributes to the accuracy denominator. */
public final class AccuracyReport {
    public long seed, createdAt;
    public int dimension, x, z, width, height, step;
    public String category, worldSession;
    public int matched, mismatched, unverified, omittedDetails;
    public List<Entry> details = new ArrayList<>();

    public void add(
            String kind,
            int x,
            int z,
            String predicted,
            String actual,
            String status,
            String reason) {
        if ("MATCH".equals(status)) matched++;
        else if ("MISMATCH".equals(status)) mismatched++;
        else unverified++;
        // Keep representative matches, prioritizing differences and missing evidence.
        if ("MATCH".equals(status) && matched > 20) {
            omittedDetails++;
            return;
        }
        if (details.size() >= 1000) {
            int replace = -1;
            if ("MISMATCH".equals(status))
                for (int i = 0; i < details.size(); i++)
                    if (!"MISMATCH".equals(details.get(i).status)) {
                        replace = i;
                        break;
                    }
            omittedDetails++;
            if (replace < 0) return;
            details.remove(replace);
        }
        Entry e = new Entry();
        e.kind = kind;
        e.x = x;
        e.z = z;
        e.predicted = predicted;
        e.actual = actual;
        e.status = status;
        e.reason = reason;
        details.add(e);
    }

    public static final class Entry {
        public String kind, predicted, actual, status, reason;
        public int x, z;
    }
}
