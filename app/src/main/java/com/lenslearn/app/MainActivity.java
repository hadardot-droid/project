package com.lenslearn.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 1, REQ_PICK = 2, PERM_CAMERA = 10;

    private SharedPreferences prefs;
    private Store store;
    private TextToSpeech tts;
    private boolean ttsReady;

    private Lang lang;
    private Word current;
    private Bitmap currentThumb;
    private Uri pendingUri;
    private boolean busy;

    private View viewCam, viewWords, viewPrac, tag, busyOverlay, exBox;
    private Button tabCam, tabWords, tabPrac;
    private ImageView imgShot;
    private TextView tvHint, tvMsg, tvHe, tvConf, tvTarget, tvTranslit, tvMeta, tvEx, tvExTr, tvExHe, tvAltsLabel, tvSaved;
    private LinearLayout altsRow;
    private Button btnSentence;

    private ListView list;
    private EditText etSearch;
    private Spinner spFilter;
    private TextView tvWordsEmpty;
    private final List<Word> shown = new ArrayList<>();
    private WordAdapter adapter;

    private final List<Word> deck = new ArrayList<>();
    private int deckPos;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("lens", MODE_PRIVATE);
        store = new Store(this);
        lang = Lang.of(prefs.getString("lang", "en"));
        if (saved != null && saved.getString("pending") != null) pendingUri = Uri.parse(saved.getString("pending"));

        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);

        bindViews();
        setupLanguage();
        setupTabs();
        setupCamera();
        setupWords();
        setupPractice();
        showTab(0);

        if (key().isEmpty()) askKey(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingUri != null) out.putString("pending", pendingUri.toString());
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    private void bindViews() {
        viewCam = findViewById(R.id.viewCam);
        viewWords = findViewById(R.id.viewWords);
        viewPrac = findViewById(R.id.viewPrac);
        tabCam = findViewById(R.id.tabCam);
        tabWords = findViewById(R.id.tabWords);
        tabPrac = findViewById(R.id.tabPrac);
        imgShot = findViewById(R.id.imgShot);
        tvHint = findViewById(R.id.tvHint);
        busyOverlay = findViewById(R.id.busy);
        tvMsg = findViewById(R.id.tvMsg);
        tag = findViewById(R.id.tag);
        tvHe = findViewById(R.id.tvHe);
        tvConf = findViewById(R.id.tvConf);
        tvTarget = findViewById(R.id.tvTarget);
        tvTranslit = findViewById(R.id.tvTranslit);
        tvMeta = findViewById(R.id.tvMeta);
        exBox = findViewById(R.id.exBox);
        tvEx = findViewById(R.id.tvEx);
        tvExTr = findViewById(R.id.tvExTr);
        tvExHe = findViewById(R.id.tvExHe);
        tvAltsLabel = findViewById(R.id.tvAltsLabel);
        altsRow = findViewById(R.id.altsRow);
        tvSaved = findViewById(R.id.tvSaved);
        btnSentence = findViewById(R.id.btnSentence);
        findViewById(R.id.btnSettings).setOnClickListener(v -> askKey(null));
    }

    // ---------------- language ----------------
    private void setupLanguage() {
        Spinner sp = findViewById(R.id.spLang);
        ArrayAdapter<Lang> a = new ArrayAdapter<>(this, R.layout.spinner_white, Lang.ALL);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(Lang.indexOf(lang.code), false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                Lang l = Lang.ALL[pos];
                if (l == lang) return;
                lang = l;
                prefs.edit().putString("lang", l.code).apply();
                if (current != null && !current.lang.equals(l.code) && !current.he.isEmpty())
                    retranslate(current.he, false);
                if (viewPrac.getVisibility() == View.VISIBLE) startPractice();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    // ---------------- tabs ----------------
    private void setupTabs() {
        tabCam.setOnClickListener(v -> showTab(0));
        tabWords.setOnClickListener(v -> showTab(1));
        tabPrac.setOnClickListener(v -> showTab(2));
    }

    private void showTab(int i) {
        Button[] tabs = {tabCam, tabWords, tabPrac};
        View[] views = {viewCam, viewWords, viewPrac};
        for (int k = 0; k < 3; k++) {
            boolean on = k == i;
            tabs[k].setBackgroundResource(on ? R.drawable.tab_on : android.R.color.transparent);
            tabs[k].setTextColor(on ? getColor(R.color.ink) : 0xFFFFFFFF);
            views[k].setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (i == 1) refreshWords();
        if (i == 2) startPractice();
        updateCount();
    }

    private void updateCount() {
        int n = store.words.size();
        tabWords.setText(n > 0 ? "המילים שלי (" + n + ")" : "המילים שלי");
    }

    // ---------------- API key ----------------
    private String key() { return prefs.getString("key", "").trim(); }

    private void askKey(Runnable then) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint("sk-ant-...");
        et.setText(key());
        LinearLayout box = new LinearLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle("מפתח API של Claude")
            .setMessage("כדי לזהות חפצים האפליקציה שולחת את התמונה ל-Claude. הדביקו כאן מפתח API של Anthropic.\n\nאפשר ליצור מפתח באתר console.anthropic.com, בעמוד API Keys. המפתח נשמר רק בטלפון הזה.")
            .setView(box)
            .setPositiveButton("שמירה", (d, w) -> {
                prefs.edit().putString("key", et.getText().toString().trim()).apply();
                if (then != null && !key().isEmpty()) then.run();
            })
            .setNegativeButton("ביטול", null)
            .show();
    }

    // ---------------- camera & gallery ----------------
    private void setupCamera() {
        findViewById(R.id.btnCamera).setOnClickListener(v -> {
            if (key().isEmpty()) { askKey(this::onCameraClick); return; }
            onCameraClick();
        });
        findViewById(R.id.btnGallery).setOnClickListener(v -> {
            if (key().isEmpty()) { askKey(this::openGallery); return; }
            openGallery();
        });
        findViewById(R.id.btnPlay).setOnClickListener(v -> speak(current, false, false));
        findViewById(R.id.btnSlow).setOnClickListener(v -> speak(current, true, false));
        btnSentence.setOnClickListener(v -> speak(current, false, true));
        findViewById(R.id.btnFix).setOnClickListener(v -> askFix());
    }

    private void onCameraClick() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new AlertDialog.Builder(this)
                .setTitle("גישה למצלמה")
                .setMessage("האפליקציה צריכה את המצלמה כדי לצלם את החפץ שאתם רוצים ללמוד את שמו.")
                .setPositiveButton("אישור", (d, w) -> requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_CAMERA))
                .setNegativeButton("לא עכשיו", null)
                .show();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != PERM_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new AlertDialog.Builder(this)
                .setTitle("המצלמה חסומה")
                .setMessage("הגישה למצלמה נדחתה. אפשר לאשר אותה בהגדרות האפליקציה, תחת הרשאות.")
                .setPositiveButton("פתיחת הגדרות", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton("סגירה", null)
                .show();
        } else {
            msg("בלי גישה למצלמה אפשר לבחור תמונה מהגלריה.");
        }
    }

    private void openCamera() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "lens_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LensLearn");
            pendingUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, pendingUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            dropPending();
            msg("לא נמצאה אפליקציית מצלמה בטלפון. בחרו תמונה מהגלריה.");
        } catch (Exception e) {
            dropPending();
            msg("לא הצלחתי לפתוח את המצלמה.");
        }
    }

    private void openGallery() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try { startActivityForResult(i, REQ_PICK); }
        catch (ActivityNotFoundException e) { msg("לא נמצאה גלריה בטלפון."); }
    }

    private void dropPending() {
        if (pendingUri != null) {
            try { getContentResolver().delete(pendingUri, null, null); } catch (Exception ignored) { }
            pendingUri = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAMERA) {
            Uri u = pendingUri;
            if (res == RESULT_OK && u != null) recognise(u, true);
            else dropPending();
        } else if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            recognise(data.getData(), false);
        }
    }

    private Bitmap decode(Uri uri, int max) throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float sc = Math.min(1f, max / (float) Math.max(w, h));
            dec.setTargetSize(Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)));
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private static Bitmap scale(Bitmap b, int max) {
        float sc = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
        if (sc >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * sc), Math.round(b.getHeight() * sc), true);
    }

    private void recognise(Uri uri, boolean fromCamera) {
        if (busy) return;
        Bitmap big;
        try {
            big = decode(uri, 1024);
        } catch (Exception e) {
            msg("לא הצלחתי לפתוח את התמונה.");
            if (fromCamera) dropPending();
            return;
        }
        if (fromCamera) dropPending();
        currentThumb = scale(big, 360);
        imgShot.setImageBitmap(big);
        imgShot.setVisibility(View.VISIBLE);
        tvHint.setVisibility(View.GONE);
        tag.setVisibility(View.GONE);
        msg(null);
        setBusy(true);

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        big.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        final Lang l = lang;
        final String k = key();
        new Thread(() -> {
            try {
                JSONObject r = Claude.identify(k, l, b64);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!r.optBoolean("found", true)) {
                        String why = r.optString("why", "");
                        msg("לא הצלחתי לזהות חפץ" + (why.isEmpty() ? "." : ": " + why) + "\nנסו לצלם מקרוב, באור טוב, חפץ אחד במרכז.");
                        return;
                    }
                    current = Word.fromAnswer(r, l.code);
                    saveCurrent();
                    showTag();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); fail(e); });
            }
        }).start();
    }

    private void retranslate(String hebrew, boolean replaceOld) {
        if (busy || current == null) return;
        final Word old = current;
        final Lang l = lang;
        final String k = key();
        if (k.isEmpty()) { askKey(() -> retranslate(hebrew, replaceOld)); return; }
        setBusy(true);
        new Thread(() -> {
            try {
                JSONObject r = Claude.translate(k, l, hebrew);
                runOnUiThread(() -> {
                    setBusy(false);
                    Word w = Word.fromAnswer(r, l.code);
                    w.conf = "high";
                    w.alts.clear();
                    if (replaceOld && old.lang.equals(l.code) && store.words.contains(old)) store.delete(old);
                    current = w;
                    saveCurrent();
                    showTag();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); fail(e); });
            }
        }).start();
    }

    private void saveCurrent() {
        if (current.target.isEmpty()) return;
        boolean dup = store.save(current, currentThumb);
        tvSaved.setText(dup ? "✓ המילה כבר הייתה באוצר המילים, עודכנה" : "✓ נשמר לאוצר המילים");
        updateCount();
    }

    private void askFix() {
        if (current == null) return;
        EditText et = new EditText(this);
        et.setHint("למשל: קומקום");
        LinearLayout box = new LinearLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle("מה החפץ בתמונה?")
            .setMessage("כתבו בעברית את השם הנכון, ואתרגם אותו מחדש.")
            .setView(box)
            .setPositiveButton("תרגום", (d, w) -> {
                String v = et.getText().toString().trim();
                if (!v.isEmpty()) retranslate(v, true);
            })
            .setNegativeButton("ביטול", null)
            .show();
    }

    private void fail(Exception e) {
        if (e instanceof Claude.ApiError) {
            Claude.ApiError a = (Claude.ApiError) e;
            String m = a.getMessage() == null ? "" : a.getMessage().toLowerCase(Locale.ROOT);
            if (a.status == 401 || a.status == 403) { msg("מפתח ה-API לא תקין. בדקו אותו בהגדרות."); return; }
            if (m.contains("credit") || m.contains("billing")) { msg("אין יתרה בחשבון ה-API. אפשר להוסיף יתרה ב-console.anthropic.com."); return; }
            if (a.status == 429 || a.status == 529 || a.status >= 500) { msg("השירות עמוס כרגע. נסו שוב בעוד רגע."); return; }
            msg("משהו השתבש בזיהוי. נסו שוב.\n(" + a.status + ": " + a.getMessage() + ")");
            return;
        }
        if (e instanceof IOException) { msg("אין חיבור לאינטרנט. בדקו את החיבור ונסו שוב."); return; }
        msg("משהו השתבש בזיהוי. נסו שוב.");
    }

    private void setBusy(boolean b) {
        busy = b;
        busyOverlay.setVisibility(b ? View.VISIBLE : View.GONE);
    }

    private void msg(String t) {
        tvMsg.setText(t == null ? "" : t);
        tvMsg.setVisibility(t == null ? View.GONE : View.VISIBLE);
    }

    private void showTag() {
        Word w = current;
        Lang l = Lang.of(w.lang);
        tvHe.setText("בעברית: " + w.he);
        String conf = "high".equals(w.conf) ? "זיהוי בטוח" : "medium".equals(w.conf) ? "כנראה" : "low".equals(w.conf) ? "לא בטוח" : "";
        tvConf.setText(l.he + (conf.isEmpty() ? "" : " · " + conf));
        tvTarget.setText(w.target);
        tvTranslit.setText(w.translit);
        tvMeta.setText(meta(w));
        tvMeta.setVisibility(meta(w).isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasEx = !w.ex.isEmpty();
        exBox.setVisibility(hasEx ? View.VISIBLE : View.GONE);
        btnSentence.setVisibility(hasEx ? View.VISIBLE : View.GONE);
        tvEx.setText(w.ex);
        tvExTr.setText(w.exTr);
        tvExHe.setText(w.exHe);

        altsRow.removeAllViews();
        tvAltsLabel.setVisibility(w.alts.isEmpty() ? View.GONE : View.VISIBLE);
        altsRow.setVisibility(w.alts.isEmpty() ? View.GONE : View.VISIBLE);
        for (String[] a : w.alts) {
            Button b = new Button(this);
            b.setText(a[0]);
            b.setAllCaps(false);
            b.setTextColor(0xFF1B1600);
            b.setBackgroundResource(R.drawable.example_bg);
            b.setStateListAnimator(null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (42 * getResources().getDisplayMetrics().density));
            lp.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
            b.setOnClickListener(v -> retranslate(a[0], true));
            altsRow.addView(b, lp);
        }
        tag.setVisibility(View.VISIBLE);
        viewCam.post(() -> ((android.widget.ScrollView) viewCam).smoothScrollTo(0, tag.getTop()));
    }

    private static String meta(Word w) {
        StringBuilder sb = new StringBuilder();
        if (!w.roman.isEmpty()) sb.append(w.roman);
        if (!w.heRead.isEmpty()) { if (sb.length() > 0) sb.append(" · "); sb.append("נשמע כמו: ").append(w.heRead); }
        if (!w.gender.isEmpty()) { if (sb.length() > 0) sb.append(" · "); sb.append(w.gender); }
        return sb.toString();
    }

    // ---------------- speech ----------------
    private void speak(Word w, boolean slow, boolean sentence) {
        if (w == null) return;
        if (!ttsReady) { msgOrToast("מנוע ההקראה עדיין נטען. נסו שוב בעוד רגע."); return; }
        Lang l = Lang.of(w.lang);
        int r = tts.setLanguage(Locale.forLanguageTag(l.bcp));
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            new AlertDialog.Builder(this)
                .setTitle("אין קול ב" + l.he)
                .setMessage("כדי לשמוע את המילה צריך להוריד קול ב" + l.he + " למנוע ההקראה של הטלפון.")
                .setPositiveButton("הורדה", (d, x) -> {
                    try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                    catch (Exception e) { msgOrToast("פתחו בהגדרות הטלפון: נגישות > המרת טקסט לדיבור."); }
                })
                .setNegativeButton("סגירה", null)
                .show();
            return;
        }
        tts.setSpeechRate(slow ? 0.55f : 0.9f);
        String text = sentence ? w.ex : w.target;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w" + System.nanoTime());
    }

    private void msgOrToast(String t) {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show();
    }

    // ---------------- vocabulary ----------------
    private void setupWords() {
        list = findViewById(R.id.list);
        etSearch = findViewById(R.id.etSearch);
        spFilter = findViewById(R.id.spFilter);
        tvWordsEmpty = findViewById(R.id.tvWordsEmpty);
        adapter = new WordAdapter();
        list.setAdapter(adapter);
        List<String> names = new ArrayList<>();
        names.add("כל השפות");
        for (Lang l : Lang.ALL) names.add(l.he);
        ArrayAdapter<String> fa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFilter.setAdapter(fa);
        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refreshWords(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refreshWords(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void refreshWords() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        int f = spFilter.getSelectedItemPosition();
        String code = f <= 0 ? null : Lang.ALL[f - 1].code;
        shown.clear();
        for (Word w : store.words) {
            if (code != null && !w.lang.equals(code)) continue;
            if (!q.isEmpty() && !(w.he + " " + w.target + " " + w.translit).toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(w);
        }
        adapter.notifyDataSetChanged();
        boolean none = store.words.isEmpty();
        tvWordsEmpty.setText(none ? "עוד אין מילים.\nכל חפץ שתצלמו יישמר כאן אוטומטית." : "לא נמצאו מילים.\nנסו חיפוש אחר או שפה אחרת.");
        tvWordsEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadThumb(ImageView iv, Word w) {
        File f = store.image(w);
        if (f != null && f.exists()) iv.setImageBitmap(BitmapFactory.decodeFile(f.getPath()));
        else iv.setImageDrawable(null);
    }

    private final class WordAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_word, parent, false);
            Word w = shown.get(i);
            loadThumb(v.findViewById(R.id.img), w);
            ((TextView) v.findViewById(R.id.tvLang)).setText(Lang.of(w.lang).he);
            ((TextView) v.findViewById(R.id.tvTarget)).setText(w.target);
            ((TextView) v.findViewById(R.id.tvTr)).setText(w.translit);
            ((TextView) v.findViewById(R.id.tvHe)).setText(w.he);
            v.findViewById(R.id.btnPlay).setOnClickListener(x -> speak(w, false, false));
            v.findViewById(R.id.btnDel).setOnClickListener(x -> new AlertDialog.Builder(MainActivity.this)
                .setMessage("למחוק את \"" + w.he + "\" מאוצר המילים?")
                .setPositiveButton("מחיקה", (d, k) -> { store.delete(w); refreshWords(); updateCount(); })
                .setNegativeButton("ביטול", null)
                .show());
            return v;
        }
    }

    // ---------------- practice ----------------
    private void setupPractice() {
        findViewById(R.id.btnReveal).setOnClickListener(v -> {
            findViewById(R.id.pracAns).setVisibility(View.VISIBLE);
            v.setVisibility(View.GONE);
            if (!deck.isEmpty()) speak(deck.get(deckPos), false, false);
        });
        findViewById(R.id.btnPracPlay).setOnClickListener(v -> { if (!deck.isEmpty()) speak(deck.get(deckPos), false, false); });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            deckPos++;
            if (deckPos >= deck.size()) startPractice(); else drawCard();
        });
    }

    private void startPractice() {
        deck.clear();
        for (Word w : store.words) if (w.lang.equals(lang.code)) deck.add(w);
        Collections.shuffle(deck);
        deckPos = 0;
        TextView empty = findViewById(R.id.tvPracEmpty);
        View card = findViewById(R.id.pracCard);
        if (deck.isEmpty()) {
            empty.setText("אין עדיין מילים ב" + lang.he + ".\nצלמו כמה חפצים, ואז חזרו לכאן לתרגל.");
            empty.setVisibility(View.VISIBLE);
            card.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        drawCard();
    }

    private void drawCard() {
        Word w = deck.get(deckPos);
        loadThumb(findViewById(R.id.imgPrac), w);
        ((TextView) findViewById(R.id.tvPracQ)).setText("איך אומרים ב" + Lang.of(w.lang).he + ": " + w.he + "?");
        ((TextView) findViewById(R.id.tvPracTarget)).setText(w.target);
        ((TextView) findViewById(R.id.tvPracTr)).setText(w.translit);
        ((TextView) findViewById(R.id.tvPracRead)).setText(w.heRead.isEmpty() ? "" : "נשמע כמו: " + w.heRead);
        findViewById(R.id.pracAns).setVisibility(View.GONE);
        findViewById(R.id.btnReveal).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.tvPracCount)).setText((deckPos + 1) + " מתוך " + deck.size());
    }
}
