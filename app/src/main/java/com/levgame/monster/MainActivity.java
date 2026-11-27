package com.levgame.monster;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public final class MainActivity extends Activity {
    private GLSurfaceView surface;
    private GameRenderer game;
    private Dashboard dashboard;

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
        dashboard = new Dashboard(this);
        FrameLayout view = new FrameLayout(this);
        view.addView(surface);
        view.addView(dashboard);
        setContentView(view);
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

    private final class Dashboard extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final float[] cx = new float[4], cy = new float[4], radius = new float[4];
        private float unit, mapX, mapY, mapR;
        private long showHintUntil = android.os.SystemClock.uptimeMillis() + 9500;

        Dashboard(Context context) { super(context); }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            unit = Math.min(w / 730f, h / 385f);
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
            float resetX = mapX - 85*s;
            ink.setColor(0xb8182941);
            canvas.drawRoundRect(resetX-35*s, 21*s, resetX+35*s, 53*s, 10*s, 10*s, ink);
            text(canvas, "Заново", resetX-25*s, 42*s, 13, 0xffe7f7ff);
            if (health == 0) {
                float mid = getWidth() / 2f;
                ink.setColor(0xdb182941);
                canvas.drawRoundRect(mid-100*s, 23*s, mid+100*s, 55*s, 9*s, 9*s, ink);
                text(canvas, "Трак разбит. Жми «Заново»", mid-88*s, 43*s, 12, 0xffffc6a2);
            }
            for (int i = 0; i < 4; i++) drawButton(canvas, i);
            if (android.os.SystemClock.uptimeMillis() < showHintUntil) {
                float mid = getWidth()/2f, y = getHeight()-23*s;
                ink.setColor(0xb8182941);
                canvas.drawRoundRect(mid-138*s, y-22*s, mid+138*s, y+8*s, 10*s, 10*s, ink);
                text(canvas, "Стрелки — руль    ▲ газ    ■ тормоз / назад", mid-129*s, y, 12, 0xffe7f7ff);
            }
            postInvalidateDelayed(70);
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
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) {
                game.buttons = 0;
                return true;
            }
            int changed = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN && Math.abs(event.getX() - (mapX-85*unit)) < 35*unit
                    && event.getY() >= 21*unit && event.getY() <= 53*unit) {
                game.buttons = 0;
                surface.queueEvent(game::restart);
            }
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
