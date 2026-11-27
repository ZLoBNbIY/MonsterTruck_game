package com.levgame.monster;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

public final class MainActivity extends Activity {
    private static final int GOLD = 0xffffc24e;
    private static final int LIGHT = 0xfff5f1e8;
    private static final int MUTED = 0xffa1adb6;
    private static final int DARK = 0xff242d35;
    private static final String[] MODELS = {"Хот-род", "Внедорожник"};
    private static final int[] PALETTE = {0xe9351c, 0xffae32, 0x35b776, 0x339ce8, 0xca70ea};
    private static final String[] COLOR_NAMES = {"Красный", "Оранжевый", "Зелёный", "Синий", "Фиолетовый"};

    private GLSurfaceView surface;
    private GameRenderer game;
    private Dashboard dashboard;
    private GameLayout layout;
    private SharedPreferences preferences;
    private int design, paletteIndex;
    private boolean inGarage = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
        surface.setPreserveEGLContextOnPause(true);
        game = new GameRenderer();
        surface.setRenderer(game);
        preferences = getSharedPreferences("garage", MODE_PRIVATE);
        design = preferences.getInt("design", 0) == 1 ? 1 : 0;
        int savedColor = preferences.getInt("color", PALETTE[0]);
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == savedColor) paletteIndex = i;
        }
        queueTruckSelection();
        dashboard = new Dashboard(this);
        layout = new GameLayout(this);
        setContentView(layout);
    }

    private void queueTruckSelection() {
        int selectedDesign = design, color = PALETTE[paletteIndex];
        surface.queueEvent(() -> game.selectTruck(selectedDesign, color));
    }

    private void selectTruck(int newDesign, int newPaletteIndex) {
        if (newDesign == design && newPaletteIndex == paletteIndex) return;
        design = newDesign;
        paletteIndex = newPaletteIndex;
        preferences.edit().putInt("design", design).putInt("color", PALETTE[paletteIndex]).apply();
        queueTruckSelection();
        layout.updateSelection();
        dashboard.invalidate();
    }

    private void showGarage(boolean enabled) {
        if (inGarage == enabled) return;
        inGarage = enabled;
        game.buttons = 0;
        surface.queueEvent(() -> game.setGarage(enabled));
        if (!enabled && dashboard.showHintUntil == 0) {
            dashboard.showHintUntil = SystemClock.uptimeMillis() + 9500;
        }
        layout.updateMode();
        dashboard.invalidate();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
        else if (game != null) game.buttons = 0;
    }

    @Override protected void onResume() {
        super.onResume();
        surface.onResume();
        dashboard.postInvalidateOnAnimation();
    }

    @Override protected void onPause() {
        game.buttons = 0;
        surface.onPause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (inGarage) super.onBackPressed();
        else showGarage(true);
    }

    private static float overlayScale(int width, int height) {
        return Math.min(width / 730f, height / 385f);
    }

    private final class GameLayout extends FrameLayout {
        private final Button[] models = new Button[MODELS.length];
        private final Button[] colors = new Button[PALETTE.length];
        private final Button[] driveTools = new Button[4];
        private final Button drive;

        GameLayout(Context context) {
            super(context);
            setMotionEventSplittingEnabled(true);
            addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(dashboard, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            for (int i = 0; i < models.length; i++) {
                final int index = i;
                models[i] = button(MODELS[i], () -> selectTruck(index, paletteIndex));
            }
            for (int i = 0; i < colors.length; i++) {
                final int index = i;
                colors[i] = button("", () -> selectTruck(design, index));
                colors[i].setContentDescription(COLOR_NAMES[i]);
            }
            drive = button("ВЫЕХАТЬ  ›", () -> showGarage(false));
            drive.setTextColor(0xff211800);
            drive.setBackground(buttonBackground(GOLD, 0xffffdf8c));
            driveTools[0] = tool("Гараж", () -> showGarage(true));
            driveTools[1] = tool("Починить", () -> surface.queueEvent(game::repair));
            driveTools[2] = tool("На колёса", () -> surface.queueEvent(game::rightTruck));
            driveTools[3] = tool("Заново", () -> {
                game.buttons = 0;
                surface.queueEvent(game::restart);
            });
            updateSelection();
            updateMode();
        }

        private Button button(String title, Runnable action) {
            Button button = new Button(getContext());
            button.setText(title);
            button.setAllCaps(false);
            button.setSingleLine(true);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(LIGHT);
            button.setPadding(0, 0, 0, 0);
            button.setStateListAnimator(null);
            button.setOnClickListener(view -> action.run());
            addView(button, new LayoutParams(0, 0));
            return button;
        }

        private Button tool(String title, Runnable action) {
            Button button = button(title, action);
            button.setBackground(buttonBackground(0xe8242d35, 0xff51616c));
            return button;
        }

        private RippleDrawable buttonBackground(int fill, int border) {
            GradientDrawable shape = new GradientDrawable();
            float density = getResources().getDisplayMetrics().density;
            shape.setColor(fill);
            shape.setCornerRadius(8 * density);
            shape.setStroke(Math.round(density), border);
            return new RippleDrawable(ColorStateList.valueOf(0x55ffffff), shape, null);
        }

        void updateSelection() {
            for (int i = 0; i < models.length; i++) {
                boolean selected = design == i;
                models[i].setSelected(selected);
                models[i].setTextColor(selected ? GOLD : LIGHT);
                models[i].setBackground(buttonBackground(selected ? 0xff3d3322 : DARK,
                        selected ? GOLD : 0xff51616c));
            }
            for (int i = 0; i < colors.length; i++) {
                boolean selected = paletteIndex == i;
                colors[i].setSelected(selected);
                colors[i].setText(selected ? "✓" : "");
                colors[i].setTextColor(0xff191d21);
                colors[i].setBackground(buttonBackground(0xff000000 | PALETTE[i], selected ? LIGHT : DARK));
            }
        }

        void updateMode() {
            int garageVisibility = inGarage ? VISIBLE : GONE;
            for (Button button : models) button.setVisibility(garageVisibility);
            for (Button button : colors) button.setVisibility(garageVisibility);
            drive.setVisibility(garageVisibility);
            for (Button button : driveTools) button.setVisibility(inGarage ? GONE : VISIBLE);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            float s = overlayScale(width, height);
            if (s > 0) positionControls(width / s, height / s, s);
            super.onMeasure(widthSpec, heightSpec);
        }

        private void positionControls(float width, float height, float s) {
            if (inGarage) {
                float panelTop = (height - 352) / 2;
                place(models[0], s, 38, panelTop + 116, 133, 56, 17);
                place(models[1], s, 178, panelTop + 116, 146, 56, 17);
                for (int i = 0; i < colors.length; i++) {
                    place(colors[i], s, 38 + i * 58, panelTop + 208, 54, 54, 24);
                }
                place(drive, s, 38, panelTop + 281, 286, 55, 20);
            } else {
                float first = width - 525;
                for (int i = 0; i < driveTools.length; i++) {
                    place(driveTools[i], s, first + i * 99, 18, 93, 54, 15);
                }
            }
        }

        private void place(Button button, float scale, float x, float y, int width, int height, int font) {
            LayoutParams params = (LayoutParams) button.getLayoutParams();
            params.leftMargin = Math.round(x * scale);
            params.topMargin = Math.round(y * scale);
            params.width = Math.round(width * scale);
            params.height = Math.round(height * scale);
            float textSize = font * scale;
            if (button.getTextSize() != textSize) button.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        }
    }

    private final class Dashboard extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final float[] cx = new float[4], cy = new float[4], radius = new float[4];
        private float unit, mapX, mapY, mapR;
        private long showHintUntil;

        Dashboard(Context context) { super(context); }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            unit = overlayScale(w, h);
            cx[0] = 62 * unit;
            cx[1] = 147 * unit;
            cx[2] = w - 63 * unit;
            cx[3] = w - 146 * unit;
            cy[0] = cy[1] = h - 65 * unit;
            cy[2] = h - 79 * unit;
            cy[3] = h - 48 * unit;
            radius[0] = radius[1] = 34 * unit;
            radius[2] = 39 * unit;
            radius[3] = 29 * unit;
            mapR = 45 * unit;
            mapX = w - 65 * unit;
            mapY = 64 * unit;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (unit == 0) return;
            if (inGarage) {
                drawGarage(canvas);
                return;
            }
            float s = unit;
            ink.setColor(0xb8182941);
            canvas.drawRoundRect(18*s, 18*s, 194*s, 110*s, 14*s, 14*s, ink);
            text(canvas, game.speed + " км/ч", 30*s, 46*s, 23, 0xffffdc88);
            text(canvas, "Смято: " + game.crushed, 30*s, 68*s, 15, 0xffe7f7ff);
            int health = game.health, maxHealth = game.maxHealth;
            text(canvas, "Здоровье", 30*s, 87*s, 12, 0xffe7f7ff);
            text(canvas, health + " / " + maxHealth, 120*s, 87*s, 12, 0xffe7f7ff);
            ink.setColor(0x766b8998);
            canvas.drawRoundRect(30*s, 94*s, 178*s, 101*s, 4*s, 4*s, ink);
            float fraction = health / (float) maxHealth;
            ink.setColor(fraction > .55f ? 0xff70dfab : fraction > .25f ? 0xffffbd60 : 0xffff6266);
            canvas.drawRoundRect(30*s, 94*s, (30+148*fraction)*s, 101*s, 4*s, 4*s, ink);
            drawMap(canvas);
            if (health == 0 || game.rolledOver) {
                float mid = getWidth() / 2f;
                String warning = health == 0 ? "Трак разбит. Почини или начни заново."
                        : "Трак перевёрнут. Нажми «На колёса».";
                ink.setColor(0xdb182941);
                canvas.drawRoundRect(mid-144*s, 86*s, mid+144*s, 120*s, 9*s, 9*s, ink);
                ink.setTextAlign(Paint.Align.CENTER);
                text(canvas, warning, mid, 107*s, 13, 0xffffc6a2);
                ink.setTextAlign(Paint.Align.LEFT);
            }
            for (int i = 0; i < 4; i++) drawButton(canvas, i);
            if (SystemClock.uptimeMillis() < showHintUntil) {
                float mid = getWidth()/2f, y = getHeight()-23*s;
                ink.setColor(0xb8182941);
                canvas.drawRoundRect(mid-138*s, y-22*s, mid+138*s, y+8*s, 10*s, 10*s, ink);
                text(canvas, "Стрелки — руль    ▲ газ    ■ тормоз / назад", mid-129*s, y, 12, 0xffe7f7ff);
            }
            postInvalidateDelayed(70);
        }

        private void drawGarage(Canvas canvas) {
            float s = unit;
            float top = (getHeight() / s - 352) / 2;
            ink.setColor(0xf2171e25);
            canvas.drawRoundRect(18*s, top*s, 344*s, (top+352)*s, 18*s, 18*s, ink);
            ink.setColor(0xff424b50);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(s);
            canvas.drawRoundRect(18*s, top*s, 344*s, (top+352)*s, 18*s, 18*s, ink);
            ink.setStyle(Paint.Style.FILL);

            text(canvas, "М О Н С Т Р - Т Р А К И", 38*s, (top+30)*s, 12, GOLD);
            text(canvas, "ГАРАЖ", 36*s, (top+74)*s, 45, LIGHT);
            ink.setColor(GOLD);
            for (int i = 0; i < 4; i++) {
                float x = (275 + i * 12)*s;
                shape.reset();
                shape.moveTo(x, (top+24)*s);
                shape.lineTo(x + 6*s, (top+24)*s);
                shape.lineTo(x + s, (top+36)*s);
                shape.lineTo(x - 5*s, (top+36)*s);
                shape.close();
                canvas.drawPath(shape, ink);
            }
            text(canvas, "01 / МОДЕЛЬ", 38*s, (top+105)*s, 13, MUTED);
            text(canvas, "02 / ЦВЕТ КУЗОВА", 38*s, (top+197)*s, 13, MUTED);

            float right = getWidth() - 24*s;
            float bottom = getHeight() - 24*s;
            ink.setColor(0xd918212a);
            canvas.drawRoundRect(right-182*s, bottom-48*s, right, bottom, 8*s, 8*s, ink);
            text(canvas, "ВЫБРАННЫЙ ТРАК", right-168*s, bottom-30*s, 10, GOLD);
            text(canvas, MODELS[design], right-168*s, bottom-11*s, 16, LIGHT);
        }

        private void drawMap(Canvas canvas) {
            ink.setColor(0xc31a3448);
            canvas.drawCircle(mapX, mapY, mapR, ink);
            shape.reset();
            shape.addCircle(mapX, mapY, mapR - 2 * unit, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(shape);
            float step = 21 * unit;
            float dx = (World.BLOCK / 2 - game.mapX % World.BLOCK) / World.BLOCK * step;
            float dz = (World.BLOCK / 2 - game.mapZ % World.BLOCK) / World.BLOCK * step;
            ink.setColor(0xb084b8bd);
            ink.setStrokeWidth(4 * unit);
            for (int i = -3; i <= 3; i++) {
                float x = mapX + dx + i * step, y = mapY - dz + i * step;
                canvas.drawLine(x, mapY - mapR, x, mapY + mapR, ink);
                canvas.drawLine(mapX - mapR, y, mapX + mapR, y, ink);
            }
            canvas.rotate((float) Math.toDegrees(game.mapYaw), mapX, mapY);
            triangle(canvas, mapX, mapY, 10*unit, 0, 0xffffcc63);
            canvas.restore();
        }

        private void drawButton(Canvas canvas, int i) {
            float x = cx[i], y = cy[i];
            ink.setColor((game.buttons & 1 << i) != 0 ? 0xdbb36b36 : 0xb8182941);
            canvas.drawCircle(x, y, radius[i], ink);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(2*unit);
            ink.setColor(0xa4d7f0ff);
            canvas.drawCircle(x, y, radius[i] - unit, ink);
            ink.setStyle(Paint.Style.FILL);
            if (i == 3) {
                ink.setColor(Color.WHITE);
                canvas.drawRoundRect(x-8*unit, y-8*unit, x+8*unit, y+8*unit, 3*unit, 3*unit, ink);
            } else {
                triangle(canvas, x, y, 15*unit, i == 0 ? -90 : i == 1 ? 90 : 0,
                        i == 2 ? 0xffffdc88 : 0xffecfbff);
            }
        }

        private void triangle(Canvas canvas, float x, float y, float r, float angle, int color) {
            canvas.save();
            canvas.rotate(angle, x, y);
            shape.reset();
            shape.moveTo(x, y-r);
            shape.lineTo(x+r*.82f, y+r*.64f);
            shape.lineTo(x-r*.82f, y+r*.64f);
            shape.close();
            ink.setColor(color);
            canvas.drawPath(shape, ink);
            canvas.restore();
        }

        private void text(Canvas canvas, String value, float x, float y, int sp, int color) {
            ink.setColor(color);
            ink.setTypeface(Typeface.DEFAULT_BOLD);
            ink.setTextSize(sp*unit);
            canvas.drawText(value, x, y, ink);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (inGarage) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) {
                game.buttons = 0;
                return true;
            }
            int changed = event.getActionIndex();
            int mask = 0;
            for (int p = 0; p < event.getPointerCount(); p++) {
                if (p == changed && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)) continue;
                for (int i = 0; i < 4; i++) {
                    float dx = event.getX(p) - cx[i], dy = event.getY(p) - cy[i];
                    if (dx*dx + dy*dy < radius[i]*radius[i]) mask |= 1 << i;
                }
            }
            game.buttons = mask;
            invalidate();
            return true;
        }
    }
}
