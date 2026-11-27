package com.levgame.monster;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Visual-only pedestrians and street furniture. */
final class UrbanLife {
    private static final float WALK_Y = World.Tree.SIDEWALK_HEIGHT;
    private static final float HALF_ROAD = World.ROAD * .5f;
    private static final float PEOPLE_RANGE = 88f;
    private static final float FURNITURE_RANGE = 124f;
    private static final float WALK_OFFSET = 2.38f;
    private static final float WALK_START = HALF_ROAD + 5.6f;
    private static final float WALK_END = World.BLOCK * .5f - 2f;
    private static final float TURN_TIME = .42f;
    private static final int STRIDE = 6 * Float.BYTES;
    // Routes fit within their home tile, so only tiles overlapping the draw range can contribute.
    private static final int PEOPLE_TILES_ACROSS = (int) (2 * PEOPLE_RANGE / World.BLOCK) + 2;
    private static final int BOX_VERTICES = 30;
    private static final int WALKER_VERTICES = 14 * BOX_VERTICES;
    private static final int BATCH_BYTES = PEOPLE_TILES_ACROSS * PEOPLE_TILES_ACROSS * 4
            * WALKER_VERTICES * STRIDE;
    private static final int[] COATS = {0xd67947, 0xe1ad3d, 0x2b847c, 0x875485, 0x3966ad, 0xc95159};
    private static final int[] SKIN = {0xf1bd91, 0xcf8e67, 0xa56949, 0x7a4937};

    // Each parity has a normal layout and four variants that omit the yard's corner.
    private final int[] furnitureStarts = new int[10];
    private final int[] furnitureCounts = new int[furnitureStarts.length];
    private final int[] buffers = new int[2];
    private final Walker[][] walkers = new Walker[World.GRID * World.GRID][];
    private final float[] building = new float[6];
    private final float[] translatedVp = new float[16];
    private final MeshWriter mesh = new MeshWriter();
    private int position, tint, transform;
    private double time;

