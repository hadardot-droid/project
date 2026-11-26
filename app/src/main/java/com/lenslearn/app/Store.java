package com.lenslearn.app;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Vocabulary kept on the phone: words.json plus one small JPEG per word. */
public final class Store {
    private final File file, imgDir;
    public final List<Word> words = new ArrayList<>();

    public Store(Context c) {
        file = new File(c.getFilesDir(), "words.json");
        imgDir = new File(c.getFilesDir(), "thumbs");
        imgDir.mkdirs();
        load();
    }

    private void load() {
        words.clear();
        try {
            if (!file.exists()) return;
            String txt = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONArray a = new JSONArray(txt);
            for (int i = 0; i < a.length(); i++) words.add(Word.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
    }

    private void persist() {
        try {
            JSONArray a = new JSONArray();
            for (Word w : words) a.put(w.toJson());
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file);
        } catch (Exception ignored) { }
    }

    public File image(Word w) { return w.img.isEmpty() ? null : new File(imgDir, w.img); }

    public Word find(String lang, String target) {
        for (Word w : words) if (w.lang.equals(lang) && w.target.equalsIgnoreCase(target)) return w;
        return null;
    }

    /** Saves the word (replacing a duplicate). Returns true if it already existed. */
    public boolean save(Word w, Bitmap thumb) {
        Word dup = find(w.lang, w.target);
        if (dup != null && dup != w) { words.remove(dup); deleteImage(dup); }
        if (w.id.isEmpty()) w.id = "w" + System.currentTimeMillis();
        if (thumb != null && w.img.isEmpty()) {
            String name = w.id + ".jpg";
            try (FileOutputStream out = new FileOutputStream(new File(imgDir, name))) {
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out);
                w.img = name;
            } catch (Exception ignored) { }
        }
        words.remove(w);
        w.time = System.currentTimeMillis();
        words.add(0, w);
        persist();
        return dup != null && dup != w;
    }

    public void delete(Word w) {
        words.remove(w);
        deleteImage(w);
        persist();
    }

    /** Removes a word from the list but keeps its photo (used when a word is corrected). */
    public void forget(Word w) {
        words.remove(w);
        persist();
    }

    private void deleteImage(Word w) {
        File f = image(w);
        if (f != null) f.delete();
    }
}
