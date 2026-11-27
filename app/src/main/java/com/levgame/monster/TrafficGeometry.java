package com.levgame.monster;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

final class TrafficGeometry {
    private static final int FRAME = 0x343c43;
    private static final int RUBBER = 0x252d34;
    private static final int GLASS = 0x394f61;
    private static final int GLASS_SKY = 0x536b7d;
    private static final int STEEL = 0xa1aeb2;
    private static final int HEADLIGHT = 0xe7e7d8;
    private static final int TAILLIGHT = 0xc9383a;
    private static final int MARKER = 0xeaa749;
    private static final int WHOLE = 0, TRACTOR = 1, TRAILER = 2;
    private static final float HITCH_Z = .42f; // In units of the original half-length.
    private static final float TRACTOR_RAIL_REAR = .23f;
    private static final float TRACTOR_BODY_REAR = .44f;
    private static final float TRAILER_NOSE = .35f;
    private static final int[] PAINT = {
        0x698ea8, 0xd7b164, 0xa4554c, 0x5c8c80, 0x77798e, 0xc9c8ba
    };

    private TrafficGeometry() {}

    static Geometry.Mesh mesh(TrafficModel type, int color, float front, float back,
                              float left, float right, float roof, boolean flattened) {
        return mesh(type, color, front, back, left, right, roof, flattened, WHOLE, 0f);
    }

    static Geometry.Mesh mesh(TrafficModel type, int color, float front, float back,
                              float left, float right, float roof, boolean flattened,
                              int part, float centerZ) {
        int component = type.hasTrailer() ? part : WHOLE;
        int paint = PAINT[Math.floorMod(color, PAINT.length)];
        Body body = new Body(type, paint, front, back, left, right, roof, flattened,
                component, centerZ);
        body.chassis();
        if (flattened) {
            body.crushed();
        } else {
            switch (type) {
                case SEDAN: body.passenger(-.69f, -.38f, .26f, .52f); break;
                case HATCHBACK: body.passenger(-.91f, -.68f, .24f, .55f); break;
                case WAGON: body.passenger(-.96f, -.86f, .24f, .53f); break;
                case CITY_BUS: body.bus(false); break;
                case COACH: body.bus(true); break;
                case BOX_TRUCK: body.truckCab(.42f, false, .79f); body.cargoBox(.31f, false); break;
                case DUMP_TRUCK: body.truckCab(.43f, false, .95f); body.dumpBed(); break;
                case SEMI_BOX:
                    if (component != TRAILER) body.truckCab(.53f, true, .85f);
                    if (component != TRACTOR) body.cargoBox(.34f, true);
                    break;
                case SEMI_FLATBED:
                    if (component != TRAILER) body.truckCab(.53f, true, 1f);
                    if (component != TRACTOR) body.flatbed();
                    break;
                case SEMI_TANKER:
                    if (component != TRAILER) body.truckCab(.53f, true, .90f);
                    if (component != TRACTOR) body.tanker();
                    break;
            }
        }
        if (component != WHOLE) body.hitch();
        return body.mesh.finish();
    }

    private static final class Body {
        final MeshBuilder mesh;
        final TrafficModel type;
        final int paint;
        final float width, length, height;
        final float front, back, left, right, roof;
        final float radius;
        final boolean flattened;
        final int part;
        final float centerZ, dentCenter, dentHalfLength;

        Body(TrafficModel type, int paint, float front, float back, float left, float right,
             float roof, boolean flattened, int part, float centerZ) {
            this.type = type;
            this.paint = paint;
            width = type.halfWidth;
            length = type.halfLength;
            height = flattened ? .62f : type.height;
            radius = type.wheelRadius;
            this.front = clamp(front);
            this.back = clamp(back);
            this.left = clamp(left);
            this.right = clamp(right);
            this.roof = clamp(roof);
            this.flattened = flattened;
            this.part = part;
            this.centerZ = centerZ;
            mesh = new MeshBuilder(centerZ);
            // Dent coordinates describe the body shell; the caller can place the mesh origin anywhere.
            float rear = part == TRACTOR ? TRACTOR_BODY_REAR : -1f;
            float nose = part == TRAILER ? TRAILER_NOSE : 1f;
            dentCenter = (rear + nose) * .5f;
            dentHalfLength = (nose - rear) * .5f;
        }