    void create(int position, int tint, int transform) {
        this.position = position;
        this.tint = tint;
        this.transform = transform;
        GLES20.glGenBuffers(buffers.length, buffers, 0);
        mesh.data.clear();
        for (int i = 0; i < furnitureStarts.length; i++) {
            furnitureStarts[i] = mesh.data.position() / 6;
            makeFurniture(i / 5, i % 5 - 1);
            furnitureCounts[i] = mesh.data.position() / 6 - furnitureStarts[i];
        }
        mesh.data.flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.data.remaining() * Float.BYTES,
                mesh.data, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, BATCH_BYTES, null, GLES20.GL_STREAM_DRAW);
    }

    void draw(float elapsed, float cameraX, float cameraZ, float[] vp) {
        time += elapsed;
        mesh.data.clear();
        System.arraycopy(vp, 0, translatedVp, 0, 16);
        int minX = Math.max(0, tile(cameraX - FURNITURE_RANGE));
        int maxX = Math.min(World.GRID - 1, tile(cameraX + FURNITURE_RANGE));
        int minZ = Math.max(0, tile(cameraZ - FURNITURE_RANGE));
        int maxZ = Math.min(World.GRID - 1, tile(cameraZ + FURNITURE_RANGE));
        int minPeopleX = tile(cameraX - PEOPLE_RANGE), maxPeopleX = tile(cameraX + PEOPLE_RANGE);
        int minPeopleZ = tile(cameraZ - PEOPLE_RANGE), maxPeopleZ = tile(cameraZ + PEOPLE_RANGE);
        bind(buffers[0]);
        for (int row = minZ; row <= maxZ; row++) {
            for (int col = minX; col <= maxX; col++) {
                float x = (col + .5f) * World.BLOCK;
                float z = (row + .5f) * World.BLOCK;
                float dx = x - cameraX, dz = z - cameraZ;
                float distanceSquared = dx * dx + dz * dz;
                if (within(distanceSquared, FURNITURE_RANGE + 23f) && visible(vp, x, 2f, z, 23f)) {
                    int layout = ((col + row) & 1) * 5 + yardCorner(col, row) + 1;
                    for (int i = 0; i < 4; i++) {
                        translatedVp[12 + i] = vp[i] * x + vp[8 + i] * z + vp[12 + i];
                    }
                    GLES20.glUniformMatrix4fv(transform, 1, false, translatedVp, 0);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, furnitureStarts[layout], furnitureCounts[layout]);
                }
                if (col >= minPeopleX && col <= maxPeopleX && row >= minPeopleZ && row <= maxPeopleZ
                        && within(distanceSquared, PEOPLE_RANGE + 33f) && visible(vp, x, 1f, z, 33f)) {
                    for (Walker walker : walkers(col, row, x, z)) {
                        drawWalker(walker, cameraX, cameraZ, vp);
                    }
                }
            }
        }
        int count = mesh.data.position() / 6;
        if (count == 0) return;
        mesh.data.flip();
        GLES20.glUniformMatrix4fv(transform, 1, false, vp, 0);
        bind(buffers[1]);
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, count * STRIDE, mesh.data);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
    }

    private void bind(int buffer) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer);
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, STRIDE, 0);
        GLES20.glVertexAttribPointer(tint, 3, GLES20.GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
    }

    private static int tile(float coordinate) {
        return (int) Math.floor(coordinate / World.BLOCK);
    }

    private static boolean within(float distanceSquared, float range) {
        return distanceSquared <= range * range;
    }

    private static boolean visible(float[] vp, float x, float y, float z, float radius) {
        float w = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (w < -radius) return false;
        float clipX = vp[0] * x + vp[4] * y + vp[8] * z + vp[12];
        float clipY = vp[1] * x + vp[5] * y + vp[9] * z + vp[13];
        // Perspective scale times the bound, plus the maximum change in clip W.
        float horizontalScale = Math.abs(vp[0]) + Math.abs(vp[4]) + Math.abs(vp[8]);
        float verticalScale = Math.abs(vp[1]) + Math.abs(vp[5]) + Math.abs(vp[9]);
        return Math.abs(clipX) <= w + radius * (1f + horizontalScale)
                && Math.abs(clipY) <= w + radius * (1f + verticalScale);
    }

    private static int yardCorner(int col, int row) {
        if (col < World.YARD_X || col > World.YARD_X + 1
                || row < World.YARD_Z || row > World.YARD_Z + 1) return -1;
        return (col == World.YARD_X ? 1 : 0) | (row == World.YARD_Z ? 2 : 0);
    }

    private Walker[] walkers(int col, int row, float x, float z) {
        int index = row * World.GRID + col;
        Walker[] cached = walkers[index];
        if (cached != null) return cached;
        Walker[] routes = new Walker[4];
        int count = 0;
        int skippedCorner = yardCorner(col, row);
        for (int corner = 0; corner < 4; corner++) {
            if (corner == skippedCorner) continue;
            int sx = (corner & 1) == 0 ? -1 : 1;
            int sz = (corner & 2) == 0 ? -1 : 1;
            int lotX = sx > 0 ? col : col - 1, lotZ = sz > 0 ? row : row - 1;
            int style = Integer.rotateLeft((index * 4 + corner + 5) * 0x9e3779b9, 11);
            boolean alongZ = (style & 1) == 0;
            for (int attempt = 0; attempt < 2; attempt++, alongZ = !alongZ) {
                float fromX = x + sx * (alongZ ? HALF_ROAD + WALK_OFFSET : WALK_START);
                float fromZ = z + sz * (alongZ ? WALK_START : HALF_ROAD + WALK_OFFSET);
                float toX = alongZ ? fromX : x + sx * WALK_END;
                float toZ = alongZ ? z + sz * WALK_END : fromZ;
                if (!routeClear(lotX, lotZ, fromX, fromZ, toX, toZ)) continue;
                routes[count++] = new Walker(fromX, fromZ, alongZ ? 0 : sx, alongZ ? sz : 0, style);
                break;
            }
        }
        Walker[] result = new Walker[count];
        System.arraycopy(routes, 0, result, 0, count);
        walkers[index] = result;
        return result;
    }

    private boolean routeClear(int lotX, int lotZ, float x0, float z0, float x1, float z1) {
        // The route is behind the tree row; reject either sidewalk if a frontage is too close.
        float clearance = .51f;
        int count = World.buildingCount(lotX, lotZ);
        for (int i = 0; i < count; i++) {
            World.building(lotX, lotZ, i, building);
            if (Math.min(x0, x1) - clearance < building[0] + building[2]
                    && Math.max(x0, x1) + clearance > building[0] - building[2]
                    && Math.min(z0, z1) - clearance < building[1] + building[3]
                    && Math.max(z0, z1) + clearance > building[1] - building[3]) return false;
        }
        return true;
    }

    private void drawWalker(Walker walker, float cameraX, float cameraZ, float[] vp) {
        float phase = (float) ((time + walker.offset) % (2 * (walker.walkTime + TURN_TIME)));
        boolean outward = phase < walker.walkTime + TURN_TIME;
        if (!outward) phase -= walker.walkTime + TURN_TIME;
        float fraction = Math.min(1f, phase / walker.walkTime);
        float distance = (WALK_END - WALK_START) * (outward ? fraction : 1f - fraction);
        float x = walker.x + walker.dx * distance, z = walker.z + walker.dz * distance;
        float dx = x - cameraX, dz = z - cameraZ;
        if (!within(dx * dx + dz * dz, PEOPLE_RANGE) || !visible(vp, x, 1f, z, 1.15f)) return;

        // End each short route by turning in place before walking back along the same sidewalk.
        float turn = Math.max(0, (phase - walker.walkTime) / TURN_TIME);
        float angle = (float) (Math.PI * turn);
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float direction = outward ? 1f : -1f;
        mesh.pose(x, z, direction * (walker.dx * cos + walker.dz * sin),
                direction * (walker.dz * cos - walker.dx * sin), walker.scale);
        float cadence = fraction * walker.strides * 6.2831855f;
        float swing = (float) Math.sin(cadence);
        float bob = .028f * Math.abs(swing);
        mesh.taper(-.17f, .84f, -.12f, .17f, .10f,
                -.23f, 1.40f + bob, -.13f, .23f, .13f, walker.coat);
        mesh.box(-.08f, 1.36f + bob, -.07f, .08f, 1.50f + bob, .08f, walker.skin);
        mesh.taper(-.106f, 1.46f + bob, -.10f, .106f, .13f,
                -.12f, 1.69f + bob, -.123f, .12f, .117f, walker.skin);
        mesh.taper(-.123f, 1.62f + bob, -.127f, .123f, .123f,
                -.093f, 1.735f + bob, -.10f, .093f, .091f, walker.hair);
        for (int side = -1; side <= 1; side += 2) {
            float legX = side * .096f, armX = side * .252f;
            float step = side * swing;
            float foot = .27f * step, knee = .15f * step;
            float lift = .075f * Math.max(0f, step);
            mesh.taper(legX - .065f, .43f + lift, knee - .076f, legX + .065f, knee + .075f,
                    legX - .082f, .89f, -.103f, legX + .082f, .103f, walker.trousers);
            mesh.taper(legX - .056f, .085f + lift, foot - .062f, legX + .056f, foot + .060f,
                    legX - .065f, .48f + lift, knee - .075f, legX + .065f, knee + .075f, walker.trousers);
            mesh.box(legX - .068f, lift, foot - .078f, legX + .068f, .10f + lift,
                    foot + .147f, walker.shoes);
            float hand = -.193f * step;
            mesh.taper(armX - .051f, .91f + bob, hand - .05f, armX + .051f, hand + .05f,
                    armX - .061f, 1.365f + bob, -.07f, armX + .061f, .069f, walker.coat);
            // The hand continues the sleeve at its lower end.
            mesh.box(armX - .048f, .824f + bob, hand - .046f,
                    armX + .048f, .925f + bob, hand + .047f, walker.skin);
        }
    }

    private void makeFurniture(int parity, int skippedCorner) {
        for (int corner = 0; corner < 4; corner++) {
            if (corner == skippedCorner) continue;
            int sx = (corner & 1) == 0 ? -1 : 1;
            int sz = (corner & 2) == 0 ? -1 : 1;
            if ((sx == sz) == (parity == 0)) {
                // Face the fixture into the intersection; its pole stays behind the curb.
                mesh.pose(sx * (HALF_ROAD + 1.26f), sz * (HALF_ROAD + 1.26f),
                        -sx * .7071068f, -sz * .7071068f, 1f);
                lamp();
            } else {
                mesh.pose(sx * (HALF_ROAD + .92f), sz * (HALF_ROAD + 9.45f), -sx, 0f, 1f);
                bench();
            }
        }
    }

    private void lamp() {
        int metal = 0x3c535c;
        mesh.box(-.15f, 0f, -.15f, .15f, .16f, .15f, 0x66747a);
        mesh.taper(-.079f, .16f, -.079f, .079f, .079f,
                -.044f, 4.18f, -.044f, .044f, .044f, metal);
        mesh.box(-.046f, 4f, -.06f, .046f, 4.14f, .94f, metal);
        mesh.taper(-.14f, 4.02f, .71f, .14f, 1.24f,
                -.103f, 4.18f, .66f, .103f, 1.17f, metal);
        mesh.panel(-.105f, 4.014f, .75f, .105f, 1.18f, 0xffe5a3);
    }

    private void bench() {
        int iron = 0x344850, wood = 0xb87549;
        for (int side = -1; side <= 1; side += 2) {
            float x = side * .69f;
            mesh.box(x - .055f, 0f, -.25f, x + .055f, .94f, -.16f, iron);
            mesh.box(x - .055f, 0f, .15f, x + .055f, .42f, .24f, iron);
            mesh.box(x - .055f, .36f, -.22f, x + .055f, .41f, .21f, iron);
        }
        for (int slat = 0; slat < 3; slat++) {
            float z = -.235f + slat * .16f;
            mesh.box(-.91f, .40f, z, .91f, .46f, z + .138f, wood);
        }
        mesh.box(-.91f, .65f, -.23f, .91f, .76f, -.17f, wood);
        mesh.box(-.91f, .80f, -.25f, .91f, .92f, -.19f, wood);
    }

    private static final class Walker {
        final float x, z, dx, dz, offset, walkTime, scale;
        final int coat, skin, hair, trousers, shoes, strides;

        Walker(float x, float z, float dx, float dz, int style) {
            this.x = x;
            this.z = z;
            this.dx = dx;
            this.dz = dz;
            offset = (style >>> 1 & 255) * .17f;
            scale = .92f + (style >>> 9 & 15) * .012f;
            walkTime = (WALK_END - WALK_START) / (1.09f + (style >>> 13 & 15) * .027f);
            strides = Math.round((WALK_END - WALK_START) / (1.23f * scale));
            coat = COATS[(style >>> 17) % COATS.length];
            skin = SKIN[style >>> 22 & 3];
            hair = (style & (1 << 24)) == 0 ? 0x382b2b : 0x574139;
            trousers = (style & (1 << 25)) == 0 ? 0x344961 : 0x504752;
            shoes = (style & (1 << 26)) == 0 ? 0x333139 : 0xccc2ae;
        }
    }

    private static final class MeshWriter {
        final FloatBuffer data = ByteBuffer.allocateDirect(BATCH_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final float[] points = new float[24];
        private float originX, originZ, forwardX, forwardZ, scale;

        void pose(float x, float z, float forwardX, float forwardZ, float scale) {
            originX = x;
            originZ = z;
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
            this.scale = scale;
        }

        void box(float x, float y, float z, float right, float top, float front, int color) {
            taper(x, y, z, right, front, x, top, z, right, front, color);
        }

        void panel(float x, float y, float z, float right, float front, int color) {
            point(0, x, y, z);
            point(1, right, y, z);
            point(2, right, y, front);
            point(3, x, y, front);
            quad(0, 1, 2, 3, color, 1f);
        }

        void taper(float x0, float y0, float z0, float right0, float front0,
                   float x1, float y1, float z1, float right1, float front1, int color) {
            point(0, x0, y0, z0);
            point(1, right0, y0, z0);
            point(2, right0, y0, front0);
            point(3, x0, y0, front0);
            point(4, x1, y1, z1);
            point(5, right1, y1, z1);
            point(6, right1, y1, front1);
            point(7, x1, y1, front1);
            quad(4, 5, 6, 7, color, 1f);
            quad(0, 1, 5, 4, color, .73f);
            quad(1, 2, 6, 5, color, .82f);
            quad(2, 3, 7, 6, color, .98f);
            quad(3, 0, 4, 7, color, .90f);
        }

        private void point(int index, float x, float y, float z) {
            int i = index * 3;
            points[i] = originX + scale * (forwardZ * x + forwardX * z);
            points[i + 1] = WALK_Y + scale * y;
            points[i + 2] = originZ + scale * (forwardZ * z - forwardX * x);
        }

        private void quad(int a, int b, int c, int d, int color, float shade) {
            float r = (color >> 16) * (shade / 255f);
            float g = (color >> 8 & 255) * (shade / 255f);
            float blue = (color & 255) * (shade / 255f);
            vertex(a, r, g, blue);
            vertex(b, r, g, blue);
            vertex(c, r, g, blue);
            vertex(a, r, g, blue);
            vertex(c, r, g, blue);
            vertex(d, r, g, blue);
        }

        private void vertex(int index, float r, float g, float b) {
            int i = index * 3;
            data.put(points[i]).put(points[i + 1]).put(points[i + 2]).put(r).put(g).put(b);
        }
    }
}
