package com.theojiang.teleprompteur;

import android.Manifest;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Téléprompteur selfie : aperçu caméra frontale plein écran + texte qui
 * défile dans un bandeau en haut de l'écran, juste sous l'objectif, pour
 * garder le regard vers la caméra. Enregistrement MP4 (CameraX
 * VideoCapture) dans Films/Téléprompteur.
 */
public class MainActivity extends ComponentActivity {

    private static final int REQ_PERMS = 7;
    private static final float BAND_FRACTION = 0.42f;
    private static final String PREFS = "teleprompteur";

    private static final String SAMPLE_TEXT =
            "Bonjour à toutes et à tous.\n\n"
            + "Aujourd'hui, je vous présente mon sujet en regardant "
            + "directement la caméra — parce que le texte défile juste "
            + "sous l'objectif.\n\n"
            + "Tu peux régler la vitesse de défilement et la taille du "
            + "texte en bas de l'écran, mettre en pause, repartir au "
            + "début, puis toucher REC pour te filmer.\n\n"
            + "La vidéo est enregistrée dans la galerie, dossier "
            + "Films/Téléprompteur. À toi de jouer !";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private View editPanel, prompterLayer, prompterBand, controlsBar;
    private View recIndicator, recDot, progressView;
    private EditText editText;
    private PreviewView previewView;
    private ScrollView scrollView;
    private TextView prompterText, recTime;
    private Button btnPlay, btnMirror, btnRecord;
    private SeekBar speedBar, sizeBar;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private long recStart;

