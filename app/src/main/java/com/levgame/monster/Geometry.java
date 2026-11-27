package com.levgame.monster;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

final class Geometry {
    static final class Mesh {
        final FloatBuffer data;
        final int count;

        Mesh(FloatBuffer data, int count) {
            this.data = data;
            this.count = count;
        }
    }

    private static final int ASPHALT = 0x434f62;
    private static final int PAVEMENT = 0x8b9da5;
    private static final int CURB = 0xb5c8cc;
    private static final int WHITE_PAINT = 0xd9e8eb;
    private static final int YELLOW_PAINT = 0xffda77;
    private static final int[] BUILDING_COLORS = {
        0x589db8, 0x76bcc3, 0x8794c2, 0xf0a974, 0x6aafd1, 0x7da9c6
    };
    private static final int[] CAR_COLORS = {
        0x6cbcd9, 0xffcd68, 0xf89193, 0x66cdc3, 0xaf9cdd, 0xffae80
    };
    private static final int GLASS = 0x569bbc;
    private static final int TRIM = 0x354659;
    private static final float WALK_Y = World.Tree.SIDEWALK_HEIGHT;

    private Geometry() {}

    static boolean onDirt(float x, float z) {
        return x > World.YARD_MIN_X && z > World.YARD_MIN_Z
                && x < World.YARD_MAX_X && z < World.YARD_MAX_Z;
    }

    private static void yard(Builder mesh, float x, float z) {
        float size = World.BLOCK - World.ROAD;
        for (int j = 0; j < 6; j++) {
            for (int i = 0; i < 6; i++) {
                float unit = size / 6;
                int seed = i * 20 + j * 23 + i*j*13;
                float x0 = x + i*unit, x1 = x + (i+1)*unit;
                float z0 = z + j*unit, z1 = z + (j+1)*unit;
                mesh.triangle(Builder.point(x0, .013f, z0), Builder.point(x0, .013f, z1),
                        Builder.point(x1, .013f, z1), shade(0xa36c43, .98f + seed % 13 * .003f));
                mesh.triangle(Builder.point(x0, .013f, z0), Builder.point(x1, .013f, z1),
                        Builder.point(x1, .013f, z0), shade(0xa36c43, .98f + seed % 19 * .003f));
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3 - i; j++) {
                crate(mesh, x+2.2f+(j+i*.52f)*1.42f, .02f+i*1.38f, z+35.8f);
                crate(mesh, x+40.8f+(j+i*.52f)*1.42f, .02f+i*1.38f, z+19.5f);
            }
        }
        for (int i = 0; i < 3; i++) {
            barrel(mesh,x+5.5f+i*1.65f,z+11.2f+i*.3f);
            barrel(mesh,x+38.5f+i*1.65f,z+40.8f-i*.6f);
        }
        float[] bounds = new float[5];
        for (int i = 0; i < World.RAMP_COUNT; i++) {
            World.ramp(i, bounds);
            ramp(mesh, bounds, i == 0 ? 0xf27a2c : 0xe3592d);
        }
    }

    private static void ramp(Builder mesh, float[] bounds, int color) {
        float x0 = bounds[0], z0 = bounds[1], x1 = bounds[2], z1 = bounds[3], height = bounds[4];
        float y = .018f;
        mesh.triangle(Builder.point(x0,y,z0), Builder.point(x0,y,z1),
                Builder.point(x0,height+y,z1), shade(color,.76f));
        mesh.triangle(Builder.point(x1,y,z0), Builder.point(x1,y,z1),
                Builder.point(x1,height+y,z1), shade(color,.91f));
        mesh.wallZ(z1, y, height+y, x0, x1, shade(color,.69f), true);
        for (int strip = 0; strip < 12; strip++) {
            float from = strip/12f, to = (strip+1)/12f;
            float za = z0 + (z1-z0)*from, zb = z0 + (z1-z0)*to;
            float ya = y + height*from, yb = y + height*to;
            int surface = strip % 3 == 0 ? 0xffc562 : shade(color, 1.13f);
            mesh.quad(Builder.point(x0,ya,za), Builder.point(x1,ya,za),
                    Builder.point(x1,yb,zb), Builder.point(x0,yb,zb), surface);
            for (int side = -1; side <= 1; side += 2) {
                float edge = side < 0 ? x0 : x1;
                float inside = edge - side * .27f;
                mesh.quad(Builder.point(edge,ya+.015f,za), Builder.point(inside,ya+.015f,za),
                        Builder.point(inside,yb+.015f,zb), Builder.point(edge,yb+.015f,zb),
                        shade(color, .76f));
            }
            float joint = to - .028f;
            float zj = z0 + (z1-z0)*joint, yj = y + height*joint;
            mesh.quad(Builder.point(x0+.28f,yj+.015f,zj), Builder.point(x1-.28f,yj+.015f,zj),
                    Builder.point(x1-.28f,yb+.015f,zb), Builder.point(x0+.28f,yb+.015f,zb),
                    shade(surface, .90f));
        }
    }

    private static void crate(Builder mesh, float x, float y, float z) {
        mesh.box(x, y, z, x+1.38f, y+1.38f, z+1.38f, 0xbb592c);
        mesh.box(x+.12f, y+.13f, z-.014f, x+1.26f, y+1.25f, z+.02f, 0xea9a58);
        mesh.box(x+.12f, y+.13f, z+1.36f, x+1.26f, y+1.398f, z+1.40f, 0xd88a49);
        for (int i = 1; i <= 3; i++) {
            float line = x + .14f + i*.27f;
            mesh.wallZ(z-.018f, y+.14f, y+1.23f, line, line+.025f, 0xd27d44, false);
        }
    }

    private static void barrel(Builder mesh, float x, float z) {
        for (int i = 0; i < 10; i++) {
            double a = i*Math.PI/5, b = (i+1)*Math.PI/5;
            float xa = x + .61f*(float)Math.cos(a), za = z + .61f*(float)Math.sin(a);
            float xb = x + .61f*(float)Math.cos(b), zb = z + .61f*(float)Math.sin(b);
            for (int ring = 0; ring < 5; ring++) {
                int color = ring == 1 || ring == 3 ? 0xffdf9e : 0xf07035;
                mesh.quad(Builder.point(xa, ring*.29f, za), Builder.point(xb, ring*.29f, zb),
                        Builder.point(xb, (ring+1)*.29f, zb), Builder.point(xa, (ring+1)*.29f, za),
                        shade(color, .76f + i*.035f));
            }
            mesh.triangle(Builder.point(x, 1.45f, z), Builder.point(xa, 1.45f, za),
                    Builder.point(xb, 1.45f, zb), 0xdf7133);
        }
    }