        void passenger(float rearBase, float rearRoof, float frontRoof, float frontBase) {
            float sill = .18f;
            float belt = .62f;
            // Corner, bumper, fender, cowl and deck remain separate planar stampings.
            float[][] deck = {
                {-1f, .81f, .49f}, {-.92f, .96f, .57f}, {-.71f, 1f, belt},
                {-.31f, 1f, belt}, {.27f, 1f, belt}, {.60f, 1f, belt},
                {.84f, .97f, .58f}, {1f, .82f, .48f}
            };
            shell(deck, sill, paint);
            float rearRoofWidth = type == TrafficModel.SEDAN ? .70f : .75f;
            float rearSill = type == TrafficModel.SEDAN ? belt - .025f
                    : type == TrafficModel.HATCHBACK ? .56f : .52f;
            cabin(type == TrafficModel.WAGON ? .865f : .91f, rearRoofWidth, .70f,
                    rearSill, belt - .025f, 1f, rearBase, frontBase, rearRoof, frontRoof,
                    type == TrafficModel.WAGON ? 3 : 2);
            fascia(1, .81f, .24f, .465f, false);
            fascia(-1, .80f, .24f, .475f, false);
            for (int side = -1; side <= 1; side += 2) {
                float x = side * 1.006f;
                sidePanel(x, .245f, .27f, -.36f, .36f, shade(paint, .72f));
                sidePanel(x, .36f, .585f, -.055f, -.041f, shade(paint, .66f));
                sidePanel(x, .538f, .555f, -.17f, -.10f, FRAME);
                sidePanel(x, .538f, .555f, .31f, .38f, FRAME);
                box(side * .94f, .622f, frontBase - .105f, side * 1.08f, .671f,
                        frontBase - .016f, shade(paint, .82f));
            }
        }

        void bus(boolean coach) {
            float sill = .12f;
            float belt = coach ? .49f : .45f;
            float rear = coach ? -.91f : -.96f;
            float frontRoof = coach ? .87f : .96f;
            shell(new float[][] {
                {-1f, .92f, belt}, {-.943f, 1f, belt}, {-.65f, 1f, belt},
                {-.24f, 1f, belt}, {.24f, 1f, belt}, {.65f, 1f, belt},
                {.94f, 1f, belt}, {1f, .93f, belt}
            }, sill, paint);
            cabin(.994f, .91f, .91f, belt - .012f, coach ? .985f : .94f,
                    -.997f, .997f, rear, frontRoof, coach ? 7 : 6);
            fascia(1, .90f, .145f, .39f, true);
            fascia(-1, .88f, .145f, .39f, true);
            for (int side = -1; side <= 1; side += 2) {
                float x = side * 1.005f;
                for (int section = 0; section < 4; section++) {
                    float z = -.935f + section * .4675f;
                    sidePanel(x, belt - .065f, belt - .02f, z, z + .4675f,
                            coach ? 0xc1b39c : shade(paint, 1.32f));
                }
                if (coach) {
                    for (int hatch = 0; hatch < 3; hatch++) {
                        float z = -.40f + hatch * .25f;
                        sidePanel(x, .175f, .389f, z, z + .23f, shade(paint, .90f));
                        sidePanel(x + side * .004f, .345f, .365f, z + .087f, z + .142f, FRAME);
                    }
                }
                if (side > 0) {
                    entrance(.745f, .902f, belt, coach ? .785f : .815f, coach ? .20f : .14f,
                            coach ? .985f : .94f);
                    if (!coach) entrance(-.25f, -.073f, belt, .80f, .145f, .94f);
                }
            }
            if (!coach) {
                box(-.57f, .938f, -.35f, .57f, .996f, .24f, shade(paint, 1.17f));
                for (int vent = 0; vent < 4; vent++) {
                    float z = -.31f + .05f * vent;
                    box(-.48f, .995f, z, .48f, 1f, z + .019f, FRAME);
                }
            }
        }

        void entrance(float z0, float z1, float belt, float top, float bottom, float roofTop) {
            float edge = .994f;
            float lean = (.994f - .91f) / (roofTop - belt + .012f);
            float upper = edge - (top - belt + .012f) * lean;
            int door = shade(paint, .65f);
            sidePanel(1.01f, bottom, belt, z0, z1, door);
            quad(p(edge + .018f, belt, z1), p(edge + .018f, belt, z0),
                    p(upper + .018f, top, z0), p(upper + .018f, top, z1), FRAME);
            for (int pane = 0; pane < 2; pane++) {
                float start = z0 + .008f + pane * (z1 - z0) * .5f;
                float end = start + (z1 - z0) * .5f - .016f;
                quad(p(edge + .021f, belt, end), p(edge + .021f, belt, start),
                        p(upper + .022f, top - .018f, start), p(upper + .022f, top - .018f, end), GLASS);
            }
        }

