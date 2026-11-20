package com.stopmotion.studio;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

public class MainActivity extends AppCompatActivity {
    static final int W = 1024, H = 576, MAXF = 200;
    final Bitmap live = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Bitmap tmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Canvas tc = new Canvas(tmp);
    final int[] px = new int[W * H];
    int[] bgPx;
    final List<byte[]> frames = new ArrayList<>();
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    Bitmap onionBmp, playBmp;
    int keyMode, sens = 50, soft = 30, delay, fps = 8, bright, bgColor = 0xFF2B2F6B, pi;
    boolean onion = true, playing, front, counting;
    VF vf; TextView cnt, cd; View left, right;

    class VF extends View {
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), g = new Paint();
        VF(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF000000);
            float s = Math.min(getWidth() / (float) W, getHeight() / (float) H), w = W * s, hh = H * s;
            float l = (getWidth() - w) / 2, t = (getHeight() - hh) / 2;
            RectF d = new RectF(l, t, l + w, t + hh);
            if (playing && playBmp != null) c.drawBitmap(playBmp, null, d, p);
            else synchronized (live) { c.drawBitmap(live, null, d, p); }
            if (!playing && onion && onionBmp != null) { p.setAlpha(100); c.drawBitmap(onionBmp, null, d, p); p.setAlpha(255); }
            g.setColor(0x44FFFFFF); g.setStrokeWidth(2);
            for (int i = 1; i < 3; i++) {
                c.drawLine(l + w * i / 3, t, l + w * i / 3, t + hh, g);
                c.drawLine(l, t + hh * i / 3, l + w, t + hh * i / 3, g);
            }
            g.setColor(0xCCFFB020); g.setStyle(Paint.Style.STROKE);
            c.drawRect(l + w / 2 - 36, t + hh / 2 - 36, l + w / 2 + 36, t + hh / 2 + 36, g);
        }
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setTextSize(11); b.setAllCaps(false);
        b.setTextColor(0xFFECEBE6); b.setBackgroundColor(0xFF202226); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40)); lp.setMargins(0, dp(3), 0, dp(3));
        b.setLayoutParams(lp); return b;
    }
    Button cycle(String label, String[] o, IntConsumer f) {
        final int[] i = {0};
        final Button b = btn(label + o[0], null);
        b.setOnClickListener(v -> { i[0] = (i[0] + 1) % o.length; b.setText(label + o[i[0]]); f.accept(i[0]); });
        return b;
    }
    View seek(String label, int max, int val, IntConsumer f) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this); t.setText(label); t.setTextSize(11); t.setTextColor(0xFF8F939B);
        SeekBar s = new SeekBar(this); s.setMax(max); s.setProgress(val);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar x, int p, boolean u) { f.accept(p); }
            public void onStartTrackingTouch(SeekBar x) {}
            public void onStopTrackingTouch(SeekBar x) {}
        });
        r.addView(t); r.addView(s); return r;
    }
    ScrollView rail(View... items) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(8), dp(6), dp(8), dp(6));
        for (View v : items) c.addView(v);
        ScrollView s = new ScrollView(this); s.setBackgroundColor(0xCC0A0A0B); s.addView(c); return s;
    }

    ActivityResultLauncher<String> pickImg = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap src = BitmapFactory.decodeStream(in);
            Bitmap o = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            float s = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
            Matrix m = new Matrix(); m.postScale(s, s); m.postTranslate((W - src.getWidth() * s) / 2, (H - src.getHeight() * s) / 2);
            new Canvas(o).drawBitmap(src, m, null);
            int[] a = new int[W * H]; o.getPixels(a, 0, W, 0, 0, W, H); bgPx = a;
        } catch (Exception e) { toast("Gambar tidak bisa dibuka"); }
    });
    ActivityResultLauncher<String> askCam = registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
        if (ok) startCam(); else toast("Izin kamera diperlukan");
    });

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        vf = new VF(this);
        vf.setOnClickListener(v -> { left.setVisibility(View.VISIBLE); right.setVisibility(View.VISIBLE); });
        final int[] cols = {0xFF2B2F6B, 0xFFFFFFFF, 0xFF000000, 0xFF2E7D32};
        left = rail(
            cycle("Kunci: ", new String[]{"Mati", "Hijau", "Biru"}, v -> keyMode = v),
            seek("Kekuatan kunci", 100, sens, v -> sens = v),
            seek("Kelembutan tepi", 100, soft, v -> soft = v),
            cycle("Latar: ", new String[]{"Biru tua", "Putih", "Hitam", "Hijau"}, v -> { bgColor = cols[v]; bgPx = null; }),
            btn("Latar gambar", v -> pickImg.launch("image/*")),
            cycle("Timer: ", new String[]{"Mati", "3 dtk", "5 dtk", "10 dtk"}, v -> delay = new int[]{0, 3, 5, 10}[v]),
            cycle("Bayangan: ", new String[]{"Hidup", "Mati"}, v -> onion = v == 0),
            seek("Kecerahan", 100, 50, v -> bright = (v - 50) * 2));
        right = rail(
            btn("Penuh", v -> { left.setVisibility(View.GONE); right.setVisibility(View.GONE); }),
            btn("Balik kamera", v -> { front = !front; startCam(); }),
            btn("Putar / Henti", v -> togglePlay()),
            btn("Hapus terakhir", v -> delLast()),
            cycle("FPS: ", new String[]{"8", "4", "12", "24"}, v -> fps = new int[]{8, 4, 12, 24}[v]),
            btn("Ekspor MP4", v -> export()));
        Button shut = new Button(this); shut.setBackground(shutterBg()); shut.setOnClickListener(v -> shoot());
        LinearLayout sc = new LinearLayout(this); sc.setGravity(Gravity.CENTER);
        sc.addView(shut, new LinearLayout.LayoutParams(dp(72), dp(72)));
        ((LinearLayout) ((ScrollView) right).getChildAt(0)).addView(sc, 0);
        cnt = new TextView(this); cnt.setTextColor(0xFFFFB020); cnt.setTextSize(16); cnt.setText("0 frame");
        cd = new TextView(this); cd.setTextColor(0xFFFFFFFF); cd.setTextSize(90); cd.setGravity(Gravity.CENTER);
        FrameLayout root = new FrameLayout(this);
        root.addView(vf, new FrameLayout.LayoutParams(-1, -1));
        root.addView(left, new FrameLayout.LayoutParams(dp(150), -1, Gravity.START));
        root.addView(right, new FrameLayout.LayoutParams(dp(130), -1, Gravity.END));
        root.addView(cnt, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.addView(cd, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root);
        if (checkSelfPermission(Manifest.permission.CAMERA) == 0) startCam(); else askCam.launch(Manifest.permission.CAMERA);
    }
    android.graphics.drawable.Drawable shutterBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL); d.setColor(0xFFFF3B30); d.setStroke(dp(5), 0xFFDDDDDD); return d;
    }
    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // kamera
    void startCam() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider p = ProcessCameraProvider.getInstance(this).get(); p.unbindAll();
                ImageAnalysis a = new ImageAnalysis.Builder().setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();
                a.setAnalyzer(ex, this::onFrame);
                p.bindToLifecycle(this, front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA, a);
            } catch (Exception e) { toast("Kamera gagal dibuka"); }
        }, ContextCompat.getMainExecutor(this));
    }
    void onFrame(ImageProxy ip) {
        try {
            Bitmap src = ip.toBitmap(); int rot = ip.getImageInfo().getRotationDegrees();
            boolean sw = rot % 180 != 0;
            float rw = sw ? src.getHeight() : src.getWidth(), rh = sw ? src.getWidth() : src.getHeight();
            float s = Math.max(W / rw, H / rh);
            Matrix m = new Matrix();
            m.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f); m.postRotate(rot);
            m.postScale(front ? -s : s, s); m.postTranslate(W / 2f, H / 2f);
            tc.drawColor(0xFF000000); tc.drawBitmap(src, m, null);
            tmp.getPixels(px, 0, W, 0, 0, W, H); key(px);
            synchronized (live) { live.setPixels(px, 0, W, 0, 0, W, H); }
            vf.postInvalidate();
        } finally { ip.close(); }
    }
    void key(int[] p) {
        int T = (int) (80 - sens * .7), E = (int) (soft * .8 + 1), km = keyMode, br = bright;
        if (km == 0 && br == 0) return;
        for (int i = 0; i < p.length; i++) {
            int c = p[i], r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255; float a = 1;
            if (km > 0) {
                int sc = km == 1 ? g - Math.max(r, b) : b - Math.max(r, g);
                if (sc >= T + E) a = 0; else if (sc > T) a = (T + E - sc) / (float) E;
                if (sc > 0) { if (km == 1) g = Math.max(r, b); else b = Math.max(r, g); }
            }
            r = Math.min(255, Math.max(0, r + br)); g = Math.min(255, Math.max(0, g + br)); b = Math.min(255, Math.max(0, b + br));
            if (a < 1) {
                int bg = bgPx != null ? bgPx[i] : bgColor;
                r = (int) (r * a + (bg >> 16 & 255) * (1 - a)); g = (int) (g * a + (bg >> 8 & 255) * (1 - a)); b = (int) (b * a + (bg & 255) * (1 - a));
            }
            p[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    // frame & timer
    void shoot() {
        if (playing || counting) return;
        if (delay == 0) snap(); else { counting = true; tick(delay); }
    }
    void tick(int n) {
        if (n == 0) { cd.setText(""); counting = false; snap(); return; }
        cd.setText(String.valueOf(n)); h.postDelayed(() -> tick(n - 1), 1000);
    }
    void snap() {
        if (frames.size() >= MAXF) { toast("Maksimal " + MAXF + " frame"); return; }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        synchronized (live) { live.compress(Bitmap.CompressFormat.JPEG, 92, o); onionBmp = live.copy(Bitmap.Config.ARGB_8888, false); }
        frames.add(o.toByteArray()); cnt.setText(frames.size() + " frame");
    }
    void delLast() {
        stopPlay(); if (frames.isEmpty()) return;
        frames.remove(frames.size() - 1); cnt.setText(frames.size() + " frame");
        if (frames.isEmpty()) onionBmp = null; else { byte[] d = frames.get(frames.size() - 1); onionBmp = BitmapFactory.decodeByteArray(d, 0, d.length); }
    }

    // putar
    final Runnable pl = new Runnable() { public void run() {
        if (!playing || frames.isEmpty()) return;
        byte[] d = frames.get(pi++ % frames.size()); playBmp = BitmapFactory.decodeByteArray(d, 0, d.length);
        vf.invalidate(); h.postDelayed(this, 1000 / fps);
    } };
    void togglePlay() { if (playing) stopPlay(); else if (!frames.isEmpty()) { playing = true; pi = 0; h.post(pl); } }
    void stopPlay() { playing = false; h.removeCallbacks(pl); }

    // ekspor MP4 ke galeri (Movies/StopMotion), berjalan sebanyak durasi video
    void export() {
        if (frames.isEmpty()) { toast("Ambil frame dulu"); return; }
        stopPlay(); final int f = fps; final List<byte[]> fr = new ArrayList<>(frames);
        toast("Mengekspor... tunggu sampai ada pesan selesai");
        new Thread(() -> {
            try {
                MediaFormat mf = MediaFormat.createVideoFormat("video/avc", W, H);
                mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                mf.setInteger(MediaFormat.KEY_BIT_RATE, 8000000); mf.setInteger(MediaFormat.KEY_FRAME_RATE, f);
                mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
                enc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface sf = enc.createInputSurface(); enc.start();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, "StopMotion_" + System.currentTimeMillis() + ".mp4");
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StopMotion");
                Uri u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "w");
                MediaMuxer mx = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo(); int[] tr = {-1};
                for (int k = 0; k <= fr.size(); k++) {
                    byte[] d = fr.get(Math.min(k, fr.size() - 1)); Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length);
                    Canvas c = sf.lockCanvas(null); c.drawBitmap(bm, 0, 0, null); sf.unlockCanvasAndPost(c); bm.recycle();
                    drain(enc, mx, bi, tr, false); Thread.sleep(1000 / f);
                }
                enc.signalEndOfInputStream(); drain(enc, mx, bi, tr, true);
                enc.stop(); enc.release(); mx.stop(); mx.release(); pfd.close();
                runOnUiThread(() -> toast("Video tersimpan di galeri (Movies/StopMotion)"));
            } catch (Exception e) { runOnUiThread(() -> toast("Ekspor gagal: " + e.getMessage())); }
        }).start();
    }
    void drain(MediaCodec e, MediaMuxer m, MediaCodec.BufferInfo bi, int[] tr, boolean eos) {
        while (true) {
            int i = e.dequeueOutputBuffer(bi, eos ? 10000 : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!eos) return; continue; }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { tr[0] = m.addTrack(e.getOutputFormat()); m.start(); continue; }
            if (i < 0) continue;
            ByteBuffer b = e.getOutputBuffer(i);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
            if (bi.size > 0 && tr[0] >= 0) m.writeSampleData(tr[0], b, bi);
            e.releaseOutputBuffer(i, false);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }
}
