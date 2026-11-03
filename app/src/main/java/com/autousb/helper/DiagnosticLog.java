package com.autousb.helper;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;

/** Only callers' fixed diagnostic fields are persisted. Never accepts node text/extras. */
final class DiagnosticLog {
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_LINES = 300;
    private final File file;
    private final ArrayDeque<String> rows = new ArrayDeque<>();
    boolean written = true;
    DiagnosticLog(Context context) {
        file = new File(context.getFilesDir(), "diagnostic.jsonl");
        try {
            if (file.length() <= MAX_BYTES) {
                for (String row : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    // Retain only our JSON object rows after an interrupted previous process.
                    JSONObject item = new JSONObject(row);
                    if (item.has("event") && item.has("time")) rows.add(item.toString());
                }
            }
        } catch (Exception ignored) { rows.clear(); }
        trim();
    }
    void append(JSONObject row) { rows.add(row.toString()); trim(); persist(); }
    String text() { return String.join("\n", rows) + (rows.isEmpty() ? "" : "\n"); }
    void clear() { rows.clear(); persist(); }
    private void trim() {
        while (rows.size() > MAX_LINES || text().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            rows.removeFirst();
        }
    }
    private void persist() {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text().getBytes(StandardCharsets.UTF_8)); written = true;
        } catch (Exception ignored) { written = false; }
    }
}