        void truckCab(float rear, boolean hood, float top) {
            float cabFront = hood ? .785f : .997f;
            float belt = top * (hood ? .63f : .64f);
            shell(new float[][] {
                {rear, .90f, belt}, {rear + .1f, .98f, belt},
                {cabFront - .04f, .98f, belt}, {cabFront, .88f, belt}
            }, .21f, paint);
            cabin(.91f, .81f, .80f, belt - .018f, top, rear + .015f, cabFront - .01f,
                    rear + .028f, cabFront - (hood ? .095f : .05f), 1);
            if (hood) {
                shell(new float[][] {{.741f, .72f, belt * .98f}, {.89f, .69f, belt * .95f},
                    {1f, .62f, belt * .85f}}, .29f, paint);
                for (int side = -1; side <= 1; side += 2) {
                    float fender = (2f * radius + .037f) / height;
                    box(side * .64f, fender, .82f, side * .96f, fender + .10f, .973f, paint);
                    endPanel(1, .975f, side * .66f, side * .91f, fender + .018f,
                            fender + .074f, HEADLIGHT);
                }
                fascia(1, .61f, .225f, belt * .83f, true);
            } else {
                fascia(1, .85f, .21f, belt - .035f, true);
            }
            for (int side = -1; side <= 1; side += 2) {
                sidePanel(side * .988f, belt - .064f, belt - .035f, rear + .084f,
                        rear + .148f, FRAME);
                box(side * .88f, .162f, rear + .031f, side * 1.01f, .202f, rear + .15f, FRAME);
                box(side * .94f, belt + .023f, cabFront - .054f, side * 1.09f,
                        belt + .088f, cabFront - .017f, FRAME);
            }
        }

        void cargoBox(float nose, boolean trailer) {
            float back = -.978f;
            float base = Math.max(.31f, (2f * radius + .09f) / height);
            int cargo = trailer ? 0xc2c1b8 : shade(paint, .95f);
            cargoFrame(back, nose, base, trailer);
            box(-.99f, base, back, .99f, .984f, nose, cargo);
            box(-1f, base, -.989f, 1f, base + .038f, nose + .004f, STEEL);
            box(-1f, .966f, -.986f, 1f, 1f, nose + .004f, shade(cargo, .87f));
            for (int side = -1; side <= 1; side += 2) {
                float x = side * .996f;
                for (int seam = 0; seam < 5; seam++) {
                    float z = mix(back, nose, .08f + seam * .21f);
                    sidePanel(x, base + .053f, .949f, z, z + .005f, shade(cargo, .82f));
                }
            }
            endPanel(-1, -.991f, -.89f, .89f, base + .056f, .944f, shade(cargo, .88f));
            for (int side = -1; side <= 1; side += 2) {
                endPanel(-1, -.994f, side * .023f, side * .88f, base + .07f, .928f, cargo);
                endPanel(-1, -.997f, side * .081f, side * .113f, base + .09f, .905f, STEEL);
                endPanel(-1, -1f, side * .11f, side * .25f, base + .12f, base + .144f, FRAME);
            }
            rearTrailerLights(-1f, base);
        }

        void dumpBed() {
            float base = (2f * radius + .065f) / height;
            float lip = .87f;
            float rear = -.967f;
            float nose = .32f;
            int bed = shade(paint, .75f);
            cargoFrame(rear, nose, base, false);
            box(-.69f, base, rear, .69f, base + .048f, nose, shade(bed, .61f));
            for (int side = -1; side <= 1; side += 2) {
                quad(p(side * .69f, base, nose), p(side * .69f, base, rear),
                        p(side * .98f, lip, rear), p(side * .98f, lip, nose), bed, side < 0);
                quad(p(side * .67f, base + .048f, rear), p(side * .67f, base + .048f, nose),
                        p(side * .94f, lip, nose), p(side * .94f, lip, rear), shade(bed, .77f), side < 0);
                box(side * .93f, lip - .035f, rear - .01f, side * 1f, lip + .014f, nose, bed);
                for (int rib = 0; rib < 5; rib++) {
                    float z = -.88f + rib * .273f;
                    quad(p(side * .71f, base + .023f, z + .034f), p(side * .71f, base + .023f, z),
                            p(side * .973f, lip - .038f, z), p(side * .973f, lip - .038f, z + .034f),
                            shade(bed, .70f), side < 0);
                }
            }
            box(-.97f, base + .016f, -.982f, .97f, lip, -.935f, bed);
            box(-.98f, base, nose - .027f, .98f, .957f, nose + .04f, bed);
            box(-1f, .937f, nose - .025f, 1f, .96f, .427f, shade(bed, 1.16f));
            rearTrailerLights(-.982f, base);
        }

