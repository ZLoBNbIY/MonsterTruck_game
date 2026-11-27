package com.levgame.monster;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class GameRenderer implements GLSurfaceView.Renderer {
    private static final float STEP = 1f / 60f;
    private static final int TILE_RADIUS = 4;
    private static final float SKY_R = .58f, SKY_G = .80f, SKY_B = .94f;
    private final World world = new World();
    private final Map<Integer, GpuMesh> tiles = new LinkedHashMap<>(110, .75f, true);
    private final VehicleMesh truck = new VehicleMesh();
    private final VehicleMesh[] cars = new VehicleMesh[world.traffic.length];
    private static final int TRAFFIC_COLORS = 6;
    private final GpuMesh[] stock = new GpuMesh[TrafficModel.values().length * TRAFFIC_COLORS];
    private final Spark[] sparks = new Spark[160];
    private final EnginePuff[] engineSmoke = new EnginePuff[36];
    private final GpuMesh[] engineSmokeMeshes = new GpuMesh[3];
    private final TireMark[] tireMarks = new TireMark[240];
    private final FloatBuffer tireMarkData = vertices(tireMarks.length * 6);
    private final Random effects = new Random(37);
    private final float[] model = new float[16], bodyMatrix = new float[16];
    private final float[] projection = new float[16], view = new float[16];
    private final float[] vp = new float[16], mvp = new float[16];
    private final int[] bufferName = new int[1];
    private int position, tint, transform, haze, opacity, terrain, groundOrigin, softShadow;
    private GpuMesh sky, truckTire, carTire, distantTire, shadow, sparkMesh, dustMesh, smokeMesh, gritMesh, tireMarkMesh;
    private GpuMesh treeMesh, stumpMesh, garageMesh;
    private final VehicleMesh garageTruck = new VehicleMesh();
    private boolean garage = true;
    private int truckDesign, truckColor = 0xe9351c;
    private float garageAngle = -1.8f;
    private int nextSpark, nextMark;
    private int nextEnginePuff;
    private float engineSmokeClock;
    private final float[] previousSuspension = new float[4];
    private final float[] cameraBuilding = new float[6];
    private float dustClock, steeringAngle, previousSteering, blend;
    private long lastFrame;
    private float accumulated, cameraX, cameraZ, cameraY, cameraYaw;
    private boolean cameraReady;
    private boolean orbitCamera;
    private float orbitYaw, orbitElevation;

    volatile int buttons, speed, crushed;
    volatile int health = (int) World.TRUCK_MAX_HEALTH, maxHealth = (int) World.TRUCK_MAX_HEALTH;
    volatile boolean rolledOver;
    volatile float mapX, mapZ, mapYaw;

    GameRenderer() {
        for (int i = 0; i < cars.length; i++) cars[i] = new VehicleMesh();
        for (int i = 0; i < sparks.length; i++) sparks[i] = new Spark();
        for (int i = 0; i < engineSmoke.length; i++) engineSmoke[i] = new EnginePuff();
        for (int i = 0; i < tireMarks.length; i++) tireMarks[i] = new TireMark();
    }

    void setGarage(boolean enabled) {
        garage = enabled;
        resetCamera();
        accumulated = 0;
        truck.previous.visible = false;
        for (VehicleMesh car : cars) car.previous.visible = false;
        if (enabled) {
            garageAngle = -1.8f;
            clearEngineSmoke();
        }
    }

    void selectTruck(int design, int colorRgb) {
        truckDesign = design;
        truckColor = colorRgb & 0xffffff;
        truck.state = garageTruck.state = -1;
    }

    void repair() {
        world.repairPlayer();
        clearEngineSmoke();
        truck.previous.visible = false;
    }

    void rightTruck() {
        world.rightPlayer();
        truck.previous.visible = false;
    }

    void rotateCamera(float horizontal, float vertical) {
        if (garage) return;
        if (!orbitCamera) {
            orbitYaw = cameraYaw + .46f;
            orbitElevation = .40f;
            orbitCamera = true;
        }
        orbitYaw = (orbitYaw - horizontal) % ((float) Math.PI * 2f);
        orbitElevation = Math.max(.16f, Math.min(1.16f, orbitElevation + vertical));
    }

    void resetCamera() {
        orbitCamera = false;
        cameraReady = false;
    }

    void restart() {
        world.reset();
        resetCamera();
        steeringAngle = 0;
        truck.previous.visible = false;
        for (Spark spark : sparks) spark.life = 0;
        clearEngineSmoke();
        for (TireMark mark : tireMarks) mark.life = 0;
        dustClock = 0;
        for (VehicleMesh car : cars) {
            car.previous.visible = false;
            car.lastDamage = 0;
            car.lastDetached = 0;
        }
        truck.lastDamage = 0;
        truck.lastDetached = 0;
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        String vertex = "attribute vec3 aPosition; attribute vec3 aTint;"
                 + "uniform mat4 uTransform; uniform float uHaze; uniform vec2 uGroundOrigin; uniform bool uTerrain;"
                + "varying mediump vec3 vColor, vGround; varying mediump float vDepth;"
                + "void main(){gl_Position=uTransform*vec4(aPosition,1.0);"
                + "float fog=smoothstep(85.0,264.0,gl_Position.w)*uHaze;"
                 + "vec2 origin=uTerrain?uGroundOrigin:vec2(0.0);"
                 + "vGround=vec3(aPosition.xz-origin,aPosition.y); vDepth=gl_Position.w;"
                + "vColor=mix(aTint,vec3(.58,.80,.94),fog);}";
        String fragment = "precision mediump float; varying vec3 vColor; uniform float uOpacity;"
                 + "uniform bool uTerrain, uSoftShadow;"
                 + "varying vec3 vGround; varying float vDepth;"
                 + "void main(){vec3 color=vColor; float alpha=uOpacity; if(uTerrain){"
                 + "vec2 p=fract(floor(mod(vGround.xy,32.0)*11.0)*vec2(.1031,.11369));"
                + "float grain=fract((p.x+p.y)*fract(p.x*p.y*23.17))-.5;"
                + "float fade=(1.0-smoothstep(.025,.11,vGround.z))*(1.0-smoothstep(16.0,65.0,vDepth));"
                  + "color+=vec3(grain*.032*fade); }"
                 + "if(uSoftShadow){alpha*=1.0-smoothstep(.42,.98,length(vGround.xy));}"
                 + "gl_FragColor=vec4(color,alpha);}";
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex), fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        GLES20.glUseProgram(program);
        position = GLES20.glGetAttribLocation(program, "aPosition");
        tint = GLES20.glGetAttribLocation(program, "aTint");
        transform = GLES20.glGetUniformLocation(program, "uTransform");
        haze = GLES20.glGetUniformLocation(program, "uHaze");
        opacity = GLES20.glGetUniformLocation(program, "uOpacity");
        terrain = GLES20.glGetUniformLocation(program, "uTerrain");
        groundOrigin = GLES20.glGetUniformLocation(program, "uGroundOrigin");
        softShadow = GLES20.glGetUniformLocation(program, "uSoftShadow");
        GLES20.glUniform1f(opacity, 1);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(tint);
        GLES20.glClearColor(SKY_R, SKY_G, SKY_B, 1);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        tiles.clear();
        for (int i = 0; i < stock.length; i++) stock[i] = null;
        for (VehicleMesh car : cars) car.gpu = null;
        truck.gpu = null;
        garageTruck.gpu = null;
        garageMesh = garageMesh();
        sky = skyMesh();
        truckTire = offRoadWheel();
        carTire = wheelMesh(36);
        distantTire = wheelMesh(12);
        shadow = shadowMesh();
        sparkMesh = particleMesh(0xffc45b, 0xffe89f);
        dustMesh = cloudMesh(0xc39770, 0xe1bb91);
        smokeMesh = cloudMesh(0x939ba9, 0xc3c9d1);
        engineSmokeMeshes[0] = cloudMesh(0xf0f3f5, 0xdbe3eb);
        engineSmokeMeshes[1] = cloudMesh(0x727781, 0xa2a9b0);
        engineSmokeMeshes[2] = cloudMesh(0x14161c, 0x464b55);
        gritMesh = particleMesh(0x78523e, 0xcf9b62);
        tireMarkMesh = new GpuMesh(tireMarkData, 0);
        Geometry.Mesh tree = Geometry.tree();
        Geometry.Mesh stump = Geometry.stump();
        treeMesh = new GpuMesh(tree.data, tree.count);
        stumpMesh = new GpuMesh(stump.data, stump.count);
        lastFrame = 0;
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        Matrix.perspectiveM(projection, 0, 55, width / (float) Math.max(1, height), 1f, 310);
    }

    @Override public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float elapsed = lastFrame == 0 ? STEP : Math.min(.10f, (now - lastFrame) * 1e-9f);
        lastFrame = now;
        if (garage) {
            drawGarage(elapsed);
            return;
        }
        accumulated += elapsed;
        int input = buttons;
        int steer = ((input & 1) != 0 ? 1 : 0) - ((input & 2) != 0 ? 1 : 0);
        while (accumulated >= STEP) {
            World.Body player = world.player;
            truck.previous.capture(player);
            for (int i = 0; i < cars.length; i++) cars[i].previous.capture(world.traffic[i]);
            for (int i = 0; i < 4; i++) previousSuspension[i] = world.suspensionTravel(i);
            previousSteering = steeringAngle;
            boolean braking = (input & 8) != 0;
            float forwardSpeed = player.vx * (float) Math.sin(player.yaw)
                    + player.vz * (float) Math.cos(player.yaw);
            boolean reverse = braking && (input & 4) == 0 && forwardSpeed < .55f;
            world.step(STEP, steer,
                    reverse ? -1 : (input & 4) != 0 ? 1 : 0, braking && !reverse ? 1 : 0);
            steeringAngle += (steer * 27f / (1 + Math.abs(forwardSpeed) * .027f) - steeringAngle) * .20f;
            updateEffects();
            accumulated -= STEP;
        }
        blend = accumulated / STEP;
        World.Body player = world.player;
        speed = Math.round(3.6f * (float) Math.hypot(player.vx, player.vz));
        crushed = world.crushed;
        maxHealth = Math.round(player.maxHealth);
        health = player.health > 0 ? (int) Math.ceil(player.health) : 0;
        rolledOver = Math.abs(player.roll) >= 1.1f;
        mapX = player.x;
        mapZ = player.z;
        mapYaw = player.yaw;
        updateCamera(player, elapsed);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        drawSky();
        GLES20.glUniform1f(haze, 1);
        GLES20.glUniform1i(terrain, 1);
        GLES20.glUniform2f(groundOrigin, 32 * (float) Math.floor(cameraX / 32),
                32 * (float) Math.floor(cameraZ / 32));
        drawCity();
        GLES20.glUniform1i(terrain, 0);
        drawTireMarks();
        drawTrees();
        drawVehicle(player, truck, true);
        for (int i = 0; i < world.traffic.length; i++) {
            World.Body car = world.traffic[i];
            if (car.visible && inView(car.x, car.y, car.z, car.model.halfLength + 2f, 189)) {
                drawVehicle(car, cars[i], false);
            }
        }
        for (World.LooseWheel wheel : world.looseWheels) {
            if (wheel.visible && inView(wheel.x, wheel.y, wheel.z, 2, 118)) drawLooseWheel(wheel);
        }
        drawEffects();
    }

    private void updateCamera(World.Body player, float elapsed) {
        Pose previous = truck.previous;
        float mixPose = previous.fraction(player, blend);
        float x = lerp(previous.x, player.x, mixPose);
        float y = lerp(previous.y, player.y, mixPose);
        float z = lerp(previous.z, player.z, mixPose);
        float yaw = lerpAngle(previous.yaw, player.yaw, mixPose);
        if (orbitCamera) {
            float focusY = y + 1.25f;
            float radius = 8.5f * (float) Math.cos(orbitElevation);
            cameraYaw = orbitYaw;
            float dx = -radius * (float) Math.sin(orbitYaw);
            float dz = -radius * (float) Math.cos(orbitYaw);
            float dy = 8.5f * (float) Math.sin(orbitElevation);
            float clearance = orbitClearance(x, focusY, z, dx, dy, dz);
            cameraX = x + dx * clearance;
            cameraZ = z + dz * clearance;
            cameraY = focusY + dy * clearance;
            Matrix.setLookAtM(view, 0, cameraX, cameraY, cameraZ, x, focusY, z, 0, 1, 0);
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
            return;
        }
        float mix = 1 - (float) Math.exp(-elapsed * 9);
        if (!cameraReady) {
            cameraYaw = yaw;
        } else {
            cameraYaw = lerpAngle(cameraYaw, yaw, mix);
        }
        float forwardX = (float) Math.sin(cameraYaw), forwardZ = (float) Math.cos(cameraYaw);
        float speedKmh = 3.6f * (float) Math.hypot(player.vx, player.vz);
        float distance = 10.2f + Math.min(speedKmh, 90) * .023f;
        float shoulder = 5.1f;
        float targetX = x - forwardX * distance - forwardZ * shoulder;
        float targetZ = z - forwardZ * distance + forwardX * shoulder;
        float targetY = 6.4f + y * .65f;
        if (cameraReady) {
            cameraX += (targetX - cameraX) * mix;
            cameraY += (targetY - cameraY) * mix;
            cameraZ += (targetZ - cameraZ) * mix;
        } else {
            cameraX = targetX;
            cameraY = targetY;
            cameraZ = targetZ;
            cameraReady = true;
        }
        Matrix.setLookAtM(view, 0, cameraX, cameraY, cameraZ,
                x + forwardX * 5, .9f + y * .45f, z + forwardZ * 5, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
    }

    private float orbitClearance(float x, float y, float z, float dx, float dy, float dz) {
        int gx = (int) (x / World.BLOCK - .5f), gz = (int) (z / World.BLOCK - .5f);
        float invX = 1f / Math.copySign(Math.max(Math.abs(dx), .0001f), dx);
        float invZ = 1f / Math.copySign(Math.max(Math.abs(dz), .0001f), dz);
        float clearance = 1f;
        for (int row = gz - 1; row <= gz + 1; row++) {
            for (int col = gx - 1; col <= gx + 1; col++) {
                int count = World.buildingCount(col, row);
                for (int i = 0; i < count; i++) {
                    World.building(col, row, i, cameraBuilding);
                    float bx = cameraBuilding[0] - x, bz = cameraBuilding[1] - z;
                    float halfX = cameraBuilding[2] + .25f, halfZ = cameraBuilding[3] + .25f;
                    float x1 = (bx - halfX) * invX, x2 = (bx + halfX) * invX;
                    float z1 = (bz - halfZ) * invZ, z2 = (bz + halfZ) * invZ;
                    float enter = Math.max(0f, Math.max(Math.min(x1, x2), Math.min(z1, z2)));
                    float exit = Math.min(Math.max(x1, x2), Math.max(z1, z2));
                    if (enter <= exit && y + enter * dy <= cameraBuilding[4] + .9f) {
                        clearance = Math.min(clearance, enter);
                    }
                }
            }
        }
        return Math.max(.08f, clearance);
    }

    private void drawGarage(float elapsed) {
        garageAngle = (garageAngle + elapsed * .36f) % 6.2831855f;
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUniform1f(haze, 0);
        GLES20.glUniform1i(terrain, 0);
        Matrix.setLookAtM(view, 0, -2.55f, 4.8f, 9.1f, -2.55f, 1.2f, 0, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
        Matrix.setIdentityM(model, 0);
        drawModel(garageMesh);
        drawShadow(0, 0, garageAngle, 2.15f, 2.65f, .48f);
        Matrix.setRotateM(bodyMatrix, 0, degrees(garageAngle), 0, 1, 0);
        System.arraycopy(bodyMatrix, 0, model, 0, 16);
        drawModel(vehicleMesh(null, garageTruck, true));
        drawTruckWheels(null, 0, 1);
    }

    private GpuMesh garageMesh() {
        FloatBuffer data = vertices(4200);
        garageBox(data, -24, -.30f, -18, 24, -.10f, 16, 0x344458);
        garageBox(data, -24, -.10f, -12, 24, 9, -11.6f, 0x273950);
        garageBox(data, -24, 6.4f, -12, 24, 6.7f, 8, 0x202e40);
        for (int x = -21; x <= 21; x += 6) {
            garageBox(data, x, -.10f, -11.5f, x+.30f, 6.4f, -11f, 0x445a71);
            garageBox(data, x+.75f, 2.0f, -11.55f, x+5.35f, 5.8f, -11.45f, 0x1a2839);
            for (int slat = 0; slat < 9; slat++) {
                float y = 2.2f + slat * .38f;
                garageBox(data, x+.83f, y, -11.45f, x+5.27f, y+.035f, -11.38f, 0x415369);
            }
            garageBox(data, x+.8f, 5.98f, -11.4f, x+5.3f, 6.07f, -11.25f, 0xa0e6f9);
        }
        for (int line = -18; line <= 18; line += 3) {
            garageQuad(data, line, -.095f, -11, line+.023f, -.095f, 14, 0x27384a, true);
            garageQuad(data, -20, -.095f, line, 20, -.095f, line+.023f, 0x27384a, true);
        }
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI / 32, b = (i+1) * Math.PI / 32;
            float ax = (float) Math.cos(a), az = (float) Math.sin(a);
            float bx = (float) Math.cos(b), bz = (float) Math.sin(b);
            colorPoint(data, 0, 0, 0, 0x495b70);
            colorPoint(data, 3.29f*ax, 0, 3.29f*az, 0x273749);
            colorPoint(data, 3.29f*bx, 0, 3.29f*bz, 0x273749);
            int rim = i % 4 < 2 ? 0xe7ab47 : 0x6b5040;
            colorPoint(data, 3.29f*ax, 0, 3.29f*az, rim);
            colorPoint(data, 3.48f*ax, 0, 3.48f*az, rim);
            colorPoint(data, 3.48f*bx, 0, 3.48f*bz, rim);
            colorPoint(data, 3.29f*ax, 0, 3.29f*az, rim);
            colorPoint(data, 3.48f*bx, 0, 3.48f*bz, rim);
            colorPoint(data, 3.29f*bx, 0, 3.29f*bz, rim);
        }
        return new GpuMesh(data, data.position() / 6);
    }

    private static void garageBox(FloatBuffer data, float x, float y, float z,
                                  float right, float top, float front, int color) {
        garageQuad(data, x, top, z, right, top, front, color, true);
        garageQuad(data, x, y, front, right, top, front, color, false);
    }

    private static void garageQuad(FloatBuffer data, float x, float y, float z,
                                   float right, float top, float front, int color, boolean floor) {
        float endY = floor ? y : top, endZ = floor ? front : z;
        colorPoint(data, x, y, z, color);
        colorPoint(data, right, y, z, color);
        colorPoint(data, right, endY, endZ, color);
        colorPoint(data, x, y, z, color);
        colorPoint(data, right, endY, endZ, color);
        colorPoint(data, x, endY, endZ, color);
    }

    private boolean inView(float x, float y, float z, float radius, float distance) {
        float dx = x - cameraX, dz = z - cameraZ;
        float lateral = view[0] * dx + view[8] * dz;
        float depth = -(view[2] * dx + view[6] * (y - cameraY) + view[10] * dz);
        return depth >= -radius && depth <= distance + radius
                && Math.abs(lateral) <= Math.max(0, depth) / projection[0] + radius;
    }

    private void drawCity() {
        int x = (int) (world.player.x / World.BLOCK), z = (int) (world.player.z / World.BLOCK);
        GLES20.glUniformMatrix4fv(transform, 1, false, vp, 0);
        for (int row = Math.max(0, z - TILE_RADIUS); row <= Math.min(World.GRID - 1, z + TILE_RADIUS); row++) {
            for (int col = Math.max(0, x - TILE_RADIUS); col <= Math.min(World.GRID - 1, x + TILE_RADIUS); col++) {
                if (!inView((col + .7f) * World.BLOCK, 0, (row + .7f) * World.BLOCK, 74, 276)) continue;
                int key = row * World.GRID + col;
                GpuMesh tile = tiles.get(key);
                if (tile == null) {
                    Geometry.Mesh mesh = Geometry.tile(col, row);
                    tile = new GpuMesh(mesh.data, mesh.count);
                    tiles.put(key, tile);
                }
                draw(tile);
            }
        }
        Iterator<GpuMesh> iterator = tiles.values().iterator();
        while (tiles.size() > 90) {
            iterator.next().delete();
            iterator.remove();
        }
    }

    private void drawSky() {
        Matrix.setIdentityM(model, 0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUniform1f(haze, 0);
        GLES20.glUniformMatrix4fv(transform, 1, false, model, 0);
        draw(sky);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private void drawTrees() {
        int x = (int) (world.player.x / World.BLOCK), z = (int) (world.player.z / World.BLOCK);
        for (int row = Math.max(0, z - 3); row <= Math.min(World.GRID - 1, z + 3); row++) {
            for (int col = Math.max(0, x - 3); col <= Math.min(World.GRID - 1, x + 3); col++) {
                if (!inView((col + .5f) * World.BLOCK, 0, (row + .5f) * World.BLOCK, 48, 185)) continue;
                for (World.Tree tree : world.trees(col, row)) {
                    if (!tree.uprooted) {
                        drawTree(tree);
                    } else if (inView(tree.rootX, tree.rootY, tree.rootZ, 2, 125)) {
                        Matrix.setIdentityM(model, 0);
                        Matrix.translateM(model, 0, tree.rootX, tree.rootY, tree.rootZ);
                        drawModel(stumpMesh);
                    }
                }
                for (World.Tree tree : world.fallenTrees(col, row)) drawTree(tree);
            }
        }
    }

    private void drawTree(World.Tree tree) {
        if (!inView(tree.x, tree.y, tree.z, 3, 185)) return;
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, tree.x, tree.y, tree.z);
        if (tree.uprooted) {
            Matrix.rotateM(model, 0, degrees(tree.yaw), 0, 1, 0);
            Matrix.rotateM(model, 0, degrees(tree.pitch), 1, 0, 0);
        }
        drawModel(treeMesh);
    }

    private void drawVehicle(World.Body body, VehicleMesh skin, boolean isTruck) {
        Pose previous = skin.previous;
        float fraction = previous.fraction(body, blend);
        float x = lerp(previous.x, body.x, fraction), y = lerp(previous.y, body.y, fraction);
        float z = lerp(previous.z, body.z, fraction), yaw = lerpAngle(previous.yaw, body.yaw, fraction);
        drawShadow(x, z, yaw, isTruck ? 1.95f : body.model.halfWidth + .15f,
                isTruck ? 2.74f : body.model.halfLength + .15f, .31f / (1 + Math.max(0, y) * .65f));
        positionBody(x, y, z, yaw, lerp(previous.pitch, body.pitch, fraction),
                lerpAngle(previous.roll, body.roll, fraction));
        System.arraycopy(bodyMatrix, 0, model, 0, 16);
        drawModel(vehicleMesh(body, skin, isTruck));

        float dx = x - cameraX, dz = z - cameraZ;
        GpuMesh tire = dx*dx + dz*dz < 45*45 ? carTire : distantTire;
        float spin = lerpAngle(previous.wheelSpin, body.wheelSpin, fraction);
        if (isTruck) drawTruckWheels(body, spin, fraction);
        else drawTrafficWheels(body, tire, spin);
    }

    private void drawTrafficWheels(World.Body body, GpuMesh tire, float spin) {
        for (int i = 0; i < body.axleCount() * 2; i++) {
            if ((body.detachedWheels & (1 << i)) != 0) continue;
            System.arraycopy(bodyMatrix, 0, model, 0, 16);
            float z = body.wheelZ(i);
            Matrix.translateM(model, 0, body.wheelX(i), body.wheelY(), z);
            boolean left = i < body.axleCount();
            float bend = left ? body.dentLeft : body.dentRight;
            float endDent = z < 0f ? body.dentBack : body.dentFront;
            Matrix.rotateM(model, 0, (left ? -1f : 1f) * (bend * 21f + endDent * 7f), 0, 1, 0);
            Matrix.rotateM(model, 0, degrees(spin), 1, 0, 0);
            float radius = body.wheelRadius();
            Matrix.scaleM(model, 0, body.tireHalfWidth(), radius, radius);
            drawModel(tire);
        }
    }

    private void positionBody(float x, float y, float z, float yaw, float pitch, float roll) {
        Matrix.setIdentityM(bodyMatrix, 0);
        Matrix.translateM(bodyMatrix, 0, x, y, z);
        Matrix.rotateM(bodyMatrix, 0, degrees(yaw), 0, 1, 0);
        Matrix.rotateM(bodyMatrix, 0, degrees(pitch), 1, 0, 0);
        Matrix.rotateM(bodyMatrix, 0, degrees(roll), 0, 0, 1);
    }

    private void drawTruckWheels(World.Body body, float wheelSpin, float fraction) {
        for (int side = -1; side <= 1; side += 2) {
            for (int axle = -1; axle <= 1; axle += 2) {
                int index = (side < 0 ? 0 : 2) + (axle < 0 ? 0 : 1);
                if (body != null && (body.detachedWheels & (1 << index)) != 0) continue;
                System.arraycopy(bodyMatrix, 0, model, 0, 16);
                float bend = body == null ? 0 : side < 0 ? body.dentLeft : body.dentRight;
                float endDent = body == null ? 0 : axle > 0 ? body.dentFront : body.dentBack;
                float travel = body == null ? 0f : lerp(previousSuspension[index], world.suspensionTravel(index), fraction);
                Matrix.translateM(model, 0, body == null ? side * World.TRUCK_WHEEL_X : body.wheelX(index),
                        body == null ? World.TRUCK_WHEEL_Y : body.wheelY() + travel,
                        body == null ? axle * World.TRUCK_WHEEL_Z : body.wheelZ(index));
                float steer = axle > 0 && body != null ? lerp(previousSteering, steeringAngle, fraction) : 0;
                Matrix.rotateM(model, 0, steer + side * (bend * 21 + endDent * 7), 0, 1, 0);
                Matrix.rotateM(model, 0, degrees(wheelSpin), 1, 0, 0);
                float r = World.TRUCK_WHEEL_RADIUS;
                Matrix.scaleM(model, 0, World.TRUCK_WHEEL_HALF_WIDTH, r, r);
                drawModel(truckTire);
            }
        }
    }

    private void drawLooseWheel(World.LooseWheel wheel) {
        drawShadow(wheel.x, wheel.z, wheel.yaw, wheel.radius * .72f, wheel.radius,
                .26f / (1 + Math.max(0, wheel.y - wheel.radius)));
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, wheel.x, wheel.y, wheel.z);
        Matrix.rotateM(model, 0, degrees(wheel.yaw), 0, 1, 0);
        Matrix.rotateM(model, 0, degrees(wheel.tilt), 0, 0, 1);
        Matrix.rotateM(model, 0, degrees(wheel.spin), 1, 0, 0);
        Matrix.scaleM(model, 0, wheel.halfWidth, wheel.radius, wheel.radius);
        drawModel(wheel.radius > .6f ? truckTire : carTire);
    }

    private void drawShadow(float x, float z, float yaw, float width, float length, float alpha) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, Math.max(.035f, World.rampHeight(x, z) + .025f), z);
        Matrix.rotateM(model, 0, degrees(yaw), 0, 1, 0);
        Matrix.scaleM(model, 0, width, 1, length);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(opacity, alpha);
        GLES20.glUniform1i(softShadow, 1);
        drawModel(shadow);
        GLES20.glUniform1i(softShadow, 0);
        GLES20.glUniform1f(opacity, 1);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private GpuMesh vehicleMesh(World.Body body, VehicleMesh skin, boolean isTruck) {
        if (body == null) return truckMesh(skin, 0, 0, 0, 0, 0, 0);
        int roof = quantize(body.dentRoof), front = quantize(body.dentFront), back = quantize(body.dentBack);
        int left = quantize(body.dentLeft), right = quantize(body.dentRight);
        int damageState = (roof << 16) | (front << 12) | (back << 8) | (left << 4) | right;
        int state = ((body.color & 255) << 20) | damageState;
        if (isTruck) return truckMesh(skin, state, front, back, left, right, roof);
        if (damageState == 0 && !body.flattened) {
            int index = body.model.ordinal() * TRAFFIC_COLORS + Math.floorMod(body.color, TRAFFIC_COLORS);
            if (stock[index] == null) {
                Geometry.Mesh mesh = TrafficGeometry.mesh(body.model, body.color, 0, 0, 0, 0, 0, false);
                stock[index] = new GpuMesh(mesh.data, mesh.count);
            }
            return stock[index];
        }
        if (body.flattened) state |= 1 << 28;
        if (skin.gpu == null || skin.state != state) {
            Geometry.Mesh mesh = TrafficGeometry.mesh(body.model, body.color,
                    front / 15f, back / 15f, left / 15f, right / 15f, roof / 15f, body.flattened);
            if (skin.gpu == null) skin.gpu = new GpuMesh(mesh.data, mesh.count);
            else skin.gpu.upload(mesh.data, mesh.count);
            skin.state = state;
        }
        return skin.gpu;
    }

    private GpuMesh truckMesh(VehicleMesh skin, int state, int front, int back, int left, int right, int roof) {
        if (skin.gpu == null || skin.state != state) {
            Geometry.Mesh mesh = Geometry.vehicle(true, truckColor, truckDesign,
                    front / 15f, back / 15f, left / 15f, right / 15f, roof / 15f);
            if (skin.gpu == null) skin.gpu = new GpuMesh(mesh.data, mesh.count);
            else skin.gpu.upload(mesh.data, mesh.count);
            skin.state = state;
        }
        return skin.gpu;
    }

    private static int quantize(float value) { return Math.min(15, Math.max(0, Math.round(value * 15))); }

    private static float degrees(float value) { return value * 57.29578f; }

    private void drawModel(GpuMesh mesh) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(transform, 1, false, mvp, 0);
        draw(mesh);
    }

    private void draw(GpuMesh mesh) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.id);
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glVertexAttribPointer(tint, 3, GLES20.GL_FLOAT, false, 24, 12);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count);
    }

    private static int shader(int type, String source) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, source);
        GLES20.glCompileShader(id);
        int[] status = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(id));
        return id;
    }

    private GpuMesh skyMesh() {
        FloatBuffer data = vertices(6);
        int horizon = 0xb4d8e6, zenith = 0x639fce;
        colorPoint(data, -1, -1, 0, horizon);
        colorPoint(data, 1, -1, 0, horizon);
        colorPoint(data, 1, 1, 0, zenith);
        colorPoint(data, -1, -1, 0, horizon);
        colorPoint(data, 1, 1, 0, zenith);
        colorPoint(data, -1, 1, 0, zenith);
        return new GpuMesh(data, 6);
    }

    private GpuMesh offRoadWheel() {
        int segments = 84;
        float[] profileX = {-1, -.98f, -.86f, -.72f, 0, .72f, .86f, .98f, 1};
        float[] profileR = {.46f, .66f, .83f, .90f, .91f, .90f, .83f, .66f, .46f};
        int[] rubber = {0x212832, 0x333c47, 0x303842, 0x222831, 0x1a2028,
                0x222831, 0x303842, 0x333c47, 0x212832};
        FloatBuffer data = vertices(15000);
        float step = 6.2831855f / segments;
        for (int i = 0; i < segments; i++) {
            float a = i * step, b = a + step;
            for (int j = 0; j < profileX.length - 1; j++) {
                tireQuad(data, profileX[j], profileX[j+1], profileR[j], profileR[j+1], a, b,
                        rubber[j], rubber[j+1]);
            }
            for (int sign = -1; sign <= 1; sign += 2) {
                tireQuad(data, sign, sign*1.028f, .49f, .45f, a, b, 0x252d35, 0x7b8a95);
                tireQuad(data, sign*1.028f, sign*1.025f, .45f, .395f, a, b, 0xfbbb46, 0x9b6029);
                tireQuad(data, sign*.91f, sign*.84f, .399f, .15f, a, b, 0x104059, 0x226181);
                if (i % 14 < 5) tireQuad(data, sign*1.01f, sign*1.09f, .40f, .14f, a, b,
                        0x5ac9ec, 0x1d78a9);
                if (i % 7 < 2) tireQuad(data, sign*1.034f, sign*1.05f, .447f, .42f, a, b,
                        0xf6dec3, 0xa89382);
                tireQuad(data, sign*1.09f, sign*1.13f, .154f, .112f, a, b, 0x496276, 0xd9ebea);
                tireQuad(data, sign*1.13f, sign*1.13f, .112f, 0, a, b, 0x7392a3, 0x3c586d);
            }
        }
        for (int lug = 0; lug < 21; lug++) {
            float angle = lug * 6.2831855f / 21;
            for (int sign = -1; sign <= 1; sign += 2) {
                float a = angle + (sign < 0 ? .105f : -.008f);
                treadBlock(data, sign*.06f, sign*.64f, a, a+.18f, .12f, .91f, 1f, .99f);
                treadBlock(data, sign*.64f, sign*.96f, a+.18f, a+.23f, .15f, .84f, .99f, .85f);
            }
        }
        return new GpuMesh(data, data.position() / 6);
    }

    private static void treadBlock(FloatBuffer data, float x0, float x1, float a, float b,
                                   float width, float base, float inner, float outer) {
        float[][] top = {{x0, inner, a}, {x1, outer, b}, {x1, outer, b+width}, {x0, inner, a+width}};
        int[] triangleCorners = {0, 1, 2, 0, 2, 3};
        for (int index : triangleCorners) treadPoint(data, top[index], top[index][1], 0x313b47);
        for (int i = 0; i < 4; i++) {
            float[] p = top[i], q = top[(i+1) % 4];
            treadPoint(data, p, p[1], 0x252e39);
            treadPoint(data, q, q[1], 0x252e39);
            treadPoint(data, q, base, 0x141a22);
            treadPoint(data, p, p[1], 0x252e39);
            treadPoint(data, q, base, 0x141a22);
            treadPoint(data, p, base, 0x141a22);
        }
    }

    private static void treadPoint(FloatBuffer data, float[] polar, float radius, int color) {
        colorPoint(data, polar[0], radius * (float) Math.cos(polar[2]),
                radius * (float) Math.sin(polar[2]), color);
    }

    private GpuMesh wheelMesh(int sides) {
        float[] width = {-1, -.98f, -.85f, -.59f, -.32f, -.21f, .21f, .32f, .59f, .85f, .98f, 1};
        float[] radius = {.59f, .77f, .89f, .973f, 1, .986f, .986f, 1, .973f, .89f, .77f, .59f};
        float[] shade = {.074f, .097f, .144f, .146f, .120f, .068f, .068f, .120f, .146f, .144f, .097f, .074f};
        FloatBuffer data = vertices(sides * ((width.length - 1) * 6 + 48));
        for (int i = 0; i < sides; i++) {
            float a = i * 6.2831855f / sides, b = (i + 1) * 6.2831855f / sides;
            for (int ring = 1; ring < width.length; ring++) {
                boolean tread = ring >= 3 && ring <= 9;
                float groove = tread && ring % 3 == 0 ? .72f : 1;
                tireQuad(data, width[ring-1], width[ring], radius[ring-1], radius[ring], a, b,
                        gray(shade[ring-1] * groove), gray(shade[ring] * groove));
            }
            for (int side = -1; side <= 1; side += 2) {
                boolean spoke = i % 6 < 2;
                int outer = 0xabb3bb;
                int metal = 0x7a8a99;
                tireQuad(data, side, side*1.01f, .61f, .54f, a, b, outer, 0x4e5965);
                tireQuad(data, side*.92f, side*.89f, .54f, 0, a, b,
                        0x293038, 0x14191e);
                if (spoke) tireQuad(data, side*1.01f, side*1.04f, .55f, .19f, a, b, metal, outer);
                tireQuad(data, side*1.045f, side*1.07f, .19f, 0, a, b,
                        metal, 0xc0c7cf);
            }
        }
        return new GpuMesh(data, data.position() / 6);
    }

    private static int gray(float value) {
        int c = Math.round(value * 255);
        return c << 16 | (c+4) << 8 | (c+9);
    }


    private static void tireQuad(FloatBuffer data, float x0, float x1, float r0, float r1,
                                 float a, float b, int color0, int color1) {
        float ya = (float) Math.cos(a), za = (float) Math.sin(a);
        float yb = (float) Math.cos(b), zb = (float) Math.sin(b);
        colorPoint(data, x0, ya*r0, za*r0, color0);
        colorPoint(data, x1, ya*r1, za*r1, color1);
        colorPoint(data, x1, yb*r1, zb*r1, color1);
        colorPoint(data, x0, ya*r0, za*r0, color0);
        colorPoint(data, x1, yb*r1, zb*r1, color1);
        colorPoint(data, x0, yb*r0, zb*r0, color0);
    }

    private GpuMesh shadowMesh() {
        int sides = 20;
        FloatBuffer data = vertices(sides * 3);
        for (int i = 0; i < sides; i++) {
            double a = i * Math.PI * 2 / sides, b = (i + 1) * Math.PI * 2 / sides;
            point(data, 0, 0, 0, .11f, .072f, .061f);
            point(data, (float) Math.cos(a), 0, (float) Math.sin(a), .21f, .16f, .13f);
            point(data, (float) Math.cos(b), 0, (float) Math.sin(b), .21f, .16f, .13f);
        }
        return new GpuMesh(data, data.position() / 6);
    }

    private GpuMesh particleMesh(int base, int bright) {
        FloatBuffer data = vertices(24);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2, b = (i + 1) * Math.PI / 2;
            float ax = (float) Math.cos(a), az = (float) Math.sin(a);
            float bx = (float) Math.cos(b), bz = (float) Math.sin(b);
            colorPoint(data, 0, 1, 0, bright);
            colorPoint(data, ax, 0, az, base);
            colorPoint(data, bx, 0, bz, base);
            colorPoint(data, 0, -1, 0, base);
            colorPoint(data, bx, 0, bz, base);
            colorPoint(data, ax, 0, az, base);
        }
        return new GpuMesh(data, 24);
    }

    private GpuMesh cloudMesh(int center, int edge) {
        int sides = 16;
        FloatBuffer data = vertices(sides * 3);
        for (int i = 0; i < sides; i++) {
            double a = i * 2 * Math.PI / sides, b = (i + 1) * 2 * Math.PI / sides;
            colorPoint(data, 0, 0, 0, center);
            colorPoint(data, (float) Math.cos(a), 0, (float) Math.sin(a), edge);
            colorPoint(data, (float) Math.cos(b), 0, (float) Math.sin(b), edge);
        }
        return new GpuMesh(data, sides * 3);
    }

    private static void colorPoint(FloatBuffer data, float x, float y, float z, int rgb) {
        point(data, x, y, z, ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
    }

    private void updateEffects() {
        for (Spark spark : sparks) {
            if (spark.life <= 0) continue;
            spark.life -= STEP;
            spark.x += spark.vx * STEP;
            spark.y += spark.vy * STEP;
            spark.z += spark.vz * STEP;
            spark.vy -= (spark.kind == 1 ? .55f : 12f) * STEP;
        }
        for (TireMark mark : tireMarks) if (mark.life > 0) mark.life -= STEP;
        World.Body player = world.player;
        impact(player, truck);
        for (int i = 0; i < cars.length; i++) {
            World.Body car = world.traffic[i];
            impact(car, cars[i]);
        }
        wheelEffects(player);
        updateEngineSmoke(player);
    }

    static int engineSmokeStage(float health, float maxHealth) {
        double percent = health * 100.0;
        if (maxHealth <= 0 || percent >= maxHealth * 60.0) return 0;
        if (percent >= maxHealth * 35.0) return 1;
        if (percent >= maxHealth * 15.0) return 2;
        return 3;
    }

    private void clearEngineSmoke() {
        for (EnginePuff puff : engineSmoke) puff.life = 0;
        engineSmokeClock = 0;
        nextEnginePuff = 0;
    }

    private void updateEngineSmoke(World.Body player) {
        for (EnginePuff puff : engineSmoke) {
            if (puff.life <= 0) continue;
            puff.previousX = puff.x;
            puff.previousY = puff.y;
            puff.previousZ = puff.z;
            puff.life -= STEP;
            puff.x += puff.vx * STEP;
            puff.y += puff.vy * STEP;
            puff.z += puff.vz * STEP;
            puff.vy += .28f * STEP;
        }
        int stage = engineSmokeStage(player.health, player.maxHealth);
        if (stage == 0) return;
        engineSmokeClock -= STEP;
        if (engineSmokeClock > 0) return;
        engineSmokeClock = .24f - stage * .055f;

        positionBody(player.x, player.y, player.z, player.yaw, player.pitch, player.roll);
        float x = (effects.nextFloat() - .5f) * (.15f + stage * .11f);
        float y = truckDesign == 1 ? 1.79f : 2.08f;
        float z = 1.60f - .16f * player.dentFront;
        EnginePuff puff = engineSmoke[nextEnginePuff];
        nextEnginePuff = (nextEnginePuff + 1) % engineSmoke.length;
        puff.x = bodyMatrix[12] + bodyMatrix[0] * x + bodyMatrix[4] * y + bodyMatrix[8] * z;
        puff.y = bodyMatrix[13] + bodyMatrix[1] * x + bodyMatrix[5] * y + bodyMatrix[9] * z;
        puff.z = bodyMatrix[14] + bodyMatrix[2] * x + bodyMatrix[6] * y + bodyMatrix[10] * z;
        puff.y = Math.max(puff.y, World.rampHeight(puff.x, puff.z) + .15f);
        puff.previousX = puff.x;
        puff.previousY = puff.y;
        puff.previousZ = puff.z;
        puff.vx = player.vx * .23f + (effects.nextFloat() - .5f) * .52f;
        puff.vz = player.vz * .23f + (effects.nextFloat() - .5f) * .52f;
        puff.vy = .75f + stage * .17f + effects.nextFloat() * .28f;
        puff.life = puff.duration = .90f + stage * .23f + effects.nextFloat() * .21f;
        puff.stage = stage;
    }

    private void wheelEffects(World.Body player) {
        float velocity = (float) Math.hypot(player.vx, player.vz);
        dustClock -= STEP;
        if (dustClock > 0 || velocity < 1.7f || Math.abs(player.roll) > 1f) return;
        float fx = (float) Math.sin(player.yaw), fz = (float) Math.cos(player.yaw);
        float slip = Math.abs(player.vx * fz - player.vz * fx);
        boolean skid = slip > 2.1f || ((buttons & 8) != 0 && velocity > 5.5f);
        dustClock = Math.max(.037f, Math.min(.11f, .54f / velocity));
        float motion = Math.copySign(1f, player.vx * fx + player.vz * fz);
        float pitchSin = (float) Math.sin(player.pitch), pitchCos = (float) Math.cos(player.pitch);
        float rollSin = (float) Math.sin(player.roll), rollCos = (float) Math.cos(player.roll);
        float wheelY = World.TRUCK_WHEEL_Y - World.WHEEL_DENT_DROP * player.damage;
        float tireReach = World.TRUCK_WHEEL_RADIUS * (float) Math.hypot(pitchCos * rollCos, pitchSin)
                + World.TRUCK_WHEEL_HALF_WIDTH * Math.abs(pitchCos * rollSin);
        for (int index = 0; index < 4; index++) {
            if ((player.groundedWheels & (1 << index)) == 0) continue;
            boolean left = index < 2, rear = index % 2 == 0;
            float sideDent = left ? player.dentLeft : player.dentRight;
            float endDent = rear ? player.dentBack : player.dentFront;
            float localX = (left ? -1 : 1) * (World.TRUCK_WHEEL_X - .14f * sideDent);
            float localZ = (rear ? -1 : 1) * (World.TRUCK_WHEEL_Z - .20f * endDent);
            float lateral = localX * rollCos - wheelY * rollSin;
            float tiltedY = localX * rollSin + wheelY * rollCos;
            float forward = localZ * pitchCos + tiltedY * pitchSin;
            float x = player.x + forward * fx + lateral * fz;
            float z = player.z + forward * fz - lateral * fx;
            float ground = World.rampHeight(x, z);
            float contactY = player.y + tiltedY * pitchCos - localZ * pitchSin - tireReach;
            if (Math.abs(contactY - ground) > .33f) continue;
            boolean contactDirt = Geometry.onDirt(x, z);
            boolean trailing = localZ * motion < 0;
            if (!trailing && !skid) continue;
            Spark cloud = spawnEffect(x, ground + .17f, z, 1);
            cloud.vx -= player.vx * .18f;
            cloud.vz -= player.vz * .18f;
            cloud.smoke = !contactDirt;
            cloud.strength = contactDirt || skid ? 1 : .44f;
            if (contactDirt) {
                Spark grit = spawnEffect(x, ground + .12f, z, 2);
                grit.vx -= player.vx * .40f;
                grit.vz -= player.vz * .40f;
            }
            if (ground > .015f || (!contactDirt && !skid)) continue;
            TireMark mark = tireMarks[nextMark];
            nextMark = (nextMark + 1) % tireMarks.length;
            mark.x = x;
            mark.z = z;
            mark.yaw = player.yaw;
            mark.halfLength = velocity * (dustClock + STEP) * .58f;
            mark.dirt = contactDirt;
            mark.life = 8;
        }
    }

    private void impact(World.Body body, VehicleMesh skin) {
        int detached = body.detachedWheels & ~skin.lastDetached;
        if (body.visible && Math.abs(body.x - world.player.x) + Math.abs(body.z - world.player.z) < 48) {
            if (body.damage - skin.lastDamage > .025f) {
                for (int i = 0; i < 6; i++) spawnEffect(body.x, .7f + body.y, body.z, 0);
            }
            if (detached != 0) {
                float fx = (float) Math.sin(body.yaw), fz = (float) Math.cos(body.yaw);
                for (int i = 0; i < body.axleCount() * 2; i++) {
                    if ((detached & (1 << i)) == 0) continue;
                    float x = body.wheelX(i);
                    float z = body.wheelZ(i);
                    for (int j = 0; j < 4; j++) spawnEffect(body.x + x*fz + z*fx,
                            body.y + body.wheelY(), body.z - x*fx + z*fz, 0);
                }
            }
        }
        skin.lastDamage = body.damage;
        skin.lastDetached = body.detachedWheels;
    }

    private Spark spawnEffect(float x, float y, float z, int kind) {
        Spark spark = sparks[nextSpark];
        nextSpark = (nextSpark + 1) % sparks.length;
        boolean dust = kind == 1;
        spark.x = x;
        spark.y = y;
        spark.z = z;
        spark.vx = (effects.nextFloat() - .5f) * (dust ? 1.7f : 11);
        spark.vy = (dust ? .45f : 2) + effects.nextFloat() * (dust ? .7f : 4);
        spark.vz = (effects.nextFloat() - .5f) * (dust ? 1.7f : 11);
        spark.life = (dust ? .66f : .24f) + effects.nextFloat() * .24f;
        spark.kind = kind;
        spark.smoke = false;
        spark.strength = 1;
        return spark;
    }

    private void drawTireMarks() {
        tireMarkData.clear();
        for (TireMark mark : tireMarks) {
            if (mark.life <= 0) continue;
            float sin = (float) Math.sin(mark.yaw), cos = (float) Math.cos(mark.yaw);
            float lx = mark.halfLength * sin, lz = mark.halfLength * cos;
            float wx = .36f * cos, wz = -.36f * sin;
            int color = mark.dirt ? 0x6c4029 : 0x182032;
            if (mark.life < 2) {
                int ground = mark.dirt ? 0xa36c43 : 0x434f62;
                color = blendColor(ground, color, mark.life / 2);
            }
            float x = mark.x, z = mark.z;
            float y = mark.dirt ? .024f : .022f;
            colorPoint(tireMarkData, x-lx-wx, y, z-lz-wz, color);
            colorPoint(tireMarkData, x-lx+wx, y, z-lz+wz, color);
            colorPoint(tireMarkData, x+lx+wx, y, z+lz+wz, color);
            colorPoint(tireMarkData, x-lx-wx, y, z-lz-wz, color);
            colorPoint(tireMarkData, x+lx+wx, y, z+lz+wz, color);
            colorPoint(tireMarkData, x+lx-wx, y, z+lz-wz, color);
        }
        int count = tireMarkData.position() / 6;
        if (count == 0) return;
        tireMarkMesh.upload(tireMarkData, count);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDepthMask(false);
        GLES20.glUniform1f(opacity, .34f);
        Matrix.setIdentityM(model, 0);
        drawModel(tireMarkMesh);
        GLES20.glUniform1f(opacity, 1);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static int blendColor(int from, int to, float mix) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int base = from >> shift & 255;
            color |= Math.round(base + ((to >> shift & 255) - base) * mix) << shift;
        }
        return color;
    }

    private void drawEffects() {
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDepthMask(false);
        for (Spark spark : sparks) {
            if (spark.life <= 0 || spark.y < 0) continue;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, spark.x, spark.y, spark.z);
            boolean dust = spark.kind == 1;
            if (dust) {
                Matrix.rotateM(model, 0, degrees(cameraYaw), 0, 1, 0);
                Matrix.rotateM(model, 0, 69, 1, 0, 0);
            } else {
                Matrix.rotateM(model, 0, spark.life * 240, 0, 1, 0);
            }
            float size = dust ? .45f + 1.05f * (.90f - spark.life)
                    : spark.kind == 2 ? .068f : .09f + .30f * spark.life;
            Matrix.scaleM(model, 0, size, size * 1.45f, size);
            GLES20.glUniform1f(opacity, dust ? spark.strength * spark.life * .53f : 1);
            GLES20.glUniform1i(softShadow, dust ? 1 : 0);
            drawModel(dust ? (spark.smoke ? smokeMesh : dustMesh) : spark.kind == 2 ? gritMesh : sparkMesh);
        }
        drawEngineSmoke();
        GLES20.glUniform1i(softShadow, 0);
        GLES20.glUniform1f(opacity, 1);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawEngineSmoke() {
        GLES20.glUniform1i(softShadow, 1);
        for (int i = 0; i < engineSmoke.length; i++) {
            EnginePuff puff = engineSmoke[(nextEnginePuff + i) % engineSmoke.length];
            if (puff.life <= 0) continue;
            float progress = 1f - puff.life / puff.duration;
            float size = .18f + puff.stage * .08f + progress * (.37f + puff.stage * .15f);
            float height = size * 1.16f;
            Matrix.setIdentityM(model, 0);
            // The cloud mesh lies in XZ; align those axes with the camera's right and up.
            model[0] = view[0] * size;
            model[1] = view[4] * size;
            model[2] = view[8] * size;
            model[8] = view[1] * height;
            model[9] = view[5] * height;
            model[10] = view[9] * height;
            model[12] = lerp(puff.previousX, puff.x, blend);
            model[13] = lerp(puff.previousY, puff.y, blend);
            model[14] = lerp(puff.previousZ, puff.z, blend);
            float fade = Math.min(1f, progress * 14f) * (1f - progress);
            GLES20.glUniform1f(opacity, fade * (.40f + puff.stage * .13f));
            drawModel(engineSmokeMeshes[puff.stage - 1]);
        }
    }

    private static FloatBuffer vertices(int count) {
        return ByteBuffer.allocateDirect(count * 24).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void point(FloatBuffer data, float x, float y, float z, float r, float g, float b) {
        data.put(x).put(y).put(z).put(r).put(g).put(b);
    }

    private final class GpuMesh {
        final int id;
        int count;

        GpuMesh(FloatBuffer vertices, int count) {
            GLES20.glGenBuffers(1, bufferName, 0);
            id = bufferName[0];
            upload(vertices, count);
        }

        void upload(FloatBuffer vertices, int count) {
            this.count = count;
            vertices.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, count * 24, vertices, GLES20.GL_STATIC_DRAW);
        }

        void delete() {
            bufferName[0] = id;
            GLES20.glDeleteBuffers(1, bufferName, 0);
        }
    }

    private static float lerp(float previous, float current, float fraction) {
        return previous + (current - previous) * fraction;
    }

    private static float lerpAngle(float previous, float current, float fraction) {
        float angle = current - previous;
        return previous + (float) Math.atan2(Math.sin(angle), Math.cos(angle)) * fraction;
    }

    private static final class Pose {
        float x, y, z, yaw, pitch, roll, wheelSpin;
        boolean visible;

        void capture(World.Body body) {
            x = body.x;
            y = body.y;
            z = body.z;
            yaw = body.yaw;
            pitch = body.pitch;
            roll = body.roll;
            wheelSpin = body.wheelSpin;
            visible = body.visible;
        }

        float fraction(World.Body body, float blend) {
            // A recycled car may be visible at both ends of a step, but in a different district.
            return visible && Math.abs(x - body.x) < 5f && Math.abs(z - body.z) < 5f ? blend : 1f;
        }
    }

    private static final class VehicleMesh {
        final Pose previous = new Pose();
        GpuMesh gpu;
        int state;
        float lastDamage;
        int lastDetached;
    }

    private static final class TireMark {
        float x, z, yaw, life, halfLength;
        boolean dirt;
    }

    private static final class EnginePuff {
        float x, y, z, previousX, previousY, previousZ, vx, vy, vz, life, duration;
        int stage;
    }

    private static final class Spark {
        float x, y, z, vx, vy, vz, life, strength;
        int kind;
        boolean smoke;
    }
}
