package ru.opsel.snezhok;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Снежок: снег, экскаваторы, снеговик и кола поверх главного экрана. Все части в одном файле. */
public final class App {
    private App() {}

    // =====================================================================
    // Настройки
    // =====================================================================
    static final class Prefs {
        private static SharedPreferences sp(Context c) { return c.getSharedPreferences("snow", Context.MODE_PRIVATE); }
        static boolean get(Context c, String k, boolean def) { return sp(c).getBoolean(k, def); }
        static void put(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }
        static boolean on(Context c) { return get(c, "on", false); }
        static void setOn(Context c, boolean v) { put(c, "on", v); }
        static int level(Context c) { return sp(c).getInt("level", 1); }
        static void setLevel(Context c, int v) { sp(c).edit().putInt("level", v).apply(); }
        static boolean garland(Context c) { return get(c, "garland", true); }
        static boolean pile(Context c) { return get(c, "pile", true); }
        static boolean excav(Context c) { return get(c, "excav", true); }
        static boolean balls(Context c) { return get(c, "balls", true); }
        static boolean cola(Context c) { return get(c, "cola", true); }
        static boolean snow(Context c) { return get(c, "snow", true); }
        static int getInt(Context c, String k, int def) { return sp(c).getInt(k, def); }
        static void putInt(Context c, String k, int v) { sp(c).edit().putInt(k, v).apply(); }
    }

    /** Запускает снег, если пользователь его включал. Используется после перезагрузки, обновления и проверок. */
    static void startIfWanted(Context c) {
        if (!Prefs.on(c) || SnowService.running || !Settings.canDrawOverlays(c)) return;
        try { c.startForegroundService(new Intent(c, SnowService.class)); } catch (Exception ignored) {}
    }