    private boolean prompterMode;
    private boolean scrolling;
    private boolean mirror;
    private float pendingPx;
    private long lastTick;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!scrolling) return;
            long now = System.currentTimeMillis();
            pendingPx += speedPxPerSec() * (now - lastTick) / 1000f;
            lastTick = now;
            int dx = (int) pendingPx;
            if (dx > 0) {
                pendingPx -= dx;
                scrollView.scrollBy(0, dx);
            }
            updateProgress();
            if (scrollView.getScrollY() >= scrollableMax()) {
                setScrolling(false);
                return;
            }
            handler.postDelayed(this, 16);
        }
    };

    private final Runnable recTimer = new Runnable() {
        @Override public void run() {
            if (recording == null) return;
            recTime.setText(formatDuration(System.currentTimeMillis() - recStart));
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable recBlink = new Runnable() {
        @Override public void run() {
            if (recording == null) return;
            recDot.setVisibility(recDot.getVisibility() == View.VISIBLE
                    ? View.INVISIBLE : View.VISIBLE);
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        editPanel = findViewById(R.id.editPanel);
        prompterLayer = findViewById(R.id.prompterLayer);
        prompterBand = findViewById(R.id.prompterBand);
        controlsBar = findViewById(R.id.controlsBar);
        recIndicator = findViewById(R.id.recIndicator);
        recDot = findViewById(R.id.recDot);
        progressView = findViewById(R.id.progressView);
        editText = findViewById(R.id.editText);
        previewView = findViewById(R.id.previewView);
        scrollView = findViewById(R.id.scrollView);
        prompterText = findViewById(R.id.prompterText);
        recTime = findViewById(R.id.recTime);
        btnPlay = findViewById(R.id.btnPlay);
        btnMirror = findViewById(R.id.btnMirror);
        btnRecord = findViewById(R.id.btnRecord);
        speedBar = findViewById(R.id.speedBar);
        sizeBar = findViewById(R.id.sizeBar);

        editText.setText(prefs.getString("text", ""));
        speedBar.setProgress(prefs.getInt("speed", 30));
        sizeBar.setProgress(prefs.getInt("size", 35));
        mirror = prefs.getBoolean("mirror", false);
        applyTextSize();
        applyMirror();

        findViewById(R.id.btnLaunch).setOnClickListener(v -> launchPrompter());
        findViewById(R.id.btnExample).setOnClickListener(v -> editText.setText(SAMPLE_TEXT));
        findViewById(R.id.btnEdit).setOnClickListener(v -> leavePrompter());
        btnPlay.setOnClickListener(v -> toggleScroll());
        findViewById(R.id.btnRestart).setOnClickListener(v -> restartScroll());
        btnMirror.setOnClickListener(v -> {
            mirror = !mirror;
            applyMirror();
            prefs.edit().putBoolean("mirror", mirror).apply();
        });
        btnRecord.setOnClickListener(v -> toggleRecording());
        // Tape sur le texte = pause/reprise du défilement
        scrollView.setOnClickListener(v -> toggleScroll());

        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) { }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) {
                prefs.edit().putInt("speed", b.getProgress()).apply();
            }
        });
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) {
                applyTextSize();
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) {
                prefs.edit().putInt("size", b.getProgress()).apply();
            }
        });

        // Recalcule la hauteur du bandeau quand l'écran change (rotation…)
        previewView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (b - t != ob - ot) updateBandHeight();
        });

        applyInsets();
    }

    // ---------- Mode édition / prompteur ----------

    private void launchPrompter() {
        String text = editText.getText().toString().trim();
        if (text.isEmpty()) {
            toast(R.string.toast_need_text);
            return;
        }
        prefs.edit().putString("text", editText.getText().toString()).apply();

        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT < 29
                && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        enterPrompter();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_PERMS) {
            if (granted(Manifest.permission.CAMERA)) {
                enterPrompter();
            } else {
                toast(R.string.toast_need_camera);
            }
        }
    }

    private void enterPrompter() {
        editPanel.setVisibility(View.GONE);
        prompterLayer.setVisibility(View.VISIBLE);
        prompterMode = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prompterText.setText(editText.getText().toString());
        updateBandHeight();
        startCamera();
    }

    private void leavePrompter() {
        setScrolling(false);
        if (recording != null) recording.stop();
        if (cameraProvider != null) cameraProvider.unbindAll();
        prompterLayer.setVisibility(View.GONE);
        editPanel.setVisibility(View.VISIBLE);
        prompterMode = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---------- Défilement ----------

    private void toggleScroll() {
        if (!prompterMode) return;
        if (!scrolling && scrollView.getScrollY() >= scrollableMax()) {
            restartScroll();
        }
        setScrolling(!scrolling);
    }

    private void setScrolling(boolean on) {
        if (scrolling == on) return;
        scrolling = on;
        btnPlay.setText(on ? R.string.btn_pause : R.string.btn_play);
        if (on) {
            lastTick = System.currentTimeMillis();
            handler.post(ticker);
        }
    }

    private void restartScroll() {
        pendingPx = 0;
        scrollView.scrollTo(0, 0);
        updateProgress();
    }

    private int scrollableMax() {
        return Math.max(0, scrollView.getChildAt(0).getHeight() - scrollView.getHeight());
    }

    private float speedPxPerSec() {
        // 20 → 420 dp/s selon le curseur
        return dp(20 + speedBar.getProgress() * 4);
    }

    private void updateProgress() {
        int max = scrollableMax();
        float frac = max > 0 ? Math.min(1f, (float) scrollView.getScrollY() / max) : 0f;
        ViewGroup.LayoutParams lp = progressView.getLayoutParams();
        lp.width = (int) (prompterBand.getWidth() * frac);
        progressView.setLayoutParams(lp);
    }

    private void applyTextSize() {
        prompterText.setTextSize(20 + sizeBar.getProgress() * 0.8f);
    }

    private void applyMirror() {
        prompterText.setScaleX(mirror ? -1f : 1f);
        btnMirror.setTextColor(mirror ? 0xffffffff : 0xffaab4c3);
    }

    // Bandeau = ~42 % de l'écran ; le texte démarre et finit à mi-bandeau
    // pour rester le plus près possible de l'objectif.
    private void updateBandHeight() {
        previewView.post(() -> {
            int h = previewView.getHeight();
            if (h <= 0) return;
            ViewGroup.LayoutParams lp = prompterBand.getLayoutParams();
            lp.height = (int) (h * BAND_FRACTION);
            prompterBand.setLayoutParams(lp);
            prompterBand.post(() -> {
                int pad = prompterBand.getHeight() / 2;
                prompterText.setPadding(prompterText.getPaddingLeft(), pad,
                        prompterText.getPaddingRight(), pad);
                restartScroll();
            });
        });
    }

    // ---------- Caméra + enregistrement ----------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                CameraSelector selector =
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                                ? CameraSelector.DEFAULT_FRONT_CAMERA
                                : CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
            } catch (Exception e) {
                toast(R.string.toast_camera_error);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleRecording() {
        if (recording != null) {
            recording.stop();
            return;
        }
        if (videoCapture == null) {
            toast(R.string.toast_camera_not_ready);
            return;
        }
        String name = "teleprompteur_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Teleprompteur");
        }
        MediaStoreOutputOptions opts = new MediaStoreOutputOptions
                .Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();
        PendingRecording pr = videoCapture.getOutput().prepareRecording(this, opts);
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            pr = pr.withAudioEnabled();
        }
        recStart = System.currentTimeMillis();
        recording = pr.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                recording = null;
                stopRecUi();
                toast(fin.hasError() ? R.string.toast_rec_error : R.string.toast_saved);
            }
        });
        recIndicator.setVisibility(View.VISIBLE);
        recDot.setVisibility(View.VISIBLE);
        btnRecord.setText(R.string.btn_stop);
        handler.post(recTimer);
        handler.post(recBlink);
    }

    private void stopRecUi() {
        recIndicator.setVisibility(View.GONE);
        btnRecord.setText(R.string.btn_rec);
    }

    // ---------- Utilitaires ----------

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void applyInsets() {
        int padV = dp(8);
        controlsBar.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    padV + insets.getSystemWindowInsetBottom());
            return insets;
        });
        recIndicator.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), insets.getSystemWindowInsetTop() + padV,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        editPanel.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), dp(20) + insets.getSystemWindowInsetTop(),
                    v.getPaddingRight(), dp(20) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        getWindow().getDecorView().requestApplyInsets();
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (prompterMode) {
            leavePrompter();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.edit().putString("text", editText.getText().toString()).apply();
    }
}