        void flatbed() {
            float base = (2f * radius + .08f) / height;
            cargoFrame(-.988f, .34f, base - .045f, true);
            box(-1f, base - .074f, -.988f, 1f, base, .34f, FRAME);
            box(-.97f, base, -.982f, .97f, base + .036f, .33f, 0x8a7866);
            for (int plank = -3; plank <= 3; plank++) {
                float x = plank * .24f;
                box(x, base + .034f, -.977f, x + .014f, base + .037f, .325f, 0x5d554c);
            }
            for (int side = -1; side <= 1; side += 2) {
                for (int stake = 0; stake < 6; stake++) {
                    float z = -.89f + stake * .22f;
                    box(side * .955f, base - .065f, z, side * 1.015f, base + .048f, z + .029f, STEEL);
                    sidePanel(side * 1.008f, base - .045f, base - .023f, z + .07f, z + .122f,
                            stake % 2 == 0 ? 0xe1ddcf : TAILLIGHT);
                }
            }
            box(-.93f, base + .024f, .293f, .93f, base + .23f, .337f, FRAME);
            box(-.96f, base + .21f, .282f, .96f, base + .242f, .345f, STEEL);
            rearTrailerLights(-.988f, base);
        }

        void tanker() {
            float center = .648f;
            float back = -.931f;
            float nose = .302f;
            int tank = 0xb3bdbe;
            cargoFrame(-.95f, .329f, .30f, true);
            box(-.69f, .287f, -.95f, .69f, .416f, .329f, FRAME);
            for (int facet = 0; facet < 12; facet++) {
                int next = (facet + 1) % 12;
                tankStrip(facet, next, center, back, 1f, nose, 1f, tank);
                tankStrip(facet, next, center, -.987f, .78f, back, 1f, tank);
                tankStrip(facet, next, center, nose, 1f, .35f, .78f, tank);
                mesh.triangle(p(0f, center, -.987f), tankPoint(next, center, -.987f, .78f),
                        tankPoint(facet, center, -.987f, .78f), tank);
                mesh.triangle(p(0f, center, .35f), tankPoint(facet, center, .35f, .78f),
                        tankPoint(next, center, .35f, .78f), tank);
                for (int band = 0; band < 3; band++) {
                    float z = -.75f + band * .44f;
                    tankStrip(facet, next, center, z, 1.016f, z + .031f, 1.016f, 0x75858a);
                }
            }
            box(-.23f, .945f, -.57f, .23f, .984f, .025f, STEEL);
            box(-.21f, .973f, -.47f, .21f, 1f, -.345f, FRAME);
            for (int side = -1; side <= 1; side += 2) {
                box(-.56f + side * .13f, .34f, -.971f, -.535f + side * .13f,
                        .831f, -.92f, FRAME);
            }
            for (int rung = 0; rung < 6; rung++) {
                float y = .38f + rung * .079f;
                box(-.69f, y, -.979f, -.405f, y + .009f, -.936f, STEEL);
            }
            rearTrailerLights(-.987f, .34f);
        }

        void tankStrip(int facet, int next, float center, float z0, float scale0, float z1,
                       float scale1, int color) {
            quad(tankPoint(facet, center, z0, scale0), tankPoint(next, center, z0, scale0),
                    tankPoint(next, center, z1, scale1), tankPoint(facet, center, z1, scale1), color);
        }

        float[] tankPoint(int facet, float center, float z, float scale) {
            double angle = facet * Math.PI / 6;
            return p(.945f * scale * (float) Math.cos(angle),
                    center + .312f * scale * (float) Math.sin(angle), z);
        }

        void cargoFrame(float rear, float nose, float deck, boolean trailer) {
            float railTop = radius * 1.24f / height;
            for (int side = -1; side <= 1; side += 2) {
                box(side * .34f, railTop, rear + .045f, side * .54f, deck,
                        trailer ? nose - .11f : nose, FRAME);
            }
            if (trailer) {
                box(-.48f, railTop, nose - .11f, .48f, deck, nose + .068f, RUBBER);
            }
        }

        void rearTrailerLights(float z, float deck) {
            box(-.91f, deck - .163f, z - .011f, .91f, deck - .095f, z + .035f, FRAME);
            for (int side = -1; side <= 1; side += 2) {
                endPanel(-1, z - .014f, side * .61f, side * .83f, deck - .15f, deck - .108f, TAILLIGHT);
            }
            endPanel(-1, z - .014f, -.15f, .15f, deck - .147f, deck - .118f, STEEL);
        }