    /** Будильник раз в ~15 минут проверяет, не убила ли система снег. */
    static void scheduleWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 10,
                    new Intent(c, KeepAlive.class).setAction("ru.opsel.snezhok.CHECK"),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                    AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
        } catch (Exception ignored) {}
    }

    /** Перезагрузка, обновление приложения, проверка по будильнику: включаем снег обратно. */
    public static class KeepAlive extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            startIfWanted(c);
            if (Prefs.on(c)) scheduleWatchdog(c);
        }
    }

    static void buzz(Context c, long... pattern) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (pattern.length == 1) v.vibrate(VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) {}
    }

    // =====================================================================
    // Игрушка: снежок или банка колы. У каждой своё маленькое окно, которое ловит касания.
    // =====================================================================
    static final class Toy {
        static final int BALL = 0, COLA = 1;
        int type;
        float x, y, r, hw, hh, vx, vy;
        boolean held, grounded;
        Toy restOn;
        int face, body;
        int cola;            // 0 закрыта, 1 открыта, 2 пустая и смятая
        float level = 1f, tiltT = 0f;
        String say;
        float sayT;
        ToyView view;
        WindowManager.LayoutParams lp;
        int winW, winH, lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
    }

    /** Рисует снежок вместе с лицом, пуговицами, руками и шапкой. Центр в (cx, cy). */
    static void drawBall(Canvas c, Toy t, float cx, float cy, Paint p, Path path) {
        float r = t.r;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        if (t.body > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(Math.max(3, r * 0.08f));
            p.setColor(0xFF5A3A1E);
            for (int sd = -1; sd <= 1; sd += 2) {
                c.drawLine(cx + sd * r * 0.85f, cy - r * 0.15f, cx + sd * r * 1.7f, cy - r * 0.75f, p);
                c.drawLine(cx + sd * r * 1.42f, cy - r * 0.55f, cx + sd * r * 1.58f, cy - r * 0.95f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        p.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.2f,
                new int[]{0xFFFFFFFF, 0xFFEEF1FF, 0xFFB6C0EE}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        if (t.body > 0) {
            p.setColor(0xFF222222);
            for (int k = 0; k < 3; k++) c.drawCircle(cx, cy - r * 0.4f + k * r * 0.36f, r * 0.08f, p);
        }
        if (t.face > 0) {
            p.setColor(0xFF1A1A1A);
            c.drawCircle(cx - r * 0.32f, cy - r * 0.22f, r * 0.1f, p);
            c.drawCircle(cx + r * 0.32f, cy - r * 0.22f, r * 0.1f, p);
            for (int k = 0; k < 5; k++) {
                double a = 0.45 + k * 0.56;
                c.drawCircle(cx + (float) Math.cos(a) * r * 0.5f, cy + r * 0.12f + (float) Math.sin(a) * r * 0.32f, r * 0.055f, p);
            }
            p.setColor(0xFFFF8A1E);
            path.reset();
            path.moveTo(cx, cy - r * 0.06f);
            path.lineTo(cx + r * 0.75f, cy + r * 0.04f);
            path.lineTo(cx, cy + r * 0.14f);
            path.close();
            c.drawPath(path, p);
            if (t.face >= 3) {
                p.setColor(0xFF16161E);
                c.drawRect(cx - r * 0.8f, cy - r * 0.88f, cx + r * 0.8f, cy - r * 0.72f, p);
                c.drawRect(cx - r * 0.5f, cy - r * 1.65f, cx + r * 0.5f, cy - r * 0.85f, p);
                p.setColor(0xFFD8263B);
                c.drawRect(cx - r * 0.5f, cy - r * 1.05f, cx + r * 0.5f, cy - r * 0.9f, p);
            } else {
                p.setColor(0xFFD8263B);
                path.reset();
                path.moveTo(cx - r * 0.75f, cy - r * 0.62f);
                path.quadTo(cx, cy - r * 1.9f, cx + r * 0.75f, cy - r * 0.62f);
                path.close();
                c.drawPath(path, p);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx + r * 0.1f, cy - r * 1.32f, r * 0.16f, p);
                c.drawRect(cx - r * 0.8f, cy - r * 0.72f, cx + r * 0.8f, cy - r * 0.54f, p);
            }
        }
    }

    public static class ToyView extends View {
        final Toy t;
        final SnowView owner;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF rf = new RectF();
        float gdx, gdy, downX, downY, svx, svy;
        long downT, lastT;
        boolean moved;

        ToyView(Context c, Toy t, SnowView owner) {
            super(c);
            this.t = t;
            this.owner = owner;
            d = getResources().getDisplayMetrics().density;
            p.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (t.type == Toy.BALL) {
                // в окне шарик может быть сдвинут, если окно упёрлось в край экрана
                drawBall(c, t, cx + (t.x - owner.winCenterX(t)), cy + (t.y - owner.winCenterY(t)), p, path);
            } else {
                drawCola(c, cx + (t.x - owner.winCenterX(t)), cy + (t.y - owner.winCenterY(t)));
            }
        }

        private void drawCola(Canvas c, float cx, float cy) {
            float w = 30 * d, h = 54 * d;
            c.save();
            if (t.cola == 2) {
                // смятая банка лежит на боку
                c.translate(cx, cy);
                c.rotate(-82);
                path.reset();
                float hw = w / 2, hh = 13 * d;
                path.moveTo(-hw, -hh);
                path.lineTo(hw, -hh);
                path.lineTo(hw - 4 * d, -hh / 3);
                path.lineTo(hw + 2 * d, hh / 3);
                path.lineTo(hw, hh);
                path.lineTo(-hw, hh);
                path.lineTo(-hw + 3 * d, hh / 3);
                path.lineTo(-hw - 2 * d, -hh / 3);
                path.close();
                p.setShader(null);
                p.setColor(0xFFB3202F);
                c.drawPath(path, p);
                p.setColor(0xFFCFD3DA);
                c.drawRect(-hw, -hh - 3 * d, hw, -hh, p);
                p.setColor(0x88FFFFFF);
                c.drawRect(-hw + 4 * d, -hh / 4, hw - 4 * d, -hh / 4 + 3 * d, p);
                c.restore();
                return;
            }
            float tilt = 0;
            if (t.tiltT > 0) tilt = (float) Math.sin(Math.PI * Math.min(1, (1 - t.tiltT) * 1.4)) * -75f;
            c.translate(cx, cy);
            c.rotate(tilt);
            // корпус
            rf.set(-w / 2, -h / 2 + 4 * d, w / 2, h / 2);
            p.setShader(new LinearGradient(-w / 2, 0, w / 2, 0,
                    new int[]{0xFF9E1626, 0xFFE8303F, 0xFFFF6A75, 0xFFC81E2E, 0xFF8A1220},
                    new float[]{0f, 0.3f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(rf, 5 * d, 5 * d, p);
            p.setShader(null);
            // белая волна
            p.setColor(0xFFFFFFFF);
            path.reset();
            path.moveTo(-w / 2, 6 * d);
            path.cubicTo(-w / 6, -4 * d, w / 6, 16 * d, w / 2, 4 * d);
            path.lineTo(w / 2, 9 * d);
            path.cubicTo(w / 6, 21 * d, -w / 6, 1 * d, -w / 2, 11 * d);
            path.close();
            c.drawPath(path, p);
            // надпись
            p.setTextSize(10 * d);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("COLA", 0, -6 * d, p);
            // крышка
            p.setShader(new LinearGradient(-w / 2, 0, w / 2, 0, 0xFF8E949E, 0xFFE9ECF1, Shader.TileMode.MIRROR));
            rf.set(-w / 2 + 2 * d, -h / 2, w / 2 - 2 * d, -h / 2 + 6 * d);
            c.drawRoundRect(rf, 3 * d, 3 * d, p);
            p.setShader(null);
            if (t.cola >= 1) {
                p.setColor(0xFF2A2A2A);
                rf.set(-5 * d, -h / 2 + 1 * d, 3 * d, -h / 2 + 4.5f * d);
                c.drawOval(rf, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(0xFFB8BEC8);
                rf.set(1 * d, -h / 2 - 8 * d, 9 * d, -h / 2 + 2 * d);
                c.drawOval(rf, p);
                p.setStyle(Paint.Style.FILL);
            } else {
                p.setColor(0xFFB8BEC8);
                rf.set(-4 * d, -h / 2 + 1 * d, 6 * d, -h / 2 + 4 * d);
                c.drawRoundRect(rf, 2 * d, 2 * d, p);
            }
            // капельки конденсата
            p.setColor(0x66FFFFFF);
            c.drawCircle(-8 * d, 16 * d, 1.6f * d, p);
            c.drawCircle(7 * d, 22 * d, 1.3f * d, p);
            c.drawCircle(-4 * d, 24 * d, 1.1f * d, p);
            c.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float rx = e.getRawX(), ry = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    t.held = true;
                    t.vx = t.vy = 0;
                    svx = svy = 0;
                    gdx = rx - owner.ox - t.x;
                    gdy = ry - owner.oy - t.y;
                    downX = rx; downY = ry;
                    downT = lastT = e.getEventTime();
                    moved = false;
                    owner.start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (Math.hypot(rx - downX, ry - downY) > 10 * d) moved = true;
                    if (!moved) return true;
                    float nx = rx - owner.ox - gdx, ny = ry - owner.oy - gdy;
                    float dt = (e.getEventTime() - lastT) / 1000f;
                    if (dt > 0.001f) {
                        svx = svx * 0.5f + (nx - t.x) / dt * 0.5f;
                        svy = svy * 0.5f + (ny - t.y) / dt * 0.5f;
                    }
                    t.x = nx; t.y = ny;
                    lastT = e.getEventTime();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    t.held = false;
                    if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        performClick();
                        owner.onTap(t);
                    } else {
                        float lim = 2200 * d;
                        t.vx = Math.max(-lim, Math.min(lim, svx));
                        t.vy = Math.max(-lim, Math.min(lim, svy));
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        public boolean performClick() { return super.performClick(); }
    }

    // =====================================================================
    // Главный слой: снег, сугроб, гирлянда, экскаваторы. Касания проходят сквозь него.
    // =====================================================================
    static final class Exc {
        float x, load, t, wheel;
        int dir;
    }

    public static class SnowView extends View implements Choreographer.FrameCallback {
        private static final int MAX = 520;
        private static final int[] LEVELS = {110, 260, 480};
        private static final int[] BULBS = {0xFFFF4D5E, 0xFFFFD84D, 0xFF4DFF8B, 0xFF4DBCFF, 0xFFFF6BF0};

        final float d;
        private final Random rnd = new Random();
        private final WindowManager wm;

        private final float[] x = new float[MAX], y = new float[MAX], r = new float[MAX], vy = new float[MAX], ph = new float[MAX];
        private final boolean[] front = new boolean[MAX];
        private int n = 0;

        private float[] pile = new float[2];
        private final float col, cap;

        private static final int PMAX = 500;
        private final float[] px = new float[PMAX], py = new float[PMAX], pvx = new float[PMAX], pvy = new float[PMAX],
                plife = new float[PMAX], pr = new float[PMAX];
        private final int[] pcol = new int[PMAX];
        private final boolean[] pfloat = new boolean[PMAX];
        private int pn = 0;

        private final Paint flakeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pileP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wireP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rf = new RectF();

        private int level = 1;
        private boolean garland = true, pileOn = true, excavOn = true, ballsOn = true, colaOn = true, snowOn = true;
        private float tilt = 0, T = 0, shakeKick = 0, excTimer = 0, nextExc = 300;
        private long last = 0, lastShake = 0;
        private boolean running = false, needSync = false;
        private final int statusH;
        private float lax, lay, laz;
        private boolean hasAcc = false;
        private int frameNo = 0, prevSnowmen = 0;
        private final int[] loc = new int[2];
        float ox = 0, oy = 0;

        final ArrayList<Toy> toys = new ArrayList<>();
        private final ArrayList<Exc> excs = new ArrayList<>();

        public SnowView(Context c, WindowManager wm) {
            super(c);
            this.wm = wm;
            d = getResources().getDisplayMetrics().density;
            col = 3 * d;
            cap = 34 * d;
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            statusH = id > 0 ? getResources().getDimensionPixelSize(id) : (int) (24 * d);
            flakeP.setColor(0xF2FFFFFF);
            backP.setColor(0x8CFFFFFF);
            wireP.setStyle(Paint.Style.STROKE);
            wireP.setStrokeWidth(1.2f * d);
            wireP.setColor(0xFF1D3B2A);
            lineP.setStyle(Paint.Style.STROKE);
            lineP.setStrokeCap(Paint.Cap.ROUND);
            textP.setTextAlign(Paint.Align.CENTER);
            textP.setTypeface(Typeface.DEFAULT_BOLD);
            textP.setTextSize(14 * d);
        }

        void setPrefs(int level, boolean snowOn, int bright, boolean garland, boolean pileOn, boolean excavOn, boolean ballsOn, boolean colaOn) {
            this.level = Math.max(0, Math.min(2, level));
            this.snowOn = snowOn;
            // яркость снега, гирлянды и сугроба: 0 ярко, 1 средне, 2 еле-еле
            setAlpha(bright == 2 ? 0.35f : bright == 1 ? 0.65f : 1f);
            this.garland = garland;
            this.pileOn = pileOn;
            this.excavOn = excavOn;
            this.ballsOn = ballsOn;
            this.colaOn = colaOn;
            if (!pileOn) Arrays.fill(pile, 0);
            if (getWidth() > 0) syncToys(); else needSync = true;
            invalidate();
        }

        void start() {
            if (running) return;
            running = true;
            last = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void stop() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float[] np = new float[(int) (w / col) + 2];
            for (int i = 0; i < np.length && i < pile.length; i++) np[i] = pile[i];
            pile = np;
            pileP.setShader(new LinearGradient(0, h - cap, 0, h, 0xFFFFFFFF, 0xFFD9E0FF, Shader.TileMode.CLAMP));
            if (n == 0 && snowOn) {
                int target = LEVELS[level];
                for (int i = 0; i < target; i++) spawn(i, false);
                n = target;
            }
            updateOffset();
            if (needSync) { needSync = false; syncToys(); }
        }

        private void updateOffset() {
            try {
                getLocationOnScreen(loc);
                ox = loc[0];
                oy = loc[1];
            } catch (Exception ignored) {}
        }

        // ---------------- игрушки ----------------
        private void syncToys() {
            boolean hasBall = false, hasCola = false;
            for (Toy t : toys) { if (t.type == Toy.BALL) hasBall = true; else hasCola = true; }
            float W = getWidth(), H = getHeight();
            if (ballsOn && !hasBall) {
                float[] rs = {34, 26, 19};
                float[] xs = {0.22f, 0.42f, 0.6f};
                for (int i = 0; i < 3; i++) {
                    Toy t = new Toy();
                    t.type = Toy.BALL;
                    t.r = t.hw = t.hh = rs[i] * d;
                    t.x = W * xs[i];
                    t.y = H * 0.45f;
                    addToy(t);
                }
            }
            if (!ballsOn && hasBall) removeToys(Toy.BALL);
            if (colaOn && !hasCola) {
                Toy t = new Toy();
                t.type = Toy.COLA;
                t.hw = 15 * d;
                t.hh = 27 * d;
                t.x = W * 0.84f;
                t.y = H * 0.45f;
                addToy(t);
            }
            if (!colaOn && hasCola) removeToys(Toy.COLA);
        }

        private void addToy(Toy t) {
            t.view = new ToyView(getContext(), t, this);
            if (t.type == Toy.BALL) {
                t.winW = t.winH = (int) Math.ceil(t.r * 2 + 4 * d);
            } else {
                t.winW = t.winH = (int) (76 * d);
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(t.winW, t.winH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            t.lp = lp;
            lp.x = Math.round(winCenterX(t) + ox - t.winW / 2f);
            lp.y = Math.round(winCenterY(t) + oy - t.winH / 2f);
            try {
                wm.addView(t.view, lp);
                toys.add(t);
            } catch (Exception ignored) {}
        }

        private void removeToys(int type) {
            for (int i = toys.size() - 1; i >= 0; i--) {
                Toy t = toys.get(i);
                if (type >= 0 && t.type != type) continue;
                try { wm.removeView(t.view); } catch (Exception ignored) {}
                toys.remove(i);
            }
        }

        void removeAllToys() { removeToys(-1); }

        /** Центр окна игрушки. Окно не уходит за экран, а сама игрушка может. */
        float winCenterX(Toy t) { return t.x; }
        float winCenterY(Toy t) { return t.y; }

        private void say(Toy t, String s) { t.say = s; t.sayT = 1.8f; }

        void onTap(Toy t) {
            if (t.type == Toy.BALL) {
                t.vy = -520 * d;
                t.vx += (rnd.nextFloat() - 0.5f) * 80 * d;
                buzz(getContext(), 15);
                return;
            }
            if (t.cola == 0) {
                t.cola = 1;
                say(t, "Пшшш!");
                fizz(t, 28);
                buzz(getContext(), 0, 30, 30, 30, 30, 60);
                t.view.invalidate();
            } else if (t.cola == 1) {
                if (t.tiltT > 0) return;
                t.tiltT = 1f;
                t.level -= 0.25f;
                say(t, "Буль-буль");
                buzz(getContext(), 0, 70, 90, 70, 90, 70);
                fizz(t, 8);
            } else {
                t.cola = 0;
                t.level = 1f;
                t.hh = 27 * d;
                t.y -= 20 * d;
                say(t, "Новая банка 🥤");
                buzz(getContext(), 20);
                t.view.invalidate();
            }
        }

        private void fizz(Toy t, int count) {
            for (int i = 0; i < count && pn < PMAX; i++) {
                int k = pn++;
                px[k] = t.x + (rnd.nextFloat() - 0.5f) * 12 * d;
                py[k] = t.y - t.hh - 2 * d;
                pvx[k] = (rnd.nextFloat() - 0.5f) * 60 * d;
                pvy[k] = -(40 + rnd.nextFloat() * 120) * d;
                pr[k] = (1 + rnd.nextFloat() * 2f) * d;
                plife[k] = 1;
                pcol[k] = rnd.nextBoolean() ? 0xFFFFF2D6 : 0xFFE3B98A;
                pfloat[k] = true;
            }
        }

        private float floorAt(float xx) {
            float H = getHeight();
            return H - (pileOn ? pile[idx(xx)] : 0);
        }

        private void updateToys(float dt) {
            float W = getWidth(), G = 1600 * d;
            for (Toy t : toys) {
                t.restOn = null;
                t.grounded = false;
                if (!t.held) {
                    t.vy = Math.min(t.vy + G * dt, 2600 * d);
                    t.x += t.vx * dt;
                    t.y += t.vy * dt;
                }
                if (t.sayT > 0) t.sayT -= dt;
            }
            for (Toy t : toys) {
                if (t.held) continue;
                float s = floorAt(t.x);
                if (t.y + t.hh >= s && t.vy >= 0) {
                    t.y = s - t.hh;
                    t.vy = t.vy > 320 * d ? -t.vy * 0.3f : 0;
                    t.vx *= (float) Math.pow(0.03, dt);
                    t.grounded = true;
                }
            }
            for (int it = 0; it < 2; it++) {
                for (int i = 0; i < toys.size(); i++) {
                    for (int j = i + 1; j < toys.size(); j++) {
                        Toy a = toys.get(i), b = toys.get(j);
                        if (a.type != Toy.BALL || b.type != Toy.BALL) continue;
                        float dx = b.x - a.x, dy = b.y - a.y, dist = (float) Math.hypot(dx, dy), R = a.r + b.r;
                        if (dist >= R || dist < 0.01f) continue;
                        Toy lo = a, hi = b;
                        if (b.y > a.y) { lo = b; hi = a; }
                        boolean above = Math.abs(hi.x - lo.x) < lo.r * 0.8f && hi.y < lo.y;
                        if (above && hi.held) continue;
                        if (above) {
                            hi.y = lo.y - (lo.r + hi.r) * 0.86f;
                            if (hi.vy > 0) hi.vy = 0;
                            hi.x += (lo.x - hi.x) * Math.min(1, dt * 8);
                            hi.vx = lo.vx;
                            hi.restOn = lo;
                            hi.grounded = true;
                        } else {
                            float ov = R - dist, nx = dx / dist, ny = dy / dist;
                            if (a.held) { b.x += nx * ov; b.y += ny * ov; }
                            else if (b.held) { a.x -= nx * ov; a.y -= ny * ov; }
                            else { a.x -= nx * ov / 2; a.y -= ny * ov / 2; b.x += nx * ov / 2; b.y += ny * ov / 2; }
                        }
                    }
                }
            }
            for (Toy t : toys) {
                if (t.x < t.hw) { t.x = t.hw; t.vx = Math.abs(t.vx) * 0.4f; }
                if (t.x > W - t.hw) { t.x = W - t.hw; t.vx = -Math.abs(t.vx) * 0.4f; }
                if (!t.held && t.y < t.hh) { t.y = t.hh; t.vy = Math.abs(t.vy) * 0.3f; }
                if (t.type == Toy.COLA) {
                    if (t.tiltT > 0) {
                        t.tiltT -= dt * 1.1f;
                        if (t.tiltT <= 0) {
                            t.tiltT = 0;
                            if (t.level <= 0.01f) {
                                t.cola = 2;
                                t.hh = 15 * d;
                                say(t, "Ааах! 😋");
                                buzz(getContext(), 120);
                            }
                        }
                        t.view.invalidate();
                    }
                    if (t.cola == 1 && rnd.nextFloat() < dt * 3) fizz(t, 1);
                }
            }
            // снеговики
            int snowmen = 0;
            for (Toy t : toys) { t.view.setTag(t.face * 10 + t.body); t.face = 0; t.body = 0; }
            for (Toy t : toys) {
                if (t.type != Toy.BALL || t.restOn == null) continue;
                boolean supports = false;
                for (Toy o : toys) if (o.restOn == t) { supports = true; break; }
                if (supports) continue;
                int len = 1;
                Toy c = t.restOn;
                while (c != null && len < 6) { len++; c = c.restOn; }
                t.face = len;
                t.restOn.body = 1;
                snowmen++;
            }
            for (Toy t : toys) {
                Object old = t.view.getTag();
                if (!(old instanceof Integer) || (Integer) old != t.face * 10 + t.body) t.view.invalidate();
            }
            if (snowmen > prevSnowmen) {
                for (Toy t : toys) if (t.face > 0) say(t, "Снеговик готов! ☃️");
                buzz(getContext(), 0, 30, 60, 30, 60, 80);
            }
            prevSnowmen = snowmen;
            // двигаем окна
            for (Toy t : toys) {
                int lx = Math.round(t.x + ox - t.winW / 2f), ly = Math.round(t.y + oy - t.winH / 2f);
                if (lx != t.lastX || ly != t.lastY) {
                    t.lastX = lx; t.lastY = ly;
                    t.lp.x = lx; t.lp.y = ly;
                    try { wm.updateViewLayout(t.view, t.lp); } catch (Exception ignored) {}
                }
            }
        }

        // ---------------- экскаваторы ----------------
        void callExcavators() {
            if (!excs.isEmpty() || getWidth() == 0) return;
            int dir = rnd.nextBoolean() ? 1 : -1;
            float W = getWidth();
            for (int i = 0; i < 2; i++) {
                Exc e = new Exc();
                e.dir = dir;
                e.x = dir > 0 ? -90 * d - i * 160 * d : W + 90 * d + i * 160 * d;
                e.t = i * 0.7f;
                excs.add(e);
            }
            excTimer = 0;
            nextExc = 300 + rnd.nextFloat() * 180;
            start();
        }

        private float avgPile() {
            float s = 0;
            for (float v : pile) s += v;
            return pile.length > 0 ? s / pile.length : 0;
        }

        private void updateExcavators(float dt) {
            excTimer += dt;
            // приезжают редко: не чаще раза в 5–8 минут и только когда снега много
            if (excavOn && excs.isEmpty() && excTimer > nextExc && avgPile() > 14 * d) callExcavators();
            float W = getWidth(), H = getHeight();
            for (int i = excs.size() - 1; i >= 0; i--) {
                Exc e = excs.get(i);
                float speed = 70 * d;
                e.x += e.dir * speed * dt;
                e.t += dt;
                e.wheel += speed * dt / (6 * d);
                float front = e.x + e.dir * 80 * d;
                int k0 = idx(Math.min(front - e.dir * 18 * d, front + e.dir * 6 * d));
                int k1 = idx(Math.max(front - e.dir * 18 * d, front + e.dir * 6 * d));
                if (front > -10 * d && front < W + 10 * d) {
                    for (int k = k0; k <= k1; k++) {
                        if (pile[k] > 1.5f * d) { e.load += pile[k] - 1.5f * d; pile[k] = 1.5f * d; }
                    }
                }
                while (e.load > 20 * d) {
                    e.load -= 20 * d;
                    for (int q = 0; q < 5 && pn < PMAX; q++) {
                        int k = pn++;
                        px[k] = front;
                        py[k] = H - 24 * d;
                        pvx[k] = -e.dir * (60 + rnd.nextFloat() * 200) * d;
                        pvy[k] = -(260 + rnd.nextFloat() * 220) * d;
                        pr[k] = (1.4f + rnd.nextFloat() * 2.4f) * d;
                        plife[k] = 1;
                        pcol[k] = 0xFFFFFFFF;
                        pfloat[k] = false;
                    }
                }
                // снеговиков и колу не трогаем: экскаватор проезжает за ними
                if ((e.dir > 0 && e.x - 80 * d > W) || (e.dir < 0 && e.x + 80 * d < 0)) {
                    // уезжает, только если уже проехал свой маршрут
                    boolean started = e.dir > 0 ? e.x > W : e.x < 0;
                    if (started) excs.remove(i);
                }
            }
        }

        private void drawExcavator(Canvas c, Exc e, float H) {
            float s = d;
            float bob = (float) Math.sin(e.t * 3) * 4 * s;
            c.save();
            c.translate(e.x, H - 1 * s);
            if (e.dir < 0) c.scale(-1, 1);
            fillP.setShader(null);
            // гусеницы
            fillP.setColor(0xFF2B2B2E);
            rf.set(-52 * s, -18 * s, 42 * s, 0);
            c.drawRoundRect(rf, 9 * s, 9 * s, fillP);
            fillP.setColor(0xFF6B6F78);
            lineP.setColor(0xFF2B2B2E);
            lineP.setStrokeWidth(1.6f * s);
            for (int k = 0; k < 6; k++) {
                float wx = -42 * s + k * 16.5f * s, wy = -9 * s;
                c.drawCircle(wx, wy, 6 * s, fillP);
                float a = e.wheel + k;
                c.drawLine(wx, wy, wx + (float) Math.cos(a) * 5 * s, wy + (float) Math.sin(a) * 5 * s, lineP);
            }
            // корпус
            fillP.setColor(0xFFD99A00);
            rf.set(-58 * s, -42 * s, -44 * s, -20 * s);
            c.drawRoundRect(rf, 3 * s, 3 * s, fillP);
            fillP.setColor(0xFFFFC21A);
            rf.set(-48 * s, -44 * s, 26 * s, -18 * s);
            c.drawRoundRect(rf, 4 * s, 4 * s, fillP);
            fillP.setColor(0xFF1E1E22);
            c.drawRect(-40 * s, -38 * s, -18 * s, -35 * s, fillP);
            c.drawRect(-40 * s, -32 * s, -18 * s, -29 * s, fillP);
            // кабина
            fillP.setColor(0xFFFFC21A);
            rf.set(-6 * s, -76 * s, 24 * s, -42 * s);
            c.drawRoundRect(rf, 4 * s, 4 * s, fillP);
            fillP.setColor(0xFF9FD8FF);
            rf.set(0, -71 * s, 19 * s, -52 * s);
            c.drawRoundRect(rf, 2 * s, 2 * s, fillP);
            fillP.setColor(0x88FFFFFF);
            c.drawRect(3 * s, -69 * s, 7 * s, -55 * s, fillP);
            // мигалка
            boolean blink = Math.sin(e.t * 9) > 0;
            fillP.setColor(blink ? 0xFFFF7A00 : 0xFF8A3C00);
            if (blink) {
                fillP.setColor(0x55FF9A2E);
                c.drawCircle(9 * s, -80 * s, 9 * s, fillP);
                fillP.setColor(0xFFFF7A00);
            }
            c.drawCircle(9 * s, -80 * s, 3.5f * s, fillP);
            // стрела и рукоять
            float ex = 56 * s, ey = -64 * s + bob;
            float bx = 74 * s, by = -16 * s + bob * 0.4f;
            lineP.setColor(0xFFE6A800);
            lineP.setStrokeWidth(9 * s);
            c.drawLine(22 * s, -40 * s, ex, ey, lineP);
            lineP.setColor(0xFFFFC21A);
            lineP.setStrokeWidth(7 * s);
            c.drawLine(ex, ey, bx, by, lineP);
            fillP.setColor(0xFF555A62);
            c.drawCircle(ex, ey, 3.5f * s, fillP);
            c.drawCircle(22 * s, -40 * s, 3.5f * s, fillP);
            // ковш
            path.reset();
            path.moveTo(bx - 8 * s, by - 6 * s);
            path.lineTo(bx + 12 * s, by - 4 * s);
            path.lineTo(bx + 10 * s, by + 15 * s);
            path.lineTo(bx - 6 * s, by + 15 * s);
            path.close();
            fillP.setColor(0xFF4A4E56);
            c.drawPath(path, fillP);
            fillP.setColor(0xFF2E3138);
            for (int k = 0; k < 3; k++) {
                float tx = bx - 5 * s + k * 5.5f * s;
                c.drawRect(tx, by + 14 * s, tx + 3 * s, by + 18 * s, fillP);
            }
            // снег в ковше
            fillP.setColor(0xFFFFFFFF);
            c.drawCircle(bx - 1 * s, by - 6 * s, 6 * s, fillP);
            c.drawCircle(bx + 6 * s, by - 5 * s, 5 * s, fillP);
            c.restore();
        }

        // ---------------- снег ----------------
        private void spawn(int i, boolean top) {
            float W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
            boolean f = rnd.nextFloat() < 0.55f;
            front[i] = f;
            x[i] = rnd.nextFloat() * W;
            y[i] = top ? -10 * d - rnd.nextFloat() * 60 * d : rnd.nextFloat() * H;
            r[i] = (f ? 1.6f + rnd.nextFloat() * 2f : 0.8f + rnd.nextFloat()) * d;
            vy[i] = (f ? 55 + rnd.nextFloat() * 50 : 28 + rnd.nextFloat() * 25) * d;
            ph[i] = rnd.nextFloat() * 6.28f;
        }

        void onAccel(float ax, float ay, float az) {
            tilt = -ax * 9 * d;
            if (hasAcc) {
                float dx = ax - lax, dy = ay - lay, dz = az - laz;
                float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > 9 || Math.abs(mag - 9.81f) > 7) shake();
            }
            lax = ax; lay = ay; laz = az;
            hasAcc = true;
        }

        void shake() {
            long now = System.currentTimeMillis();
            if (now - lastShake < 700) return;
            lastShake = now;
            shakeKick = (rnd.nextBoolean() ? 1 : -1) * 420 * d;
            buzz(getContext(), 40);
            float H = getHeight();
            for (int k = 0; k < pile.length; k += 2) {
                if (pile[k] > 2 * d && pn < PMAX) {
                    int i = pn++;
                    px[i] = k * col;
                    py[i] = H - pile[k];
                    pvx[i] = (rnd.nextFloat() - 0.5f) * 220 * d;
                    pvy[i] = -(120 + rnd.nextFloat() * 260) * d;
                    pr[i] = (1 + rnd.nextFloat() * 2.2f) * d;
                    plife[i] = 1;
                    pcol[i] = 0xFFFFFFFF;
                    pfloat[i] = false;
                }
            }
            for (int k = 0; k < pile.length; k++) pile[k] *= 0.25f;
            for (Toy t : toys) {
                if (t.held) continue;
                t.vy -= (250 + rnd.nextFloat() * 250) * d;
                t.vx += (rnd.nextFloat() - 0.5f) * 300 * d;
            }
            start();
        }

        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = last == 0 ? 0.016f : Math.min(0.033f, (now - last) / 1e9f);
            last = now;
            if (++frameNo % 90 == 0) updateOffset();
            update(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void update(float dt) {
            T += dt;
            float W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            float gust = level == 2 ? (45 + (float) Math.sin(T * 0.7) * 35) * d : 0;
            shakeKick *= (float) Math.pow(0.08, dt);
            float wind = (float) Math.sin(T * 0.3) * 12 * d + tilt + gust + shakeKick;
            float spd = level == 2 ? 1.35f : 1f;
            int target = snowOn ? LEVELS[level] : 0;

            for (int k = 0; k < 8 && n < target && n < MAX; k++) { spawn(n, true); n++; }

            int i = 0;
            while (i < n) {
                ph[i] += dt * 1.5f;
                x[i] += ((float) Math.sin(ph[i]) * 14 * d + wind * (front[i] ? 1f : 0.6f)) * dt;
                float prevY = y[i];
                y[i] += vy[i] * dt * spd;
                if (x[i] < -6 * d) x[i] += W + 12 * d;
                else if (x[i] > W + 6 * d) x[i] -= W + 12 * d;
                boolean dead = false;
                if (front[i] && pileOn) {
                    int k = idx(x[i]);
                    float s = H - pile[k];
                    if (y[i] >= s && prevY <= s + 4 * d) { deposit(k, r[i]); dead = true; }
                    else if (y[i] > H + 10 * d) dead = true;
                } else if (y[i] > H + 10 * d) dead = true;
                if (dead) {
                    if (n > target) {
                        n--;
                        x[i] = x[n]; y[i] = y[n]; r[i] = r[n]; vy[i] = vy[n]; ph[i] = ph[n]; front[i] = front[n];
                        continue;
                    }
                    spawn(i, true);
                }
                i++;
            }

            if (pileOn) settle(dt);
            updateExcavators(dt);
            updateToys(dt);

            int j = 0;
            while (j < pn) {
                pvy[j] += (pfloat[j] ? -60 : 700) * d * dt;
                px[j] += pvx[j] * dt;
                py[j] += pvy[j] * dt;
                plife[j] -= dt * (pfloat[j] ? 1.4f : 1.1f);
                if (plife[j] <= 0 || py[j] > H + 10 * d) {
                    pn--;
                    px[j] = px[pn]; py[j] = py[pn]; pvx[j] = pvx[pn]; pvy[j] = pvy[pn];
                    plife[j] = plife[pn]; pr[j] = pr[pn]; pcol[j] = pcol[pn]; pfloat[j] = pfloat[pn];
                    continue;
                }
                j++;
            }
        }

        private int idx(float xx) {
            int k = (int) (xx / col);
            return Math.max(0, Math.min(pile.length - 1, k));
        }

        private void deposit(int k, float rr) {
            float rd = rr / d;
            float amt = rd * rd * 0.3f * d;
            pile[k] += amt * 0.5f;
            if (k > 0) pile[k - 1] += amt * 0.25f;
            if (k < pile.length - 1) pile[k + 1] += amt * 0.25f;
        }

        private void settle(float dt) {
            float th = 1.4f * d;
            int m = pile.length;
            for (int k = 0; k < m - 1; k++) {
                float df = pile[k] - pile[k + 1];
                if (df > th) { float q = (df - th) * 0.3f; pile[k] -= q; pile[k + 1] += q; }
                else if (df < -th) { float q = (-df - th) * 0.3f; pile[k] += q; pile[k + 1] -= q; }
            }
            for (int k = 0; k < m; k++) {
                if (pile[k] > cap) pile[k] = cap;
                if (pile[k] > 0) pile[k] = Math.max(0, pile[k] - dt * 0.25f * d);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            for (int i = 0; i < n; i++) if (!front[i]) c.drawCircle(x[i], y[i], r[i], backP);
            if (garland) drawGarland(c, W);
            if (pileOn) {
                path.reset();
                path.moveTo(0, H + 2);
                for (int k = 0; k < pile.length; k++) path.lineTo(Math.min(k * col, W), H - pile[k]);
                path.lineTo(W, H + 2);
                path.close();
                c.drawPath(path, pileP);
            }
            for (Exc e : excs) drawExcavator(c, e, H);
            // руки и шапки снеговиков торчат за пределы окошек шариков, поэтому дорисовываем их здесь
            for (Toy t : toys) if (t.type == Toy.BALL && (t.face > 0 || t.body > 0)) drawBall(c, t, t.x, t.y, fillP, path);
            fillP.setShader(null);
            for (int i = 0; i < n; i++) if (front[i]) c.drawCircle(x[i], y[i], r[i], flakeP);
            for (int j = 0; j < pn; j++) {
                fillP.setColor(pcol[j]);
                fillP.setAlpha((int) (255 * Math.max(0, Math.min(1, plife[j]))));
                c.drawCircle(px[j], py[j], pr[j], fillP);
            }
            fillP.setAlpha(255);
            for (Toy t : toys) {
                if (t.sayT <= 0 || t.say == null) continue;
                float a = Math.min(1, t.sayT / 0.4f);
                float ty = t.y - t.hh - 22 * d - (1.8f - t.sayT) * 12 * d;
                float tw = textP.measureText(t.say) + 20 * d;
                rf.set(t.x - tw / 2, ty - 18 * d, t.x + tw / 2, ty + 8 * d);
                fillP.setColor(0xFFFFFFFF);
                fillP.setAlpha((int) (235 * a));
                c.drawRoundRect(rf, 13 * d, 13 * d, fillP);
                textP.setColor(0xFF1C1E3A);
                textP.setAlpha((int) (255 * a));
                c.drawText(t.say, t.x, ty, textP);
            }
            fillP.setAlpha(255);
        }

        private void drawGarland(Canvas c, float W) {
            // гирлянда висит в строке состояния и не закрывает приложения
            float y0 = Math.max(1 * d, statusH * 0.1f), sag = Math.min(14 * d, statusH * 0.55f);
            int segs = Math.max(3, Math.round(W / (80 * d)));
            float sw = W / segs;
            path.reset();
            for (int s = 0; s < segs; s++) {
                float a = s * sw;
                path.moveTo(a, y0);
                path.quadTo(a + sw / 2, y0 + sag, a + sw, y0);
            }
            c.drawPath(path, wireP);
            int idx = 0;
            fillP.setShader(null);
            for (int s = 0; s < segs; s++) {
                for (int k = 1; k <= 3; k++) {
                    float t = k / 4f;
                    float bx = s * sw + sw * t;
                    float by = y0 + 2 * sag * t * (1 - t) + 3.5f * d;
                    idx++;
                    boolean on = Math.sin(T * 2.6 + idx * 1.3) > -0.3;
                    int color = BULBS[idx % BULBS.length];
                    if (on) {
                        fillP.setColor((color & 0x00FFFFFF) | 0x40000000);
                        c.drawCircle(bx, by, 6.5f * d, fillP);
                        fillP.setColor(color);
                    } else {
                        fillP.setColor(0xB3787890);
                    }
                    c.drawOval(bx - 2.2f * d, by - 3f * d, bx + 2.2f * d, by + 3f * d, fillP);
                    fillP.setColor(0xFF2C3A30);
                    c.drawRect(bx - 1.2f * d, by - 4.8f * d, bx + 1.2f * d, by - 2.8f * d, fillP);
                }
            }
        }
    }

    // =====================================================================
    // Сервис: держит все окна поверх экрана
    // =====================================================================
    public static class SnowService extends Service implements SensorEventListener {
        static final String ACTION_STOP = "ru.opsel.snezhok.STOP";
        static final String ACTION_UPDATE = "ru.opsel.snezhok.UPDATE";
        static final String ACTION_SHAKE = "ru.opsel.snezhok.SHAKE";
        static final String ACTION_EXCAV = "ru.opsel.snezhok.EXCAV";
        private static final String CHANNEL = "snow";

        static volatile boolean running = false;

        private WindowManager wm;
        private SnowView view;
        private SensorManager sm;
        private BroadcastReceiver screenRx;

        @Override
        public void onCreate() {
            super.onCreate();
            startFg();
            if (!Settings.canDrawOverlays(this)) {
                Prefs.setOn(this, false);
                stopSelf();
                return;
            }
            running = true;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            view = new SnowView(this, wm);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            if (Build.VERSION.SDK_INT >= 31) {
                // На Android 12+ касания проходят сквозь слой, только если его прозрачность не выше 0.8
                lp.alpha = 0.8f;
            }
            try {
                wm.addView(view, lp);
            } catch (Exception e) {
                // окно не дали создать (например, разрешение отозвали прямо сейчас)
                view = null;
                Prefs.setOn(this, false);
                stopSelf();
                return;
            }
            applyPrefs();
            view.start();

            sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            listenSensor(true);

            screenRx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (view == null) return;
                    // при выключенном экране не рисуем и не опрашиваем датчик, чтобы беречь батарею
                    boolean off = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
                    if (off) view.stop(); else view.start();
                    listenSensor(!off);
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenRx, f);

            scheduleWatchdog(this);
            SnowTile.refresh(this);
        }

        private boolean sensorOn = false;

        private void listenSensor(boolean on) {
            if (sm == null || on == sensorOn) return;
            if (on) {
                Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (acc != null) sensorOn = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
            } else {
                sm.unregisterListener(this);
                sensorOn = false;
            }
        }

        /** Приложение смахнули из недавних: через секунду запускаемся снова. */
        @Override
        public void onTaskRemoved(Intent rootIntent) {
            if (Prefs.on(this)) {
                try {
                    PendingIntent pi = PendingIntent.getForegroundService(this, 11,
                            new Intent(this, SnowService.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1200, pi);
                } catch (Exception ignored) {}
            }
            super.onTaskRemoved(rootIntent);
        }

        private void applyPrefs() {
            if (view != null) view.setPrefs(Prefs.level(this), Prefs.snow(this), Prefs.getInt(this, "bright", 0),
                    Prefs.garland(this), Prefs.pile(this),
                    Prefs.excav(this), Prefs.balls(this), Prefs.cola(this));
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String a = intent != null ? intent.getAction() : null;
            if (ACTION_STOP.equals(a)) {
                Prefs.setOn(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (view != null) view.start();
            if (ACTION_UPDATE.equals(a)) applyPrefs();
            if (ACTION_SHAKE.equals(a) && view != null) view.shake();
            if (ACTION_EXCAV.equals(a) && view != null) view.callExcavators();
            return START_STICKY;
        }

        private PendingIntent action(String act, int code) {
            return PendingIntent.getService(this, code, new Intent(this, SnowService.class).setAction(act),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }

        private void startFg() {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Снег на экране", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_snow)
                    .setContentTitle("Идёт снег ❄️")
                    .setContentText("Стряхнуть, вызвать экскаваторы или выключить")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "Стряхнуть", action(ACTION_SHAKE, 2)).build())
                    .addAction(new Notification.Action.Builder(null, "🚜 Экскаватор", action(ACTION_EXCAV, 3)).build())
                    .addAction(new Notification.Action.Builder(null, "Выключить", action(ACTION_STOP, 1)).build())
                    .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (view != null) view.onAccel(e.values[0], e.values[1], e.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {}

        @Override
        public void onDestroy() {
            running = false;
            listenSensor(false);
            if (screenRx != null) {
                try { unregisterReceiver(screenRx); } catch (Exception ignored) {}
            }
            if (view != null) {
                view.stop();
                view.removeAllToys();
                try { wm.removeView(view); } catch (Exception ignored) {}
                view = null;
            }
            SnowTile.refresh(this);
            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }
    }

    // =====================================================================
    // Плитка «Снег» в шторке
    // =====================================================================
    public static class SnowTile extends TileService {
        static void refresh(Context c) {
            try { TileService.requestListeningState(c, new ComponentName(c, SnowTile.class)); } catch (Exception ignored) {}
        }

        @Override
        public void onStartListening() { update(SnowService.running); }

        @Override
        public void onClick() {
            boolean turnOn = !SnowService.running;
            if (turnOn) {
                if (!Settings.canDrawOverlays(this)) { openApp(); return; }
                Prefs.setOn(this, true);
                try {
                    startForegroundService(new Intent(this, SnowService.class));
                } catch (Exception e) {
                    openApp();
                    return;
                }
            } else {
                Prefs.setOn(this, false);
                stopService(new Intent(this, SnowService.class));
            }
            update(turnOn);
        }

        private void update(boolean on) {
            Tile t = getQsTile();
            if (t == null) return;
            t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            t.setLabel("Снег");
            t.updateTile();
        }

        @SuppressWarnings("deprecation")
        private void openApp() {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(i);
        }
    }

    // =====================================================================
    // Экран приложения
    // =====================================================================
    public static class MainActivity extends Activity {
        private float d;
        private Button big;
        private TextView status;
        private final Button[] lv = new Button[3];
        private final Button[] br = new Button[3];
        private boolean pendingStart = false;

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            d = getResources().getDisplayMetrics().density;

            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF141A52, 0xFF3A3196, 0xFF8A50C2, 0xFFFF7FA6}));

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(24), dp(48), dp(24), dp(32));
            sv.addView(root);

            root.addView(text("Снежок ❄️", 34, true));
            TextView sub = text("Снег прямо на главном экране телефона", 15, false);
            sub.setAlpha(0.8f);
            root.addView(sub, lp(0, dp(6), 0, dp(32)));

            big = new Button(this);
            big.setAllCaps(false);
            big.setTextColor(Color.WHITE);
            big.setTextSize(22);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            big.setStateListAnimator(null);
            big.setOnClickListener(v -> toggle());
            root.addView(big, new LinearLayout.LayoutParams(dp(220), dp(220)));

            status = text("", 15, false);
            root.addView(status, lp(0, dp(18), 0, dp(26)));

            root.addView(text("Сила снегопада", 15, true), lp(0, 0, 0, dp(10)));
            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setBackground(round(0x26FFFFFF, dp(14)));
            seg.setPadding(dp(4), dp(4), dp(4), dp(4));
            String[] names = {"Лёгкий", "Снегопад", "Метель"};
            for (int i = 0; i < 3; i++) {
                final int v = i;
                Button bt = new Button(this);
                bt.setText(names[i]);
                bt.setAllCaps(false);
                bt.setTextColor(Color.WHITE);
                bt.setStateListAnimator(null);
                bt.setOnClickListener(x -> { Prefs.setLevel(this, v); refresh(); pushUpdate(); });
                lv[i] = bt;
                seg.addView(bt, new LinearLayout.LayoutParams(0, dp(46), 1));
            }
            root.addView(seg, lp(0, 0, 0, 0));

            root.addView(text("Яркость снега и гирлянды", 15, true), lp(0, dp(16), 0, dp(10)));
            LinearLayout seg2 = new LinearLayout(this);
            seg2.setOrientation(LinearLayout.HORIZONTAL);
            seg2.setBackground(round(0x26FFFFFF, dp(14)));
            seg2.setPadding(dp(4), dp(4), dp(4), dp(4));
            String[] bn = {"Ярко", "Средне", "Еле-еле"};
            for (int i = 0; i < 3; i++) {
                final int v = i;
                Button bt = new Button(this);
                bt.setText(bn[i]);
                bt.setAllCaps(false);
                bt.setTextColor(Color.WHITE);
                bt.setStateListAnimator(null);
                bt.setOnClickListener(x -> { Prefs.putInt(this, "bright", v); refresh(); pushUpdate(); });
                br[i] = bt;
                seg2.addView(bt, new LinearLayout.LayoutParams(0, dp(46), 1));
            }
            root.addView(seg2, lp(0, 0, 0, 0));

            root.addView(text("Что показывать на экране", 15, true), lp(0, dp(20), 0, 0));
            root.addView(sw("❄️ Падающий снег", "snow", true), lp(0, dp(10), 0, 0));

            root.addView(sw("🎄 Гирлянда в строке состояния", "garland", true), lp(0, dp(6), 0, 0));
            root.addView(sw("Сугроб внизу экрана", "pile", true), lp(0, dp(6), 0, 0));
            root.addView(sw("🚜 Экскаваторы убирают снег", "excav", true), lp(0, dp(6), 0, 0));
            root.addView(sw("☃️ Снежки для снеговика", "balls", true), lp(0, dp(6), 0, 0));
            root.addView(sw("🥤 Банка колы", "cola", true), lp(0, dp(6), 0, 0));

            Button exc = new Button(this);
            exc.setText("🚜 Вызвать экскаваторы");
            exc.setAllCaps(false);
            exc.setTextColor(Color.WHITE);
            exc.setTextSize(15);
            exc.setStateListAnimator(null);
            exc.setBackground(round(0xFFE0A100, dp(14)));
            exc.setOnClickListener(v -> {
                if (SnowService.running) {
                    startService(new Intent(this, SnowService.class).setAction(SnowService.ACTION_EXCAV));
                    moveTaskToBack(true);
                } else {
                    Toast.makeText(this, "Сначала включи снег", Toast.LENGTH_SHORT).show();
                }
            });
            root.addView(exc, lp(0, dp(14), 0, 0));

            Button bat = new Button(this);
            bat.setText("🔋 Чтобы снег не пропадал");
            bat.setAllCaps(false);
            bat.setTextColor(Color.WHITE);
            bat.setTextSize(15);
            bat.setStateListAnimator(null);
            bat.setBackground(round(0x33FFFFFF, dp(14)));
            bat.setOnClickListener(v -> keepAliveSettings(true));
            root.addView(bat, lp(0, dp(10), 0, 0));

            TextView tip = text("💡 Снежки: перетаскивай пальцем, ставь друг на друга, получится снеговик. Тап, и снежок подпрыгнет.\n\n"
                    + "🥤 Кола: тап откроет банку, следующие тапы — глотки. Пустую банку тапни ещё раз, будет новая.\n\n"
                    + "🚜 Экскаваторы приезжают сами, когда навалит снега, или по кнопке в шторке.\n\n"
                    + "Опусти шторку, нажми ✏️ и добавь плитку «Снег», чтобы включать и выключать одним касанием. "
                    + "Снег, сугроб, гирлянда и экскаваторы не мешают нажимать, касания проходят сквозь них. "
                    + "Снежки и кола ловят касания, чтобы их можно было трогать. Если мешают, отодвинь их или выключи переключателем.", 13, false);
            tip.setGravity(Gravity.START);
            tip.setAlpha(0.85f);
            root.addView(tip, lp(0, dp(22), 0, 0));

            setContentView(sv);

            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (pendingStart && Settings.canDrawOverlays(this)) startSnow();
            pendingStart = false;
            refresh();
        }

        private void toggle() {
            if (SnowService.running) {
                Prefs.setOn(this, false);
                stopService(new Intent(this, SnowService.class));
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    pendingStart = true;
                    Toast.makeText(this, "Включи «Поверх других приложений» для Снежка и вернись назад", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    return;
                }
                startSnow();
            }
            big.postDelayed(this::refresh, 250);
        }

        private void startSnow() {
            Prefs.setOn(this, true);
            startForegroundService(new Intent(this, SnowService.class));
            big.postDelayed(this::refresh, 250);
            keepAliveSettings(false);
        }

        /** Просим систему не экономить батарею на Снежке, иначе она может его выключать. */
        private void keepAliveSettings(boolean fromButton) {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                    return;
                }
            } catch (Exception ignored) {}
            if (!fromButton) return;
            // Xiaomi / Redmi / POCO: отдельный «Автозапуск»
            try {
                Intent mi = new Intent();
                mi.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                startActivity(mi);
                Toast.makeText(this, "Включи автозапуск для Снежка", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {}
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                Toast.makeText(this, "Батарея → «Без ограничений», и включи автозапуск, если есть", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        }

        private void pushUpdate() {
            if (SnowService.running) startService(new Intent(this, SnowService.class).setAction(SnowService.ACTION_UPDATE));
        }

        private void refresh() {
            boolean on = SnowService.running;
            big.setText(on ? "❄️\nВЫКЛЮЧИТЬ\nснег" : "❄️\nВКЛЮЧИТЬ\nснег");
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    on ? new int[]{0xFFFF7A59, 0xFFFF4F8B} : new int[]{0xFF4FC3FF, 0xFF7A5CFF});
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(dp(3), 0x66FFFFFF);
            big.setBackground(g);
            big.setElevation(dp(10));
            status.setText(on ? "Снег идёт на экране ❄️" : "Снег выключен");
            mark(lv, Prefs.level(this));
            mark(br, Prefs.getInt(this, "bright", 0));
        }

        private void mark(Button[] arr, int sel) {
            for (int i = 0; i < arr.length; i++) {
                if (i == sel) {
                    GradientDrawable a = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4FC3FF, 0xFF7A5CFF});
                    a.setCornerRadius(dp(11));
                    arr[i].setBackground(a);
                } else {
                    arr[i].setBackground(round(0x00000000, dp(11)));
                }
            }
        }

        private View sw(String label, String key, boolean def) {
            Switch s = new Switch(this);
            s.setText(label);
            s.setTextColor(Color.WHITE);
            s.setTextSize(15);
            s.setChecked(Prefs.get(this, key, def));
            s.setPadding(dp(16), dp(12), dp(16), dp(12));
            s.setBackground(round(0x1FFFFFFF, dp(14)));
            s.setOnCheckedChangeListener((btn, v) -> { Prefs.put(this, key, v); pushUpdate(); });
            return s;
        }

        private TextView text(String s, float size, boolean bold) {
            TextView t = new TextView(this);
            t.setText(s);
            t.setTextColor(Color.WHITE);
            t.setTextSize(size);
            t.setGravity(Gravity.CENTER);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        private GradientDrawable round(int color, int radius) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(radius);
            return g;
        }

        private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMargins(l, t, r, b);
            return p;
        }

        private int dp(float v) { return Math.round(v * d); }
    }
}
