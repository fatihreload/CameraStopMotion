package com.stopmotion.studio;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.*;
import android.graphics.Typeface;
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
    VF vf; TextView cnt, cd; View left, right; LinearLayout strip; HorizontalScrollView hs; ScrollView drawer;
    View[] pnl; TextView[] tabs; int cur = -1, con = 100, sat = 100, sel = -1; boolean review, loop = true; Bitmap thumb;
    final Map<byte[], Bitmap> thumbs = new IdentityHashMap<>();
    final List<TextView> mi = new ArrayList<>(); final List<String[]> mlabel = new ArrayList<>(); boolean labels;

    class VF extends View {
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG), g = new Paint();
        VF(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF000000);
            float s = Math.min(getWidth() / (float) W, getHeight() / (float) H), w = W * s, hh = H * s;
            float l = (getWidth() - w) / 2, t = (getHeight() - hh) / 2;
            RectF d = new RectF(l, t, l + w, t + hh);
            if ((playing || review) && playBmp != null) c.drawBitmap(playBmp, null, d, p);
            else synchronized (live) { c.drawBitmap(live, null, d, p); }
            if (!playing && !review && onion && onionBmp != null) { p.setAlpha(100); c.drawBitmap(onionBmp, null, d, p); p.setAlpha(255); }
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

    android.graphics.drawable.GradientDrawable shape(int color, int r) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(r)); return d;
    }
    TextView chip(String t, View.OnClickListener l) {
        TextView b = new TextView(this); b.setText(t); b.setTextSize(12); b.setTextColor(0xFFF4F2ED); b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(13), dp(10), dp(13), dp(10));
        android.graphics.drawable.GradientDrawable bg = shape(0xFF252930, 14);
        bg.setStroke(dp(1), 0xFF3B414B); b.setBackground(bg); b.setElevation(dp(2));
        b.setMinHeight(dp(42)); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp); return b;
    }
    TextView cycle(String label, String[] o, IntConsumer f) {
        final int[] i = {0};
        final TextView b = chip(label + o[0], null);
        b.setOnClickListener(v -> { i[0] = (i[0] + 1) % o.length; b.setText(label + o[i[0]]); f.accept(i[0]); });
        return b;
    }
    TextView tip(String t) { TextView x = new TextView(this); x.setText(t); x.setTextSize(11); x.setTextColor(0xFF8F939B); x.setPadding(dp(4), dp(6), dp(4), 0); return x; }
    LinearLayout row(View... v) { LinearLayout r = new LinearLayout(this); for (View x : v) r.addView(x); return r; }
    LinearLayout panel(View... v) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12)); c.setBackground(shape(0xFF191D23, 16));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.setMargins(dp(8), dp(8), dp(8), dp(4)); c.setLayoutParams(outer);
        for (View x : v) { LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-1, -2); item.setMargins(0, dp(3), 0, dp(3)); c.addView(x, item); }
        return c;
    }
    View seek(String label, int max, int val, IntConsumer f) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(dp(4), dp(4), dp(4), dp(2));
        TextView t = new TextView(this); t.setText(label); t.setTextSize(11); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setTextColor(0xFFB0B6C0);
        SeekBar s = new SeekBar(this); s.setMax(max); s.setProgress(val);
        s.getProgressDrawable().setTint(0xFFFFB020); s.getThumb().setTint(0xFFFFB020);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar x, int p, boolean u) { f.accept(p); }
            public void onStartTrackingTouch(SeekBar x) {}
            public void onStopTrackingTouch(SeekBar x) {}
        });
        r.addView(t); r.addView(s); return r;
    }
    TextView mb(String icon, String name, View.OnClickListener l) {
        TextView t = chip(icon, l); mi.add(t); mlabel.add(new String[]{icon, name}); return t;
    }
    void refreshMenus() {
        for (int i = 0; i < mi.size(); i++) {
            TextView t = mi.get(i); String[] l = mlabel.get(i);
            t.setText(labels ? l[0] + "  " + l[1] : l[0]); t.setTextSize(labels ? 13 : 20);
            t.setPadding(dp(labels ? 12 : 0), dp(6), dp(labels ? 12 : 0), dp(6)); t.setMinWidth(dp(labels ? 100 : 46)); t.setMinHeight(dp(46));
        }
        int lw = dp(labels ? 124 : 60), rw = dp(labels ? 136 : 66);
        left.getLayoutParams().width = lw; right.getLayoutParams().width = rw;
        ((FrameLayout.LayoutParams) drawer.getLayoutParams()).leftMargin = lw + dp(6);
        FrameLayout.LayoutParams h2 = (FrameLayout.LayoutParams) hs.getLayoutParams(); h2.leftMargin = lw + dp(6); h2.rightMargin = rw + dp(6);
        left.requestLayout(); right.requestLayout(); drawer.requestLayout(); hs.requestLayout();
    }
    void openTab(int i) {
        cur = (cur == i) ? -1 : i;
        drawer.setVisibility(cur < 0 ? View.GONE : View.VISIBLE);
        for (int j = 0; j < 3; j++) {
            pnl[j].setVisibility(j == cur ? View.VISIBLE : View.GONE);
            tabs[j].setBackground(shape(j == cur ? 0xFFFFB020 : 0xFF20252C, 14)); tabs[j].setTextColor(j == cur ? 0xFF17191D : 0xFFECEBE6); tabs[j].setElevation(dp(j == cur ? 3 : 0));
        }
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
        vf.setOnClickListener(v -> { left.setVisibility(View.VISIBLE); hs.setVisibility(View.VISIBLE); });
        final int[] cols = {0xFF2B2F6B, 0xFFFFFFFF, 0xFF000000, 0xFF2E7D32};
        pnl = new View[]{
            panel(cycle("Kunci: ", new String[]{"Mati", "Hijau", "Biru"}, v -> keyMode = v),
                seek("Kekuatan kunci", 100, sens, v -> sens = v),
                seek("Kelembutan tepi", 100, soft, v -> soft = v),
                row(cycle("Latar: ", new String[]{"Biru tua", "Putih", "Hitam", "Hijau"}, v -> { bgColor = cols[v]; bgPx = null; }),
                    chip("Latar gambar", v -> pickImg.launch("image/*"))),
                tip("Pakai kain hijau/biru yang rata dan terang, tanpa bayangan.")),
            panel(cycle("Timer: ", new String[]{"Mati", "3 dtk", "5 dtk", "10 dtk"}, v -> delay = new int[]{0, 3, 5, 10}[v]),
                cycle("Bayangan frame: ", new String[]{"Hidup", "Mati"}, v -> onion = v == 0),
                tip("Bayangan menampilkan frame terakhir samar-samar agar gerakan mudah diatur.")),
            panel(seek("Kecerahan", 100, 50, v -> bright = (v - 50) * 2),
                seek("Kontras", 100, 50, v -> con = v * 2),
                seek("Saturasi", 100, 50, v -> sat = v * 2),
                row(cycle("FPS: ", new String[]{"8", "4", "12", "24"}, v -> fps = new int[]{8, 4, 12, 24}[v]),
                    cycle("Ulang: ", new String[]{"Ya", "Tidak"}, v -> loop = v == 0)),
                row(chip("Hapus frame", v -> delSel()), chip("Duplikat", v -> dupSel())),
                row(chip("Geser kiri", v -> moveSel(-1)), chip("Geser kanan", v -> moveSel(1))),
                row(chip("Balik urutan", v -> revAll()), chip("Hapus semua", v -> clearAll())),
                chip("Ekspor MP4 ke galeri", v -> export()),
                tip("Ketuk gambar kecil di bawah untuk memilih frame. Ketuk lagi untuk kembali ke kamera."))};
        FrameLayout holder = new FrameLayout(this);
        for (View v : pnl) { v.setVisibility(View.GONE); holder.addView(v); }
        drawer = new ScrollView(this); drawer.setBackground(shape(0xF012151A, 18)); drawer.setElevation(dp(12)); drawer.addView(holder); drawer.setClipToPadding(false); drawer.setVisibility(View.GONE);
        String[] tn = {"Layar", "Timer", "Edit"}, ti = {"\uD83C\uDFA8", "\u23F1", "\u270F"}; tabs = new TextView[3];
        LinearLayout lc = new LinearLayout(this); lc.setOrientation(LinearLayout.VERTICAL); lc.setGravity(Gravity.CENTER); lc.setPadding(dp(5), dp(8), dp(5), dp(8)); lc.setBackground(shape(0xD9101318, 18));
        lc.addView(mb("\u2630", "Tutup nama", v -> { labels = !labels; refreshMenus(); }));
        for (int i = 0; i < 3; i++) { final int k = i; tabs[i] = mb(ti[i], tn[i], v -> openTab(k)); lc.addView(tabs[i]); }
        left = lc;
        strip = new LinearLayout(this); strip.setGravity(Gravity.CENTER_VERTICAL);
        hs = new HorizontalScrollView(this); hs.setBackground(shape(0xE9161A20, 16)); hs.setElevation(dp(8)); hs.addView(strip);
        Button shut = new Button(this); shut.setBackground(shutterBg()); shut.setOnClickListener(v -> { review = false; shoot(); });
        LinearLayout rc = new LinearLayout(this); rc.setOrientation(LinearLayout.VERTICAL); rc.setGravity(Gravity.CENTER); rc.setPadding(dp(4), dp(8), dp(4), dp(8)); rc.setBackground(shape(0xD9101318, 18));
        rc.addView(mb("\uD83D\uDD33", "Penuh", v -> { left.setVisibility(View.GONE); hs.setVisibility(View.GONE); if (cur >= 0) openTab(cur); }));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(72), dp(72)); sp.setMargins(0, dp(10), 0, dp(10));
        rc.addView(shut, sp);
        rc.addView(mb("\u25B6", "Putar", v -> togglePlay()));
        rc.addView(mb("\uD83D\uDD04", "Balik kamera", v -> { front = !front; startCam(); }));
        right = rc;
        cnt = new TextView(this); cnt.setTextColor(0xFFFFC15A); cnt.setTextSize(13); cnt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); cnt.setText("0 FRAME"); cnt.setPadding(dp(16), dp(8), dp(16), dp(8)); cnt.setBackground(shape(0xD9161A20, 18));
        cd = new TextView(this); cd.setTextColor(0xFFFFFFFF); cd.setTextSize(90); cd.setGravity(Gravity.CENTER);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF101216);
        root.addView(vf, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(300), -1, Gravity.START); dl.setMargins(dp(76), dp(8), 0, dp(76));
        root.addView(drawer, dl);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, dp(62), Gravity.BOTTOM); hl.setMargins(dp(76), 0, dp(104), dp(10));
        root.addView(hs, hl);
        root.addView(left, new FrameLayout.LayoutParams(dp(70), -1, Gravity.START));
        root.addView(right, new FrameLayout.LayoutParams(dp(100), -1, Gravity.END));
        root.addView(cnt, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.addView(cd, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root);
        refreshMenus();
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
    int cl(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }
    void key(int[] p) {
        int T = (int) (80 - sens * .7), E = (int) (soft * .8 + 1), km = keyMode, br = bright;
        float c = con / 100f, sa = sat / 100f; boolean adj = br != 0 || con != 100 || sat != 100;
        if (km == 0 && !adj) return;
        for (int i = 0; i < p.length; i++) {
            int cc = p[i], r = cc >> 16 & 255, g = cc >> 8 & 255, b = cc & 255; float a = 1;
            if (km > 0) {
                int sc = km == 1 ? g - Math.max(r, b) : b - Math.max(r, g);
                if (sc >= T + E) a = 0; else if (sc > T) a = (T + E - sc) / (float) E;
                if (sc > 0) { if (km == 1) g = Math.max(r, b); else b = Math.max(r, g); }
            }
            if (adj) {
                float rf = (r - 128) * c + 128 + br, gf = (g - 128) * c + 128 + br, bf = (b - 128) * c + 128 + br;
                float y = .299f * rf + .587f * gf + .114f * bf;
                r = cl(y + (rf - y) * sa); g = cl(y + (gf - y) * sa); b = cl(y + (bf - y) * sa);
            }
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
        synchronized (live) { live.compress(Bitmap.CompressFormat.JPEG, 92, o); onionBmp = live.copy(Bitmap.Config.ARGB_8888, false); thumb = Bitmap.createScaledBitmap(live, 160, 90, true); }
        byte[] jb = o.toByteArray(); frames.add(jb); thumbs.put(jb, thumb); review = false; sel = frames.size() - 1; refreshStrip();
    }
    void select(int i) {
        if (i < 0 || i >= frames.size()) { sel = -1; review = false; }
        else { sel = i; byte[] d = frames.get(i); playBmp = BitmapFactory.decodeByteArray(d, 0, d.length); review = true; }
        refreshStrip(); vf.invalidate();
    }
    void refreshStrip() {
        strip.removeAllViews();
        for (int i = 0; i < frames.size(); i++) {
            final int k = i; ImageView v = new ImageView(this);
            v.setImageBitmap(thumbs.get(frames.get(i))); v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setPadding(dp(2), dp(2), dp(2), dp(2)); v.setBackground(shape(i == sel ? 0xFFFFB020 : 0xFF343A44, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(44)); lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            v.setLayoutParams(lp); v.setOnClickListener(x -> select(k == sel ? -1 : k)); strip.addView(v);
        }
        cnt.setText(frames.size() + " FRAME");
        final View c = strip.getChildAt(sel);
        if (c != null) hs.post(() -> hs.smoothScrollTo(c.getLeft() - dp(100), 0));
    }
    void after() {
        if (frames.isEmpty()) onionBmp = null; else { byte[] d = frames.get(frames.size() - 1); onionBmp = BitmapFactory.decodeByteArray(d, 0, d.length); }
        if (review && sel >= 0) select(sel); else { review = false; refreshStrip(); vf.invalidate(); }
    }
    void delSel() {
        stopPlay(); if (frames.isEmpty()) return;
        int i = sel >= 0 ? sel : frames.size() - 1; frames.remove(i);
        sel = frames.isEmpty() ? -1 : Math.min(i, frames.size() - 1); after();
    }
    void dupSel() {
        stopPlay(); if (frames.isEmpty() || frames.size() >= MAXF) return;
        int i = sel >= 0 ? sel : frames.size() - 1; frames.add(i + 1, frames.get(i)); sel = i + 1; after();
    }
    void moveSel(int dir) {
        stopPlay(); int j = sel + dir; if (sel < 0 || j < 0 || j >= frames.size()) return;
        Collections.swap(frames, sel, j); sel = j; after();
    }
    void revAll() { stopPlay(); Collections.reverse(frames); if (sel >= 0) sel = frames.size() - 1 - sel; after(); }
    void clearAll() { stopPlay(); frames.clear(); thumbs.clear(); sel = -1; review = false; after(); }

    // putar
    final Runnable pl = new Runnable() { public void run() {
        if (!playing || frames.isEmpty()) return;
        if (!loop && pi >= frames.size()) { stopPlay(); vf.invalidate(); return; }
        byte[] d = frames.get(pi++ % frames.size()); playBmp = BitmapFactory.decodeByteArray(d, 0, d.length);
        vf.invalidate(); h.postDelayed(this, 1000 / fps);
    } };
    void togglePlay() { if (playing) stopPlay(); else if (!frames.isEmpty()) { review = false; playing = true; pi = 0; h.post(pl); } }
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
