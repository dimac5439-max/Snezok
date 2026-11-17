package ru.opsel.snezhok;

import android.Manifest;
import android.app.Activity;
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
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Random;

/** Снежок: снег поверх главного экрана. Все части приложения в одном файле. */
public final class App {
    private App() {}

    /** Настройки приложения. */
    static final class Prefs {


        private static SharedPreferences sp(Context c) {
            return c.getSharedPreferences("snow", Context.MODE_PRIVATE);
        }

        static boolean on(Context c) { return sp(c).getBoolean("on", false); }
        static void setOn(Context c, boolean v) { sp(c).edit().putBoolean("on", v).apply(); }

        /** 0 = лёгкий, 1 = снегопад, 2 = метель */
        static int level(Context c) { return sp(c).getInt("level", 1); }
        static void setLevel(Context c, int v) { sp(c).edit().putInt("level", v).apply(); }

        static boolean garland(Context c) { return sp(c).getBoolean("garland", true); }
        static void setGarland(Context c, boolean v) { sp(c).edit().putBoolean("garland", v).apply(); }

        static boolean pile(Context c) { return sp(c).getBoolean("pile", true); }
        static void setPile(Context c, boolean v) { sp(c).edit().putBoolean("pile", v).apply(); }
    }

    /**
     * Прозрачный слой со снегом: снежинки, сугроб внизу экрана, гирлянда сверху.
     * Нажатия проходят сквозь него (это настраивает SnowService).
     */
    public static class SnowView extends View implements Choreographer.FrameCallback {

        private static final int MAX = 520;
        private static final int[] LEVELS = {110, 260, 480};
        private static final int[] BULBS = {0xFFFF4D5E, 0xFFFFD84D, 0xFF4DFF8B, 0xFF4DBCFF, 0xFFFF6BF0};

        private final float d;
        private final Random rnd = new Random();

        // снежинки
        private final float[] x = new float[MAX], y = new float[MAX], r = new float[MAX], vy = new float[MAX], ph = new float[MAX];
        private final boolean[] front = new boolean[MAX];
        private int n = 0;

        // сугроб
        private float[] pile = new float[2];
        private final float col, cap;

        // частицы (снег, слетающий при тряске)
        private static final int PMAX = 400;
        private final float[] px = new float[PMAX], py = new float[PMAX], pvx = new float[PMAX], pvy = new float[PMAX], plife = new float[PMAX], pr = new float[PMAX];
        private int pn = 0;

        private final Paint flakeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pileP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pileEdgeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wireP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bulbP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint partP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private int level = 1;
        private boolean garland = true, pileOn = true;
        private float tilt = 0, T = 0;
        private long last = 0;
        private boolean running = false;
        private final int statusH;

        // тряска
        private float lax, lay, laz;
        private boolean hasAcc = false;
        private long lastShake = 0;

        public SnowView(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            col = 3 * d;
            cap = 34 * d;
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            statusH = id > 0 ? getResources().getDimensionPixelSize(id) : (int) (24 * d);

            flakeP.setColor(0xF2FFFFFF);
            backP.setColor(0x8CFFFFFF);
            partP.setColor(0xFFFFFFFF);
            pileEdgeP.setStyle(Paint.Style.STROKE);
            pileEdgeP.setStrokeWidth(1.2f * d);
            pileEdgeP.setColor(0x66B4BEFF);
            wireP.setStyle(Paint.Style.STROKE);
            wireP.setStrokeWidth(1.6f * d);
            wireP.setColor(0xFF1D3B2A);
        }

        void setPrefs(int level, boolean garland, boolean pileOn) {
            this.level = Math.max(0, Math.min(2, level));
            this.garland = garland;
            this.pileOn = pileOn;
            if (!pileOn) java.util.Arrays.fill(pile, 0);
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
            if (n == 0) {
                int target = LEVELS[level];
                for (int i = 0; i < target; i++) spawn(i, false);
                n = target;
            }
        }

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

        /** Данные акселерометра: наклон даёт ветер, резкий рывок стряхивает снег. */
        void onAccel(float ax, float ay, float az) {
            tilt = -ax * 9 * d;
            if (hasAcc) {
                float dx = ax - lax, dy = ay - lay, dz = az - laz;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > 14) shake();
            }
            lax = ax; lay = ay; laz = az;
            hasAcc = true;
        }

