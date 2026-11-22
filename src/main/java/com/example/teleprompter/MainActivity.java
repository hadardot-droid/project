package com.example.teleprompter;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final int REQ_PERMS = 1;
    private static final String DEFAULT_SCRIPT =
            "שלום לכולם!\n\nזה הטקסט שיגלול על המסך בזמן שאתם מצלמים.\n\n"
            + "החליפו אותו בתסריט שלכם, כוונו את המהירות, ולחצו על כפתור ההקלטה.\n\n"
            + "הסתכלו לכיוון המצלמה בחלק העליון של הטלפון כדי שייראה שאתם מדברים ישר לצופים.";

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Editor
    private EditText scriptInput;
    private TextView fontLabel;
    private CheckBox mirrorBox;
    private CheckBox frontBox;

    // Prompter
    private boolean inPrompter = false;
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private boolean surfaceReady = false;
    private ScrollView scrollView;
    private TextView promptText;
    private TextView countdownText;
    private TextView timerText;
    private Button playBtn, recordBtn, switchBtn;

    // Camera / recording
    private Camera camera;
    private int cameraId = -1;
    private CamcorderProfile profile;
    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri videoUri;
    private boolean recording = false;
    private long recordStart;
    private boolean counting = false;

    // Scrolling
    private boolean scrolling = false;
    private float scrollPos = 0f;
    private long lastFrameNs = 0;
    private int speed;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!scrolling || scrollView == null) return;
            if (lastFrameNs != 0) {
                int actual = scrollView.getScrollY();
                if (Math.abs(actual - (int) scrollPos) > 3) scrollPos = actual; // user dragged
                float dt = (now - lastFrameNs) / 1e9f;
                float density = getResources().getDisplayMetrics().density;
                scrollPos += dt * (8 + speed * 1.6f) * density;
                scrollView.scrollTo(0, (int) scrollPos);
                View child = scrollView.getChildAt(0);
                if (child != null && scrollView.getScrollY() + scrollView.getHeight() >= child.getHeight()) {
                    setScrolling(false);
                    return;
                }
            }
            lastFrameNs = now;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!recording || timerText == null) return;
            long s = (System.currentTimeMillis() - recordStart) / 1000;
            timerText.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("prompter", MODE_PRIVATE);
        speed = prefs.getInt("speed", 30);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showEditor();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button makeButton(String text, int bg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(24));
        b.setBackground(d);
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    // ---------------- Editor screen ----------------

    private void showEditor() {
        inPrompter = false;
        editing = false;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 22));
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("טלפרומפטר");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("הדביקו או כתבו את התסריט:");
        hint.setTextColor(Color.LTGRAY);
        hint.setPadding(0, dp(6), 0, dp(8));
        root.addView(hint);

        scriptInput = new EditText(this);
        scriptInput.setText(prefs.getString("script", DEFAULT_SCRIPT));
        scriptInput.setTextColor(Color.WHITE);
        scriptInput.setTextSize(17);
        scriptInput.setGravity(Gravity.TOP | Gravity.START);
        scriptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        scriptInput.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.rgb(34, 34, 40));
        box.setCornerRadius(dp(12));
        scriptInput.setBackground(box);
        scriptInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        scriptInput.setEnabled(true);
        scriptInput.setFocusable(true);
        scriptInput.setFocusableInTouchMode(true);
        scriptInput.setCursorVisible(true);
        scriptInput.setLongClickable(true);
        scriptInput.setShowSoftInputOnFocus(true);
        scriptInput.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP && !editing) enterEdit();
            return false;
        });

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        int blue = Color.rgb(40, 110, 200);
        Button pasteBtn = makeButton("הדבק", blue);
        Button clearBtn = makeButton("נקה", blue);
        final Button editBtn = makeButton("✎ ערוך", blue);
        for (Button b : new Button[]{editBtn, pasteBtn, clearBtn}) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, dp(44), 1f);
            tl.setMargins(dp(3), 0, dp(3), dp(8));
            tools.addView(b, tl);
        }
        root.addView(tools);
        editBtnRef = editBtn;
        editBtn.setOnClickListener(v -> {
            if (editing) exitEdit();
            else {
                enterEdit();
                scriptInput.setSelection(scriptInput.getText().length());
            }
        });
        clearBtn.setOnClickListener(v -> {
            scriptInput.setText("");
            enterEdit();
        });
        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                scriptInput.setText(t);
                prefs.edit().putString("script", t.toString()).apply();
                Toast.makeText(this, "הטקסט הודבק", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "אין טקסט בלוח ההעתקה", Toast.LENGTH_SHORT).show();
            }
        });

        root.addView(scriptInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fontLabel = new TextView(this);
        fontLabel.setTextColor(Color.WHITE);
        fontLabel.setPadding(0, dp(14), 0, 0);
        root.addView(fontLabel);
        SeekBar fontBar = new SeekBar(this);
        fontBar.setMax(50);
        int font = prefs.getInt("font", 30);
        fontBar.setProgress(font - 14);
        fontLabel.setText("גודל טקסט: " + font);
        fontBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int f = p + 14;
                fontLabel.setText("גודל טקסט: " + f);
                prefs.edit().putInt("font", f).apply();
            }
        });
        root.addView(fontBar);

        frontBox = new CheckBox(this);
        frontBox.setText("מצלמה קדמית (סלפי)");
        frontBox.setTextColor(Color.WHITE);
        frontBox.setChecked(prefs.getBoolean("front", true));
        root.addView(frontBox);

        mirrorBox = new CheckBox(this);
        mirrorBox.setText("טקסט במראה (למשקף טלפרומפטר)");
        mirrorBox.setTextColor(Color.WHITE);
        mirrorBox.setChecked(prefs.getBoolean("mirror", false));
        root.addView(mirrorBox);

        Button start = makeButton("פתח מצלמה וטלפרומפטר", Color.rgb(220, 50, 60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(12);
        root.addView(start, lp);
        start.setOnClickListener(v -> {
            prefs.edit()
                    .putString("script", scriptInput.getText().toString())
                    .putBoolean("mirror", mirrorBox.isChecked())
                    .putBoolean("front", frontBox.isChecked())
                    .apply();
            if (hasPermissions()) showPrompter();
            else requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        });

        // While editing, hide everything except the text box and its buttons
        hideWhileTyping = new View[]{title, hint, fontLabel, fontBar, frontBox, mirrorBox, start};
        editorRoot = root;
        root.setFocusableInTouchMode(true);
        editing = false;

        setContentView(root);
    }

    private boolean editing = false;
    private Button editBtnRef;
    private View[] hideWhileTyping;
    private View editorRoot;

    private void enterEdit() {
        editing = true;
        for (View hv : hideWhileTyping) hv.setVisibility(View.GONE);
        editBtnRef.setText("✓ סיום");
        scriptInput.requestFocus();
        showKeyboard(scriptInput);
    }

    private void exitEdit() {
        editing = false;
        hideKeyboard(scriptInput);
        scriptInput.clearFocus();
        editorRoot.requestFocus();
        for (View hv : hideWhileTyping) hv.setVisibility(View.VISIBLE);
        editBtnRef.setText("✎ ערוך");
        prefs.edit().putString("script", scriptInput.getText().toString()).apply();
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void showKeyboard(View v) {
        v.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMS) {
            if (hasPermissions()) showPrompter();
            else Toast.makeText(this, "צריך הרשאה למצלמה ולמיקרופון", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- Prompter screen ----------------

    private void showPrompter() {
        inPrompter = true;
        cameraId = findCamera(prefs.getBoolean("front", true));
        int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        holder = surfaceView.getHolder();
        holder.addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Text overlay at the top, close to the lens
        int overlayH = (int) (screenH * 0.42f);
        scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.argb(150, 0, 0, 0));
        scrollView.setVerticalScrollBarEnabled(false);
        promptText = new TextView(this);
        promptText.setText(prefs.getString("script", DEFAULT_SCRIPT));
        promptText.setTextColor(Color.WHITE);
        promptText.setTextSize(prefs.getInt("font", 30));
        promptText.setTypeface(Typeface.DEFAULT_BOLD);
        promptText.setLineSpacing(0, 1.25f);
        promptText.setGravity(Gravity.CENTER_HORIZONTAL);
        promptText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        promptText.setPadding(dp(18), overlayH / 3, dp(18), overlayH);
        if (prefs.getBoolean("mirror", false)) promptText.setScaleX(-1f);
        scrollView.addView(promptText);
        promptText.setOnClickListener(v -> setScrolling(!scrolling));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, overlayH, Gravity.TOP);
        root.addView(scrollView, sp);

        // Reading line marker
        View marker = new View(this);
        marker.setBackgroundColor(Color.argb(120, 255, 70, 80));
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(10), dp(4), Gravity.TOP | Gravity.START);
        mp.topMargin = overlayH / 3 + dp(16);
        root.addView(marker, mp);

        timerText = new TextView(this);
        timerText.setTextColor(Color.rgb(255, 80, 80));
        timerText.setTextSize(16);
        timerText.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = overlayH + dp(8);
        root.addView(timerText, tp);

        countdownText = new TextView(this);
        countdownText.setTextColor(Color.WHITE);
        countdownText.setTextSize(96);
        countdownText.setTypeface(Typeface.DEFAULT_BOLD);
        countdownText.setShadowLayer(12, 0, 0, Color.BLACK);
        countdownText.setVisibility(View.GONE);
        root.addView(countdownText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Bottom controls
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(Color.argb(140, 0, 0, 0));
        bottom.setPadding(dp(12), dp(8), dp(12), dp(16));
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        final TextView speedLabel = new TextView(this);
        speedLabel.setTextColor(Color.WHITE);
        speedLabel.setText("מהירות גלילה: " + speed);
        bottom.addView(speedLabel);
        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(100);
        speedBar.setProgress(speed);
        speedBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                speed = p;
                speedLabel.setText("מהירות גלילה: " + p);
                prefs.edit().putInt("speed", p).apply();
            }
        });
        bottom.addView(speedBar);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button back = makeButton("עריכה", Color.argb(200, 70, 70, 80));
        playBtn = makeButton("▶ גלילה", Color.argb(200, 40, 110, 200));
        recordBtn = makeButton("● הקלט", Color.rgb(220, 50, 60));
        switchBtn = makeButton("⟲ מצלמה", Color.argb(200, 70, 70, 80));
        Button[] btns = {back, playBtn, recordBtn, switchBtn};
        for (Button b : btns) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
            lp.setMargins(dp(4), dp(6), dp(4), 0);
            row.addView(b, lp);
        }
        bottom.addView(row);
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        back.setOnClickListener(v -> leavePrompter());
        playBtn.setOnClickListener(v -> setScrolling(!scrolling));
        recordBtn.setOnClickListener(v -> {
            if (recording) {
                stopRecording();
                setScrolling(false);
            } else if (!counting) {
                startCountdown(3);
            }
        });
        switchBtn.setOnClickListener(v -> {
            if (recording || counting) return;
            boolean front = !isFront(cameraId);
            prefs.edit().putBoolean("front", front).apply();
            releaseCamera();
            cameraId = findCamera(front);
            openCamera();
        });

        scrollPos = 0;
        setContentView(root);
    }

    private void leavePrompter() {
        setScrolling(false);
        counting = false;
        handler.removeCallbacksAndMessages(null);
        if (recording) stopRecording();
        releaseCamera();
        surfaceReady = false;
        showEditor();
    }

    @Override
    public void onBackPressed() {
        if (inPrompter) leavePrompter();
        else if (editing) exitEdit();
        else super.onBackPressed();
    }

    private void startCountdown(final int n) {
        counting = true;
        if (n == 0) {
            countdownText.setVisibility(View.GONE);
            counting = false;
            if (startRecording()) {
                scrollPos = 0;
                scrollView.scrollTo(0, 0);
                setScrolling(true);
            }
            return;
        }
        countdownText.setText(String.valueOf(n));
        countdownText.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> {
            if (counting) startCountdown(n - 1);
        }, 1000);
    }

    private void setScrolling(boolean on) {
        scrolling = on;
        if (playBtn != null) playBtn.setText(on ? "❚❚ עצור" : "▶ גלילה");
        lastFrameNs = 0;
        if (on) {
            scrollPos = scrollView.getScrollY();
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
    }

    // ---------------- Camera ----------------

    private int findCamera(boolean front) {
        int wanted = front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == wanted) return i;
        }
        return 0;
    }

    private boolean isFront(int id) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    private void openCamera() {
        if (camera != null || !surfaceReady || !hasPermissions()) return;
        try {
            camera = Camera.open(cameraId);
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            int disp = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - info.orientation % 360) % 360
                    : info.orientation;
            camera.setDisplayOrientation(disp);

            if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_1080P)) {
                profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P);
            } else {
                profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH);
            }

            Camera.Parameters p = camera.getParameters();
            Camera.Size best = choosePreviewSize(p.getSupportedPreviewSizes(),
                    profile.videoFrameWidth, profile.videoFrameHeight);
            p.setPreviewSize(best.width, best.height);
            p.setRecordingHint(true);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            sizePreview(best);
        } catch (Exception e) {
            Toast.makeText(this, "לא הצלחתי לפתוח את המצלמה: " + e.getMessage(), Toast.LENGTH_LONG).show();
            releaseCamera();
        }
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes, int vw, int vh) {
        double target = (double) vw / vh;
        Camera.Size best = sizes.get(0);
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size s : sizes) {
            double ratioDiff = Math.abs((double) s.width / s.height - target);
            double score = ratioDiff * 10000 + Math.abs(s.height - Math.min(vh, 1080)) / 10.0;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void sizePreview(Camera.Size s) {
        // Portrait: preview is rotated, so the long side is vertical. Fill the width.
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = (int) ((long) w * s.width / s.height);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            camera.release();
            camera = null;
        }
    }

    // ---------------- Recording ----------------

    private boolean startRecording() {
        if (camera == null) return false;
        try {
            String name = "Prompter_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Teleprompter");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (videoUri == null) throw new IllegalStateException("MediaStore insert failed");
            pfd = getContentResolver().openFileDescriptor(videoUri, "w");

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);

            recorder = new MediaRecorder();
            camera.unlock();
            recorder.setCamera(camera);
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            recorder.setProfile(profile);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.setOrientationHint(info.orientation);
            recorder.setPreviewDisplay(holder.getSurface());
            recorder.prepare();
            recorder.start();

            recording = true;
            recordStart = System.currentTimeMillis();
            recordBtn.setText("■ עצור");
            switchBtn.setEnabled(false);
            handler.post(timerTick);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "שגיאה בהקלטה: " + e.getMessage(), Toast.LENGTH_LONG).show();
            cleanupRecorder(false);
            return false;
        }
    }

    private void stopRecording() {
        boolean ok = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            ok = false; // too short or failed
        }
        cleanupRecorder(ok);
        Toast.makeText(this, ok ? "הסרטון נשמר בגלריה (Movies/Teleprompter)" : "ההקלטה קצרה מדי ולא נשמרה",
                Toast.LENGTH_LONG).show();
    }

    private void cleanupRecorder(boolean keep) {
        recording = false;
        if (recorder != null) {
            recorder.reset();
            recorder.release();
            recorder = null;
        }
        if (camera != null) {
            try {
                camera.lock();
            } catch (Exception ignored) {
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            pfd = null;
        }
        if (videoUri != null) {
            if (keep) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(videoUri, v, null, null);
            } else {
                getContentResolver().delete(videoUri, null, null);
            }
            videoUri = null;
        }
        if (recordBtn != null) recordBtn.setText("● הקלט");
        if (switchBtn != null) switchBtn.setEnabled(true);
        if (timerText != null) timerText.setText("");
    }

    // ---------------- Lifecycle ----------------

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        if (recording) stopRecording();
        releaseCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inPrompter) {
            counting = false;
            if (countdownText != null) countdownText.setVisibility(View.GONE);
            setScrolling(false);
            if (recording) stopRecording();
            releaseCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (inPrompter && surfaceReady) openCamera();
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
