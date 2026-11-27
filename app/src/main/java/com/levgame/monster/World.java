package com.levgame.monster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class World {
    public static final int GRID = 32;
    public static final float BLOCK = 64f;
    public static final float ROAD = 16f;
    public static final int YARD_X = GRID / 2 - 1;
    public static final int YARD_Z = GRID / 2 - 1;
    public static final float YARD_MIN_X = (YARD_X + 0.5f) * BLOCK + ROAD * 0.5f;
    public static final float YARD_MIN_Z = (YARD_Z + 0.5f) * BLOCK + ROAD * 0.5f;
    public static final float YARD_MAX_X = YARD_MIN_X + BLOCK - ROAD;
    public static final float YARD_MAX_Z = YARD_MIN_Z + BLOCK - ROAD;
    public static final float SPAWN_X = YARD_MIN_X + 24f;
    public static final float SPAWN_Z = YARD_MIN_Z + 8f;
    public static final float SPAWN_YAW = 0f;
    public static final int RAMP_COUNT = 2;

    private static final float SIZE = GRID * BLOCK;
    private static final double TICK_SECONDS = 1.0 / 120.0;
    private static final float TICK = (float) TICK_SECONDS;
    private static final float LANE = 3.1f;
    private static final float JUNCTION = ROAD * 0.5f + 2f;
    private static final float PI = (float) Math.PI;
    private static final float GRAVITY = 14f;
    private static final float CAR_MAX_HEALTH = 100f;
    private static final float TRUCK_MAX_HEALTH = 250f;
    // Keep the truck over four times as heavy as even the heaviest traffic car (1,410 kg).
    private static final float TRUCK_MASS = 5700f;
    private static final float CAR_HEALTH_PER_JOULE = 0.00055f;
    private static final float TRUCK_HEALTH_PER_JOULE = 0.00018f;
    private static final float SAFE_LANDING_SPEED = 7.5f;
    private static final float CAR_WHEEL_BREAK_ENERGY = 35_000f;
    private static final float TRUCK_WHEEL_BREAK_ENERGY = 165_000f;
    private static final int LOOSE_WHEEL_LIFETIME = 14 * 120;
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {1, 0, -1, 0};
    // min X, min Z (toe), max X, max Z (lip), lip height. Both rise toward +Z.
    private static final float[][] RAMPS = {
        {YARD_MIN_X + 7f, YARD_MIN_Z + 12f, YARD_MIN_X + 15f, YARD_MIN_Z + 26f, 2.8f},
        {YARD_MIN_X + 32f, YARD_MIN_Z + 18f, YARD_MIN_X + 42f, YARD_MIN_Z + 34f, 4.4f}
    };
    // x, z, yaw. The two central targets are staggered; both sides remain open.
    private static final float[][] TARGETS = {
        {SPAWN_X - 1.1f, SPAWN_Z + 19f, 0.18f},
        {SPAWN_X + 1.5f, SPAWN_Z + 29f, -0.24f},
        {YARD_MIN_X + 12f, YARD_MIN_Z + 43f, PI * 0.5f}
    };
    private static final float[][][] BUILDINGS = createBuildings();

    public static final class Body {
        public float x, y, z, yaw, vx, vz;
        public float pitch, roll, wheelSpin;
        public float damage, dentFront, dentBack, dentLeft, dentRight;
        public float health;
        public final float maxHealth;
        public int color;
        /** Bits 0 through 3: left rear, left front, right rear, right front. */
        public int detachedWheels;
        public boolean visible;

        private final float halfWidth, halfLength, invMass, invInertia, radius;
        private float spin, verticalSpeed, stun, cruise;
        private int blockedTicks;
        private int gx, gz, direction, nextDirection, curveSample;
        private int reserved = -1;
        private boolean crossing, flattened, grounded, parked;

        private Body(float halfWidth, float halfLength, float mass, float maxHealth) {
            this.halfWidth = halfWidth;
            this.halfLength = halfLength;
            this.maxHealth = maxHealth;
            invMass = 1f / mass;
            invInertia = 3f * invMass / (halfWidth * halfWidth + halfLength * halfLength);
            radius = length(halfWidth, halfLength);
        }
    }

    public static final class LooseWheel {
        public boolean visible;
        /** Centre, dimensions and Y yaw / Z tilt / X spin angles in radians. */
        public float x, y, z, yaw, spin, tilt, radius, halfWidth;

        private float vx, vy, vz, yawRate, spinRate, tiltRate;
        private int age;

        private LooseWheel() {}
    }

    public static final class Tree {
        static final float SIDEWALK_HEIGHT = .10f;
        static final float PIVOT = 1.44f;
        static final float HEIGHT = 2.86f;
        static final float[] CROWN_HEIGHTS = {1.22f, 1.55f, 2.06f, 2.61f};
        static final float[] CROWN_RADII = {.18f, .68f, .81f, .49f};
        private static final float TRUNK_COLLIDER = .24f;
        private static final float EDGE_MARGIN = 1.7f;
        private static final float MASS = 155f;

        public final float rootX, rootY, rootZ;
        /** Mesh pivot and Y yaw / X pitch in radians. */
        public float x, y, z, yaw, pitch;
        public boolean uprooted;

        private float vx, vy, vz, yawRate, pitchRate, restPitch;
        private int tile;
        private boolean landed;

        private Tree(float x, float z) {
            rootX = this.x = x;
            rootZ = this.z = z;
            rootY = SIDEWALK_HEIGHT;
            y = rootY + PIVOT;
        }
    }

    private static final class TreeTile {
        final List<Tree> roots = new ArrayList<>(8);
        final List<Tree> fallen = new ArrayList<>();
        final List<Tree> rootView = Collections.unmodifiableList(roots);
        final List<Tree> fallenView = Collections.unmodifiableList(fallen);
    }

    public final Body player = new Body(1.55f, 2.6f, TRUCK_MASS, TRUCK_MAX_HEALTH);
    public final Body[] traffic = new Body[112];
    public final LooseWheel[] looseWheels = new LooseWheel[64];
    public int crushed;

    private final Body[] junctions = new Body[GRID * GRID];
    private final TreeTile[] treeTiles = new TreeTile[GRID * GRID];
    private final List<Tree> movingTrees = new ArrayList<>();
    private final Random random = new Random();
    private double accumulator;
    private int recycleIndex;
    private int recycleTicks;
    private int nextLooseWheel;

    // Scratch state for the separating-axis test. The simulation is single-threaded.
    private float normalX, normalZ, overlap;

    public World() {
        for (int i = 0; i < traffic.length; i++) {
            traffic[i] = new Body(0.94f, 1.95f, 1150f + 65f * (i % 5), CAR_MAX_HEALTH);
        }
        Arrays.setAll(looseWheels, i -> new LooseWheel());
        reset();
    }

    public static int buildingCount(int gx, int gz) {
        if (gx < 0 || gz < 0 || gx >= GRID - 1 || gz >= GRID - 1) return 0;
        return BUILDINGS[gz * (GRID - 1) + gx].length;
    }

    /** Original trees in this tile. Uprooted entries mark the location of the stump. */
    public List<Tree> trees(int gx, int gz) {
        if (gx < 0 || gx >= GRID || gz < 0 || gz >= GRID) return Collections.emptyList();
        return treeTile(gx, gz).rootView;
    }

    /** Uprooted trees whose mesh pivots are currently in this tile. */
    public List<Tree> fallenTrees(int gx, int gz) {
        if (gx < 0 || gx >= GRID || gz < 0 || gz >= GRID) return Collections.emptyList();
        TreeTile tile = treeTiles[gz * GRID + gx];
        return tile == null ? Collections.emptyList() : tile.fallenView;
    }

    private TreeTile treeTile(int gx, int gz) {
        int index = gz * GRID + gx;
        TreeTile tile = treeTiles[index];
        if (tile != null) return tile;
        tile = treeTiles[index] = new TreeTile();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int lotX = sx > 0 ? gx : gx - 1, lotZ = sz > 0 ? gz : gz - 1;
                if (lotX == YARD_X && lotZ == YARD_Z) continue;
                float edgeX = road(gx) + sx * ROAD * .5f;
                float edgeZ = road(gz) + sz * ROAD * .5f;
                // Retain alternating spots from the original sidewalk rows.
                float step = ((gx + gz + (sx == sz ? 1 : 0)) & 1) == 0 ? 4.8f : 14.3f;
                tile.roots.add(new Tree(edgeX + sx * .90f, edgeZ + sz * step));
                tile.roots.add(new Tree(edgeX + sx * step, edgeZ + sz * .90f));
            }
        }
        return tile;
    }

    public static void building(int gx, int gz, int i, float[] out) {
        if (i < 0 || i >= buildingCount(gx, gz)) throw new IndexOutOfBoundsException();
        System.arraycopy(BUILDINGS[gz * (GRID - 1) + gx][i], 0, out, 0, 6);
    }

    /** Copies [minX, toeZ, maxX, lipZ, lipHeight], in world metres. */
    public static void ramp(int index, float[] out) {
        System.arraycopy(RAMPS[index], 0, out, 0, 5);
    }

    static float rampHeight(float x, float z) {
        for (float[] ramp : RAMPS) {
            if (x >= ramp[0] && x <= ramp[2] && z >= ramp[1] && z <= ramp[3]) {
                return (z - ramp[1]) * rampSlope(ramp);
            }
        }
        return 0f;
    }

    private static float rampSlope(float[] ramp) {
        return ramp[4] / (ramp[3] - ramp[1]);
    }

    private static float[][][] createBuildings() {
        Random layout = new Random(0x4c4556L);
        float[][][] result = new float[(GRID - 1) * (GRID - 1)][][];
        float lot = (BLOCK - ROAD) * 0.5f;
        for (int gz = 0; gz < GRID - 1; gz++) {
            for (int gx = 0; gx < GRID - 1; gx++) {
                int count = 2 + layout.nextInt(3);
                int firstLot = layout.nextInt(4);
                float[][] block = new float[count][6];
                for (int i = 0; i < count; i++) {
                    int slot = (firstLot + i) % 4;
                    float[] b = block[i];
                    b[0] = road(gx) + ROAD * 0.5f + lot * ((slot % 2) + 0.5f);
                    b[1] = road(gz) + ROAD * 0.5f + lot * ((slot / 2) + 0.5f);
                    b[2] = lot * (0.28f + 0.15f * layout.nextFloat());
                    b[3] = lot * (0.28f + 0.15f * layout.nextFloat());
                    b[4] = 7f + layout.nextFloat() * 26f;
                    b[5] = layout.nextInt(6);
                }
                result[gz * (GRID - 1) + gx] = gx == YARD_X && gz == YARD_Z ? new float[0][] : block;
            }
        }
        return result;
    }

    public void reset() {
        Arrays.fill(treeTiles, null);
        movingTrees.clear();
        Arrays.fill(junctions, null);
        random.setSeed(0x54524146464943L);
        accumulator = 0.0;
        recycleTicks = recycleIndex = crushed = 0;
        nextLooseWheel = 0;
        for (LooseWheel wheel : looseWheels) wheel.visible = false;
        for (Body car : traffic) clear(car);
        clear(player);
        player.x = SPAWN_X;
        player.z = SPAWN_Z;
        player.yaw = SPAWN_YAW;
        player.visible = true;
        for (int i = 0; i < TARGETS.length; i++) {
            Body car = traffic[i];
            car.x = TARGETS[i][0];
            car.z = TARGETS[i][1];
            car.yaw = TARGETS[i][2];
            car.color = i + 1;
            car.visible = car.parked = true;
        }
        for (int i = TARGETS.length; i < traffic.length; i++) {
            place(traffic[i], i, 13f, 345f, 160);
        }
    }

    private static void clear(Body b) {
        b.x = b.y = b.z = b.vx = b.vz = b.yaw = 0f;
        b.pitch = b.roll = b.wheelSpin = b.spin = b.verticalSpeed = b.stun = b.cruise = 0f;
        b.damage = b.dentFront = b.dentBack = b.dentLeft = b.dentRight = 0f;
        b.health = b.maxHealth;
        b.detachedWheels = 0;
        b.color = b.gx = b.gz = b.direction = b.nextDirection = b.curveSample = b.blockedTicks = 0;
        b.reserved = -1;
        b.visible = b.crossing = b.flattened = b.parked = false;
        b.grounded = true;
    }

    /** Positive steer turns right; negative throttle reverses. */
    public void step(float dt, float steer, float throttle, float brake) {
        if (!(dt > 0f) || !Float.isFinite(dt)) return;
        steer = input(steer, -1f, 1f);
        throttle = input(throttle, -1f, 1f);
        brake = input(brake, 0f, 1f);
        accumulator += Math.min(dt, 0.25f);
        while (accumulator >= TICK_SECONDS) {
            tick(steer, throttle, brake);
            accumulator -= TICK_SECONDS;
        }
    }

    private void tick(float steer, float throttle, float brake) {
        boolean driveable = player.health > 0f;
        if (!driveable) steer = throttle = brake = 0f;
        float speed = forwardSpeed(player);
        float grip = player.grounded ? 1f : 0.12f;
        float steerRate = steer * clamp(speed * 0.22f, -1.35f, 1.65f);
        player.yaw = wrap(player.yaw + steerRate * grip * TICK);
        float deceleration = driveable ? brake * 23f : 5f;
        drive(player, throttle * grip * (throttle >= 0f ? 15f : 9f), deceleration * grip, grip * 7f, 43f, 12f);

        for (Body car : traffic) if (car.visible) driveTraffic(car);
        float oldX = player.x, oldZ = player.z, oldHeight = player.y, oldVertical = player.verticalSpeed;
        move(player);
        float slope = rampMotion(oldX, oldZ);
        float pitch = (float) -Math.atan(slope * cos(player.yaw)) - throttle * 0.05f
                + brake * 0.10f - player.verticalSpeed * 0.025f;
        player.pitch += (clamp(pitch, -0.4f, 0.4f) - player.pitch) * 8f * TICK;
        float roll = (float) -Math.atan(slope * sin(player.yaw)) - steerRate * speed * 0.004f;
        player.roll += (clamp(roll, -0.4f, 0.4f) - player.roll) * 8f * TICK;
        float landingSpeed = oldHeight >= 0.85f && player.y < 0.85f ? Math.max(0f, -oldVertical) : 0f;
        for (Body car : traffic) if (car.visible) move(car);

        // At the speed cap a vehicle moves less than half a metre per tick,
        // smaller than a car's half-width and the thinnest static collider.
        for (int i = 0; i < traffic.length; i++) {
            Body car = traffic[i];
            if (!car.visible) continue;
            if (player.y < 0.85f) collide(player, car, landingSpeed);
            for (int j = i + 1; j < traffic.length; j++) {
                Body other = traffic[j];
                if (other.visible) collide(car, other, 0f);
            }
        }
        scenery(player);
        collideTrees();
        moveTrees();
        for (Body car : traffic) if (car.visible) scenery(car);
        for (LooseWheel wheel : looseWheels) if (wheel.visible) moveLooseWheel(wheel);
        if (++recycleTicks == 15) {
            recycleTicks = 0;
            recycleTraffic();
        }
    }

    private float rampMotion(float oldX, float oldZ) {
        float oldSurface = rampHeight(oldX, oldZ);
        float surface = rampHeight(player.x, player.z);
        float slope = 0f;
        for (float[] ramp : RAMPS) {
            if (player.x < ramp[0] || player.x > ramp[2] || player.z < ramp[1] || player.z > ramp[3]) continue;
            // A tall side or the lip blocks entry from below; the toe is flush with the ground.
            if (surface > player.y + 0.25f && surface > oldSurface + 0.25f) {
                float f = sin(player.yaw), c = cos(player.yaw);
                if (oldZ > ramp[3]) wall(player, 0f, -1f, ramp[3] - player.z + 0.001f, f, c);
                else if (oldX < ramp[0]) wall(player, 1f, 0f, player.x - ramp[0] + 0.001f, f, c);
                else if (oldX > ramp[2]) wall(player, -1f, 0f, ramp[2] - player.x + 0.001f, f, c);
                surface = rampHeight(player.x, player.z);
            } else {
                slope = rampSlope(ramp);
            }
            break;
        }
        if (player.grounded && oldSurface > surface + 0.25f) {
            player.grounded = false;
            player.verticalSpeed = Math.max(0f, player.verticalSpeed);
        }
        if (!player.grounded) {
            player.y += player.verticalSpeed * TICK;
            player.verticalSpeed -= GRAVITY * TICK;
            if (player.y <= surface) {
                float closing = slope * player.vz - player.verticalSpeed;
                // The suspension absorbs ordinary ramp and car-climb landings.
                if (closing > SAFE_LANDING_SPEED) {
                    float excessEnergy = 0.5f * (closing * closing - SAFE_LANDING_SPEED * SAFE_LANDING_SPEED)
                            / player.invMass;
                    impactHealth(player, excessEnergy);
                }
                player.grounded = true;
            }
        }
        if (player.grounded) {
            player.y = surface;
            player.verticalSpeed = slope * player.vz;
        }
        return player.grounded ? slope : 0f;
    }

    private void driveTraffic(Body car) {
        int missing = car.detachedWheels;
        boolean disabled = (missing != 0 || car.health <= 0f) && !car.parked && !car.flattened;
        float roll = 0f, pitch = 0f;
        if (missing != 0 && !car.flattened) {
            roll = 0.045f * (Integer.bitCount(missing & 3) - Integer.bitCount(missing & 12));
            pitch = 0.025f * (Integer.bitCount(missing & 10) - Integer.bitCount(missing & 5));
        }
        car.roll += (roll - car.roll) * 5f * TICK;
        car.pitch += (pitch - car.pitch) * 5f * TICK;
        if (disabled) {
            car.blockedTicks++;
            drive(car, 0f, 6f, 4f, 43f, 43f);
            return;
        }
        if (car.parked || car.flattened || car.stun > 0f) {
            car.stun = Math.max(0f, car.stun - TICK);
            drive(car, 0f, car.parked || car.flattened ? 5f : 1f,
                    car.parked || car.flattened ? 4f : 1.5f, 43f, 43f);
            return;
        }

        int direction = car.direction;
        int dx = DX[direction], dz = DZ[direction];
        float cx = road(car.gx), cz = road(car.gz);
        float along = (car.x - cx) * dx + (car.z - cz) * dz;
        float entry = -JUNCTION - along;
        float speed = Math.max(0f, forwardSpeed(car));
        if (speed < 0.7f) car.blockedTicks++;
        else car.blockedTicks = 0;
        float targetSpeed = car.cruise * (1f - 0.45f * car.damage);
        float targetX = cx + dz * LANE + dx * Math.min(-JUNCTION, along + 7f);
        float targetZ = cz - dx * LANE + dz * Math.min(-JUNCTION, along + 7f);

        if (!car.crossing && entry < Math.max(7f, speed + 2f)) {
            int key = car.gz * GRID + car.gx;
            if (junctions[key] == null && exitFree(car, cx, cz)) {
                junctions[key] = car;
                car.reserved = key;
                car.crossing = true;
                car.curveSample = 1;
            } else {
                targetSpeed = Math.min(targetSpeed, (float) Math.sqrt(9f * Math.max(0f, entry - 0.5f)));
                // Aim along the lane even when a bumped car has overshot the stop line.
                targetX = cx + dz * LANE + dx * (along + 5f);
                targetZ = cz - dx * LANE + dz * (along + 5f);
            }
        }

        if (car.crossing) {
            int nx = DX[car.nextDirection], nz = DZ[car.nextDirection];
            boolean turn = car.nextDirection != direction;
            targetSpeed = Math.min(targetSpeed, turn ? 6.5f : 12f);
            float endX = cx + nz * LANE + nx * JUNCTION;
            float endZ = cz - nx * LANE + nz * JUNCTION;
            float startX = cx + dz * LANE - dx * JUNCTION;
            float startZ = cz - dx * LANE - dz * JUNCTION;
            // Follow points along a cubic with tangents aligned to both lanes.
            do {
                float t = car.curveSample / 8f;
                float u = 1f - t;
                float tangent = turn ? 6f : JUNCTION * 2f / 3f;
                targetX = u * u * (startX + 2f * t * startX + 3f * t * dx * tangent)
                        + t * t * (endX + 2f * u * endX - 3f * u * nx * tangent);
                targetZ = u * u * (startZ + 2f * t * startZ + 3f * t * dz * tangent)
                        + t * t * (endZ + 2f * u * endZ - 3f * u * nz * tangent);
                if (car.curveSample == 8 || distanceSq(car.x, car.z, targetX, targetZ) > 13f) break;
                car.curveSample++;
            } while (true);
            if (car.curveSample == 8 && distanceSq(car.x, car.z, endX, endZ) < 6.25f) {
                release(car);
                car.crossing = false;
                car.blockedTicks = 0;
                car.direction = car.nextDirection;
                car.gx += nx;
                car.gz += nz;
                car.nextDirection = chooseDirection(car);
                targetX = endX + nx * 6f;
                targetZ = endZ + nz * 6f;
            }
        }

        float desiredYaw = (float) Math.atan2(targetX - car.x, targetZ - car.z);
        car.yaw = wrap(car.yaw + clamp(wrap(desiredYaw - car.yaw) * 4f, -1.9f, 1.9f) * TICK);
        float acceleration = clamp((targetSpeed - speed) * 2.5f, -9f, 4.2f);
        acceleration = follow(car, speed, acceleration, Math.max(3f, targetSpeed));
        drive(car, Math.max(acceleration, 0f), Math.max(-acceleration, 0f), 10f, 24f, 6f);
    }

    private float follow(Body car, float speed, float acceleration, float targetSpeed) {
        float fx = sin(car.yaw), fz = cos(car.yaw);
        float bestGap = 30f;
        float leadSpeed = speed;
        for (int i = -1; i < traffic.length; i++) {
            Body lead = i < 0 ? player : traffic[i];
            if (lead == car || !lead.visible || lead.y >= 0.85f) continue;
            float px = lead.x - car.x, pz = lead.z - car.z;
            float along = px * fx + pz * fz;
            if (along <= 0f || along > 36f || Math.abs(px * fz - pz * fx) > 2.5f) continue;
            float gap = along - car.halfLength - lead.halfLength;
            if (gap < bestGap) {
                bestGap = gap;
                leadSpeed = lead.vx * fx + lead.vz * fz;
            }
        }
        if (bestGap >= 30f) return acceleration;
        float desiredGap = 1.6f + Math.max(0f, speed * 1.05f + speed * (speed - leadSpeed) / 9f);
        float ratio = speed / targetSpeed;
        float gapRatio = desiredGap / Math.max(0.2f, bestGap);
        float following = 4.2f * (1f - ratio * ratio * ratio * ratio - gapRatio * gapRatio);
        return Math.min(acceleration, Math.max(-12f, following));
    }

    private boolean exitFree(Body car, float cx, float cz) {
        int dx = DX[car.nextDirection], dz = DZ[car.nextDirection];
        for (int i = -1; i < traffic.length; i++) {
            Body other = i < 0 ? player : traffic[i];
            if (other == car || !other.visible || other.y >= 0.85f) continue;
            float x = other.x - cx, z = other.z - cz;
            float along = x * dx + z * dz;
            float lateral = x * dz - z * dx - LANE;
            if (along > JUNCTION - 2f && along < JUNCTION + 9f && Math.abs(lateral) < 2.7f) return false;
        }
        return true;
    }

    private int chooseDirection(Body car) {
        int roll = random.nextInt(10);
        int direction = (car.direction + (roll < 2 ? 1 : roll < 4 ? 3 : 0)) % 4;
        while (!validNode(car.gx + DX[direction], car.gz + DZ[direction])) {
            direction = (direction + 1) % 4;
            if (direction == (car.direction + 2) % 4) direction = (direction + 1) % 4;
        }
        return direction;
    }

    private static boolean validNode(int gx, int gz) {
        return gx >= 0 && gz >= 0 && gx < GRID && gz < GRID;
    }

    private boolean place(Body car, int index, float near, float far, int attempts) {
        int px = Math.round((player.x - BLOCK * 0.5f) / BLOCK);
        int pz = Math.round((player.z - BLOCK * 0.5f) / BLOCK);
        int range = (int) (far / BLOCK) + 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int gx = px + random.nextInt(2 * range + 1) - range;
            int gz = pz + random.nextInt(2 * range + 1) - range;
            int direction = random.nextInt(4);
            int dx = DX[direction], dz = DZ[direction];
            if (!validNode(gx, gz) || !validNode(gx - dx, gz - dz)) continue;
            float back = JUNCTION + 8f + random.nextFloat() * (BLOCK - 2f * (JUNCTION + 8f));
            float x = road(gx) + dz * LANE - dx * back;
            float z = road(gz) - dx * LANE - dz * back;
            float distance = distanceSq(x, z, player.x, player.z);
            if (distance < near * near || distance > far * far) continue;
            boolean clear = true;
            for (Body other : traffic) {
                if (other != car && other.visible && distanceSq(x, z, other.x, other.z) < 81f) {
                    clear = false;
                    break;
                }
            }
            if (!clear) continue;
            release(car);
            clear(car);
            car.x = x;
            car.z = z;
            car.gx = gx;
            car.gz = gz;
            car.direction = direction;
            car.nextDirection = chooseDirection(car);
            car.yaw = wrap(direction * PI * 0.5f);
            car.cruise = 9f + random.nextFloat() * 6f;
            car.vx = dx * car.cruise * 0.65f;
            car.vz = dz * car.cruise * 0.65f;
            car.color = index % 6;
            car.visible = true;
            return true;
        }
        return false;
    }

    private void recycleTraffic() {
        // Refill around the player as they travel, moving only cars far from them.
        for (int i = 0; i < 8; i++) {
            int index = TARGETS.length + recycleIndex++;
            if (recycleIndex == traffic.length - TARGETS.length) recycleIndex = 0;
            Body car = traffic[index];
            float far = car.flattened ? 360f : car.blockedTicks > 14 * 120 ? 100f : 500f;
            if (!car.visible || distanceSq(player.x, player.z, car.x, car.z) > far * far) {
                place(car, index, 290f, 415f, 30);
            }
        }
    }

    private void release(Body car) {
        if (car.reserved >= 0) {
            junctions[car.reserved] = null;
            car.reserved = -1;
        }
    }

    private static void drive(Body body, float acceleration, float brake, float grip,
                              float maxForward, float maxReverse) {
        float fx = sin(body.yaw), fz = cos(body.yaw);
        float forward = body.vx * fx + body.vz * fz;
        float lateral = body.vx * fz - body.vz * fx;
        forward += (acceleration - 0.0035f * forward * Math.abs(forward)) * TICK;
        forward -= Math.copySign(Math.min(Math.abs(forward), brake * TICK), forward);
        forward = clamp(forward, -maxReverse, maxForward);
        lateral *= 1f - grip * TICK;
        body.vx = fx * forward + fz * lateral;
        body.vz = fz * forward - fx * lateral;
        body.spin *= 1f - (1.3f + 0.35f * grip) * TICK;
    }

    private static void move(Body body) {
        float speed = length(body.vx, body.vz);
        if (speed > 48f) {
            body.vx *= 48f / speed;
            body.vz *= 48f / speed;
        }
        body.x += body.vx * TICK;
        body.z += body.vz * TICK;
        body.yaw = wrap(body.yaw + body.spin * TICK);
        body.wheelSpin = wrap(body.wheelSpin + forwardSpeed(body) * TICK
                / (body.halfWidth > 1f ? 0.8f : 0.36f));
    }

    private void collide(Body a, Body b, float landingSpeed) {
        float dx = b.x - a.x, dz = b.z - a.z;
        float range = a.radius + b.radius;
        if (dx * dx + dz * dz > range * range) return;
        collide(a, b, b.x, b.z, sin(b.yaw), cos(b.yaw), b.halfWidth, b.halfLength, landingSpeed);
    }

    private void scenery(Body body) {
        int gx = (int) Math.floor((body.x - BLOCK * 0.5f) / BLOCK);
        int gz = (int) Math.floor((body.z - BLOCK * 0.5f) / BLOCK);
        if (gx >= 0 && gx < GRID - 1 && gz >= 0 && gz < GRID - 1) {
            // Buildings have enough setback that a vehicle can only touch a building
            // in the quarter containing its centre.
            for (float[] b : BUILDINGS[gz * (GRID - 1) + gx]) {
                if (Math.abs(b[0] - body.x) <= b[2] + body.radius
                        && Math.abs(b[1] - body.z) <= b[3] + body.radius) {
                    collide(body, null, b[0], b[1], 0f, 1f, b[2], b[3], 0f);
                }
            }
        }

        float f = sin(body.yaw), c = cos(body.yaw);
        float rx = projection(1f, 0f, f, c, body.halfWidth, body.halfLength);
        float rz = projection(0f, 1f, f, c, body.halfWidth, body.halfLength);
        if (body.x < rx) wall(body, -1f, 0f, rx - body.x, f, c);
        if (body.x > SIZE - rx) wall(body, 1f, 0f, body.x - (SIZE - rx), f, c);
        if (body.z < rz) wall(body, 0f, -1f, rz - body.z, f, c);
        if (body.z > SIZE - rz) wall(body, 0f, 1f, body.z - (SIZE - rz), f, c);
    }

    private void collideTrees() {
        Body truck = player;
        if (truck.y >= Tree.SIDEWALK_HEIGHT + Tree.HEIGHT) return;

        // An airborne bumper can clip the canopy after it clears the trunk.
        float radius = truck.y < 1.65f ? Tree.TRUNK_COLLIDER : Tree.CROWN_RADII[2];
        float reach = truck.radius + radius;
        int minX = treeTileIndex(truck.x - reach), maxX = treeTileIndex(truck.x + reach);
        int minZ = treeTileIndex(truck.z - reach), maxZ = treeTileIndex(truck.z + reach);
        float f = sin(truck.yaw), c = cos(truck.yaw);
        for (int gz = minZ; gz <= maxZ; gz++) {
            for (int gx = minX; gx <= maxX; gx++) {
                List<Tree> roots = treeTile(gx, gz).roots;
                for (int i = 0; i < roots.size(); i++) {
                    Tree tree = roots.get(i);
                    if (!tree.uprooted) collideTree(truck, tree, radius, f, c, gz * GRID + gx);
                }
            }
        }
    }

    private void collideTree(Body truck, Tree tree, float radius, float f, float c, int tile) {
        float dx = tree.rootX - truck.x, dz = tree.rootZ - truck.z;
        float localX = dx * c - dz * f, localZ = dx * f + dz * c;
        float hitX = clamp(localX, -truck.halfWidth, truck.halfWidth);
        float hitZ = clamp(localZ, -truck.halfLength, truck.halfLength);
        float nx = localX - hitX, nz = localZ - hitZ;
        float distanceSquared = nx * nx + nz * nz;
        if (distanceSquared >= radius * radius) return;
        if (distanceSquared > 0f) {
            float distance = (float) Math.sqrt(distanceSquared);
            nx /= distance;
            nz /= distance;
        } else if (truck.halfWidth - Math.abs(localX) < truck.halfLength - Math.abs(localZ)) {
            nx = Math.copySign(1f, localX);
            hitX = truck.halfWidth * nx;
        } else {
            nz = Math.copySign(1f, localZ);
            hitZ = truck.halfLength * nz;
        }

        float normalX = nx * c + nz * f, normalZ = -nx * f + nz * c;
        float contactX = hitX * c + hitZ * f, contactZ = -hitX * f + hitZ * c;
        float speedX = truck.vx + truck.spin * contactZ;
        float speedZ = truck.vz - truck.spin * contactX;
        float closing = speedX * normalX + speedZ * normalZ;
        float arm = contactZ * normalX - contactX * normalZ;
        float kick = Math.min(25f, Math.max(0f, closing) * .78f + 1.5f);
        float push = Math.min(kick * Tree.MASS,
                Math.max(0f, closing) / (truck.invMass + arm * arm * truck.invInertia));
        impulse(truck, -normalX * push, -normalZ * push, contactX, contactZ);
        float along = clamp(speedX * normalZ - speedZ * normalX, -16f, 16f) * .27f;
        tree.vx = normalX * kick + normalZ * along;
        tree.vz = normalZ * kick - normalX * along;
        tree.vy = 3.8f + kick * .20f;
        tree.yaw = (float) Math.atan2(tree.vx, tree.vz);
        tree.pitchRate = 2.4f + kick * .17f;
        tree.yawRate = .18f * along;
        tree.uprooted = true;
        tree.tile = tile;
        treeTiles[tile].fallen.add(tree);
        movingTrees.add(tree);
    }

    private void moveTrees() {
        for (int i = movingTrees.size() - 1; i >= 0; i--) {
            Tree tree = movingTrees.get(i);
            tree.x += tree.vx * TICK;
            tree.z += tree.vz * TICK;
            float x = clamp(tree.x, Tree.EDGE_MARGIN, SIZE - Tree.EDGE_MARGIN);
            float z = clamp(tree.z, Tree.EDGE_MARGIN, SIZE - Tree.EDGE_MARGIN);
            if (x != tree.x) tree.vx *= -.25f;
            if (z != tree.z) tree.vz *= -.25f;
            tree.x = x;
            tree.z = z;
            tree.yaw = wrap(tree.yaw + tree.yawRate * TICK);
            tree.pitch = wrap(tree.pitch + tree.pitchRate * TICK);
            float floor = treeFloor(tree);
            if (!tree.landed) {
                tree.vy -= GRAVITY * TICK;
                tree.y += tree.vy * TICK;
                if (tree.y < floor) {
                    tree.y = floor;
                    if (tree.vy < -3f) {
                        tree.vy *= -.28f;
                        tree.vx *= .66f;
                        tree.vz *= .66f;
                        tree.pitchRate *= .75f;
                    } else {
                        tree.landed = true;
                        tree.restPitch = Math.copySign(PI * .5f, tree.pitch);
                    }
                }
            } else {
                tree.pitch += (tree.restPitch - tree.pitch) * 7f * TICK;
                tree.y = treeFloor(tree);
                tree.vx *= 1f - 3.5f * TICK;
                tree.vz *= 1f - 3.5f * TICK;
                tree.yawRate *= 1f - 4f * TICK;
                tree.pitchRate *= 1f - 8f * TICK;
                if (tree.vx * tree.vx + tree.vz * tree.vz < .01f
                        && Math.abs(tree.pitch - tree.restPitch) < .02f) {
                    tree.pitch = tree.restPitch;
                    tree.y = treeFloor(tree);
                    movingTrees.remove(i);
                }
            }

            int tile = treeTileIndex(tree.z) * GRID + treeTileIndex(tree.x);
            if (tree.tile != tile) {
                treeTiles[tree.tile].fallen.remove(tree);
                treeTile(tile % GRID, tile / GRID).fallen.add(tree);
                tree.tile = tile;
            }
        }
    }

    private static int treeTileIndex(float position) {
        return Math.max(0, Math.min(GRID - 1, (int) (position / BLOCK)));
    }

    private static float treeFloor(Tree tree) {
        boolean inYard = tree.x > YARD_MIN_X && tree.x < YARD_MAX_X
                && tree.z > YARD_MIN_Z && tree.z < YARD_MAX_Z;
        float roadX = Math.abs(tree.x % BLOCK - BLOCK * .5f);
        float roadZ = Math.abs(tree.z % BLOCK - BLOCK * .5f);
        float surface = inYard ? rampHeight(tree.x, tree.z)
                : roadX > ROAD * .5f && roadZ > ROAD * .5f ? Tree.SIDEWALK_HEIGHT : 0f;
        float axis = cos(tree.pitch);
        // The hexagonal mesh reaches sin(60 degrees) times its radius along local Z.
        float lean = .8660254f * Math.abs(sin(tree.pitch));
        float support = Math.max(Tree.PIVOT * axis + .14f * lean, (Tree.PIVOT - Tree.HEIGHT) * axis);
        for (int i = 0; i < Tree.CROWN_HEIGHTS.length; i++) {
            support = Math.max(support, (Tree.PIVOT - Tree.CROWN_HEIGHTS[i]) * axis
                    + Tree.CROWN_RADII[i] * lean);
        }
        return surface + support;
    }

    private void wall(Body a, float nx, float nz, float depth, float f, float c) {
        float tangent = supportTangent(nx, nz, f, c, a.halfWidth, a.halfLength);
        float extent = projection(nx, nz, f, c, a.halfWidth, a.halfLength);
        normalX = nx;
        normalZ = nz;
        overlap = depth;
        resolve(a, null, a.x + nx * extent + nz * tangent,
                a.z + nz * extent - nx * tangent, 0f);
        // Only positional correction keeps the entire footprint inside the map.
        float remainder = depth - correction(depth);
        a.x -= nx * remainder;
        a.z -= nz * remainder;
    }

    private void collide(Body a, Body b, float bx, float bz, float bf, float bc,
                         float bw, float bl, float landingSpeed) {
        float af = sin(a.yaw), ac = cos(a.yaw);
        float dx = bx - a.x, dz = bz - a.z;
        overlap = Float.POSITIVE_INFINITY;
        if (!axis(ac, -af, dx, dz, af, ac, a, bf, bc, bw, bl)
                || !axis(af, ac, dx, dz, af, ac, a, bf, bc, bw, bl)
                || !axis(bc, -bf, dx, dz, af, ac, a, bf, bc, bw, bl)
                || !axis(bf, bc, dx, dz, af, ac, a, bf, bc, bw, bl)) return;

        float nx = normalX, nz = normalZ;
        float offset = dx * nz - dz * nx;
        float ta = supportTangent(nx, nz, af, ac, a.halfWidth, a.halfLength);
        float tb = offset - supportTangent(-nx, -nz, bf, bc, bw, bl);
        float ea = faceHalfSpan(nx, nz, af, ac, a.halfWidth, a.halfLength);
        float eb = faceHalfSpan(nx, nz, bf, bc, bw, bl);
        float low = Math.max(ta - ea, tb - eb);
        float high = Math.min(ta + ea, tb + eb);
        float tangent = (low + high) * 0.5f;
        float depth = projection(nx, nz, af, ac, a.halfWidth, a.halfLength) - overlap * 0.5f;
        resolve(a, b, a.x + nx * depth + nz * tangent,
                 a.z + nz * depth - nx * tangent, landingSpeed);
    }

    private boolean axis(float nx, float nz, float dx, float dz, float af, float ac,
                         Body a, float bf, float bc, float bw, float bl) {
        float signedDistance = dx * nx + dz * nz;
        float depth = projection(nx, nz, af, ac, a.halfWidth, a.halfLength)
                + projection(nx, nz, bf, bc, bw, bl) - Math.abs(signedDistance);
        if (depth <= 0f) return false;
        if (depth < overlap) {
            overlap = depth;
            normalX = signedDistance < 0f ? -nx : nx;
            normalZ = signedDistance < 0f ? -nz : nz;
        }
        return true;
    }

    private static float projection(float nx, float nz, float f, float c, float w, float l) {
        return w * Math.abs(nx * c - nz * f) + l * Math.abs(nx * f + nz * c);
    }

    private static float supportTangent(float nx, float nz, float f, float c, float w, float l) {
        float right = nx * c - nz * f, forward = nx * f + nz * c;
        float rw = Math.abs(right) < 0.001f ? 0f : Math.copySign(w, right);
        float fl = Math.abs(forward) < 0.001f ? 0f : Math.copySign(l, forward);
        return rw * (nz * c + nx * f) + fl * (nz * f - nx * c);
    }

    private static float faceHalfSpan(float nx, float nz, float f, float c, float w, float l) {
        if (Math.abs(nx * c - nz * f) < 0.001f) return w;
        if (Math.abs(nx * f + nz * c) < 0.001f) return l;
        return 0f;
    }

    private void resolve(Body a, Body b, float x, float z, float landingSpeed) {
        float nx = normalX, nz = normalZ;
        float ax = x - a.x, az = z - a.z;
        float bx = b == null ? 0f : x - b.x, bz = b == null ? 0f : z - b.z;
        float invB = b == null ? 0f : b.invMass;
        float speedX = (b == null ? 0f : b.vx + b.spin * bz) - a.vx - a.spin * az;
        float speedZ = (b == null ? 0f : b.vz - b.spin * bx) - a.vz + a.spin * ax;
        float closing = -(speedX * nx + speedZ * nz);

        float energy = 0f;
        if (closing > 0f) {
            float an = az * nx - ax * nz, bn = bz * nx - bx * nz;
            float invEffective = a.invMass + invB + an * an * a.invInertia
                    + (b == null ? 0f : bn * bn * b.invInertia);
            float restitution = closing > 2.5f ? 0.14f : 0f;
            float normalImpulse = (1f + restitution) * closing / invEffective;
            impulse(a, -nx * normalImpulse, -nz * normalImpulse, ax, az);
            if (b != null) impulse(b, nx * normalImpulse, nz * normalImpulse, bx, bz);

            float tx = nz, tz = -nx;
            float at = az * tx - ax * tz, bt = bz * tx - bx * tz;
            float slip = (b == null ? 0f : b.vx * tx + b.vz * tz + b.spin * bt)
                    - (a.vx * tx + a.vz * tz + a.spin * at);
            float invTangent = a.invMass + invB + at * at * a.invInertia
                    + (b == null ? 0f : bt * bt * b.invInertia);
            float limit = 0.43f * normalImpulse;
            float tangentImpulse = clamp(-slip / invTangent, -limit, limit);
            impulse(a, -tx * tangentImpulse, -tz * tangentImpulse, ax, az);
            if (b != null) impulse(b, tx * tangentImpulse, tz * tangentImpulse, bx, bz);

            if (closing > 2.5f) {
                energy = 0.5f * closing * closing * (1f - restitution * restitution) / invEffective;
                dent(a, ax, az, energy, a == player ? 2.6e-6f : 7e-6f);
                impactHealth(a, energy);
                if (b != null) {
                    dent(b, bx, bz, energy, 7e-6f);
                    impactHealth(b, energy);
                }
            }
        }

        float landingEnergy = 0.5f * landingSpeed * landingSpeed / a.invMass;
        breakWheels(a, ax, az, nx, nz, Math.max(energy, landingEnergy));
        if (b != null) breakWheels(b, bx, bz, -nx, -nz, Math.max(energy, landingEnergy));

        if (a == player && b != null) {
            float noseAlignment = nx * sin(a.yaw) + nz * cos(a.yaw);
            boolean climb = closing > 7.5f && Math.abs(noseAlignment) > 0.63f;
            if ((landingSpeed > 0f || climb) && !b.flattened) {
                b.flattened = true;
                b.health = 0f;
                crushed++;
                b.damage = Math.max(b.damage, 0.76f);
                b.dentFront = Math.max(b.dentFront, 0.52f);
                b.dentBack = Math.max(b.dentBack, 0.52f);
                b.dentLeft = Math.max(b.dentLeft, 0.52f);
                b.dentRight = Math.max(b.dentRight, 0.52f);
                release(b);
                if (climb && a.grounded && a.y == 0f) {
                    a.y = 0.025f;
                    a.grounded = false;
                    a.verticalSpeed = clamp(4f + closing * 0.14f, 5.3f, 7.4f);
                }
            }
        }

        float correction = correction(overlap) / (a.invMass + invB);
        a.x -= nx * correction * a.invMass;
        a.z -= nz * correction * a.invMass;
        if (b != null) {
            b.x += nx * correction * invB;
            b.z += nz * correction * invB;
        }
    }

    private static void impulse(Body b, float ix, float iz, float rx, float rz) {
        b.vx += ix * b.invMass;
        b.vz += iz * b.invMass;
        b.spin = clamp(b.spin + (rz * ix - rx * iz) * b.invInertia, -8f, 8f);
    }

    private void impactHealth(Body body, float energy) {
        if (body.health <= 0f) return;
        float rate = body == player ? TRUCK_HEALTH_PER_JOULE : CAR_HEALTH_PER_JOULE;
        body.health = Math.max(0f, body.health - Math.max(0f, energy - 2200f) * rate);
        if (body != player && body.health == 0f) release(body);
    }

    private void dent(Body b, float x, float z, float energy, float susceptibility) {
        float amount = Math.max(0f, energy - 2200f) * susceptibility;
        if (amount <= 0f) return;
        float f = sin(b.yaw), c = cos(b.yaw);
        float side = (x * c - z * f) / b.halfWidth;
        float end = (x * f + z * c) / b.halfLength;
        if (Math.abs(end) >= 0.65f * Math.abs(side)) {
            if (end > 0f) b.dentFront = Math.min(1f, b.dentFront + amount);
            else b.dentBack = Math.min(1f, b.dentBack + amount);
        }
        if (Math.abs(side) >= 0.65f * Math.abs(end)) {
            if (side > 0f) b.dentRight = Math.min(1f, b.dentRight + amount);
            else b.dentLeft = Math.min(1f, b.dentLeft + amount);
        }
        b.damage = Math.min(1f, b.damage + amount * 0.65f);
        if (b != player) b.stun = Math.max(b.stun, clamp(amount * 5f, 0.25f, 2f));
    }

    private void breakWheels(Body body, float x, float z, float nx, float nz, float energy) {
        float threshold = body == player ? TRUCK_WHEEL_BREAK_ENERGY : CAR_WHEEL_BREAK_ENERGY;
        if (energy < threshold || body.detachedWheels == 15) return;

        float f = sin(body.yaw), c = cos(body.yaw);
        float localX = (x * c - z * f) / body.halfWidth;
        float localZ = (x * f + z * c) / body.halfLength;
        float normalX = nx * c - nz * f;
        float normalZ = nx * f + nz * c;
        boolean sideHit = Math.abs(normalX) > Math.abs(normalZ);
        int side = (sideHit ? normalX : localX) < 0f ? 0 : 2;
        int axle = (sideHit ? localZ : normalZ) < 0f ? 0 : 1;
        float kick = clamp((float) Math.sqrt(energy / threshold), 1f, 3f);
        detachWheel(body, side + axle, nx, nz, kick);
        // A strong hit across the middle of a face can break both nearby mounts.
        float offset = sideHit ? localZ : localX;
        if (energy >= threshold * 2.25f && Math.abs(offset) < 0.45f) {
            detachWheel(body, (side + axle) ^ (sideHit ? 1 : 2), nx, nz, kick);
        }
    }

    private void detachWheel(Body body, int index, float nx, float nz, float kick) {
        int bit = 1 << index;
        if ((body.detachedWheels & bit) != 0) return;
        body.detachedWheels |= bit;
        if (body != player) release(body);

        LooseWheel wheel = looseWheels[nextLooseWheel];
        nextLooseWheel = (nextLooseWheel + 1) % looseWheels.length;

        boolean truck = body == player;
        float side = index < 2 ? -1f : 1f;
        float axle = (index & 1) == 0 ? -1f : 1f;
        float bend = side < 0f ? body.dentLeft : body.dentRight;
        float endDent = axle > 0f ? body.dentFront : body.dentBack;
        // Match the attached wheel's centre, including the chassis roll and pitch.
        float mountX = side * ((truck ? 1.54f : 0.86f) - 0.14f * bend);
        float mountY = (truck ? 0.92f : 0.35f) - 0.18f * body.damage;
        float mountZ = axle * ((truck ? 1.69f : 1.29f) - 0.20f * endDent);
        float rollX = cos(body.roll) * mountX - sin(body.roll) * mountY;
        float rollY = sin(body.roll) * mountX + cos(body.roll) * mountY;
        float pitchZ = sin(body.pitch) * rollY + cos(body.pitch) * mountZ;
        float f = sin(body.yaw), c = cos(body.yaw);
        float rx = c * rollX + f * pitchZ, rz = -f * rollX + c * pitchZ;

        wheel.radius = truck ? 0.91f : 0.35f;
        wheel.halfWidth = truck ? 0.48f : 0.20f;
        wheel.x = body.x + rx;
        wheel.z = body.z + rz;
        wheel.tilt = body.roll;
        wheel.y = Math.max(body.y + cos(body.pitch) * rollY - sin(body.pitch) * mountZ,
                rampHeight(wheel.x, wheel.z) + wheelClearance(wheel));
        wheel.yaw = wrap(body.yaw + side * (bend * 21f + endDent * 7f) * PI / 180f);
        wheel.spin = body.wheelSpin;

        // Inherit the mount's motion, then kick outward and away from the strike.
        float outward = 2.3f + 2.4f * kick;
        float lengthwise = axle * (0.9f + 0.6f * kick);
        wheel.vx = body.vx + body.spin * rz + c * side * outward + f * lengthwise - nx * kick;
        wheel.vz = body.vz - body.spin * rx - f * side * outward + c * lengthwise - nz * kick;
        wheel.vy = 2.8f + 2.2f * kick + Math.max(0f, body.verticalSpeed);
        wheel.yawRate = body.spin + side * axle * (1.2f + kick);
        wheel.tiltRate = -side * (2.2f + kick);
        wheel.spinRate = forwardSpeed(body) / wheel.radius + axle * kick * 3f;
        wheel.age = 0;
        wheel.visible = true;
    }

    private static float wheelClearance(LooseWheel wheel) {
        return wheel.radius * Math.abs(cos(wheel.tilt)) + wheel.halfWidth * Math.abs(sin(wheel.tilt));
    }

    private static void moveLooseWheel(LooseWheel wheel) {
        if (++wheel.age >= LOOSE_WHEEL_LIFETIME) {
            wheel.visible = false;
            return;
        }

        float oldSurface = rampHeight(wheel.x, wheel.z);
        wheel.vy -= GRAVITY * TICK;
        wheel.x += wheel.vx * TICK;
        wheel.y += wheel.vy * TICK;
        wheel.z += wheel.vz * TICK;
        wheel.yaw = wrap(wheel.yaw + wheel.yawRate * TICK);
        wheel.tilt = wrap(wheel.tilt + wheel.tiltRate * TICK);
        wheel.spin = wrap(wheel.spin + wheel.spinRate * TICK);

        float edge = Math.max(wheel.radius, wheel.halfWidth);
        if (wheel.x < edge || wheel.x > SIZE - edge) {
            wheel.x = clamp(wheel.x, edge, SIZE - edge);
            wheel.vx *= -0.45f;
        }
        if (wheel.z < edge || wheel.z > SIZE - edge) {
            wheel.z = clamp(wheel.z, edge, SIZE - edge);
            wheel.vz *= -0.45f;
        }

        float surface = rampHeight(wheel.x, wheel.z);
        float floor = surface + wheelClearance(wheel);
        if (wheel.y > floor) return;
        wheel.y = floor;
        float surfaceSpeed = clamp((surface - oldSurface) / TICK, -12f, 12f);
        float impact = surfaceSpeed - wheel.vy;
        if (impact > 1.5f) {
            wheel.vy = surfaceSpeed + 0.43f * impact;
            wheel.vx *= 0.82f;
            wheel.vz *= 0.82f;
            wheel.tiltRate *= -0.45f;
            return;
        }

        wheel.vy = surfaceSpeed;
        float f = sin(wheel.yaw), c = cos(wheel.yaw);
        float upright = Math.abs(cos(wheel.tilt));
        float forward = (wheel.vx * f + wheel.vz * c) * (1f - (4.0f - 3.5f * upright) * TICK);
        float lateral = (wheel.vx * c - wheel.vz * f) * (1f - 6f * TICK);
        wheel.vx = forward * f + lateral * c;
        wheel.vz = forward * c - lateral * f;
        float rollingSpin = forward * cos(wheel.tilt) / wheel.radius;
        wheel.spinRate += (rollingSpin - wheel.spinRate) * 12f * TICK;
        wheel.yawRate *= 1f - 5f * TICK;
        // A slow wheel falls onto its side; rolling speed keeps it closer to upright.
        float lean = Math.copySign(PI * 0.5f, wheel.tilt);
        float restTilt = Math.abs(forward) > 2f && upright > 0.65f
                ? (Math.abs(wheel.tilt) < PI * 0.5f ? 0f : Math.copySign(PI, wheel.tilt)) : lean;
        wheel.tiltRate += (wrap(restTilt - wheel.tilt) * 12f - wheel.tiltRate * 7f) * TICK;
    }

    private static float correction(float depth) {
        return Math.max(0f, depth - 0.008f) * 0.78f;
    }

    private static float road(int index) { return BLOCK * 0.5f + index * BLOCK; }
    private static float sin(float angle) { return (float) Math.sin(angle); }
    private static float cos(float angle) { return (float) Math.cos(angle); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float input(float v, float lo, float hi) { return Float.isFinite(v) ? clamp(v, lo, hi) : 0f; }
    private static float forwardSpeed(Body b) { return b.vx * sin(b.yaw) + b.vz * cos(b.yaw); }
    private static float length(float x, float z) { return (float) Math.sqrt(x * x + z * z); }

    private static float distanceSq(float x, float z, float xx, float zz) {
        float dx = x - xx, dz = z - zz;
        return dx * dx + dz * dz;
    }

    private static float wrap(float angle) {
        if (angle > PI) return angle - 2f * PI;
        if (angle < -PI) return angle + 2f * PI;
        return angle;
    }
}