        private void shake() {
            long now = System.currentTimeMillis();
            if (now - lastShake < 800) return;
            lastShake = now;
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
                }
            }
            for (int k = 0; k < pile.length; k++) pile[k] *= 0.25f;
        }

        @Override
        public void doFrame(long now) {
            if (!running) return;
            float dt = last == 0 ? 0.016f : Math.min(0.033f, (now - last) / 1e9f);
            last = now;
            update(dt);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void update(float dt) {
            T += dt;
            float W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            float gust = level == 2 ? (45 + (float) Math.sin(T * 0.7) * 35) * d : 0;
            float wind = (float) Math.sin(T * 0.3) * 12 * d + tilt + gust;
            float spd = level == 2 ? 1.35f : 1f;
            int target = LEVELS[level];

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

            int j = 0;
            while (j < pn) {
                pvy[j] += 700 * d * dt;
                px[j] += pvx[j] * dt;
                py[j] += pvy[j] * dt;
                plife[j] -= dt * 1.1f;
                if (plife[j] <= 0 || py[j] > H + 10 * d) {
                    pn--;
                    px[j] = px[pn]; py[j] = py[pn]; pvx[j] = pvx[pn]; pvy[j] = pvy[pn]; plife[j] = plife[pn]; pr[j] = pr[pn];
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
                // медленно подтаивает, чтобы сугроб не стоял стеной
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

            for (int i = 0; i < n; i++) if (front[i]) c.drawCircle(x[i], y[i], r[i], flakeP);

            for (int j = 0; j < pn; j++) {
                partP.setAlpha((int) (255 * Math.max(0, Math.min(1, plife[j]))));
                c.drawCircle(px[j], py[j], pr[j], partP);
            }
        }

        private void drawGarland(Canvas c, float W) {
            float y0 = statusH + 2 * d, sag = 22 * d;
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
            for (int s = 0; s < segs; s++) {
                for (int k = 1; k <= 3; k++) {
                    float t = k / 4f;
                    float bx = s * sw + sw * t;
                    float by = y0 + 2 * sag * t * (1 - t) + 5 * d;
                    idx++;
                    boolean on = Math.sin(T * 2.6 + idx * 1.3) > -0.3;
                    int color = BULBS[idx % BULBS.length];
                    if (on) {
                        bulbP.setColor((color & 0x00FFFFFF) | 0x40000000);
                        c.drawCircle(bx, by, 9 * d, bulbP);
                        bulbP.setColor(color);
                    } else {
                        bulbP.setColor(0xB3787890);
                    }
                    c.drawOval(bx - 3 * d, by - 4.2f * d, bx + 3 * d, by + 4.2f * d, bulbP);
                    bulbP.setColor(0xFF2C3A30);
                    c.drawRect(bx - 1.6f * d, by - 6.5f * d, bx + 1.6f * d, by - 3.8f * d, bulbP);
                }
            }
        }
    }

    /** Держит слой со снегом поверх экрана (рабочий стол, приложения). */
    public static class SnowService extends Service implements SensorEventListener {

        static final String ACTION_STOP = "ru.opsel.snezhok.STOP";
        static final String ACTION_UPDATE = "ru.opsel.snezhok.UPDATE";
        private static final String CHANNEL = "snow";

        static volatile boolean running = false;

        private WindowManager wm;
        private SnowView view;
        private SensorManager sm;
        private BroadcastReceiver screenRx;

        @Override
        public void onCreate() {
            super.onCreate();
            startFg(); // обязательно первым делом, иначе Android закроет приложение

            if (!Settings.canDrawOverlays(this)) {
                Prefs.setOn(this, false);
                stopSelf();
                return;
            }

            running = true;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            view = new SnowView(this);
            applyPrefs();

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
                // На Android 12+ нажатия проходят сквозь слой, только если его прозрачность не выше 0.8
                lp.alpha = 0.8f;
            }
            wm.addView(view, lp);
            view.start();

            sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            Sensor acc = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
            if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);

            // Экран выключен: не рисуем, бережём батарею
            screenRx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (view == null) return;
                    if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) view.stop();
                    else view.start();
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenRx, f);

            SnowTile.refresh(this);
        }

        private void applyPrefs() {
            if (view != null) view.setPrefs(Prefs.level(this), Prefs.garland(this), Prefs.pile(this));
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            String a = intent != null ? intent.getAction() : null;
            if (ACTION_STOP.equals(a)) {
                Prefs.setOn(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_UPDATE.equals(a)) applyPrefs();
            return START_STICKY;
        }

        private void startFg() {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Снег на экране", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);

            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, SnowService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_snow)
                    .setContentTitle("Идёт снег ❄️")
                    .setContentText("Нажми «Выключить», чтобы убрать снег")
                    .setContentIntent(open)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "Выключить", stop).build())
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
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
            if (sm != null) sm.unregisterListener(this);
            if (screenRx != null) {
                try { unregisterReceiver(screenRx); } catch (Exception ignored) {}
            }
            if (view != null) {
                view.stop();
                try { wm.removeView(view); } catch (Exception ignored) {}
                view = null;
            }
            SnowTile.refresh(this);
            super.onDestroy();
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }
    }

    /** Плитка «Снег» в шторке: включить или выключить в одно касание. */
    public static class SnowTile extends TileService {

        static void refresh(Context c) {
            try {
                TileService.requestListeningState(c, new ComponentName(c, SnowTile.class));
            } catch (Exception ignored) {}
        }

        @Override
        public void onStartListening() {
            update(SnowService.running);
        }

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
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(i);
            }
        }
    }

    /** Главный экран: одна большая кнопка «включить / выключить снег» и пара настроек. */
    public static class MainActivity extends Activity {

        private float d;
        private Button big;
        private TextView status;
        private final Button[] lv = new Button[3];
        private boolean pendingStart = false;

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            d = getResources().getDisplayMetrics().density;

            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF141A52, 0xFF3A3196, 0xFF8A50C2, 0xFFFF7FA6});
            sv.setBackground(bg);

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = dp(24);
            root.setPadding(p, dp(48), p, dp(32));
            sv.addView(root);

            TextView title = text("Снежок ❄️", 34, true);
            root.addView(title);
            TextView sub = text("Снег прямо на главном экране телефона", 15, false);
            sub.setAlpha(0.8f);
            root.addView(sub, lp(0, dp(6), 0, dp(36)));

            big = new Button(this);
            big.setAllCaps(false);
            big.setTextColor(Color.WHITE);
            big.setTextSize(22);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            big.setStateListAnimator(null);
            big.setOnClickListener(v -> toggle());
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(230), dp(230));
            root.addView(big, bl);

            status = text("", 15, false);
            root.addView(status, lp(0, dp(20), 0, dp(30)));

            TextView lvlTitle = text("Сила снегопада", 15, true);
            root.addView(lvlTitle, lp(0, 0, 0, dp(10)));
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
            root.addView(seg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            root.addView(sw("Гирлянда сверху экрана", Prefs.garland(this), v -> { Prefs.setGarland(this, v); pushUpdate(); }), lp(0, dp(18), 0, 0));
            root.addView(sw("Сугроб внизу экрана", Prefs.pile(this), v -> { Prefs.setPile(this, v); pushUpdate(); }), lp(0, dp(6), 0, 0));

            TextView tip = text("💡 Опусти шторку, нажми ✏️ и перетащи плитку «Снег»: так снег включается и выключается одним касанием с любого экрана.\n\n"
                    + "Потряси телефон, и сугроб слетит. Наклони телефон, и снег полетит в сторону.\n\n"
                    + "Снег не мешает нажимать на иконки, касания проходят сквозь него.", 13, false);
            tip.setGravity(Gravity.START);
            tip.setAlpha(0.8f);
            root.addView(tip, lp(0, dp(26), 0, 0));

            setContentView(sv);

            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        @Override
        protected void onResume() {
            super.onResume();
            if (pendingStart && Settings.canDrawOverlays(this)) {
                pendingStart = false;
                startSnow();
            } else {
                pendingStart = false;
            }
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
        }

        private void pushUpdate() {
            if (SnowService.running) {
                startService(new Intent(this, SnowService.class).setAction(SnowService.ACTION_UPDATE));
            }
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
            int level = Prefs.level(this);
            for (int i = 0; i < 3; i++) {
                if (i == level) {
                    GradientDrawable a = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4FC3FF, 0xFF7A5CFF});
                    a.setCornerRadius(dp(11));
                    lv[i].setBackground(a);
                } else {
                    lv[i].setBackground(round(0x00000000, dp(11)));
                }
            }
        }

        // ---- мелкие помощники для интерфейса ----
        private interface OnBool { void on(boolean v); }

        private View sw(String label, boolean checked, OnBool cb) {
            Switch s = new Switch(this);
            s.setText(label);
            s.setTextColor(Color.WHITE);
            s.setTextSize(15);
            s.setChecked(checked);
            s.setPadding(dp(16), dp(12), dp(16), dp(12));
            s.setBackground(round(0x1FFFFFFF, dp(14)));
            s.setOnCheckedChangeListener((b, v) -> cb.on(v));
            s.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
