package com.levgame.monster;

enum TrafficModel {
    SEDAN(.94f, 2.23f, 1.44f, .35f, .79f, .13f, 1390, -1.36f, 1.39f),
    HATCHBACK(.89f, 1.94f, 1.47f, .33f, .75f, .12f, 1150, -1.21f, 1.24f),
    WAGON(.97f, 2.34f, 1.58f, .36f, .81f, .14f, 1550, -1.42f, 1.45f),
    CITY_BUS(1.24f, 5.35f, 3.13f, .49f, 1.04f, .17f, 8500, -2.78f, 3.37f),
    COACH(1.27f, 5.85f, 3.54f, .52f, 1.06f, .18f, 9600, -4.02f, -2.82f, 3.83f),
    BOX_TRUCK(1.21f, 3.85f, 3.45f, .47f, 1.01f, .17f, 4900, -2.12f, 2.69f),
    DUMP_TRUCK(1.25f, 3.95f, 3.12f, .52f, 1.04f, .18f, 6500, -2.84f, -1.66f, 2.63f),
    SEMI_BOX(1.27f, 6.35f, 3.85f, .51f, 1.06f, .18f, 10800, -5.05f, -3.88f, 2.17f, 3.23f, 5.02f),
    SEMI_FLATBED(1.27f, 6.25f, 3.11f, .51f, 1.06f, .18f, 8600, -5.0f, -3.84f, 2.13f, 3.18f, 4.94f),
    SEMI_TANKER(1.27f, 6.30f, 3.55f, .51f, 1.06f, .18f, 10500, -5.03f, -3.86f, 2.15f, 3.21f, 4.98f);

    final float halfWidth, halfLength, height, wheelRadius, wheelX, halfTireWidth, mass;
    final float[] axles;

    TrafficModel(float halfWidth, float halfLength, float height, float wheelRadius,
                 float wheelX, float halfTireWidth, float mass, float... axles) {
        this.halfWidth = halfWidth;
        this.halfLength = halfLength;
        this.height = height;
        this.wheelRadius = wheelRadius;
        this.wheelX = wheelX;
        this.halfTireWidth = halfTireWidth;
        this.mass = mass;
        this.axles = axles;
    }

    boolean isPassengerCar() {
        return height < 2f;
    }

    boolean hasTrailer() {
        return this == SEMI_BOX || this == SEMI_FLATBED || this == SEMI_TANKER;
    }
}