        void fascia(int end, float halfWidth, float bottom, float top, boolean heavy) {
            float face = end * 1.003f;
            float bumperTop = mix(bottom, top, heavy ? .31f : .24f);
            endPanel(end, face, -halfWidth, halfWidth, bottom, bumperTop, FRAME);
            if (end > 0) {
                endPanel(end, face, -halfWidth * .47f, halfWidth * .47f, bumperTop + .012f,
                        mix(bottom, top, .86f), RUBBER);
                if (heavy) {
                    for (int slat = 0; slat < 3; slat++) {
                        float y = mix(bottom, top, .49f + slat * .12f);
                        endPanel(end, face + .003f, -halfWidth * .41f, halfWidth * .41f,
                                y, y + .008f, STEEL);
                    }
                }
            } else {
                endPanel(end, face, -.18f, .18f, mix(bottom, top, .39f), mix(bottom, top, .59f), STEEL);
            }
            for (int side = -1; side <= 1; side += 2) {
                int light = end > 0 ? HEADLIGHT : TAILLIGHT;
                endPanel(end, face, side * halfWidth * .54f, side * halfWidth * .96f,
                        mix(bottom, top, .55f), mix(bottom, top, .93f), light);
                endPanel(end, face + end * .002f, side * halfWidth * .84f, side * halfWidth * .95f,
                        mix(bottom, top, .55f), mix(bottom, top, .66f), end > 0 ? MARKER : HEADLIGHT);
            }
        }

        void chassis() {
            float rail = Math.min(.64f, (type.wheelX - type.halfTireWidth) / width * .86f);
            float y = radius / height;
            for (int side = -1; side <= 1; side += 2) {
                if (type.hasTrailer() && (!flattened || part != WHOLE)) {
                    if (part != TRACTOR) {
                        box(side * rail * .68f, y * .72f, -.92f, side * rail, y * 1.25f, -.42f, FRAME);
                    }
                    if (part != TRAILER) {
                        box(side * rail * .68f, y * .72f, TRACTOR_RAIL_REAR,
                                side * rail, y * 1.25f, .92f, FRAME);
                    }
                } else {
                    box(side * rail * .68f, y * .72f, -.92f, side * rail, y * 1.25f, .92f, FRAME);
                }
            }
            for (float axle : type.axles) {
                if (!ownsAxle(axle)) continue;
                boolean whole = part == WHOLE;
                float z = (axle + (whole ? 0f : wheelShift(axle))) / length;
                box(-rail * 1.07f, y * .88f, z - .06f / length, rail * 1.07f,
                        y * 1.11f, z + .06f / length, FRAME, whole);
            }
        }

        boolean ownsAxle(float axle) {
            return part == WHOLE || (part == TRACTOR ? axle >= 0f : axle < 0f);
        }

        void hitch() {
            // Center the plate and kingpin on the shared yaw pivot. Keep both rigid under dents.
            float plate = (flattened ? .46f : .78f) / height;
            float plateBottom = plate - .055f / height;
            float support = (flattened ? radius * .72f : radius * 1.25f) / height;
            if (part == TRACTOR) {
                rigidBox(-.58f, support, HITCH_Z - .032f, .58f, plateBottom, HITCH_Z + .032f, FRAME);
                rigidBox(-.42f, plateBottom, HITCH_Z - .053f,
                        .42f, plate, HITCH_Z + .053f, STEEL);
            } else {
                float tongue = plate + .055f / height;
                rigidBox(-.10f, plate, HITCH_Z - .019f, .10f, tongue, HITCH_Z + .019f, STEEL);
                rigidBox(-.24f, flattened ? .30f / height : tongue, .26f,
                        .24f, tongue + .095f / height,
                        HITCH_Z + .03f, FRAME);
            }
        }

        void crushed() {
            // The collapsed deck wraps inside the tires, which stand above the damaged shell.
            float inner = (type.wheelX - type.halfTireWidth - .17f) / width;
            float[] stations = new float[5 + type.axles.length * 4];
            int count = 0;
            float rear = part == TRACTOR ? .48f : -1f;
            float nose = part == TRAILER ? TRAILER_NOSE : 1f;
            float deckCenter = (rear + nose) * .5f;
            float deckHalfLength = (nose - rear) * .5f;
            for (float z : new float[] {-1f, -.25f, .03f, .28f, 1f}) {
                stations[count++] = deckCenter + z * deckHalfLength;
            }
            for (float axle : type.axles) {
                if (!ownsAxle(axle)) continue;
                for (float delta : new float[] {-radius - .2f, -radius, radius, radius + .2f}) {
                    float z = (archCenter(axle) + delta) / length;
                    if (z > rear && z < nose) stations[count++] = z;
                }
            }
            Arrays.sort(stations, 0, count);
            float[][] deck = new float[count][3];
            for (int i = 0; i < count; i++) {
                float z = stations[i];
                float tire = 0f;
                for (float axle : type.axles) {
                    if (!ownsAxle(axle)) continue;
                    tire = Math.max(tire, 1f - ramp(Math.abs(z * length - archCenter(axle)),
                            radius, radius + .2f));
                }
                float profile = (z - deckCenter) / deckHalfLength;
                deck[i][0] = z;
                deck[i][1] = mix(.86f + .095f * ridge(profile, .09f, .73f), inner, tire);
                deck[i][2] = .58f + .34f * ridge(profile, -.14f, 1.15f);
            }
            shell(deck, .14f, shade(paint, .92f), false);
            if (part != TRAILER) {
                cabin(inner * .98f, inner * .87f, inner * .92f, .712f, 1f,
                        deckCenter - .59f * deckHalfLength, deckCenter + .54f * deckHalfLength,
                        deckCenter - .41f * deckHalfLength, deckCenter + .26f * deckHalfLength, 2);
                fascia(1, .79f, .16f, .53f, false);
            }
            if (part != TRACTOR) fascia(-1, .82f, .18f, .56f, false);
        }

