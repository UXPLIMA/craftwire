package com.uxplima.craftwire.bedrock.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The skin a bot logs in with: a plain 64x32 texture on the classic humanoid model ("geometry.humanoid.custom":
 * head, body, two arms, two legs, the shapes every Bedrock player model starts from), built here rather than
 * shipped as a data file. Servers check that the geometry parses and that the texture size matches.
 */
public final class SkinModel {
    public static final int WIDTH = 64;
    public static final int HEIGHT = 32;
    public static final String GEOMETRY_NAME = "geometry.humanoid.custom";

    private SkinModel() {}

    /** RGBA pixels: a mid-grey body, opaque everywhere so no part of the model turns invisible. */
    public static String textureBase64() {
        byte[] px = new byte[WIDTH * HEIGHT * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte) 0x70;
            px[i + 1] = (byte) 0x80;
            px[i + 2] = (byte) 0x90;
            px[i + 3] = (byte) 0xff;
        }
        return Base64.getEncoder().encodeToString(px);
    }

    public static String resourcePatchBase64() {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("default", GEOMETRY_NAME);
        JsonObject patch = new JsonObject();
        patch.add("geometry", geometry);
        return base64(patch.toString());
    }

    public static String geometryBase64() {
        JsonArray bones = new JsonArray();
        bones.add(bone("body", null, 0, 24, 0, cube(-4, 12, -2, 8, 12, 4, 16, 16)));
        bones.add(bone("head", "body", 0, 24, 0, cube(-4, 24, -4, 8, 8, 8, 0, 0)));
        bones.add(bone("rightArm", "body", -5, 22, 0, cube(-8, 12, -2, 4, 12, 4, 40, 16)));
        bones.add(bone("leftArm", "body", 5, 22, 0, cube(4, 12, -2, 4, 12, 4, 40, 16)));
        bones.add(bone("rightLeg", "body", -1.9, 12, 0, cube(-3.9, 0, -2, 4, 12, 4, 0, 16)));
        bones.add(bone("leftLeg", "body", 1.9, 12, 0, cube(-0.1, 0, -2, 4, 12, 4, 0, 16)));
        JsonObject description = new JsonObject();
        description.addProperty("identifier", GEOMETRY_NAME);
        description.addProperty("texture_width", WIDTH);
        description.addProperty("texture_height", HEIGHT);
        JsonObject model = new JsonObject();
        model.add("description", description);
        model.add("bones", bones);
        JsonArray models = new JsonArray();
        models.add(model);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        root.add("minecraft:geometry", models);
        return base64(root.toString());
    }

    public static String engineVersionBase64() {
        return base64("0.0.0");
    }

    private static JsonObject bone(String name, String parent, double px, double py, double pz, JsonObject cube) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        if (parent != null) b.addProperty("parent", parent);
        b.add("pivot", triple(px, py, pz));
        JsonArray cubes = new JsonArray();
        cubes.add(cube);
        b.add("cubes", cubes);
        return b;
    }

    private static JsonObject cube(double x, double y, double z, int w, int h, int d, int u, int v) {
        JsonObject c = new JsonObject();
        c.add("origin", triple(x, y, z));
        c.add("size", triple(w, h, d));
        JsonArray uv = new JsonArray();
        uv.add(u);
        uv.add(v);
        c.add("uv", uv);
        return c;
    }

    private static JsonArray triple(double a, double b, double c) {
        JsonArray t = new JsonArray();
        t.add(a);
        t.add(b);
        t.add(c);
        return t;
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
