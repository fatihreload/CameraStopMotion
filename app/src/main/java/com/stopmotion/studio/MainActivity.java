package com.stopmotion.studio;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
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
import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {
    static final int W = 1024, H = 576, MAXF = 200;
    final Bitmap live = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Bitmap rawLive = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Bitmap tmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Canvas tc = new Canvas(tmp);
    final int[] px = new int[W * H];
    int[] bgPx;
    final List<byte[]> frames = new ArrayList<>();
    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService ex = Executors.newSingleThreadExecutor();
    Toast activeToast;
    Bitmap onionBmp, playBmp;
    int keyMode, sens = 50, soft = 30, delay, fps = 8, bright, bgColor = 0xFF2B2F6B, pi;
    // Output quality: SD/HD/HQ/4K. These are rendered/export dimensions.
    int quality = 1, filterMode = 0, aspectMode = 0, foregroundAlpha = 65, beautyStrength = 45;
    String projectName = "Untitled Project";
    boolean onion = true, playing, paused, front, counting, foregroundVisible = false, autoCapturing = false;
	boolean flashOn = false;
	Camera activeCamera;
	MediaActionSound shutterSound;
	TextView flashButton;
    int autoRemaining = 0;
    Bitmap foregroundBmp;
    VF vf; TextView cnt, cd, projectTitle, qualityTool, aspectTool, foregroundTool, filterTool, playPauseButton, topToolsToggle; View left, right; LinearLayout strip, topToolsPanel; HorizontalScrollView hs, topTools; ScrollView drawer; boolean topToolsVisible = true;
    View[] pnl; TextView[] tabs; TextView autoCaptureButton; int cur = -1, con = 100, sat = 100, sel = -1; boolean review, loop = true; Bitmap thumb;
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
            if (!playing && !review && foregroundVisible && foregroundBmp != null) {
                p.setAlpha(Math.max(0, Math.min(255, foregroundAlpha * 255 / 100)));
                c.drawBitmap(foregroundBmp, null, d, p); p.setAlpha(255);
            }
            // Aspect-ratio guide: darken outside the selected crop and outline the frame.
            RectF crop = previewCrop(d);
            if (aspectMode != 0) {
                g.setStyle(Paint.Style.FILL); g.setColor(0x88000000);
                c.drawRect(d.left, d.top, d.right, crop.top, g); c.drawRect(d.left, crop.bottom, d.right, d.bottom, g);
                c.drawRect(d.left, crop.top, crop.left, crop.bottom, g); c.drawRect(crop.right, crop.top, d.right, crop.bottom, g);
            }
            g.setColor(0xFFFFB547); g.setStyle(Paint.Style.STROKE); g.setStrokeWidth(2); c.drawRect(crop, g);
            g.setColor(0x44FFFFFF); g.setStrokeWidth(2);
            for (int i = 1; i < 3; i++) {
                c.drawLine(l + w * i / 3, t, l + w * i / 3, t + hh, g);
                c.drawLine(l, t + hh * i / 3, l + w, t + hh * i / 3, g);
            }
            g.setColor(0xCCFFB020); g.setStyle(Paint.Style.STROKE);
            c.drawRect(l + w / 2 - 36, t + hh / 2 - 36, l + w / 2 + 36, t + hh / 2 + 36, g);
        }
    }

    float aspectValue() { return new float[]{16f/9f, 4f/3f, 1f, 9f/16f}[aspectMode]; }
    RectF previewCrop(RectF bounds) {
        float ar = aspectValue(), bw = bounds.width(), bh = bounds.height();
        float w = bw, hh = bh;
        if (w / hh > ar) w = hh * ar; else hh = w / ar;
        float l = bounds.centerX() - w / 2f, t = bounds.centerY() - hh / 2f;
        return new RectF(l, t, l + w, t + hh);
    }
    int[] outputSize() {
        int longSide = new int[]{640, 1280, 1920, 3840}[quality];
        switch (aspectMode) {
            case 1: return new int[]{longSide, longSide * 3 / 4};
            case 2: return new int[]{longSide, longSide};
            case 3: return new int[]{longSide * 9 / 16, longSide};
            default: return new int[]{longSide, longSide * 9 / 16};
        }
    }
    String outputSizeLabel() { int[] z = outputSize(); return z[0] + " × " + z[1]; }
    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    void toast(String s) {
        // Replace the previous message instead of letting Toasts queue behind one another.
        runOnUiThread(() -> {
            if (activeToast != null) activeToast.cancel();
            activeToast = Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT);
            activeToast.show();
        });
    }

    // ───────────────────────── Design tokens ─────────────────────────
    static final int C_BG = 0xFF090C12, C_GLASS = 0xE60E131B, C_SURF = 0xFF1A212C,
        C_LINE = 0x2EFFFFFF, C_ACC = 0xFFFFB020, C_ACC2 = 0xFFFFD27A, C_DANGER = 0xFFE5484D,
        C_OK = 0xFF3DD68C, C_TXT = 0xFFF2F4F8, C_MUTE = 0xFF8E9AAE, C_DARK = 0xFF171B23;
    static final int EX = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    GradientDrawable box(int color, int r, int stroke) { return box(color, r, stroke, 1); }
    GradientDrawable box(int color, int r, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(r));
        if (stroke != 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }
    GradientDrawable shape(int color, int r) { return box(color, r, 0); }
    GradientDrawable gradient(int from, int to, int r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(dp(r)); return g;
    }
    // Efek riak (ripple) yang terpotong mengikuti sudut membulat.
    Drawable fx(Drawable base, int r) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), base, box(0xFFFFFFFF, r, 0));
    }
    // Efek menyusut halus saat tombol ditekan.
    void pressFx(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) x.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
    }
    // kind: 0 biasa · 1 utama (aksen) · 2 bahaya (outline) · 3 bahaya (solid) · 4 aksen (outline)
    void style(TextView b, int kind) {
        final int r = 14;
        switch (kind) {
            case 1: b.setBackground(fx(gradient(C_ACC2, C_ACC, r), r)); b.setTextColor(C_DARK); break;
            case 2: b.setBackground(fx(box(0x22E5484D, r, 0x66E5484D), r)); b.setTextColor(0xFFFF8A8E); break;
            case 3: b.setBackground(fx(box(C_DANGER, r, 0), r)); b.setTextColor(0xFFFFFFFF); break;
            case 4: b.setBackground(fx(box(0x22FFB020, r, 0x88FFB020), r)); b.setTextColor(C_ACC2); break;
            default: b.setBackground(fx(box(C_SURF, r, C_LINE), r)); b.setTextColor(C_TXT);
        }
    }
    // Tombol pada rail kiri/kanan. state: 0 biasa · 1 aktif · 2 bahaya/merah
    void rail(TextView t, int state) {
        final int r = 14;
        if (state == 1) { t.setBackground(fx(gradient(C_ACC2, C_ACC, r), r)); t.setTextColor(C_DARK); }
        else if (state == 2) { t.setBackground(fx(box(C_DANGER, r, 0), r)); t.setTextColor(0xFFFFFFFF); }
        else { t.setBackground(fx(box(0x14FFFFFF, r, 0), r)); t.setTextColor(C_TXT); }
    }
    CharSequence twoTone(String label, String value) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (label.length() > 0) { s.append(label); s.setSpan(new ForegroundColorSpan(C_MUTE), 0, s.length(), EX); }
        int st = s.length(); s.append(value);
        s.setSpan(new ForegroundColorSpan(C_ACC2), st, s.length(), EX);
        s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), EX);
        return s;
    }
    // Teks toolbar atas: label kecil redup + nilai tebal berwarna + panah dropdown.
    void setTool(TextView t, String label, String value) {
        if (t == null) return;
        SpannableStringBuilder s = new SpannableStringBuilder(label + "  ");
        s.setSpan(new ForegroundColorSpan(C_MUTE), 0, s.length(), EX);
        s.setSpan(new RelativeSizeSpan(0.82f), 0, s.length(), EX);
        int st = s.length(); s.append(value);
        int col = value.equals("ON") ? C_OK : value.equals("OFF") ? C_MUTE : C_ACC2;
        s.setSpan(new ForegroundColorSpan(col), st, s.length(), EX);
        s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), EX);
        int ar = s.length(); s.append("  ▾");
        s.setSpan(new ForegroundColorSpan(C_MUTE), ar, s.length(), EX);
        t.setText(s);
    }
    void setFg() { setTool(foregroundTool, "FOREGROUND", foregroundVisible ? "ON" : "OFF"); }
    CharSequence countText(int n) {
        SpannableStringBuilder s = new SpannableStringBuilder("● ");
        s.setSpan(new ForegroundColorSpan(C_ACC), 0, 1, EX);
        int a = s.length(); s.append(String.valueOf(n));
        s.setSpan(new ForegroundColorSpan(C_TXT), a, s.length(), EX);
        s.setSpan(new RelativeSizeSpan(1.2f), a, s.length(), EX);
        int c = s.length(); s.append("/" + MAXF + "  FRAME" + (n == 1 ? "" : "S"));
        s.setSpan(new ForegroundColorSpan(C_MUTE), c, s.length(), EX);
        return s;
    }

    TextView chip(String t, View.OnClickListener l) {
        TextView b = new TextView(this); b.setText(t); b.setTextSize(12); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(13), dp(10), dp(13), dp(10)); b.setMinHeight(dp(42));
        style(b, 0); b.setOnClickListener(l); pressFx(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(4), dp(4), dp(4), dp(4)); b.setLayoutParams(lp); return b;
    }
    TextView chipS(String t, int kind, View.OnClickListener l) { TextView c = chip(t, l); style(c, kind); return c; }
    TextView cycle(String label, String[] o, IntConsumer f) { return cycleAt(label, o, f, 0); }
    TextView cycleAt(String label, String[] o, IntConsumer f, int initial) {
        final int[] i = {Math.max(0, Math.min(initial, o.length - 1))};
        final TextView b = chip("", null);
        b.setText(twoTone(label, o[i[0]]));
        b.setOnClickListener(v -> { i[0] = (i[0] + 1) % o.length; b.setText(twoTone(label, o[i[0]])); f.accept(i[0]); });
        return b;
    }
    // Catatan kecil bergaya kartu.
    TextView tip(String t) {
        TextView x = new TextView(this); x.setText(t); x.setTextSize(11); x.setTextColor(C_MUTE); x.setLineSpacing(0f, 1.1f);
        x.setPadding(dp(12), dp(9), dp(12), dp(9)); x.setBackground(box(0x0FFFFFFF, 12, 0)); return x;
    }
    // Judul seksi dengan garis aksen di kiri.
    View title(String t) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(2), dp(8), dp(2), dp(4));
        View bar = new View(this); bar.setBackground(box(C_ACC, 2, 0));
        r.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(14)));
        TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(12); tv.setTextColor(C_TXT);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD); tv.setLetterSpacing(0.1f); tv.setPadding(dp(8), 0, 0, 0);
        r.addView(tv); return r;
    }
    // Baris tombol: setiap anak berbagi lebar sama rata.
    LinearLayout row(View... v) {
        LinearLayout r = new LinearLayout(this);
        for (View x : v) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (x.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) x.getLayoutParams();
                lp.setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin);
            }
            r.addView(x, lp);
        }
        return r;
    }
    LinearLayout panel(View... v) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(10), dp(6), dp(10), dp(10));
        for (View x : v) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); c.addView(x, lp); }
        return c;
    }
    View seek(String label, int max, int val, IntConsumer f) { return seek(label, max, val, p -> p + "%", f); }
    View seek(String label, int max, int val, IntFunction<String> fmt, IntConsumer f) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(dp(6), dp(6), dp(6), dp(2));
        r.setBackground(box(0x0FFFFFFF, 12, 0));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); t.setText(label); t.setTextSize(11); t.setTextColor(C_MUTE);
        final TextView vt = new TextView(this); vt.setText(fmt.apply(val)); vt.setTextSize(11); vt.setTextColor(C_ACC2); vt.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f)); top.addView(vt);
        top.setPadding(dp(6), 0, dp(6), 0);
        SeekBar s = new SeekBar(this); s.setMax(max); s.setProgress(val);
        s.setProgressTintList(ColorStateList.valueOf(C_ACC)); s.setThumbTintList(ColorStateList.valueOf(C_ACC2));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar x, int p, boolean u) { vt.setText(fmt.apply(p)); f.accept(p); }
            public void onStartTrackingTouch(SeekBar x) {}
            public void onStopTrackingTouch(SeekBar x) {}
        });
        r.addView(top); r.addView(s); return r;
    }
    View divider() {
        View v = new View(this); v.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(1)); lp.setMargins(0, dp(6), 0, dp(6)); lp.gravity = Gravity.CENTER_HORIZONTAL;
        v.setLayoutParams(lp); return v;
    }
    // Rail dibungkus ScrollView agar tetap bisa di-scroll pada layar landscape yang pendek.
    ScrollView railScroll(View content) {
        ScrollView sv = new ScrollView(this); sv.setFillViewport(true); sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER); sv.setBackground(box(C_GLASS, 22, C_LINE)); sv.setClipToOutline(true);
        sv.addView(content, new FrameLayout.LayoutParams(-1, -2)); return sv;
    }
    TextView mb(String icon, String name, View.OnClickListener l) {
        TextView t = chip(icon, l); rail(t, 0);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).setMargins(dp(4), dp(3), dp(4), dp(3));
        mi.add(t); mlabel.add(new String[]{icon, name}); return t;
    }
    void refreshMenus() {
        for (int i = 0; i < mi.size(); i++) {
            TextView t = mi.get(i); String[] l = mlabel.get(i);
            t.setText(labels ? l[0] + "  " + l[1] : l[0]); t.setTextSize(labels ? 12 : 20);
            t.setGravity(labels ? (Gravity.CENTER_VERTICAL | Gravity.START) : Gravity.CENTER);
            t.setPadding(dp(labels ? 12 : 0), dp(6), dp(labels ? 12 : 0), dp(6)); t.setMinWidth(dp(labels ? 100 : 46)); t.setMinHeight(dp(44));
        }
        int lw = dp(labels ? 132 : 64), rw = dp(labels ? 142 : 88);
        left.getLayoutParams().width = lw; right.getLayoutParams().width = rw;
        ((FrameLayout.LayoutParams) drawer.getLayoutParams()).leftMargin = lw + dp(14);
        FrameLayout.LayoutParams h2 = (FrameLayout.LayoutParams) hs.getLayoutParams(); h2.leftMargin = lw + dp(14); h2.rightMargin = rw + dp(14);
        left.requestLayout(); right.requestLayout(); drawer.requestLayout(); hs.requestLayout();
    }
    void openTab(int i) {
        cur = (cur == i) ? -1 : i;
        drawer.setVisibility(cur < 0 ? View.GONE : View.VISIBLE);
        for (int j = 0; j < pnl.length; j++) {
            pnl[j].setVisibility(j == cur ? View.VISIBLE : View.GONE);
            rail(tabs[j], j == cur ? 1 : 0);
        }
    }

    ActivityResultLauncher<String> pickForeground = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap src = BitmapFactory.decodeStream(in);
            if (src == null) throw new IOException("Format gambar tidak didukung");
            foregroundBmp = src.copy(Bitmap.Config.ARGB_8888, false);
            foregroundVisible = true; setFg(); vf.invalidate(); toast("Foreground berhasil dimuat");
        } catch (Exception e) { toast("Foreground tidak bisa dibuka"); }
    });
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
    // Simpan/buka project melalui pemilih file Android (format .smproj).
    ActivityResultLauncher<String> createProjectFile = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
            if (uri != null) saveProjectToUri(uri);
        });
    ActivityResultLauncher<String[]> openProjectFile = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) openProjectFromUri(uri);
        });

    TextView heading(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    View glassPanel() { View v = new View(this); v.setBackground(shape(0xD910151D, 18)); if (Build.VERSION.SDK_INT >= 21) v.setElevation(dp(8)); return v; }
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(C_BG); getWindow().setNavigationBarColor(C_BG);
        vf = new VF(this);
        vf.setOnClickListener(v -> { left.setVisibility(View.VISIBLE); hs.setVisibility(View.VISIBLE); });
        final int[] cols = {0xFF2B2F6B, 0xFFFFFFFF, 0xFF000000, 0xFF2E7D32};
        autoCaptureButton = chipS("▶ MULAI FOTO OTOMATIS", 1, v -> toggleAutoCapture());
        pnl = new View[]{
            panel(title("CHROMA KEY"), cycle("Kunci: ", new String[]{"Mati", "Hijau", "Biru"}, v -> keyMode = v),
                seek("Kekuatan kunci", 100, sens, v -> sens = v), seek("Kelembutan tepi", 100, soft, v -> soft = v),
                row(cycle("Latar: ", new String[]{"Biru tua", "Putih", "Hitam", "Hijau"}, v -> { bgColor = cols[v]; bgPx = null; }), chip("＋ Gambar latar", v -> pickImg.launch("image/*"))),
                title("FOREGROUND / OVERLAY"),
                row(chip("＋ Muat gambar", v -> pickForeground.launch("image/*")), chip("Tampil / Sembunyi", v -> { foregroundVisible = !foregroundVisible; setFg(); vf.invalidate(); })),
                seek("Transparansi foreground", 100, foregroundAlpha, v -> { foregroundAlpha = v; vf.invalidate(); }),
                tip("Foreground adalah gambar PNG/JPG yang ditumpangkan di atas kamera dan ikut tersimpan pada frame. Kualitas mengatur ukuran render ekspor; 4K dapat berupa pembesaran dari preview kamera.")),
            panel(title("PENGATURAN FRAME"), cycle("Timer: ", new String[]{"Mati", "3 dtk", "5 dtk", "10 dtk"}, v -> { delay = new int[]{0, 3, 5, 10}[v]; }),
                autoCaptureButton,
                cycle("Bayangan frame: ", new String[]{"Hidup", "Mati"}, v -> onion = v == 0),
                tip("Bayangan frame membantu mengatur gerakan antar-frame dengan lebih presisi.")),
            panel(title("EDIT"), seek("Kecerahan", 100, 50, v -> (v - 50) * 2 > 0 ? "+" + ((v - 50) * 2) : String.valueOf((v - 50) * 2), v -> bright = (v - 50) * 2),
                seek("Kontras", 100, 50, v -> (v * 2) + "%", v -> con = v * 2), seek("Saturasi", 100, 50, v -> (v * 2) + "%", v -> sat = v * 2),
                row(cycle("FPS: ", new String[]{"8", "4", "12", "24"}, v -> fps = new int[]{8, 4, 12, 24}[v]), cycle("Ulang: ", new String[]{"Ya", "Tidak"}, v -> loop = v == 0)),
                chip("Duplikat", v -> dupSel()),
                tip("Pilih thumbnail untuk meninjau frame. Tekan thumbnail sekali lagi untuk kembali ke kamera.")),
            panel(title("EXPORT VIDEO"),
                chipS("⬇ EXPORT VIDEO MP4", 1, v -> export()),
                tip("Video MP4 akan disimpan ke galeri pada folder Movies/StopMotion."),
                title("HAPUS FRAME"),
                row(chipS("HAPUS FRAME", 2, v -> delSel()), chipS("HAPUS SEMUA", 2, v -> confirmClearAll())))};
        FrameLayout holder = new FrameLayout(this);
        for (View v : pnl) { v.setVisibility(View.GONE); holder.addView(v); }
        drawer = new ScrollView(this); drawer.setFillViewport(true); drawer.setPadding(dp(6), dp(6), dp(6), dp(6));
        drawer.setVerticalScrollBarEnabled(false);
        drawer.setBackground(box(0xF20D121A, 22, C_LINE)); drawer.setClipToOutline(true);
        drawer.addView(holder); drawer.setVisibility(View.GONE);

        String[] tn = {"Layar", "Timer", "Edit", "Export"}, ti = {"◈", "◷", "✎", "⬇"}; tabs = new TextView[4];
        LinearLayout lc = new LinearLayout(this); lc.setOrientation(LinearLayout.VERTICAL); lc.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lc.setPadding(dp(5), dp(8), dp(5), dp(8));
        // Tombol pintas project: gradien aksen, garis halus, dan efek tekan.
        TextView newProjectShortcut = new TextView(this);
        SpannableString npText = new SpannableString("＋\nNEW\nPROJECT");
        npText.setSpan(new RelativeSizeSpan(1.9f), 0, 1, EX);
        newProjectShortcut.setText(npText);
        newProjectShortcut.setTextSize(9);
        newProjectShortcut.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        newProjectShortcut.setTextColor(C_DARK);
        newProjectShortcut.setGravity(Gravity.CENTER);
        newProjectShortcut.setLineSpacing(0f, 0.9f);
        GradientDrawable shortcutBg = gradient(C_ACC2, C_ACC, 16);
        shortcutBg.setStroke(dp(1), 0x99FFFFFF);
        newProjectShortcut.setBackground(shortcutBg);
        newProjectShortcut.setElevation(dp(4));
        newProjectShortcut.setContentDescription("Buat project baru");
        newProjectShortcut.setOnClickListener(v -> newProjectDialog());
        pressFx(newProjectShortcut);
        LinearLayout.LayoutParams bm = new LinearLayout.LayoutParams(dp(52), dp(58));
        bm.bottomMargin = dp(4);
        lc.addView(newProjectShortcut, bm);
        lc.addView(divider());
        lc.addView(mb("☰", "Nama menu", v -> { labels = !labels; refreshMenus(); }));
        for (int i = 0; i < tabs.length; i++) { final int k = i; tabs[i] = mb(ti[i], tn[i], v -> openTab(k)); lc.addView(tabs[i]); }
        left = railScroll(lc);

        strip = new LinearLayout(this); strip.setGravity(Gravity.CENTER_VERTICAL); strip.setPadding(dp(5), dp(3), dp(5), dp(3));
        hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setBackground(box(C_GLASS, 18, C_LINE)); hs.addView(strip);

        LinearLayout rc = new LinearLayout(this); rc.setOrientation(LinearLayout.VERTICAL); rc.setGravity(Gravity.CENTER); rc.setPadding(dp(5), dp(8), dp(5), dp(8));
        // Tombol simpan ditempatkan paling atas kanan, tepat di atas menu layar penuh.
        TextView saveProjectTop = mb("💾", "Simpan Project", v -> saveProjectDialog());
        rc.addView(saveProjectTop);
        rc.addView(mb("⛶", "Layar penuh", v -> { left.setVisibility(View.GONE); hs.setVisibility(View.GONE); if (cur >= 0) openTab(cur); }));
        flashButton = mb("⚡", "Flash", v -> toggleFlash());
        rc.addView(flashButton);
        rc.addView(divider());
        TextView shut = new TextView(this); shut.setGravity(Gravity.CENTER);
        shut.setBackground(shutterBg()); shut.setContentDescription("Ambil foto");
        shut.setElevation(dp(6));
        shut.setOnClickListener(v -> { review = false; shoot(); }); pressFx(shut);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(66), dp(66)); sp.setMargins(0, dp(6), 0, dp(6)); rc.addView(shut, sp);
        TextView captureLabel = heading("CAPTURE", 9, C_ACC); captureLabel.setLetterSpacing(0.14f); captureLabel.setGravity(Gravity.CENTER); rc.addView(captureLabel);
        rc.addView(divider());
        playPauseButton = mb("▶", "Play / Jeda", v -> togglePlay());
        rc.addView(playPauseButton);
        rc.addView(mb("⟳", "Balik kamera", v -> { front = !front; startCam(); }));
        right = railScroll(rc);

        cnt = heading("0 FRAME", 12, C_ACC2); cnt.setText(countText(0)); cnt.setGravity(Gravity.CENTER); cnt.setPadding(dp(14), dp(7), dp(14), dp(7)); cnt.setBackground(box(C_GLASS, 20, C_LINE));
        cd = new TextView(this); cd.setTextColor(0xFFFFFFFF); cd.setTextSize(90); cd.setTypeface(Typeface.DEFAULT, Typeface.BOLD); cd.setGravity(Gravity.CENTER);
        cd.setShadowLayer(dp(10), 0, dp(2), 0xCC000000);

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(C_BG);
        root.addView(vf, new FrameLayout.LayoutParams(-1, -1));
        // Toolbar sebagai jendela mengambang; handle ikut bergerak bersama panel.
        LinearLayout toolRow = new LinearLayout(this); toolRow.setGravity(Gravity.CENTER_VERTICAL); toolRow.setPadding(dp(6), dp(2), dp(6), dp(2));
        qualityTool = chip("", v -> chooseQuality());
        aspectTool = chip("", v -> chooseAspect());
        foregroundTool = chip("", v -> foregroundMenu(v));
        filterTool = chip("", v -> chooseFilter());
        setTool(qualityTool, "QUALITY", "HD"); setTool(aspectTool, "ASPECT", "16:9");
        setFg(); setTool(filterTool, "FILTER", "ORIGINAL");
        TextView openProject = chipS("📂  OPEN PROJECT", 4, v -> openProjectDialog());
        openProject.setSingleLine(true);
        openProject.setMinWidth(dp(142));
        openProject.setPadding(dp(12), dp(8), dp(12), dp(8));
        for (TextView item : new TextView[]{qualityTool, aspectTool, foregroundTool, filterTool, openProject}) {
            toolRow.addView(item, new LinearLayout.LayoutParams(-2, -2));
        }
        topTools = new HorizontalScrollView(this);
        topTools.setHorizontalScrollBarEnabled(false);
        topTools.setFillViewport(false);
        topTools.setClipToPadding(false);
        topTools.setPadding(0, 0, dp(8), 0);
        topTools.addView(toolRow);

        topToolsPanel = new LinearLayout(this);
        topToolsPanel.setOrientation(LinearLayout.VERTICAL);
        topToolsPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        topToolsPanel.setBackground(box(C_GLASS, 18, C_LINE));
        topToolsPanel.setElevation(dp(10));
        topToolsPanel.setClipToPadding(false);
        topToolsPanel.addView(topTools, new LinearLayout.LayoutParams(-1, dp(50)));

        topToolsToggle = new TextView(this);
        topToolsToggle.setText("⌃");
        topToolsToggle.setTextSize(14);
        topToolsToggle.setTextColor(C_MUTE);
        topToolsToggle.setGravity(Gravity.CENTER);
        topToolsToggle.setBackground(box(C_GLASS, 10, C_LINE));
        topToolsToggle.setContentDescription("Sembunyikan menu atas");
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(70), dp(20));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        topToolsPanel.addView(topToolsToggle, handleLp);
        topToolsToggle.setOnClickListener(v -> {
            topToolsVisible = !topToolsVisible;
            topToolsToggle.setText(topToolsVisible ? "⌃" : "⌄");
            topToolsToggle.setContentDescription(topToolsVisible ? "Sembunyikan menu atas" : "Tampilkan menu atas");
            // Geser panel dan handle sebagai satu unit, sehingga handle tidak tertinggal.
            topToolsPanel.animate().translationY(topToolsVisible ? 0f : -dp(50)).setDuration(190).start();
        });

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.setMargins(dp(82), dp(8), dp(104), 0);
        root.addView(topToolsPanel, tp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cp.topMargin = dp(82);
        root.addView(cnt, cp);

        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(300), -1, Gravity.START); dl.setMargins(dp(78), dp(116), 0, dp(82)); root.addView(drawer, dl);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM); hl.setMargins(dp(78), 0, dp(112), dp(8)); root.addView(hs, hl);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(64), -1, Gravity.START); ll.setMargins(dp(8), dp(8), 0, dp(8)); root.addView(left, ll);
        FrameLayout.LayoutParams rr = new FrameLayout.LayoutParams(dp(88), -1, Gravity.END); rr.setMargins(0, dp(8), dp(8), dp(8)); root.addView(right, rr);
        root.addView(cd, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        setContentView(root); refreshMenus(); refreshStrip();

        shutterSound = new MediaActionSound();
        shutterSound.load(MediaActionSound.SHUTTER_CLICK);
        if (checkSelfPermission(Manifest.permission.CAMERA) == 0) startCam(); else askCam.launch(Manifest.permission.CAMERA);
    }
    void chooseQuality() {
        String[] opts = {"SD", "HD", "HQ", "4K"};
        String[] desc = {"SD   ·   640 px   —   hemat ruang", "HD   ·   1280 px   —   seimbang", "HQ   ·   1920 px   —   tajam", "4K   ·   3840 px   —   maksimal"};
        new AlertDialog.Builder(this).setTitle("Quality").setSingleChoiceItems(desc, quality, (d, which) -> {
            quality = which; setTool(qualityTool, "QUALITY", opts[which]); d.dismiss(); toast("Quality " + opts[which] + " · " + outputSizeLabel());
        }).setNegativeButton("Batal", null).show();
    }
    void chooseAspect() {
        String[] opts = {"16:9", "4:3", "1:1", "9:16"};
        String[] desc = {"16:9   ·   Lebar / YouTube", "4:3   ·   Klasik", "1:1   ·   Persegi / Instagram", "9:16   ·   Vertikal / Reels & TikTok"};
        new AlertDialog.Builder(this).setTitle("Aspect Ratio").setSingleChoiceItems(desc, aspectMode, (d, which) -> {
            aspectMode = which; setTool(aspectTool, "ASPECT", opts[which]); d.dismiss(); vf.invalidate(); toast("Aspect ratio " + opts[which]);
        }).setNegativeButton("Batal", null).show();
    }
    void chooseFilter() {
        String[] opts = {"Original", "B&W", "Sepia", "Vintage", "Cool", "Warm", "Skin Smooth", "Brighten", "Soft Glow"};
        new AlertDialog.Builder(this).setTitle("Filter & Beauty").setSingleChoiceItems(opts, filterMode, (d, which) -> {
            filterMode = which;
            setTool(filterTool, "FILTER", opts[which].toUpperCase(Locale.ROOT));
            d.dismiss();
            if (which >= 6) beautyIntensityDialog(opts[which]);
            else { vf.invalidate(); toast("Filter " + opts[which]); }
        }).setNegativeButton("Batal", null).show();
    }
    void beautyIntensityDialog(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        TextView value = new TextView(this);
        value.setText("Intensitas: " + beautyStrength + "%");
        value.setTextColor(0xFFF2F4F8);
        SeekBar seek = new SeekBar(this);
        seek.setMax(100); seek.setProgress(beautyStrength);
        seek.setProgressTintList(ColorStateList.valueOf(C_ACC)); seek.setThumbTintList(ColorStateList.valueOf(C_ACC2));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { beautyStrength = progress; value.setText("Intensitas: " + progress + "%"); vf.invalidate(); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        box.addView(value); box.addView(seek);
        new AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Selesai", (dlg, w) -> { vf.invalidate(); toast(title + " aktif"); })
            .setNegativeButton("Batal", null).show();
    }
    void foregroundMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Muat gambar foreground").setOnMenuItemClickListener(item -> { pickForeground.launch("image/*"); return true; });
        menu.getMenu().add(foregroundVisible ? "Sembunyikan foreground" : "Tampilkan foreground").setOnMenuItemClickListener(item -> {
            foregroundVisible = !foregroundVisible; setFg(); vf.invalidate(); toast(foregroundVisible ? "Foreground aktif" : "Foreground disembunyikan"); return true;
        });
        menu.getMenu().add("Atur transparansi").setOnMenuItemClickListener(item -> { openTab(0); return true; });
        menu.show();
    }
    void saveProjectDialog() {
        if (frames.isEmpty()) { toast("Belum ada frame untuk disimpan"); return; }
        String safe = projectName.replaceAll("[^a-zA-Z0-9_-]", "_");
        createProjectFile.launch((safe.isEmpty() ? "StopMotion_Project" : safe) + ".smproj");
    }
    void openProjectDialog() {
        if (!frames.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Buka project lain?")
                .setMessage("Timeline saat ini akan diganti. Simpan project sekarang terlebih dahulu jika masih diperlukan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buka Project", (d, w) -> openProjectFile.launch(new String[]{"application/zip", "application/octet-stream"}))
                .show();
        } else openProjectFile.launch(new String[]{"application/zip", "application/octet-stream"});
    }
    void saveProjectToUri(Uri uri) {
        try (OutputStream raw = getContentResolver().openOutputStream(uri); ZipOutputStream zip = new ZipOutputStream(raw)) {
            if (raw == null) throw new IOException("File tidak bisa dibuat");
            Properties props = new Properties();
            props.setProperty("projectName", projectName);
            props.setProperty("quality", String.valueOf(quality)); props.setProperty("filterMode", String.valueOf(filterMode));
            props.setProperty("beautyStrength", String.valueOf(beautyStrength));
            props.setProperty("aspectMode", String.valueOf(aspectMode)); props.setProperty("fps", String.valueOf(fps));
            props.setProperty("delay", String.valueOf(delay)); props.setProperty("keyMode", String.valueOf(keyMode));
            props.setProperty("sens", String.valueOf(sens)); props.setProperty("soft", String.valueOf(soft));
            props.setProperty("bright", String.valueOf(bright)); props.setProperty("con", String.valueOf(con)); props.setProperty("sat", String.valueOf(sat));
            props.setProperty("foregroundAlpha", String.valueOf(foregroundAlpha)); props.setProperty("foregroundVisible", String.valueOf(foregroundVisible));
            props.setProperty("onion", String.valueOf(onion)); props.setProperty("loop", String.valueOf(loop));
            props.setProperty("bgColor", String.valueOf(bgColor));
            zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "Stop Motion Project"); zip.closeEntry();
            for (int i = 0; i < frames.size(); i++) {
                zip.putNextEntry(new ZipEntry(String.format(Locale.US, "frames/frame_%04d.png", i)));
                zip.write(frames.get(i)); zip.closeEntry();
            }
            if (foregroundBmp != null) {
                zip.putNextEntry(new ZipEntry("foreground.png"));
                foregroundBmp.compress(Bitmap.CompressFormat.PNG, 100, zip); zip.closeEntry();
            }
            zip.finish(); toast("Project berhasil disimpan: " + projectName);
        } catch (Exception e) { toast("Gagal menyimpan project: " + e.getMessage()); }
    }
    void openProjectFromUri(Uri uri) {
        try (InputStream raw = getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(raw)) {
            if (raw == null) throw new IOException("File tidak bisa dibaca");
            Properties props = new Properties(); List<byte[]> loaded = new ArrayList<>(); Bitmap loadedForeground = null;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                String name = entry.getName(); byte[] data = out.toByteArray();
                if (name.equals("project.properties")) props.load(new ByteArrayInputStream(data));
                else if (name.startsWith("frames/") && (name.endsWith(".jpg") || name.endsWith(".png"))) loaded.add(data);
                else if (name.equals("foreground.png")) loadedForeground = BitmapFactory.decodeByteArray(data, 0, data.length);
                zip.closeEntry();
            }
            if (loaded.isEmpty()) throw new IOException("Project tidak berisi frame");
            if (loaded.size() > MAXF) throw new IOException("Project melebihi batas " + MAXF + " frame");
            stopPlay(); stopAutoCapture(); counting = false; h.removeCallbacksAndMessages(null);
            frames.clear(); thumbs.clear(); frames.addAll(loaded); sel = -1; review = false; playBmp = null; onionBmp = null;
            projectName = props.getProperty("projectName", "Imported Project");
            quality = readInt(props, "quality", 1, 0, 3); filterMode = readInt(props, "filterMode", 0, 0, 8);
            beautyStrength = readInt(props, "beautyStrength", 45, 0, 100);
            aspectMode = readInt(props, "aspectMode", 0, 0, 3); fps = readInt(props, "fps", 8, 1, 60);
            delay = readInt(props, "delay", 0, 0, 10); keyMode = readInt(props, "keyMode", 0, 0, 2);
            sens = readInt(props, "sens", 50, 0, 100); soft = readInt(props, "soft", 30, 0, 100);
            bright = readInt(props, "bright", 0, -100, 100); con = readInt(props, "con", 100, 0, 200); sat = readInt(props, "sat", 100, 0, 200);
            foregroundAlpha = readInt(props, "foregroundAlpha", 65, 0, 100);
            foregroundVisible = Boolean.parseBoolean(props.getProperty("foregroundVisible", "false"));
            onion = Boolean.parseBoolean(props.getProperty("onion", "true")); loop = Boolean.parseBoolean(props.getProperty("loop", "true"));
            try { bgColor = Integer.parseInt(props.getProperty("bgColor", String.valueOf(0xFF2B2F6B))); } catch (Exception ignored) { bgColor = 0xFF2B2F6B; }
            foregroundBmp = loadedForeground; bgPx = null;
            for (byte[] data : frames) {
                Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (b != null) thumbs.put(data, Bitmap.createScaledBitmap(b, 160, 90, true));
                if (b != null) b.recycle();
            }
            setTool(qualityTool, "QUALITY", new String[]{"SD", "HD", "HQ", "4K"}[quality]);
            setTool(aspectTool, "ASPECT", new String[]{"16:9", "4:3", "1:1", "9:16"}[aspectMode]);
            setTool(filterTool, "FILTER", new String[]{"ORIGINAL", "B&W", "SEPIA", "VINTAGE", "COOL", "WARM", "SKIN SMOOTH", "BRIGHTEN", "SOFT GLOW"}[filterMode]);
            setFg();
            after(); toast("Project berhasil dibuka: " + projectName);
        } catch (Exception e) { toast("Gagal membuka project: " + e.getMessage()); }
    }
    int readInt(Properties p, String key, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, String.valueOf(fallback))))); }
        catch (Exception e) { return fallback; }
    }

    void newProjectDialog() {
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(projectName.equals("Untitled Project") ? "" : "");
        name.setHint("Nama project baru"); name.setTextSize(15); name.setTextColor(C_TXT); name.setHintTextColor(C_MUTE);
        name.setBackground(box(C_SURF, 12, C_LINE)); name.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(dp(20), dp(8), dp(20), dp(4)); wrap.addView(name, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Buat Project Baru").setMessage("Project baru akan dimulai dengan timeline kosong.")
            .setView(wrap).setNegativeButton("Batal", null).setPositiveButton("Buat", (dialog, which) -> {
                String entered = name.getText().toString().trim();
                if (entered.isEmpty()) { toast("Masukkan nama project"); return; }
                Runnable create = () -> {
                    stopPlay(); stopAutoCapture(); counting = false; h.removeCallbacksAndMessages(null);
                    frames.clear(); thumbs.clear(); sel = -1; review = false; playing = false; onionBmp = null; playBmp = null;
                    projectName = entered; if (projectTitle != null) projectTitle.setText(projectName);
                    quality = 1; filterMode = 0; aspectMode = 0; foregroundVisible = false; foregroundAlpha = 65; foregroundBmp = null;
                    setTool(qualityTool, "QUALITY", "HD"); setTool(aspectTool, "ASPECT", "16:9");
                    setFg(); setTool(filterTool, "FILTER", "ORIGINAL");
                    refreshStrip(); vf.invalidate();
                    toast("Project baru dibuat: " + projectName);
                };
                if (!frames.isEmpty()) new AlertDialog.Builder(this).setTitle("Mulai project baru?")
                    .setMessage("" + frames.size() + " frame pada project saat ini akan dilepas dari timeline. Pastikan sudah mengekspor video jika masih diperlukan.")
                    .setNegativeButton("Kembali", null).setPositiveButton("Buat Project", (d, w) -> create.run()).show();
                else create.run();
            }).show();
    }

    Drawable shutterBg() {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL); ring.setColor(0x33000000); ring.setStroke(dp(3), 0xFFFFFFFF);
        GradientDrawable core = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFF7A6E, 0xFFE5251B});
        core.setShape(GradientDrawable.OVAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, core});
        ld.setLayerInset(1, dp(7), dp(7), dp(7), dp(7));
        return ld;
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
            ProcessCameraProvider p = ProcessCameraProvider.getInstance(this).get();
            p.unbindAll();

            ImageAnalysis a = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

            a.setAnalyzer(ex, this::onFrame);

            activeCamera = p.bindToLifecycle(
                this,
                front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA,
                a
            );

            // Paksa pembesaran awal ke 1x. Perangkat yang tidak mendukung zoom ratio
            // akan tetap memakai zoom default CameraX.
            try {
                activeCamera.getCameraControl().setZoomRatio(1.0f);
            } catch (Exception ignored) { }

            if (flashOn) {
                if (!front && activeCamera.getCameraInfo().hasFlashUnit()) {
                    activeCamera.getCameraControl().enableTorch(true);
                } else {
                    flashOn = false;
                    updateFlashButton();
                }
            }

        } catch (Exception e) {
            toast("Kamera gagal dibuka");
        }
    }, ContextCompat.getMainExecutor(this));
}
void toggleFlash() {
    if (front) {
        toast("Flash hanya tersedia pada kamera belakang");
        return;
    }

    if (activeCamera == null || !activeCamera.getCameraInfo().hasFlashUnit()) {
        toast("Perangkat tidak memiliki flash kamera");
        return;
    }

    flashOn = !flashOn;

    activeCamera.getCameraControl().enableTorch(flashOn);

    updateFlashButton();

    toast(flashOn ? "Flash dinyalakan" : "Flash dimatikan");
}

void updateFlashButton() {
    // Ikon ⚡ tetap; status ditunjukkan lewat warna tombol (menyala = aksen).
    if (flashButton != null) rail(flashButton, flashOn ? 1 : 0);
}
    void onFrame(ImageProxy ip) {
        try {
            Bitmap src = ip.toBitmap(); int rot = ip.getImageInfo().getRotationDegrees();
            boolean sw = rot % 180 != 0;
            float rw = sw ? src.getHeight() : src.getWidth(), rh = sw ? src.getWidth() : src.getHeight();
            // Fit-center: tampilkan seluruh bidang kamera tanpa crop tambahan.
            // Sebelumnya max() memenuhi kanvas 16:9 dengan memotong gambar sensor
            // (sering tampak seperti zoom sekitar 1.3x pada sensor 4:3).
            float s = Math.min(W / rw, H / rh);
            Matrix m = new Matrix();
            m.postTranslate(-src.getWidth() / 2f, -src.getHeight() / 2f); m.postRotate(rot);
            m.postScale(front ? -s : s, s); m.postTranslate(W / 2f, H / 2f);
            tc.drawColor(0xFF000000); tc.drawBitmap(src, m, null);
            tmp.getPixels(px, 0, W, 0, 0, W, H);
            synchronized (rawLive) { rawLive.setPixels(px, 0, W, 0, 0, W, H); }
            key(px); applyFilter(px);
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

    void applyFilter(int[] p) {
        int mode = filterMode;
        if (mode == 0) return;
        int strength = beautyStrength;
        int[] source = null;
        if (mode == 6 || mode == 8) source = p.clone();
        for (int i = 0; i < p.length; i++) {
            int c = p[i], r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
            int nr = r, ng = g, nb = b;
            if (mode == 6 || mode == 8) {
                int x = i % W, y = i / W;
                int sr = 0, sg = 0, sb = 0, count = 0;
                for (int yy = Math.max(0, y - 1); yy <= Math.min(H - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(W - 1, x + 1); xx++) {
                        int q = source[yy * W + xx]; sr += q >> 16 & 255; sg += q >> 8 & 255; sb += q & 255; count++;
                    }
                }
                float mix = (strength / 100f) * (mode == 6 ? .72f : .42f);
                nr = cl((int)(r * (1f - mix) + (sr / (float)count) * mix));
                ng = cl((int)(g * (1f - mix) + (sg / (float)count) * mix));
                nb = cl((int)(b * (1f - mix) + (sb / (float)count) * mix));
                if (mode == 8) { nr = cl((int)(nr + strength * .18f)); ng = cl((int)(ng + strength * .16f)); nb = cl((int)(nb + strength * .12f)); }
            }
            else if (mode == 7) { float lift = strength * .55f; nr = cl((int)(r + lift)); ng = cl((int)(g + lift)); nb = cl((int)(b + lift)); }
            else if (mode == 1) { int y = (int)(.299f*r + .587f*g + .114f*b); nr = ng = nb = y; }
            else if (mode == 2 || mode == 3) {
                nr = cl((int)(.393f*r + .769f*g + .189f*b));
                ng = cl((int)(.349f*r + .686f*g + .168f*b));
                nb = cl((int)(.272f*r + .534f*g + .131f*b));
                if (mode == 3) { nr = cl((nr - 128) * 1.12f + 128); ng = cl((ng - 128) * 1.08f + 128); }
            } else if (mode == 4) { nr = cl(r * .82f); ng = cl(g * .96f + 5); nb = cl(b * 1.16f + 8); }
            else if (mode == 5) { nr = cl(r * 1.12f + 8); ng = cl(g * 1.04f + 3); nb = cl(b * .82f); }
            p[i] = (c & 0xFF000000) | nr << 16 | ng << 8 | nb;
        }
    }
    // Ambil frame asli, lalu jadikan warna hijau/biru transparan sebelum disimpan.
    void applyTransparentKey(int[] p) {
        int km = keyMode;
        int T = (int) (80 - sens * .7), E = (int) (soft * .8 + 1);
        int br = bright;
        float c = con / 100f, sa = sat / 100f;
        boolean adj = br != 0 || con != 100 || sat != 100;
        for (int i = 0; i < p.length; i++) {
            int cc = p[i], r = cc >> 16 & 255, g = cc >> 8 & 255, b = cc & 255;
            int a = 255;
            if (km > 0) {
                int sc = km == 1 ? g - Math.max(r, b) : b - Math.max(r, g);
                if (sc >= T + E) a = 0;
                else if (sc > T) a = Math.round(255f * (T + E - sc) / E);
                // Kurangi pantulan hijau/biru di tepi subjek.
                if (sc > 0) {
                    if (km == 1) g = Math.max(r, b);
                    else b = Math.max(r, g);
                }
            }
            if (adj) {
                float rf = (r - 128) * c + 128 + br, gf = (g - 128) * c + 128 + br, bf = (b - 128) * c + 128 + br;
                float y = .299f * rf + .587f * gf + .114f * bf;
                r = cl(y + (rf - y) * sa); g = cl(y + (gf - y) * sa); b = cl(y + (bf - y) * sa);
            }
            p[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    Bitmap makeCaptureBitmap() {
        Bitmap base;
        synchronized (rawLive) { base = rawLive.copy(Bitmap.Config.ARGB_8888, true); }
        int[] capturePixels = new int[W * H];
        base.getPixels(capturePixels, 0, W, 0, 0, W, H);
        applyTransparentKey(capturePixels);
        applyFilter(capturePixels);
        base.setPixels(capturePixels, 0, W, 0, 0, W, H);
        if (foregroundVisible && foregroundBmp != null) {
            Canvas c = new Canvas(base); Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            fp.setAlpha(foregroundAlpha * 255 / 100);
            c.drawBitmap(foregroundBmp, null, new Rect(0, 0, W, H), fp);
        }
        float ar = aspectValue(); int cw = W, ch = H;
        if (cw / (float) ch > ar) cw = Math.round(ch * ar); else ch = Math.round(cw / ar);
        int left = (W - cw) / 2, top = (H - ch) / 2;
        Bitmap cropped = Bitmap.createBitmap(base, left, top, cw, ch);
        if (cropped != base) base.recycle();
        int[] size = outputSize();
        Bitmap out = Bitmap.createScaledBitmap(cropped, size[0], size[1], true);
        if (out != cropped) cropped.recycle();
        return out;
    }

    // frame & timer
    void shoot() {
        if (playing || counting || autoCapturing) return;
        if (delay == 0) snap(); else { counting = true; tick(delay); }
    }

    // Timer otomatis mengambil frame berulang sesuai interval yang dipilih.
    final Runnable autoCaptureTick = new Runnable() {
        @Override public void run() {
            if (!autoCapturing) return;
            if (autoRemaining > 0) {
                cd.setText(String.valueOf(autoRemaining));
                autoRemaining--;
                h.postDelayed(this, 1000);
                return;
            }
            cd.setText("");
            snap();
            if (frames.size() >= MAXF) {
                stopAutoCapture();
                toast("Batas maksimal frame tercapai");
                return;
            }
            autoRemaining = delay;
            h.post(this);
        }
    };

    void toggleAutoCapture() {
        if (autoCapturing) {
            stopAutoCapture();
            toast("Foto otomatis dihentikan");
            return;
        }
        if (delay <= 0) {
            toast("Pilih timer 3, 5, atau 10 detik terlebih dahulu");
            return;
        }
        if (frames.size() >= MAXF) {
            toast("Maksimal " + MAXF + " frame");
            return;
        }
        stopPlay();
        counting = false;
        h.removeCallbacksAndMessages(null);
        autoCapturing = true;
        autoRemaining = delay;
        cd.setText(String.valueOf(autoRemaining));
        h.post(autoCaptureTick);
        if (autoCaptureButton != null) { autoCaptureButton.setText("■ HENTIKAN FOTO OTOMATIS"); style(autoCaptureButton, 3); }
        toast("Foto otomatis dimulai setiap " + delay + " detik. Tekan tombol lagi untuk berhenti.");
    }

    void stopAutoCapture() {
        autoCapturing = false;
        h.removeCallbacks(autoCaptureTick);
        autoRemaining = 0;
        if (cd != null) cd.setText("");
        if (autoCaptureButton != null) { autoCaptureButton.setText("▶ MULAI FOTO OTOMATIS"); style(autoCaptureButton, 1); }
    }

    void tick(int n) {
        if (n == 0) { cd.setText(""); counting = false; snap(); return; }
        cd.setText(String.valueOf(n)); h.postDelayed(() -> tick(n - 1), 1000);
    }
       void snap() {
        if (frames.size() >= MAXF) {
            toast("Maksimal " + MAXF + " frame");
            return;
        }

        if (shutterSound != null) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK);
        }

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Bitmap capture = makeCaptureBitmap();
        capture.compress(Bitmap.CompressFormat.PNG, 100, o);
        onionBmp = capture.copy(Bitmap.Config.ARGB_8888, false);
        thumb = Bitmap.createScaledBitmap(capture, 160, 90, true);
        capture.recycle();

        byte[] jb = o.toByteArray();
        frames.add(jb);
        thumbs.put(jb, thumb);
        review = false;
        sel = frames.size() - 1;
        refreshStrip();

        toast("Frame disimpan");
    }

    @Override
    protected void onDestroy() {
        if (activeCamera != null) {
            try {
                activeCamera.getCameraControl().enableTorch(false);
            } catch (Exception ignored) {
            }
        }

        if (shutterSound != null) {
            shutterSound.release();
            shutterSound = null;
        }

        ex.shutdown();
        super.onDestroy();
    }

    void select(int i) {
        if (i < 0 || i >= frames.size()) { sel = -1; review = false; }
        else { sel = i; byte[] d = frames.get(i); playBmp = BitmapFactory.decodeByteArray(d, 0, d.length); review = true; }
        refreshStrip(); vf.invalidate();
    }
    void refreshStrip() {
        strip.removeAllViews();
        if (frames.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Belum ada frame  ·  tekan tombol ● untuk mengambil foto pertama");
            hint.setTextSize(11); hint.setTextColor(C_MUTE); hint.setGravity(Gravity.CENTER_VERTICAL); hint.setPadding(dp(12), 0, dp(12), 0);
            strip.addView(hint, new LinearLayout.LayoutParams(-2, dp(50)));
        }
        for (int i = 0; i < frames.size(); i++) {
            final int k = i; final boolean on = i == sel;
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(dp(2), dp(2), dp(2), dp(2));
            cell.setBackground(box(0xFF11161E, 9, on ? C_ACC : C_LINE, on ? 2 : 1));
            cell.setAlpha(sel < 0 || on ? 1f : 0.78f);
            ImageView v = new ImageView(this);
            v.setImageBitmap(thumbs.get(frames.get(i))); v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View vw, Outline o) { o.setRoundRect(0, 0, vw.getWidth(), vw.getHeight(), dp(7)); }
            });
            v.setClipToOutline(true);
            cell.addView(v, new FrameLayout.LayoutParams(-1, -1));
            TextView num = new TextView(this); num.setText(String.valueOf(i + 1)); num.setTextSize(8); num.setTextColor(0xFFFFFFFF);
            num.setTypeface(Typeface.DEFAULT, Typeface.BOLD); num.setPadding(dp(4), dp(1), dp(4), dp(1)); num.setBackground(box(0xAA000000, 6, 0));
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); nl.setMargins(dp(3), 0, 0, dp(3));
            cell.addView(num, nl);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(50)); lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            cell.setLayoutParams(lp); cell.setOnClickListener(x -> select(k == sel ? -1 : k)); strip.addView(cell);
        }
        cnt.setText(countText(frames.size()));
        final View c = strip.getChildAt(sel);
        if (c != null) hs.post(() -> hs.smoothScrollTo(c.getLeft() - dp(100), 0));
    }
    void after() {
        if (frames.isEmpty()) onionBmp = null; else { byte[] d = frames.get(frames.size() - 1); onionBmp = BitmapFactory.decodeByteArray(d, 0, d.length); }
        if (review && sel >= 0) select(sel); else { review = false; refreshStrip(); vf.invalidate(); }
    }
    void delSel() {
        stopPlay(); if (frames.isEmpty()) return;
        int i = sel >= 0 ? sel : frames.size() - 1; byte[] removed = frames.remove(i); thumbs.remove(removed);
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
    void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle("Hapus semua frame?")
            .setMessage("Semua frame dalam project ini akan dihapus. Tindakan ini tidak bisa dibatalkan.")
            .setNegativeButton("BATAL", (d, w) -> d.dismiss())
            .setPositiveButton("HAPUS SEMUA", (d, w) -> clearAll())
            .show();
    }
    void clearAll() { stopPlay(); frames.clear(); thumbs.clear(); sel = -1; review = false; after(); }

    // putar
    final Runnable pl = new Runnable() { public void run() {
        if (!playing || frames.isEmpty()) return;
        if (!loop && pi >= frames.size()) { stopPlay(); vf.invalidate(); return; }
        byte[] d = frames.get(pi++ % frames.size()); playBmp = BitmapFactory.decodeByteArray(d, 0, d.length);
        vf.invalidate(); h.postDelayed(this, 1000 / fps);
    } };
    // Satu tombol berganti antara Play dan Jeda. Resume mempertahankan posisi frame.
    void togglePlay() {
        if (playing) {
            playing = false;
            paused = true;
            review = true;
            h.removeCallbacks(pl);
            updatePlayPauseButton();
            vf.invalidate();
            return;
        }
        if (frames.isEmpty()) { toast("Ambil frame dulu"); return; }
        if (!paused) pi = 0;
        review = false;
        paused = false;
        playing = true;
        updatePlayPauseButton();
        h.removeCallbacks(pl);
        h.post(pl);
    }

    void updatePlayPauseButton() {
        if (playPauseButton != null) {
            String icon = playing ? "Ⅱ" : "▶";
            playPauseButton.setContentDescription(playing ? "Jeda" : "Play");
            int index = mi.indexOf(playPauseButton);
            if (index >= 0) mlabel.get(index)[0] = icon;
            playPauseButton.setText(labels && index >= 0 ? icon + "  " + mlabel.get(index)[1] : icon);
            // Merah saat pemutaran aktif, kembali ke gaya normal saat jeda/berhenti.
            rail(playPauseButton, playing ? 2 : 0);
        }
    }

    // Menghentikan pemutaran dari proses internal tanpa menambahkan tombol Stop di layar.
    void stopPlay() {
        playing = false;
        paused = false;
        h.removeCallbacks(pl);
        updatePlayPauseButton();
    }

    // ekspor MP4 ke galeri (Movies/StopMotion), berjalan sebanyak durasi video
    void export() {
        if (frames.isEmpty()) { toast("Ambil frame dulu"); return; }
        stopPlay(); final int f = fps; final List<byte[]> fr = new ArrayList<>(frames); final int[] outSize = outputSize();
        toast("Mengekspor " + outputSizeLabel() + "...");
        new Thread(() -> {
            try {
                MediaFormat mf = MediaFormat.createVideoFormat("video/avc", outSize[0], outSize[1]);
                mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                mf.setInteger(MediaFormat.KEY_BIT_RATE, quality == 3 ? 24000000 : quality == 2 ? 12000000 : quality == 1 ? 6000000 : 2500000); mf.setInteger(MediaFormat.KEY_FRAME_RATE, f);
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
                    Canvas c = sf.lockCanvas(null); c.drawBitmap(bm, null, new Rect(0, 0, outSize[0], outSize[1]), null); sf.unlockCanvasAndPost(c); bm.recycle();
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