        void shell(float[][] deck, float bottom, int color) {
            shell(deck, bottom, color, true);
        }

        void shell(float[][] deck, float bottom, int color, boolean wheelWells) {
            for (int i = 1; i < deck.length; i++) {
                float[] a = deck[i - 1];
                float[] b = deck[i];
                if (b[0] - a[0] < .00001f) continue;
                quad(p(-a[1], a[2], a[0]), p(-b[1], b[2], b[0]),
                        p(b[1], b[2], b[0]), p(a[1], a[2], a[0]), color);
                for (int side = -1; side <= 1; side += 2) {
                    skirt(side, a, b, bottom, color, wheelWells);
                }
            }
            for (int end = -1; end <= 1; end += 2) {
                float[] face = deck[end < 0 ? 0 : deck.length - 1];
                endPanel(end, face[0], -face[1], face[1], bottom, face[2], color);
            }
        }

        void skirt(int side, float[] a, float[] b, float bottom, int color, boolean wheelWells) {
            float[] cuts = new float[2 + type.axles.length * 7];
            int count = 0;
            cuts[count++] = a[0];
            cuts[count++] = b[0];
            if (wheelWells) {
                for (float axle : type.axles) {
                    if (!ownsAxle(axle)) continue;
                    for (int step = -3; step <= 3; step++) {
                        float z = (archCenter(axle) + step * (radius + .075f) / 3) / length;
                        if (z > a[0] && z < b[0]) cuts[count++] = z;
                    }
                }
            }
            Arrays.sort(cuts, 0, count);
            for (int i = 1; i < count; i++) {
                float z0 = cuts[i - 1], z1 = cuts[i];
                float t0 = (z0 - a[0]) / (b[0] - a[0]), t1 = (z1 - a[0]) / (b[0] - a[0]);
                float x0 = side * mix(a[1], b[1], t0), x1 = side * mix(a[1], b[1], t1);
                float top0 = mix(a[2], b[2], t0), top1 = mix(a[2], b[2], t1);
                float low0 = wheelWells ? Math.min(top0, opening(z0, bottom)) : bottom;
                float low1 = wheelWells ? Math.min(top1, opening(z1, bottom)) : bottom;
                float[] topA = p(side * a[1], a[2], a[0]), topB = p(side * b[1], b[2], b[0]);
                quad(low1 == top1 ? interpolate(topA, topB, t1) : p(x1, low1, z1),
                        low0 == top0 ? interpolate(topA, topB, t0) : p(x0, low0, z0),
                        interpolate(topA, topB, t0), interpolate(topA, topB, t1), color, side < 0);
                if (low0 > bottom || low1 > bottom) {
                    float liner = Math.min(.69f, (type.wheelX - type.halfTireWidth - .13f) / width);
                    quad(p(x0, low0, z0), p(x1, low1, z1), p(side * liner, low1, z1),
                            p(side * liner, low0, z0), RUBBER, side < 0);
                }
            }
        }

        float opening(float z, float sill) {
            float clearance = radius + .075f;
            float low = sill;
            for (float axle : type.axles) {
                if (!ownsAxle(axle)) continue;
                float fraction = Math.abs(z * length - archCenter(axle)) / clearance;
                if (fraction < 1f) {
                    float shoulder = Math.min(1f, (1f - fraction) * 3f);
                    float arch = (radius + clearance * (1f - .38f * fraction)) / height;
                    low = Math.max(low, mix(sill, arch, shoulder));
                }
            }
            return low;
        }

        float archCenter(float axle) {
            if (part == WHOLE) {
                float end = axle < 0f ? back : front;
                float shellShift = ramp(Math.abs(axle) / length, .57f, 1f)
                        * (.15f + .106f * length) * .77f;
                return axle - Math.copySign(1f, axle) * end * (.20f - shellShift);
            }
            float z = axle / length;
            float local = dentZ(z);
            float shellShift = endDent(local) * (.15f + .106f * length * dentHalfLength) * .77f;
            return axle + wheelShift(axle) + Math.signum(local) * shellShift;
        }

        float wheelShift(float axle) {
            // Match the suspension's inward movement relative to the component origin.
            float local = axle - centerZ;
            return -Math.copySign(.20f, local) * (local < 0f ? back : front);
        }

