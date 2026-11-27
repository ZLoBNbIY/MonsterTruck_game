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
    private final GpuMesh[] stock = new GpuMesh[32];
    private final Spark[] sparks = new Spark[112];
    private final TireMark[] tireMarks = new TireMark[240];
    private final FloatBuffer tireMarkData = vertices(tireMarks.length * 6);
    private final Random effects = new Random(37);
    private final float[] model = new float[16], bodyMatrix = new float[16];
    private final float[] projection = new float[16], view = new float[16];
    private final float[] vp = new float[16], mvp = new float[16];
    private final int[] bufferName = new int[1];
    private int position, tint, transform, haze, opacity, terrain, groundOrigin, softShadow;
    private GpuMesh sky, truckTire, carTire, distantTire, shadow, sparkMesh, dustMesh, smokeMesh, gritMesh, tireMarkMesh;
    private GpuMesh treeMesh, stumpMesh;
    private int nextSpark, nextMark;
    private float dustClock, steeringAngle;
    private long lastFrame;
    private float accumulated, cameraX, cameraZ, cameraY, cameraYaw;
    private boolean cameraReady;

    volatile int buttons, speed, crushed;
    volatile int health = 250, maxHealth = 250;
    volatile float mapX, mapZ, mapYaw;

    GameRenderer() {
        for (int i = 0; i < cars.length; i++) cars[i] = new VehicleMesh();
        for (int i = 0; i < sparks.length; i++) sparks[i] = new Spark();
        for (int i = 0; i < tireMarks.length; i++) tireMarks[i] = new TireMark();
    }

    void restart() {
        world.reset();
        cameraReady = false;
        steeringAngle = 0;
        for (Spark spark : sparks) spark.life = 0;
        for (TireMark mark : tireMarks) mark.life = 0;
        dustClock = 0;
        for (VehicleMesh car : cars) {
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
        sky = skyMesh();
        truckTire = wheelMesh(true, 48);
        carTire = wheelMesh(false, 36);
        distantTire = wheelMesh(false, 12);
        shadow = shadowMesh();
        sparkMesh = particleMesh(0xffc45b, 0xffe89f);
        dustMesh = cloudMesh(0xc39770, 0xe1bb91);
        smokeMesh = cloudMesh(0x939ba9, 0xc3c9d1);
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
        accumulated += elapsed;
        int input = buttons;
        int steer = ((input & 1) != 0 ? 1 : 0) - ((input & 2) != 0 ? 1 : 0);
        while (accumulated >= STEP) {
            World.Body player = world.player;
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
        World.Body player = world.player;
        speed = Math.round(3.6f * (float) Math.hypot(player.vx, player.vz));
        crushed = world.crushed;
        maxHealth = Math.round(player.maxHealth);
        health = player.health > 0 ? (int) Math.ceil(player.health) : 0;
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
            if (car.visible && inView(car.x, car.z, 8, 189)) drawVehicle(car, cars[i], false);
        }
        for (World.LooseWheel wheel : world.looseWheels) {
            if (wheel.visible && inView(wheel.x, wheel.z, 2, 118)) drawLooseWheel(wheel);
        }
        drawEffects();
    }

    private void updateCamera(World.Body player, float elapsed) {
        float mix = 1 - (float) Math.exp(-elapsed * 9);
        if (!cameraReady) {
            cameraYaw = player.yaw;
        } else {
            float angle = player.yaw - cameraYaw;
            cameraYaw += (float) Math.atan2(Math.sin(angle), Math.cos(angle)) * mix;
        }
        float forwardX = (float) Math.sin(cameraYaw), forwardZ = (float) Math.cos(cameraYaw);
        float distance = 9.3f + Math.min(speed, 90) * .023f;
        float shoulder = 5.1f;
        float targetX = player.x - forwardX * distance - forwardZ * shoulder;
        float targetZ = player.z - forwardZ * distance + forwardX * shoulder;
        float targetY = 6.4f + player.y * .65f;
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
                player.x + forwardX * 5, .9f + player.y * .45f, player.z + forwardZ * 5, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
    }

    private boolean inView(float x, float z, float radius, float distance) {
        float dx = x - cameraX, dz = z - cameraZ;
        float lateral = view[0] * dx + view[8] * dz;
        float depth = -(view[2] * dx + view[10] * dz);
        return depth >= -radius && depth <= distance + radius
                && Math.abs(lateral) <= Math.max(0, depth) / projection[0] + radius;
    }

    private void drawCity() {
        int x = (int) (world.player.x / World.BLOCK), z = (int) (world.player.z / World.BLOCK);
        GLES20.glUniformMatrix4fv(transform, 1, false, vp, 0);
        for (int row = Math.max(0, z - TILE_RADIUS); row <= Math.min(World.GRID - 1, z + TILE_RADIUS); row++) {
            for (int col = Math.max(0, x - TILE_RADIUS); col <= Math.min(World.GRID - 1, x + TILE_RADIUS); col++) {
                if (!inView((col + .7f) * World.BLOCK, (row + .7f) * World.BLOCK, 74, 276)) continue;
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
                if (!inView((col + .5f) * World.BLOCK, (row + .5f) * World.BLOCK, 48, 185)) continue;
                for (World.Tree tree : world.trees(col, row)) {
                    if (!tree.uprooted) {
                        drawTree(tree);
                    } else if (inView(tree.rootX, tree.rootZ, 2, 125)) {
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
        if (!inView(tree.x, tree.z, 3, 185)) return;
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, tree.x, tree.y, tree.z);
        if (tree.uprooted) {
            Matrix.rotateM(model, 0, degrees(tree.yaw), 0, 1, 0);
            Matrix.rotateM(model, 0, degrees(tree.pitch), 1, 0, 0);
        }
        drawModel(treeMesh);
    }

    private void drawVehicle(World.Body body, VehicleMesh skin, boolean isTruck) {
        drawShadow(body.x, body.z, body.yaw, isTruck ? 2.20f : 1.15f,
                isTruck ? 3.15f : 2.06f, .31f / (1 + body.y * .65f));
        Matrix.setIdentityM(bodyMatrix, 0);
        Matrix.translateM(bodyMatrix, 0, body.x, body.y, body.z);
        Matrix.rotateM(bodyMatrix, 0, degrees(body.yaw), 0, 1, 0);
        Matrix.rotateM(bodyMatrix, 0, degrees(body.pitch), 1, 0, 0);
        Matrix.rotateM(bodyMatrix, 0, degrees(body.roll), 0, 0, 1);
        System.arraycopy(bodyMatrix, 0, model, 0, 16);
        drawModel(vehicleMesh(body, skin, isTruck));

        float dx = body.x - cameraX, dz = body.z - cameraZ;
        GpuMesh tire = isTruck ? truckTire : dx*dx + dz*dz < 45*45 ? carTire : distantTire;
        for (int side = -1; side <= 1; side += 2) {
            for (int axle = -1; axle <= 1; axle += 2) {
                int index = (side < 0 ? 0 : 2) + (axle < 0 ? 0 : 1);
                if ((body.detachedWheels & (1 << index)) != 0) continue;
                System.arraycopy(bodyMatrix, 0, model, 0, 16);
                float bend = side < 0 ? body.dentLeft : body.dentRight;
                float endDent = axle > 0 ? body.dentFront : body.dentBack;
                Matrix.translateM(model, 0, side * (isTruck ? 1.54f : .86f) - side * .14f * bend,
                        (isTruck ? .92f : .35f) - .18f * body.damage,
                        axle * ((isTruck ? 1.69f : 1.29f) - .20f * endDent));
                float steer = isTruck && axle > 0 ? steeringAngle : 0;
                Matrix.rotateM(model, 0, steer + side * (bend * 21 + endDent * 7), 0, 1, 0);
                Matrix.rotateM(model, 0, degrees(body.wheelSpin), 1, 0, 0);
                float r = isTruck ? .91f : .35f;
                Matrix.scaleM(model, 0, isTruck ? .48f : .20f, r, r);
                drawModel(tire);
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
        int damage = quantize(body.damage), front = quantize(body.dentFront), back = quantize(body.dentBack);
        int left = quantize(body.dentLeft), right = quantize(body.dentRight);
        int state = ((body.color & 255) << 20) | (damage << 16) | (front << 12) | (back << 8) | (left << 4) | right;
        if (!isTruck && (state & 0xfffff) == 0 && body.color >= 0 && body.color < stock.length) {
            if (stock[body.color] == null) {
                Geometry.Mesh mesh = Geometry.vehicle(false, body.color, 0, 0, 0, 0, 0);
                stock[body.color] = new GpuMesh(mesh.data, mesh.count);
            }
            return stock[body.color];
        }
        if (skin.gpu == null || skin.state != state) {
            Geometry.Mesh mesh = Geometry.vehicle(isTruck, body.color,
                    front / 15f, back / 15f, left / 15f, right / 15f, damage / 15f);
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

    private GpuMesh wheelMesh(boolean truckWheel, int sides) {
        float[] width = {-1, -.98f, -.85f, -.59f, -.32f, -.21f, .21f, .32f, .59f, .85f, .98f, 1};
        float[] radius = {.59f, .77f, .89f, .973f, 1, .986f, .986f, 1, .973f, .89f, .77f, .59f};
        float[] shade = {.074f, .097f, .144f, .146f, .120f, .068f, .068f, .120f, .146f, .144f, .097f, .074f};
        FloatBuffer data = vertices(sides * ((width.length - 1) * 6 + (truckWheel ? 132 : 48)));
        for (int i = 0; i < sides; i++) {
            float a = i * 6.2831855f / sides, b = (i + 1) * 6.2831855f / sides;
            for (int ring = 1; ring < width.length; ring++) {
                boolean tread = ring >= 3 && ring <= 9;
                float groove = tread && (i + ring / 2) % (truckWheel ? 4 : 3) == 0 ? .66f : 1;
                float lug = truckWheel && tread && (i + ring / 2) % 4 >= 2 ? .042f : 0;
                tireQuad(data, width[ring-1], width[ring], radius[ring-1] + lug, radius[ring] + lug, a, b,
                        gray(shade[ring-1] * groove), gray(shade[ring] * groove));
                if (lug > 0) {
                    treadEdge(data, width[ring-1], width[ring], radius[ring-1], radius[ring], lug, a);
                    treadEdge(data, width[ring-1], width[ring], radius[ring-1], radius[ring], lug, b);
                }
            }
            for (int side = -1; side <= 1; side += 2) {
                boolean spoke = i % (truckWheel ? 8 : 6) < 2;
                int outer = truckWheel ? 0x37c0f1 : 0xabb3bb;
                int metal = truckWheel ? 0xffb632 : 0x7a8a99;
                tireQuad(data, side, side*1.01f, .61f, .54f, a, b, outer, 0x4e5965);
                tireQuad(data, side*.92f, side*.89f, .54f, 0, a, b,
                        truckWheel ? 0x197aae : 0x293038, truckWheel ? 0x095471 : 0x14191e);
                if (spoke) tireQuad(data, side*1.01f, side*1.04f, .55f, .19f, a, b, metal, outer);
                tireQuad(data, side*1.045f, side*1.07f, .19f, 0, a, b,
                        metal, truckWheel ? 0xffdb6c : 0xc0c7cf);
            }
        }
        return new GpuMesh(data, data.position() / 6);
    }

    private static int gray(float value) {
        int c = Math.round(value * 255);
        return c << 16 | (c+4) << 8 | (c+9);
    }

    private static void treadEdge(FloatBuffer data, float x0, float x1, float r0, float r1,
                                  float lug, float angle) {
        float y = (float) Math.cos(angle), z = (float) Math.sin(angle);
        int low = 0x151c24, high = 0x303844;
        colorPoint(data, x0, y*r0, z*r0, low);
        colorPoint(data, x1, y*r1, z*r1, low);
        colorPoint(data, x1, y*(r1+lug), z*(r1+lug), high);
        colorPoint(data, x0, y*r0, z*r0, low);
        colorPoint(data, x1, y*(r1+lug), z*(r1+lug), high);
        colorPoint(data, x0, y*(r0+lug), z*(r0+lug), high);
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
    }

    private void wheelEffects(World.Body player) {
        float velocity = (float) Math.hypot(player.vx, player.vz);
        dustClock -= STEP;
        if (dustClock > 0 || velocity < 3.1f
                || player.y > World.rampHeight(player.x, player.z) + .34f) return;
        float fx = (float) Math.sin(player.yaw), fz = (float) Math.cos(player.yaw);
        float slip = Math.abs(player.vx * fz - player.vz * fx);
        boolean skid = slip > 2.1f || ((buttons & 8) != 0 && velocity > 5.5f);
        boolean dirt = Geometry.onDirt(player.x, player.z);
        if (!dirt && !skid) return;
        dustClock = Math.max(.029f, .52f / velocity);
        float motion = Math.copySign(1f, player.vx * fx + player.vz * fz);
        for (int side = -1; side <= 1; side += 2) {
            int index = (side < 0 ? 0 : 2) + (motion < 0 ? 1 : 0);
            if ((player.detachedWheels & (1 << index)) != 0) continue;
            float x = player.x - motion * 1.69f * fx + side * 1.54f * fz;
            float z = player.z - motion * 1.69f * fz - side * 1.54f * fx;
            float ground = World.rampHeight(x, z);
            if (ground > .015f) continue;
            boolean contactDirt = Geometry.onDirt(x, z);
            if (!contactDirt && !skid) continue;
            Spark cloud = spawnEffect(x, .16f, z, 1);
            cloud.vx -= player.vx * .18f;
            cloud.vz -= player.vz * .18f;
            cloud.smoke = !contactDirt;
            if (contactDirt) {
                Spark grit = spawnEffect(x, .12f, z, 2);
                grit.vx -= player.vx * .40f;
                grit.vz -= player.vz * .40f;
            }
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
                boolean player = body == world.player;
                for (int i = 0; i < 4; i++) {
                    if ((detached & (1 << i)) == 0) continue;
                    float x = (i < 2 ? -1 : 1) * (player ? 1.54f : .86f);
                    float z = (i % 2 == 0 ? -1 : 1) * (player ? 1.69f : 1.29f);
                    for (int j = 0; j < 4; j++) spawnEffect(body.x + x*fz + z*fx,
                            body.y + (player ? .92f : .35f), body.z - x*fx + z*fz, 0);
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
            GLES20.glUniform1f(opacity, dust ? Math.min(.37f, spark.life * .72f) : 1);
            GLES20.glUniform1i(softShadow, dust ? 1 : 0);
            drawModel(dust ? (spark.smoke ? smokeMesh : dustMesh) : spark.kind == 2 ? gritMesh : sparkMesh);
        }
        GLES20.glUniform1i(softShadow, 0);
        GLES20.glUniform1f(opacity, 1);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
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

    private static final class VehicleMesh {
        GpuMesh gpu;
        int state;
        float lastDamage;
        int lastDetached;
    }

    private static final class TireMark {
        float x, z, yaw, life, halfLength;
        boolean dirt;
    }

    private static final class Spark {
        float x, y, z, vx, vy, vz, life;
        int kind;
        boolean smoke;
    }
}