    static Mesh tile(int gx, int gz) {
        Builder mesh = new Builder();
        float x0 = gx * (float) World.BLOCK;
        float z0 = gz * (float) World.BLOCK;
        float block = World.BLOCK;
        float x1 = x0 + block;
        float z1 = z0 + block;
        float cx = x0 + block * 0.5f;
        float cz = z0 + block * 0.5f;
        float halfRoad = World.ROAD * 0.5f;

        mesh.flat(x0, z0, x1, z1, 0f, ASPHALT);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float edgeX = cx + sx * halfRoad;
                float edgeZ = cz + sz * halfRoad;
                float outerX = sx < 0 ? x0 : x1;
                float outerZ = sz < 0 ? z0 : z1;
                int lotX = sx > 0 ? gx : gx - 1;
                int lotZ = sz > 0 ? gz : gz - 1;
                if (lotX != World.YARD_X || lotZ != World.YARD_Z) {
                    sidewalk(mesh, edgeX, edgeZ, outerX, outerZ, sx, sz);
                }
            }
        }
        roadMarkings(mesh, cx, cz, block * 0.5f, halfRoad);
        if (gx == World.YARD_X && gz == World.YARD_Z) yard(mesh, cx + halfRoad, cz + halfRoad);

        float[] footprint = new float[6];
        int count = World.buildingCount(gx, gz);
        for (int i = 0; i < count; i++) {
            World.building(gx, gz, i, footprint);
            building(mesh, footprint);
        }
        return mesh.finish();
    }

    private static void sidewalk(Builder mesh, float edgeX, float edgeZ, float outerX,
                                 float outerZ, int sx, int sz) {
        float minX = Math.min(edgeX, outerX);
        float maxX = Math.max(edgeX, outerX);
        float minZ = Math.min(edgeZ, outerZ);
        float maxZ = Math.max(edgeZ, outerZ);
        mesh.flat(minX, minZ, maxX, maxZ, WALK_Y, PAVEMENT);

        float band = 0.22f;
        mesh.box(edgeX, 0f, minZ, edgeX + sx * band, WALK_Y + 0.012f, maxZ, CURB);
        float innerX = edgeX + sx * band;
        mesh.box(innerX, 0f, edgeZ, outerX, WALK_Y + 0.012f, edgeZ + sz * band, CURB);
    }

    private static void roadMarkings(Builder mesh, float x, float z, float halfBlock,
                                     float halfRoad) {
        float crossing = halfRoad + 0.43f;
        float stripeStep = Math.min(0.88f, halfRoad / 4f);
        if (crossing + 1.3f < halfBlock) {
            for (float lane = -halfRoad + stripeStep; lane < halfRoad - stripeStep * 0.5f;
                 lane += stripeStep) {
                float halfStripe = stripeStep * 0.28f;
                for (int side = -1; side <= 1; side += 2) {
                    float from = side * crossing;
                    float to = side * (crossing + 1.15f);
                    mesh.flat(x + lane - halfStripe, z + Math.min(from, to),
                        x + lane + halfStripe, z + Math.max(from, to), 0.012f, WHITE_PAINT);
                    mesh.flat(x + Math.min(from, to), z + lane - halfStripe,
                        x + Math.max(from, to), z + lane + halfStripe, 0.012f, WHITE_PAINT);
                }
            }
        }

        for (float distance = crossing + 2.1f; distance + 1.3f < halfBlock; distance += 3.6f) {
            for (int side = -1; side <= 1; side += 2) {
                float along = side * distance;
                dash(mesh, x, z, along, 0f, 0.07f, YELLOW_PAINT);
                dash(mesh, x, z, along, -halfRoad * 0.48f, 0.05f, WHITE_PAINT);
                dash(mesh, x, z, along, halfRoad * 0.48f, 0.05f, WHITE_PAINT);
            }
        }
    }

    private static void dash(Builder mesh, float x, float z, float along, float across,
                             float halfWidth, int color) {
        float halfLength = 0.76f;
        mesh.flat(x + across - halfWidth, z + along - halfLength,
            x + across + halfWidth, z + along + halfLength, 0.012f, color);
        mesh.flat(x + along - halfLength, z + across - halfWidth,
            x + along + halfLength, z + across + halfWidth, 0.012f, color);
    }

    static Mesh tree() {
        Builder mesh = new Builder();
        float pivot = World.Tree.PIVOT;
        float[] height = World.Tree.CROWN_HEIGHTS;
        float[] radius = World.Tree.CROWN_RADII;
        for (int i = 0; i < 6; i++) {
            double angle = i * Math.PI / 3, next = (i + 1) * Math.PI / 3;
            float ax = (float) Math.cos(angle), az = (float) Math.sin(angle);
            float bx = (float) Math.cos(next), bz = (float) Math.sin(next);
            mesh.quad(Builder.point(ax*.14f, -pivot, az*.14f),
                    Builder.point(bx*.14f, -pivot, bz*.14f),
                    Builder.point(bx*.10f, 1.77f-pivot, bz*.10f),
                    Builder.point(ax*.10f, 1.77f-pivot, az*.10f), shade(0x86664d, .92f-.14f*ax));
            for (int h = 1; h < height.length; h++) {
                float a = radius[h-1], b = radius[h];
                mesh.quad(Builder.point(ax*a, height[h-1]-pivot, az*a),
                        Builder.point(bx*a, height[h-1]-pivot, bz*a),
                        Builder.point(bx*b, height[h]-pivot, bz*b),
                        Builder.point(ax*b, height[h]-pivot, az*b),
                        shade(0x5d9a56, .72f + .082f*h - .12f*ax - .09f*az));
            }
            int top = height.length - 1;
            mesh.triangle(Builder.point(ax*radius[top], height[top]-pivot, az*radius[top]),
                    Builder.point(bx*radius[top], height[top]-pivot, bz*radius[top]),
                    Builder.point(0f, World.Tree.HEIGHT-pivot, 0f),
                    shade(0x5d9a56, 1.09f - .12f*ax - .09f*az));
        }
        return mesh.finish();
    }

    static Mesh stump() {
        Builder mesh = new Builder();
        mesh.box(-.15f, 0f, -.15f, .15f, .22f, .15f, 0x86664d);
        mesh.flat(-.135f, -.135f, .135f, .135f, .222f, 0xc5a37c);
        return mesh.finish();
    }

    private static void building(Builder mesh, float[] footprint) {
        float x = footprint[0];
        float z = footprint[1];
        float hx = footprint[2];
        float hz = footprint[3];
        float roof = footprint[4];
        int color = BUILDING_COLORS[Math.floorMod((int) footprint[5], BUILDING_COLORS.length)];
        mesh.box(x - hx, WALK_Y, z - hz, x + hx, roof, z + hz, color);
        mesh.box(x - hx - 0.07f, roof, z - hz - 0.07f,
             x + hx + 0.07f, roof + 0.19f, z + hz + 0.07f, shade(color, 1.13f));
        mesh.box(x - hx - .09f, WALK_Y, z - hz - .09f,
                x + hx + .09f, 1.06f, z + hz + .09f, shade(color, .75f));
        mesh.box(x - hx*.31f, roof + .19f, z - hz*.26f,
                x + hx*.28f, roof + .85f, z + hz*.26f, shade(color, .89f));

        windows(mesh, x, z, hx, hz, roof, false);
        windows(mesh, x, z, hx, hz, roof, true);
    }

    private static void windows(Builder mesh, float x, float z, float hx, float hz,
                                float roof, boolean alongZ) {
        float span = 2f * (alongZ ? hz : hx);
        int columns = Math.max(1, Math.min(10, (int) (span / 2.25f)));
        int floors = Math.max(1, Math.min(16, (int) (roof / 2.45f)));
        float columnWidth = span / columns;
        float floorHeight = (roof - WALK_Y) / floors;
        for (int floor = 0; floor < floors; floor++) {
            float bottom = WALK_Y + (floor + 0.31f) * floorHeight;
            float top = bottom + floorHeight * 0.38f;
            for (int col = 0; col < columns; col++) {
                float start = -span * 0.5f + (col + 0.21f) * columnWidth;
                float end = start + 0.58f * columnWidth;
                if (alongZ) {
                    mesh.wallX(x + hx + .040f, bottom, top, z + start, z + end, 0x42627b, true);
                    mesh.wallX(x - hx - .040f, bottom, top, z + start, z + end, 0x416478, false);
                    mesh.wallX(x + hx + .091f, bottom+.055f, top-.061f, z+start+.055f, z+end-.055f,
                            floor % 4 == 0 ? 0x92b9c7 : 0x789daf, true);
                    mesh.wallX(x - hx - .091f, bottom+.055f, top-.061f, z+start+.055f, z+end-.055f,
                            floor % 4 == 0 ? 0x81a8b8 : 0x6c90a4, false);
                } else {
                    mesh.wallZ(z + hz + .040f, bottom, top, x + start, x + end, 0x476375, true);
                    mesh.wallZ(z - hz - .040f, bottom, top, x + start, x + end, 0x3c5f74, false);
                    mesh.wallZ(z + hz + .091f, bottom+.055f, top-.061f, x+start+.055f, x+end-.055f,
                            floor % 4 == 0 ? 0x9fc4ce : 0x84aabd, true);
                    mesh.wallZ(z - hz - .091f, bottom+.055f, top-.061f, x+start+.055f, x+end-.055f,
                            floor % 4 == 0 ? 0x90b9c6 : 0x789eae, false);
                }
            }
        }
    }

    static Mesh vehicle(boolean truck, int color, float front, float back, float left,
                        float right, float damage) {
        Builder mesh = new Builder();
        Deformation shape = new Deformation(truck, front, back, left, right, damage);
        if (truck) {
            truck(mesh, shape);
        } else {
            car(mesh, shape, CAR_COLORS[Math.floorMod(color, CAR_COLORS.length)]);
        }
        return mesh.finish();
    }

    private static void truck(Builder mesh, Deformation shape) {
        int paint = 0xef3521;
        VehiclePanel body = panel(mesh, shape, Panel.BODY, paint, 0, 1.32f, -2.55f,
                1.34f, 2.05f, 2.54f, .28f, .20f, .39f);
        panel(mesh, shape, Panel.CABIN, 0xe82c22, 0, 1.94f, -1.33f, 1.11f, 2.89f, 1.17f,
                .29f, .29f, .32f);
        truckPaint(mesh, body);
        truckChassis(mesh, shape);
        for (int side = -1; side <= 1; side += 2) {
            for (int axle = -1; axle <= 1; axle += 2) truckFender(mesh, shape, side, axle, paint);
            truckPipes(mesh, shape, side);
            detail(mesh, shape, 0xaa251c, side * 1.20f, 1.27f, -.65f, .16f, 1.51f, .66f, .094f);
            detail(mesh, shape, 0x7d201b, side * 1.07f, 2.11f, -.57f, .068f, 2.19f, -.32f, .035f);
            detail(mesh, shape, 0xffbf52, side * 1.10f, 2.145f, -.542f, .044f, 2.178f, -.363f, .014f);
            detail(mesh, shape, 0x321f29, side * 1.06f, 2.19f, .48f, .17f, 2.35f, .73f, .072f);
            detail(mesh, shape, 0xf84926, side * 1.13f, 2.235f, .51f, .12f, 2.372f, .70f, .061f);
            detail(mesh, shape, 0x401c24, side * .83f, 1.66f, -2.57f, .25f, 1.91f, -2.39f, .090f);
            detail(mesh, shape, 0xf76529, side * .85f, 1.742f, -2.585f, .184f, 1.87f, -2.50f, .044f);
            detail(mesh, shape, 0xffaf49, side * .86f, 1.80f, -2.60f, .15f, 1.835f, -2.55f, .015f);
            // Round inset lamps, with an amber bezel and a warm lens.
            truckTube(mesh, shape, Builder.point(side * .88f, 1.83f, 2.35f),
                    Builder.point(side * .88f, 1.88f, 2.55f),
                    new float[][] {{0, .23f}, {.55f, .215f}, {.88f, .175f},
                            {.95f, .133f}, {1, .102f}, {1.06f, 0}},
                    new int[] {0x88231c, 0xe76c20, 0xffbf45, 0xffdf95, 0xfff3d4});
        }
        detail(mesh, shape, 0x93241b, 0, 1.57f, -2.584f, .69f, 1.94f, -2.41f, .096f);
        detail(mesh, shape, 0xf2471d, 0, 1.62f, -2.603f, .62f, 1.898f, -2.48f, .074f);
        detail(mesh, shape, 0x51252c, 0, 1.817f, -2.623f, .18f, 1.890f, -2.56f, .029f);
        detail(mesh, shape, 0xffba45, 0, 1.684f, -2.627f, .43f, 1.731f, -2.55f, .021f);

        detail(mesh, shape, 0xe78b23, 0, 1.54f, 2.37f, .65f, 1.98f, 2.594f, .125f);
        detail(mesh, shape, 0x252732, 0, 1.60f, 2.50f, .54f, 1.927f, 2.615f, .090f);
        for (int bar = -3; bar <= 3; bar++) {
            float top = 1.913f - .0045f * bar * bar;
            detail(mesh, shape, bar == 0 ? 0xeca43f : 0x73818a, bar * .128f,
                    1.649f, 2.606f, .023f, top, 2.634f, .012f);
        }
        detail(mesh, shape, 0x44222a, 0, 1.29f, 2.30f, 1.10f, 1.59f, 2.69f, .141f);
        detail(mesh, shape, 0xf84724, 0, 1.44f, 2.43f, 1.08f, 1.61f, 2.705f, .079f);
        detail(mesh, shape, 0x547784, 0, 1.23f, 2.44f, .56f, 1.40f, 2.708f, .072f);
        detail(mesh, shape, 0x41232c, 0, 1.29f, -2.72f, 1.09f, 1.59f, -2.36f, .133f);
        detail(mesh, shape, 0x62818d, 0, 1.325f, -2.757f, .70f, 1.48f, -2.60f, .060f);
    }

    private static void truckPaint(Builder mesh, VehiclePanel body) {
        // Each pair of edges traces a tapered tongue directly on the hood or quarter panel.
        for (int side = 0; side <= 1; side++) {
            float mirror = side == 0 ? -1 : 1;
            paintRibbon(mesh, body, 1, 1, new float[][] {
                    {.94f, .5f + mirror * .19f, .94f, .5f + mirror * .33f},
                    {.88f, .5f + mirror * .13f, .88f, .5f + mirror * .35f},
                    {.82f, .5f + mirror * .09f, .82f, .5f + mirror * .26f},
                    {.77f, .5f + mirror * .11f, .77f, .5f + mirror * .195f},
                    {.72f, .5f + mirror * .18f, .72f, .5f + mirror * .24f},
                    {.68f, .5f + mirror * .23f, .68f, .5f + mirror * .23f}
            }, 0xffa227, 0xffdc52);
            paintRibbon(mesh, body, 1, 1, new float[][] {
                    {.90f, .5f + mirror * .08f, .90f, .5f + mirror * .13f},
                    {.84f, .5f + mirror * .04f, .84f, .5f + mirror * .118f},
                    {.78f, .5f + mirror * .05f, .78f, .5f + mirror * .095f},
                    {.728f, .5f + mirror * .082f, .728f, .5f + mirror * .082f}
            }, 0xffb22a, 0xffed69);
            paintRibbon(mesh, body, 0, side, new float[][] {
                    {.84f, .76f, .93f, .76f}, {.60f, .67f, .89f, .67f},
                    {.34f, .57f, .80f, .57f}, {.37f, .49f, .73f, .49f},
                    {.58f, .43f, .76f, .43f}, {.62f, .35f, .72f, .35f},
                    {.50f, .29f, .50f, .29f}
            }, 0xff951a, 0xffcf45);
            paintRibbon(mesh, body, 0, side, new float[][] {
                    {.35f, .59f, .62f, .59f}, {.23f, .52f, .40f, .52f},
                    {.19f, .46f, .27f, .46f}, {.27f, .39f, .34f, .39f},
                    {.30f, .34f, .30f, .34f}
            }, 0xf98b22, 0xffbc30);
        }
    }

    private static void paintRibbon(Builder mesh, VehiclePanel surface, int axis, int side,
                                    float[][] edges, int base, int highlight) {
        int across = 6, perSegment = 10;
        float[][] previous = new float[across + 1][3], current = new float[across + 1][3];
        float[] boundaries = new float[4];
        int[] colors = new int[across + 1];
        for (int i = 0; i <= across; i++) colors[i] = mixPaint(base, highlight, i / (float) across);
        int steps = (edges.length - 1) * perSegment;
        for (int step = 0; step <= steps; step++) {
            int segment = Math.min(edges.length - 2, step / perSegment);
            float t = (step - segment * perSegment) / (float) perSegment;
            float[] a = edges[Math.max(0, segment - 1)], b = edges[segment];
            float[] c = edges[segment + 1], d = edges[Math.min(edges.length - 1, segment + 2)];
            for (int i = 0; i < boundaries.length; i++) {
                float incoming = .5f * (c[i] - a[i]);
                float outgoing = .5f * (d[i] - b[i]);
                boundaries[i] = b[i] + (c[i] - b[i]) * smooth(t)
                        + t * (1 - t) * ((1 - t) * incoming - t * outgoing);
            }
            for (int i = 0; i <= across; i++) {
                float f = i / (float) across;
                surface.paintPoint(axis, side, boundaries[0] + f * (boundaries[2] - boundaries[0]),
                        boundaries[1] + f * (boundaries[3] - boundaries[1]), current[i]);
            }
            if (step > 0) {
                for (int i = 0; i < across; i++) {
                    mesh.triangle(previous[i], current[i], current[i + 1], colors[i], colors[i], colors[i + 1]);
                    mesh.triangle(previous[i], current[i + 1], previous[i + 1],
                            colors[i], colors[i + 1], colors[i + 1]);
                }
            }
            float[][] swap = previous;
            previous = current;
            current = swap;
        }
    }

    private static int mixPaint(int base, int highlight, float fraction) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int start = (base >> shift) & 255, end = (highlight >> shift) & 255;
            color |= Math.round(start + (end - start) * fraction) << shift;
        }
        return color;
    }

    private static void truckChassis(Builder mesh, Deformation shape) {
        detail(mesh, shape, 0x28303e, 0, .91f, -.87f, .53f, 1.35f, .96f, .12f);
        detail(mesh, shape, 0x536675, 0, .88f, -.51f, .34f, .99f, .57f, .053f);
        for (int axle = -1; axle <= 1; axle += 2) {
            float z = axle * shape.axle;
            detail(mesh, shape, 0x4a6473, 0, .735f, z - .185f, .235f, 1.08f, z + .185f, .12f);
        }
        for (int side = -1; side <= 1; side += 2) {
            detail(mesh, shape, 0x323239, side * .57f, 1.01f, -2.20f, .14f, 1.40f, 2.22f, .095f);
            for (int axle = -1; axle <= 1; axle += 2) {
                float z = axle * shape.axle;
                truckRod(mesh, shape, Builder.point(0, shape.wheelY, z),
                        Builder.point(side * 1.38f, shape.wheelY, z), .091f, 0x455665);
                truckRod(mesh, shape, Builder.point(side * .54f, .98f, axle * .31f),
                        Builder.point(side * 1.25f, .79f, z), .048f, 0x748d97);
                truckRod(mesh, shape, Builder.point(side * 1.28f, .66f, axle * .69f),
                        Builder.point(side * 1.22f, .71f, z), .051f, 0x435a69);
                truckShock(mesh, shape, side, axle);
            }
        }
    }

    private static void truckShock(Builder mesh, Deformation shape, int side, int axle) {
        float x = side * 1.28f, y = .66f, z = axle * .69f;
        float height = .83f, lean = axle * -.15f, inward = side * -.15f;
        float[] lower = Builder.point(x, y, z), upper = Builder.point(x + inward, y + height, z + lean);
        truckTube(mesh, shape, lower, upper,
                new float[][] {{0, .11f}, {.12f, .11f}, {.15f, .066f}, {.44f, .066f},
                        {.46f, .039f}, {.84f, .039f}, {.90f, .105f}, {1, .105f}},
                new int[] {0x276e92, 0x59bccf, 0x225374, 0x5ab4cc, 0xa0dbe3, 0x4ca9c5, 0x2b789d});

        int segments = 48, facets = 6;
        float[][] previous = new float[facets][3], current = new float[facets][3];
        int[] colors = {0x92f1f6, 0x48ccf3, 0x238abf, 0x174e83, 0x226d99, 0x46a7d0};
        for (int step = 0; step <= segments; step++) {
            float fraction = step / (float) segments;
            float along = .10f + fraction * .82f;
            double angle = fraction * 8 * Math.PI;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            for (int facet = 0; facet < facets; facet++) {
                double around = facet * 2 * Math.PI / facets;
                float radius = .133f + .029f * (float) Math.sin(around);
                shape.apply(x + along * inward + radius * cos,
                        y + along * height + .029f * (float) Math.cos(around),
                        z + along * lean + radius * sin, current[facet]);
            }
            if (step > 0) {
                for (int facet = 0; facet < facets; facet++) {
                    int next = (facet + 1) % facets;
                    mesh.triangle(previous[facet], current[facet], current[next],
                            colors[facet], colors[facet], colors[next]);
                    mesh.triangle(previous[facet], current[next], previous[next],
                            colors[facet], colors[next], colors[next]);
                }
            }
            float[][] swap = previous;
            previous = current;
            current = swap;
        }
    }

    private static void truckPipes(Builder mesh, Deformation shape, int side) {
        truckRod(mesh, shape, Builder.point(side * .97f, 1.32f, .38f),
                Builder.point(side * 1.37f, 1.16f, .20f), .081f, 0x237ba6);
        truckRod(mesh, shape, Builder.point(side * 1.07f, 1.35f, -.53f),
                Builder.point(side * 1.36f, 1.18f, -.53f), .051f, 0x506773);
        // A rolled lip and recessed end make the blue exhaust read as a hollow pipe.
        truckTube(mesh, shape, Builder.point(side * 1.36f, 1.16f, .26f),
                Builder.point(side * 1.40f, 1.24f, -.86f),
                new float[][] {{0, .070f}, {.10f, .078f}, {.13f, .130f}, {.19f, .141f},
                        {.27f, .141f}, {.29f, .127f}, {.67f, .127f}, {.70f, .146f},
                        {.80f, .146f}, {.83f, .113f}, {.95f, .113f}, {.98f, .143f},
                        {1.02f, .142f}, {1.04f, .121f}, {1.035f, .094f}, {.99f, .079f}, {.98f, 0}},
                new int[] {0x236d9d, 0x297eaf, 0x47b7dc, 0x83d7e5, 0x47a9cd, 0x248ebb,
                        0x277d9d, 0x7bcde2, 0x46a4c7, 0x39b1d2, 0x4daed0, 0x7bddf3,
                        0x54bcdc, 0x28758c, 0x19394c, 0x132833});
    }

    private static void truckRod(Builder mesh, Deformation shape, float[] from, float[] to,
                                 float radius, int color) {
        truckTube(mesh, shape, from, to, new float[][] {{0, 0}, {0, radius}, {1, radius}, {1, 0}},
                new int[] {color, color, color});
    }

    private static void truckTube(Builder mesh, Deformation shape, float[] from, float[] to,
                                  float[][] profile, int[] colors) {
        float dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Use a crosswise axis unless the tube itself is nearly crosswise.
        boolean crosswise = Math.abs(dx) > .85f * length;
        float ux = crosswise ? -dz : 0, uy = crosswise ? 0 : dz, uz = crosswise ? dx : -dy;
        float scale = 1 / (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux *= scale;
        uy *= scale;
        uz *= scale;
        float vx = (dy * uz - dz * uy) / length, vy = (dz * ux - dx * uz) / length;
        float vz = (dx * uy - dy * ux) / length;
        int facets = 12;
        float[][] previous = new float[facets][3], current = new float[facets][3];
        float[] light = new float[facets];
        for (int ring = 0; ring < profile.length; ring++) {
            float along = profile[ring][0], radius = profile[ring][1];
            for (int facet = 0; facet < facets; facet++) {
                double angle = facet * 2 * Math.PI / facets;
                float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
                float nx = ux * cos + vx * sin, ny = uy * cos + vy * sin;
                float nz = uz * cos + vz * sin;
                light[facet] = .76f + .39f * Math.max(0, -.38f * nx + .80f * ny + .47f * nz);
                shape.apply(from[0] + along * dx + radius * nx, from[1] + along * dy + radius * ny,
                        from[2] + along * dz + radius * nz, current[facet]);
            }
            if (ring > 0) {
                int color = colors[ring - 1];
                for (int facet = 0; facet < facets; facet++) {
                    int next = (facet + 1) % facets;
                    int a = shade(color, light[facet]), b = shade(color, light[next]);
                    if (profile[ring - 1][1] > 0) {
                        mesh.triangle(previous[facet], previous[next], current[facet], a, b, a);
                    }
                    if (radius > 0) mesh.triangle(previous[next], current[next], current[facet], b, b, a);
                }
            }
            float[][] swap = previous;
            previous = current;
            current = swap;
        }
    }

    private static void truckFender(Builder mesh, Deformation shape, int side, int axle, int paint) {
        // Cross-section runs from the painted shoulder across the lip to the wheel-well liner.
        float[] widths = {1.14f, 1.30f, 1.48f, 1.625f, 1.676f, 1.687f, 1.65f, 1.54f, 1.28f};
        float[] radii = {1.04f, 1.186f, 1.215f, 1.171f, 1.128f, 1.055f, 1.01f, .978f, .974f};
        int[] colors = {paint, paint, 0xff5730, 0xed8622, 0xffdf62, 0xffaf2d, 0x9e461c, 0x3c2b31, 0x28262a};
        float[][] previous = new float[widths.length][3];
        float[][] current = new float[widths.length][3];
        int segments = 22;
        for (int step = 0; step <= segments; step++) {
            float fraction = step / (float) segments;
            float angle = .36f + 2.42f * fraction;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            float fade = smooth(Math.min(fraction, 1 - fraction) * 7f);
            for (int ring = 0; ring < widths.length; ring++) {
                float radius = .982f + (radii[ring] - .982f) * fade;
                float x = 1.15f + (widths[ring] - 1.15f) * fade;
                shape.apply(side * x, shape.wheelY + radius * sin, axle * shape.axle + radius * cos,
                        current[ring]);
            }
            if (step > 0) {
                float now = .66f + .37f * sin + .08f * cos;
                float a = angle - 2.42f / segments;
                float before = .66f + .37f * (float) Math.sin(a) + .08f * (float) Math.cos(a);
                for (int ring = 0; ring + 1 < widths.length; ring++) {
                    int outerBefore = shade(colors[ring], before);
                    int innerBefore = shade(colors[ring + 1], before);
                    int outerNow = shade(colors[ring], now);
                    int innerNow = shade(colors[ring + 1], now);
                    mesh.triangle(previous[ring], current[ring], current[ring + 1],
                            outerBefore, outerNow, innerNow);
                    mesh.triangle(previous[ring], current[ring + 1], previous[ring + 1],
                            outerBefore, innerNow, innerBefore);
                }
            }
            float[][] swap = previous;
            previous = current;
            current = swap;
        }
    }

    private static void car(Builder mesh, Deformation shape, int paint) {
        panel(mesh, shape, Panel.BODY, paint, 0, .34f, -1.97f, .94f, .91f, 1.96f,
                .13f, .072f, .20f);
        panel(mesh, shape, Panel.CABIN, paint, 0, .80f, -1.47f, .88f, 1.43f, 1.02f,
                .17f, .145f, .205f);
        detail(mesh, shape, shade(paint, .67f), 0, .335f, -.78f, .89f, .43f, .78f, .038f);
        detail(mesh, shape, shade(paint, .85f), 0, .38f, 1.84f, .80f, .54f, 2.015f, .064f);
        detail(mesh, shape, shade(paint, .82f), 0, .385f, -2.01f, .81f, .54f, -1.87f, .055f);
        detail(mesh, shape, 0x29323a, 0, .49f, 1.95f, .40f, .595f, 2.02f, .030f);
        detail(mesh, shape, 0x3f4b53, 0, .49f, -2.02f, .19f, .59f, -1.94f, .026f);
        detail(mesh, shape, 0xcad2d2, 0, .51f, -2.027f, .143f, .568f, -1.997f, .014f);
        for (int side = -1; side <= 1; side += 2) {
            detail(mesh, shape, 0xfff0ce, side * .64f, .602f, 1.82f, .207f, .727f, 1.98f, .048f);
            detail(mesh, shape, 0xd94252, side * .64f, .59f, -1.99f, .20f, .727f, -1.885f, .04f);
            detail(mesh, shape, shade(paint, .59f), side * .922f, .755f, -.26f, .018f, .793f, -.09f, .014f);
        }
    }

    private enum Panel { BODY, CABIN, DETAIL }

    private static void detail(Builder mesh, Deformation shape, int color, float x,
                               float y0, float z0, float width, float y1, float z1, float radius) {
        panel(mesh, shape, Panel.DETAIL, color, x, y0, z0, width, y1, z1, radius, radius, radius);
    }

    private static VehiclePanel panel(Builder mesh, Deformation shape, Panel kind, int color, float x,
                                      float y0, float z0, float width, float y1, float z1,
                                      float bevelX, float bevelY, float bevelZ) {
        VehiclePanel panel = new VehiclePanel(shape, kind, color, new float[] {x - width, y0, z0},
                new float[] {2 * width, y1 - y0, z1 - z0}, new float[] {bevelX, bevelY, bevelZ});
        for (int axis = 0; axis < 3; axis++) {
            panel.draw(mesh, axis, 0);
            panel.draw(mesh, axis, 1);
        }
        return panel;
    }

    private static final class VehiclePanel {
        private final Deformation shape;
        private final Panel kind;
        private final int paint;
        private final float[] origin, span, bevel, border;
        private final float[][] grid = new float[3][];
        private final float[][][] paintFaces;

        VehiclePanel(Deformation shape, Panel kind, int paint, float[] origin, float[] span,
                     float[] bevel) {
            this.shape = shape;
            this.kind = kind;
            this.paint = paint;
            this.origin = origin;
            this.span = span;
            this.bevel = bevel;
            paintFaces = kind == Panel.BODY && shape.truck ? new float[4][][] : null;
            border = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                bevel[axis] = Math.min(bevel[axis], span[axis] * .47f);
                border[axis] = bevel[axis] / span[axis];
                grid[axis] = grid(axis);
            }
        }

        private float[] grid(int axis) {
            float edge = border[axis];
            // Identical sampling along shared edges keeps the six patches closed.
            float[] samples = new float[64];
            int count = 0;
            samples[count++] = edge;
            samples[count++] = 1 - edge;
            if (kind != Panel.DETAIL || bevel[axis] >= .06f) {
                samples[count++] = edge * .50f;
                samples[count++] = 1 - edge * .50f;
            }
            float spacing = kind == Panel.DETAIL ? .47f : .29f;
            int divisions = Math.max(1, (int) Math.ceil(span[axis] / spacing));
            for (int i = 0; i <= divisions; i++) samples[count++] = i / (float) divisions;
            if (kind == Panel.CABIN) {
                float[] frame = axis == 1 ? new float[] {.16f, .20f, .79f, .83f}
                        : axis == 0 ? new float[] {.08f, .11f, .89f, .92f}
                        : new float[] {.09f, .12f, .445f, .47f, .52f, .545f, .88f, .91f};
                for (float line : frame) samples[count++] = line;
            } else if (kind == Panel.BODY && axis == 2) {
                for (int side = -1; side <= 1; side += 2) {
                    for (int step = -4; step <= 4; step++) {
                        float z = side * shape.axle + step * shape.archRadius / 4;
                        samples[count++] = unit((z - origin[2]) / span[2]);
                    }
                }
                if (shape.truck) {
                    for (float z : new float[] {-2.43f, -2.34f, -2.25f, -1.57f, -1.48f, -1.32f}) {
                        samples[count++] = (z - origin[2]) / span[2];
                    }
                }
            } else if (kind == Panel.BODY && shape.truck && axis == 0) {
                for (float x : new float[] {-1.24f, -1.10f, -.87f, -.63f, -.39f, -.15f,
                        .15f, .39f, .63f, .87f, 1.10f, 1.24f}) {
                    samples[count++] = (x - origin[0]) / span[0];
                }
            }
            Arrays.sort(samples, 0, count);
            int distinct = 0;
            for (int i = 0; i < count; i++) {
                if (distinct == 0 || samples[i] - samples[distinct - 1] > .0001f) {
                    samples[distinct++] = samples[i];
                }
            }
            return Arrays.copyOf(samples, distinct);
        }

        void draw(Builder mesh, int axis, int side) {
            int uAxis = (axis + 1) % 3, vAxis = (axis + 2) % 3;
            float[] u = grid[uAxis], v = grid[vAxis];
            float[][] points = new float[u.length * v.length][3];
            float[] light = new float[points.length];
            float[] reflection = kind == Panel.CABIN && axis != 1 ? new float[points.length] : null;
            float[] param = new float[3];
            float[] before = new float[3], after = new float[3], tangent = new float[3];
            param[axis] = side;
            for (int i = 0; i < u.length; i++) {
                for (int j = 0; j < v.length; j++) {
                    int index = i * v.length + j;
                    param[uAxis] = u[i];
                    param[vAxis] = v[j];
                    point(param, points[index]);
                    // Differentiate the actual crumpled surface, including the bevels.
                    derivative(param, uAxis, before, after);
                    for (int k = 0; k < 3; k++) tangent[k] = after[k] - before[k];
                    derivative(param, vAxis, before, after);
                    float dx = after[0] - before[0], dy = after[1] - before[1], dz = after[2] - before[2];
                    float nx = tangent[1] * dz - tangent[2] * dy;
                    float ny = tangent[2] * dx - tangent[0] * dz;
                    float nz = tangent[0] * dy - tangent[1] * dx;
                    float scale = (side == 0 ? -1f : 1f)
                            / Math.max(1e-12f, (float) Math.sqrt(nx*nx + ny*ny + nz*nz));
                    nx *= scale;
                    ny *= scale;
                    nz *= scale;
                    float diffuse = Math.max(0, -.48f * nx + .77f * ny + .42f * nz);
                    float sheen = unit(.86f * ny + .38f * nz - .24f * nx);
                    light[index] = .66f + .43f * diffuse + .15f * sheen * sheen * sheen;
                    if (reflection != null) {
                        float sky = unit((param[1] - .20f) / .59f);
                        float along = param[axis == 0 ? 2 : 0];
                        reflection[index] = unit(sky * .68f + .17f * unit(ny)
                                + .20f * ridge(sky + .54f * along - .82f, .31f));
                    }
                }
            }
            if (paintFaces != null && axis < 2) paintFaces[axis * 2 + side] = points;
            for (int i = 0; i + 1 < u.length; i++) {
                for (int j = 0; j + 1 < v.length; j++) {
                    int a = i * v.length + j, b = a + v.length, c = b + 1, d = a + 1;
                    param[uAxis] = (u[i] + u[i + 1]) * .5f;
                    param[vAxis] = (v[j] + v[j + 1]) * .5f;
                    int color = material(axis, param);
                    boolean glass = reflection != null && color == GLASS;
                    int ca = glass ? glassColor(reflection[a]) : shade(color, light[a]);
                    int cb = glass ? glassColor(reflection[b]) : shade(color, light[b]);
                    int cc = glass ? glassColor(reflection[c]) : shade(color, light[c]);
                    int cd = glass ? glassColor(reflection[d]) : shade(color, light[d]);
                    if (side == 1) {
                        mesh.triangle(points[a], points[b], points[c], ca, cb, cc);
                        mesh.triangle(points[a], points[c], points[d], ca, cc, cd);
                    } else {
                        mesh.triangle(points[a], points[c], points[b], ca, cc, cb);
                        mesh.triangle(points[a], points[d], points[c], ca, cd, cc);
                    }
                }
            }
        }

        void paintPoint(int axis, int side, float uAt, float vAt, float[] out) {
            // Follow the actual deformed triangles, including the creases across the hood.
            float[] u = grid[(axis + 1) % 3], v = grid[(axis + 2) % 3];
            int i = gridCell(u, uAt), j = gridCell(v, vAt);
            float s = (uAt - u[i]) / (u[i + 1] - u[i]);
            float t = (vAt - v[j]) / (v[j + 1] - v[j]);
            float[][] points = paintFaces[axis * 2 + side];
            int index = i * v.length + j;
            float[] a = points[index], c = points[index + v.length + 1];
            float[] b = points[index + (s >= t ? v.length : 1)];
            float low = Math.min(s, t), high = Math.max(s, t);
            for (int k = 0; k < 3; k++) out[k] = a[k] * (1 - high) + b[k] * (high - low) + c[k] * low;
            out[axis] += side == 0 ? -.014f : .014f;
        }

        private static int gridCell(float[] grid, float at) {
            int index = Arrays.binarySearch(grid, at);
            return index >= 0 ? Math.min(index, grid.length - 2) : Math.max(0, -index - 2);
        }

        private int material(int axis, float[] param) {
            if (kind == Panel.BODY && shape.truck && axis == 1 && param[1] == 1) {
                float z = origin[2] + span[2] * param[2];
                float x = origin[0] + span[0] * param[0];
                if (z > -2.34f && z < -1.48f && Math.abs(x) < 1.10f) return 0x403b3e;
            }
            if (kind != Panel.CABIN || axis == 1) return paint;
            float height = param[1];
            float across = param[axis == 0 ? 2 : 0];
            if (height < .16f || height > .83f) return paint;
            if (axis == 0) {
                if (across < .09f || across > .91f) return paint;
                if (across < .12f || across > .88f || (across > .445f && across < .545f)) return TRIM;
            } else {
                if (across < .08f || across > .92f) return paint;
                if (across < .11f || across > .89f) return TRIM;
            }
            if (height < .20f || height > .79f) return TRIM;
            return GLASS;
        }

        private int glassColor(float reflection) {
            if (shape.truck) {
                int r = Math.round(16 + 49 * reflection);
                int g = Math.round(25 + 64 * reflection);
                int b = Math.round(38 + 78 * reflection);
                return (r << 16) | (g << 8) | b;
            }
            int r = Math.round(27 + 86 * reflection);
            int g = Math.round(53 + 108 * reflection);
            int b = Math.round(69 + 116 * reflection);
            return (r << 16) | (g << 8) | b;
        }

        private void derivative(float[] param, int axis, float[] before, float[] after) {
            float at = param[axis];
            param[axis] = Math.max(0, at - .002f);
            point(param, before);
            param[axis] = Math.min(1, at + .002f);
            point(param, after);
            param[axis] = at;
        }

        private void point(float[] param, float[] out) {
            // Project the corner of the parametric box onto an ellipsoidal fillet.
            float lengthSquared = 0;
            for (int k = 0; k < 3; k++) {
                float inset = Math.max(border[k], Math.min(1 - border[k], param[k]));
                out[k] = (param[k] - inset) / border[k];
                lengthSquared += out[k] * out[k];
            }
            float scale = 1 / (float) Math.sqrt(lengthSquared);
            for (int k = 0; k < 3; k++) {
                out[k] = origin[k] + span[k] * param[k] + bevel[k] * out[k] * (scale - 1);
            }
            float x = out[0], y = out[1], z = out[2];
            float height = (y - origin[1]) / span[1];
            if (kind == Panel.BODY) {
                float across = Math.abs(x) / shape.halfWidth;
                float end = Math.abs(z) / shape.halfLength;
                float wheelDistance = Math.abs(Math.abs(z) - shape.axle);
                float wheelProfile = (float) Math.sqrt(Math.max(0,
                        shape.archRadius * shape.archRadius - wheelDistance * wheelDistance));
                float opening = Math.max(0, shape.wheelY + wheelProfile - origin[1]);
                float shoulder = unit((across - (shape.truck ? .64f : .61f)) * 6);
                y += opening * shoulder * (1 - height);
                float taper = shape.truck ? .135f : z > 0 ? .185f : .125f;
                y -= taper * height * end * end * end;
                y -= (shape.truck ? .065f : .083f) * height * across * across;
                x *= 1 - .10f * end * end * end - .09f * (1 - height)
                        + (shape.truck ? .12f : .057f) * ridge(wheelDistance, shape.archRadius * 1.15f);
                if (shape.truck) {
                    float bed = smooth((-z - 1.32f) / .24f) * smooth((z + 2.43f) / .18f)
                            * smooth((1.16f - Math.abs(x)) / .25f);
                    float rib = ridge(Math.abs(x) % .24f - .15f, .065f);
                    y -= (.32f - .036f * rib) * bed * height * height;
                    float hood = smooth((z - .12f) / 1.02f) * smooth((2.63f - z) / .75f);
                    y += height * (hood * (.27f + .048f * ridge(x, .53f))
                            * (1 - across * across) + .075f * shoulder * ridge(wheelDistance, 1.06f));
                    x += Math.copySign(.044f * ridge(z + .22f, 1.13f) * shoulder
                            * ridge(height - .65f, .57f), x);
                    z -= Math.copySign(.17f * unit(end * end * end) * across * across, z);
                } else {
                    y += .054f * height * ridge(z - 1.23f, .71f) * (1 - across * across);
                }
            } else if (kind == Panel.CABIN) {
                x *= 1 - (shape.truck ? .31f : .29f) * height * height;
                float along = (z - origin[2]) / span[2];
                float rake = height * (.83f + .17f * height);
                z += rake * ((shape.truck ? .42f : .83f) * (1 - along)
                        - (shape.truck ? .78f : .86f) * along);
                float across = x / shape.halfWidth;
                y += height * (.045f - .13f * across * across
                        - (shape.truck ? .15f : .19f) * (2 * along - 1) * (2 * along - 1));
                z += (shape.truck ? .14f : .105f) * (2 * along - 1)
                        * (1 - across * across) * ridge(height - .47f, .53f);
            }
            shape.apply(x, y, z, out);
        }
    }

    private static final class Deformation {
        private final boolean truck;
        private final float halfWidth;
        private final float halfLength;
        private final float baseY;
        private final float beltY;
        private final float roofHeight;
        private final float axle;
        private final float wheelY;
        private final float archRadius;
        private final float front;
        private final float back;
        private final float left;
        private final float right;
        private final float damage;

        Deformation(boolean truck, float front, float back, float left, float right, float damage) {
            this.truck = truck;
            halfWidth = truck ? 1.43f : .94f;
            halfLength = truck ? 2.55f : 1.97f;
            baseY = truck ? 1.25f : .33f;
            beltY = truck ? 2.02f : .88f;
            roofHeight = truck ? .99f : .61f;
            axle = truck ? 1.69f : 1.29f;
            wheelY = truck ? .92f : .35f;
            archRadius = truck ? .975f : .388f;
            this.front = unit(front);
            this.back = unit(back);
            this.left = unit(left);
            this.right = unit(right);
            this.damage = unit(damage);
        }

        void apply(float x, float y, float z, float[] out) {
            if (front == 0 && back == 0 && left == 0 && right == 0 && damage == 0) {
                out[0] = x;
                out[1] = y;
                out[2] = z;
                return;
            }
            float nx = x / halfWidth;
            float nz = z / halfLength;
            float end = unit((Math.abs(nz) - .20f) / .80f);
            float side = unit((Math.abs(nx) - .23f) / .77f);
            float localEnd = (z >= 0 ? front : back) * end * end;
            float localSide = (x >= 0 ? right : left) * side * side;
            float acrossImpact = .66f + .34f * unit(.61f - nx * (z >= 0 ? .38f : -.31f));
            float sideImpact = ridge(nz - (x >= 0 ? .18f : -.16f), .87f);
            float panelY = (y - baseY) / (beltY - baseY);

            // Opposing ridges and valleys buckle the nose and the struck door or quarter panel.
            float endFold = ridge(end + .10f * nx - .67f, .15f)
                    - .82f * ridge(end + .10f * nx - .86f, .13f);
            float sideFold = ridge(nz + .19f * panelY - .32f, .21f)
                    - .78f * ridge(nz + .23f * panelY - .06f, .18f);
            out[0] = x - Math.copySign(halfWidth, x) * localSide
                    * (.23f * sideImpact - .088f * sideFold)
                    + halfWidth * .063f * nx * localEnd * endFold;
            out[2] = z - Math.copySign(halfLength * .27f * localEnd * acrossImpact, z)
                    + halfLength * .045f * localSide * sideFold;
            out[1] = y - (truck ? .115f : .14f) * damage
                    + (beltY - baseY) * localEnd * (.57f * endFold - .26f * acrossImpact)
                    + (beltY - baseY) * localSide * (.20f * sideFold - .15f * sideImpact);

            float aboveBelt = unit((y - beltY) / roofHeight);
            float collapse = unit((damage - .22f) / .78f);
            float roofBowl = ridge(nx + .16f, 1.0f) * ridge(nz - .12f, .57f);
            float roofCrease = ridge(nz + .24f * nx + .12f, .19f);
            float roofDrop = collapse * (.43f + .49f * roofBowl - .14f * roofCrease)
                    + .11f * (left + right) + .09f * (front + back);
            out[1] -= roofHeight * aboveBelt * Math.min(.96f, roofDrop);
            out[1] = Math.max(.035f, out[1]);
            out[0] += halfWidth * .11f * aboveBelt * (left - right);
            out[2] += halfLength * .07f * aboveBelt * (back - front);
        }
    }

    private static float ridge(float offset, float width) {
        return smooth(1 - Math.abs(offset) / width);
    }

    private static float smooth(float value) {
        float t = unit(value);
        return t * t * (3 - 2 * t);
    }

    private static float unit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int shade(int color, float amount) {
        int r = Math.min(255, Math.round(((color >> 16) & 255) * amount));
        int g = Math.min(255, Math.round(((color >> 8) & 255) * amount));
        int b = Math.min(255, Math.round((color & 255) * amount));
        return (r << 16) | (g << 8) | b;
    }

    private static final class Builder {
        private static final int STRIDE = 6;
        private float[] vertices = new float[STRIDE * 256];
        private int size;

        void flat(float x0, float z0, float x1, float z1, float y, int color) {
            quad(point(x0, y, z1), point(x1, y, z1), point(x1, y, z0), point(x0, y, z0), color);
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            float minX = Math.min(x0, x1);
            float maxX = Math.max(x0, x1);
            float minZ = Math.min(z0, z1);
            float maxZ = Math.max(z0, z1);
            flat(minX, minZ, maxX, maxZ, y1, shade(color, 1.13f));
            wallZ(maxZ, y0, y1, minX, maxX, color, true);
            wallZ(minZ, y0, y1, minX, maxX, shade(color, 0.91f), false);
            wallX(minX, y0, y1, minZ, maxZ, shade(color, 0.85f), false);
            wallX(maxX, y0, y1, minZ, maxZ, shade(color, 0.77f), true);
        }

        void wallX(float x, float bottom, float top, float z0, float z1, int color, boolean positive) {
            float start = positive ? z1 : z0;
            float end = positive ? z0 : z1;
            quad(point(x, bottom, start), point(x, bottom, end),
                point(x, top, end), point(x, top, start), color);
        }

        void wallZ(float z, float bottom, float top, float x0, float x1, int color, boolean positive) {
            float start = positive ? x0 : x1;
            float end = positive ? x1 : x0;
            quad(point(start, bottom, z), point(end, bottom, z),
                point(end, top, z), point(start, top, z), color);
        }

        void quad(float[] a, float[] b, float[] c, float[] d, int color) {
            triangle(a, b, c, color);
            triangle(a, c, d, color);
        }

        void triangle(float[] a, float[] b, float[] c, int color) {
            triangle(a, b, c, color, color, color);
        }

        void triangle(float[] a, float[] b, float[] c, int ca, int cb, int cc) {
            if (size + STRIDE * 3 > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            vertex(a, ca);
            vertex(b, cb);
            vertex(c, cc);
        }

        private void vertex(float[] point, int color) {
            vertices[size++] = point[0];
            vertices[size++] = point[1];
            vertices[size++] = point[2];
            vertices[size++] = ((color >> 16) & 255) / 255f;
            vertices[size++] = ((color >> 8) & 255) / 255f;
            vertices[size++] = (color & 255) / 255f;
        }

        private static float[] point(float x, float y, float z) {
            return new float[] {x, y, z};
        }

        Mesh finish() {
            FloatBuffer data = ByteBuffer.allocateDirect(size * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
            data.put(vertices, 0, size);
            data.flip();
            return new Mesh(data, size / STRIDE);
        }
    }
}