        void cabin(float lowerWidth, float rearWidth, float frontWidth, float base, float top,
                   float rear, float front, float rearRoof, float frontRoof, int windows) {
            cabin(lowerWidth, rearWidth, frontWidth, base, base, top, rear, front, rearRoof, frontRoof, windows);
        }

        void cabin(float lowerWidth, float rearWidth, float frontWidth, float rearSill, float frontSill,
                   float top, float rear, float front, float rearRoof, float frontRoof, int windows) {
            float[] rearLeft = p(-rearWidth, top, rearRoof), rearRight = p(rearWidth, top, rearRoof);
            float[] frontLeft = p(-frontWidth, top, frontRoof), frontRight = p(frontWidth, top, frontRoof);
            float[] previousLeft = rearLeft, previousRight = rearRight;
            for (int i = 1; i <= windows; i++) {
                float t = i / (float) windows;
                float w = mix(rearWidth, frontWidth, t), z = mix(rearRoof, frontRoof, t);
                float[] nextLeft = p(-w, top, z), nextRight = p(w, top, z);
                quad(previousLeft, nextLeft, nextRight, previousRight, paint);
                previousLeft = nextLeft;
                previousRight = nextRight;
            }
            glazing(p(-lowerWidth, frontSill, front), p(lowerWidth, frontSill, front), frontRight,
                    frontLeft, type == TrafficModel.CITY_BUS ? 2 : 1, true);
            glazing(p(lowerWidth, rearSill, rear), p(-lowerWidth, rearSill, rear), rearLeft,
                    rearRight, 1, true);
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < windows; i++) {
                    float a = i / (float) windows, b = (i + 1f) / windows;
                    float[] lowA = p(side * lowerWidth, mix(rearSill, frontSill, a), mix(rear, front, a));
                    float[] lowB = p(side * lowerWidth, mix(rearSill, frontSill, b), mix(rear, front, b));
                    float[] highA = p(side * mix(rearWidth, frontWidth, a), top, mix(rearRoof, frontRoof, a));
                    float[] highB = p(side * mix(rearWidth, frontWidth, b), top, mix(rearRoof, frontRoof, b));
                    if (side < 0) glazing(lowA, lowB, highB, highA, 1, false);
                    else glazing(lowB, lowA, highA, highB, 1, false);
                }
            }
        }

        void glazing(float[] a, float[] b, float[] c, float[] d, int panes, boolean end) {
            // Partition the actual panel into colored polygons so glass and pillars share edges.
            float bottom = end ? .15f : .13f;
            float top = end ? .84f : .86f;
            region(a, b, c, d, 0f, 1f, 0f, bottom, paint);
            region(a, b, c, d, 0f, 1f, top, 1f, paint);
            for (int i = 0; i < panes; i++) {
                float start = i / (float) panes, finish = (i + 1f) / panes;
                float margin = Math.min(.055f, (finish - start) * .075f);
                region(a, b, c, d, start, start + margin, bottom, top, FRAME);
                region(a, b, c, d, finish - margin, finish, bottom, top, FRAME);
                region(a, b, c, d, start + margin, finish - margin, bottom, top,
                        flattened ? RUBBER : end ? GLASS_SKY : GLASS);
            }
        }

        void region(float[] a, float[] b, float[] c, float[] d, float x0, float x1,
                    float y0, float y1, int color) {
            quad(panelPoint(a, b, c, d, x0, y0), panelPoint(a, b, c, d, x1, y0),
                    panelPoint(a, b, c, d, x1, y1), panelPoint(a, b, c, d, x0, y1), color);
        }

        float[] p(float x, float y, float z) {
            // Localized, piecewise-linear folds preserve the undamaged panels and axle clearance.
            float local = dentZ(z);
            float cap = endDent(local);
            float side = (x < 0f ? left : right) * ramp(Math.abs(x), .47f, 1f);
            float dent = side * ridge(local + .14f * x, .03f, .66f) * ridge(y, .45f, .59f);
            float across = .77f - .17f * x * Math.signum(local);
            float fold = ridge(Math.abs(local) - .10f * x, .82f, .19f);
            float roofWeight = ramp(y, .54f, 1f);
            float buckle = roof * roofWeight * (.22f + .13f * ridge(x, -.18f, 1.1f)
                    * ridge(local, .06f, .82f));
            if (flattened) buckle *= .3f;
            float xOut = x * (1f - .22f * dent) + .047f * cap * x * fold;
            float zOut = z - Math.signum(local) * cap * across * (.15f / length + .106f * dentHalfLength)
                    + .035f * dent * Math.signum(x) * dentHalfLength;
            float yOut = y + cap * (.11f * fold - .12f * across) * ramp(y, .05f, .55f)
                    - .064f * dent - buckle;
            return point(xOut, yOut, zOut);
        }

        float dentZ(float z) {
            return (z - dentCenter) / dentHalfLength;
        }

        float endDent(float z) {
            return (z < 0f ? back : front) * ramp(Math.abs(z), .57f, 1f);
        }

        float[] point(float x, float y, float z) {
            return new float[] {x * width, Math.max(.025f, Math.min(1f, y) * height), z * length};
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            box(x0, y0, z0, x1, y1, z1, color, true);
        }

        void rigidBox(float x0, float y0, float z0, float x1, float y1, float z1, int color) {
            box(x0, y0, z0, x1, y1, z1, color, false);
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, int color, boolean dented) {
            float minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
            float[] a = boxPoint(minX, y0, z0, dented), b = boxPoint(maxX, y0, z0, dented);
            float[] c = boxPoint(maxX, y0, z1, dented), d = boxPoint(minX, y0, z1, dented);
            float[] e = boxPoint(minX, y1, z0, dented), f = boxPoint(maxX, y1, z0, dented);
            float[] g = boxPoint(maxX, y1, z1, dented), h = boxPoint(minX, y1, z1, dented);
            quad(a, b, c, d, color);
            quad(e, h, g, f, color);
            quad(b, a, e, f, color);
            quad(d, c, g, h, color);
            quad(a, d, h, e, color);
            quad(c, b, f, g, color);
        }

        float[] boxPoint(float x, float y, float z, boolean dented) {
            return dented ? p(x, y, z) : point(x, y, z);
        }

        void endPanel(int end, float z, float x0, float x1, float bottom, float top, int color) {
            float low = Math.min(x0, x1), high = Math.max(x0, x1);
            quad(p(low, bottom, z), p(high, bottom, z), p(high, top, z), p(low, top, z), color, end < 0);
        }

        void sidePanel(float x, float bottom, float top, float rear, float front, int color) {
            quad(p(x, bottom, front), p(x, bottom, rear), p(x, top, rear), p(x, top, front), color, x < 0f);
        }

        void quad(float[] a, float[] b, float[] c, float[] d, int color) {
            quad(a, b, c, d, color, false);
        }

        void quad(float[] a, float[] b, float[] c, float[] d, int color, boolean reverse) {
            if (reverse) mesh.quad(d, c, b, a, color);
            else mesh.quad(a, b, c, d, color);
        }
    }

    private static float[] panelPoint(float[] a, float[] b, float[] c, float[] d, float x, float y) {
        float[] point = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            point[axis] = mix(mix(a[axis], b[axis], x), mix(d[axis], c[axis], x), y);
        }
        return point;
    }

    private static float[] interpolate(float[] a, float[] b, float t) {
        return new float[] {mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t)};
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float ramp(float value, float start, float end) {
        return clamp((value - start) / (end - start));
    }

    private static float ridge(float value, float center, float halfWidth) {
        return clamp(1f - Math.abs(value - center) / halfWidth);
    }

    private static int shade(int color, float scale) {
        int r = Math.min(255, Math.round(((color >> 16) & 255) * scale));
        int g = Math.min(255, Math.round(((color >> 8) & 255) * scale));
        int b = Math.min(255, Math.round((color & 255) * scale));
        return (r << 16) | (g << 8) | b;
    }

    private static final class MeshBuilder {
        private static final int STRIDE = 6;
        private final float centerZ;
        private float[] vertices = new float[STRIDE * 512];
        private int size;

        MeshBuilder(float centerZ) {
            this.centerZ = centerZ;
        }

        void quad(float[] a, float[] b, float[] c, float[] d, int color) {
            triangle(a, b, c, color);
            triangle(a, c, d, color);
        }

        void triangle(float[] a, float[] b, float[] c, int color) {
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < .000001f) return;
            float diffuse = Math.max(0f, (-.49f * nx + .76f * ny + .42f * nz) / length);
            int lit = shade(color, .73f + .40f * diffuse);
            if (size + STRIDE * 3 > vertices.length) vertices = Arrays.copyOf(vertices, vertices.length * 2);
            vertex(a, lit);
            vertex(b, lit);
            vertex(c, lit);
        }

        private void vertex(float[] point, int color) {
            vertices[size++] = point[0];
            vertices[size++] = point[1];
            vertices[size++] = point[2] - centerZ;
            vertices[size++] = ((color >> 16) & 255) / 255f;
            vertices[size++] = ((color >> 8) & 255) / 255f;
            vertices[size++] = (color & 255) / 255f;
        }

        Geometry.Mesh finish() {
            FloatBuffer buffer = ByteBuffer.allocateDirect(size * Float.BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(vertices, 0, size);
            buffer.flip();
            return new Geometry.Mesh(buffer, size / STRIDE);
        }
    }
}
