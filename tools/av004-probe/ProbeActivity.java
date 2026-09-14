package org.ankivoice.av004;

import android.app.Activity;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Disposable API experiment. No network permission, retries, or product integration. */
public class ProbeActivity extends Activity {
    private static final String BASE = "content://com.ichi2.anki.flashcards/";
    private static final String PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE";
    private static final String[] CARD_COLUMNS = {"_id", "note_id", "ord", "deck_id", "question", "answer",
        "reps", "lapses", "type", "queue", "due", "interval", "last_review_time_secs"};
    private TextView text;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this);
        text.setText("AV004 probe running");
        setContentView(text);
        if ("permission".equals(getIntent().getStringExtra("op"))) {
            requestPermissions(new String[]{PERMISSION}, 4);
        } else new Thread(this::runProbe).start();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        new Thread(this::runProbe).start();
    }

    private JSONArray query(String path, String[] columns, String selection, String[] args) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor c = getContentResolver().query(Uri.parse(BASE + path), columns, selection, args, null)) {
            if (c == null) throw new IllegalStateException("null cursor: " + path);
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    Object value = c.isNull(i) ? JSONObject.NULL : c.getType(i) == Cursor.FIELD_TYPE_INTEGER
                        ? c.getLong(i) : c.getType(i) == Cursor.FIELD_TYPE_FLOAT ? c.getDouble(i) : c.getString(i);
                    row.put(c.getColumnName(i), value);
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private JSONArray schedule(long deck) throws Exception {
        return query("schedule", null, "limit=?,deckID=?", new String[]{"1", Long.toString(deck)});
    }

    private void runProbe() {
        JSONObject result = new JSONObject();
        long start = System.currentTimeMillis();
        try {
            String op = getIntent().getStringExtra("op");
            result.put("op", op).put("started_at_ms", start)
                .put("permission_granted", checkSelfPermission(PERMISSION) == 0);
            if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE))
                throw new IllegalStateException("Disposable emulator only");
            result.put("decks", query("decks", null, null, null));
            JSONArray notes = query("notes", null, null, null);
            result.put("notes", notes);
            result.put("models", query("models", null, null, null));
            if (notes.length() == 0) throw new IllegalStateException("No AV002 fixtures imported");
            for (int i = 0; i < notes.length(); i++) {
                if (!notes.getJSONObject(i).getString("tags").contains(" av002 "))
                    throw new IllegalStateException("Refusing non-AV002 collection");
            }
            long deck = getIntent().getLongExtra("deck", -1);
            result.put("cards_before", query("cards", CARD_COLUMNS, "tag:av002", null));
            if (deck != -1) {
                ContentValues selected = new ContentValues();
                selected.put("deck_id", deck);
                result.put("select_count", getContentResolver().update(Uri.parse(BASE + "selected_deck"), selected, null, null));
                result.put("schedule_before", schedule(deck));
            }
            if ("answer".equals(op) || "raw-answer".equals(op)) {
                if (!"AV004_SYNTHETIC_ONLY".equals(getIntent().getStringExtra("confirm")))
                    throw new IllegalArgumentException("Explicit synthetic write confirmation required");
                long note = getIntent().getLongExtra("note", -1);
                int ord = getIntent().getIntExtra("ord", 0);
                boolean found = false;
                for (int i = 0; i < notes.length(); i++)
                    if (notes.getJSONObject(i).getLong("_id") == note) found = true;
                if (!found) throw new IllegalArgumentException("Note not in synthetic collection");
                JSONArray current = result.getJSONArray("schedule_before");
                if ("answer".equals(op) && (current.length() != 1
                        || current.getJSONObject(0).getLong("note_id") != note
                        || current.getJSONObject(0).getInt("ord") != ord))
                    throw new IllegalStateException("Selected card is stale; no write attempted");
                int rating = getIntent().getIntExtra("ease", 4);
                long elapsed = getIntent().getLongExtra("elapsed", 12345);
                if ("answer".equals(op) && (rating < 1 || rating > 4 || elapsed < 0))
                    throw new IllegalArgumentException("Invalid rating/time; no write attempted");
                ContentValues values = new ContentValues();
                values.put("note_id", note);
                values.put("ord", ord);
                values.put("answer_ease", rating);
                values.put("time_taken", elapsed);
                result.put("submitted", new JSONObject().put("note_id", note).put("ord", ord)
                    .put("answer_ease", values.getAsInteger("answer_ease"))
                    .put("time_taken", values.getAsLong("time_taken")));
                result.put("update_count", getContentResolver().update(Uri.parse(BASE + "schedule"), values, null, null));
                result.put("cards_after", query("cards", CARD_COLUMNS, "tag:av002", null));
                result.put("schedule_after", schedule(deck));
            }
        } catch (Exception e) {
            try { result.put("error_class", e.getClass().getName()).put("error", String.valueOf(e.getMessage())); }
            catch (Exception ignored) { }
        }
        try {
            result.put("duration_ms", System.currentTimeMillis() - start);
            String json = result.toString(2);
            try (FileOutputStream file = openFileOutput("result.json", MODE_PRIVATE)) {
                file.write(json.getBytes(StandardCharsets.UTF_8));
            }
            runOnUiThread(() -> text.setText(json));
        } catch (Exception e) { runOnUiThread(() -> text.setText(e.toString())); }
    }
}
