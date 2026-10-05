package com.uxplima.craftwire.paper.world;

import com.uxplima.craftwire.paper.Args;
import java.util.regex.Pattern;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

/** Where a structure lands when placed at a point with a rotation and mirror (vanilla pivot: its origin). */
public final class Placement {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Placement() {}

    public static String checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw Args.invalid("Schematic names may only use letters, digits, _ and - (1-64 characters): " + name);
        }
        return name;
    }

    public static Box footprint(int atX, int atY, int atZ, int sizeX, int sizeY, int sizeZ, StructureRotation rotation, Mirror mirror) {
        int[] a = transform(0, 0, rotation, mirror);
        int[] b = transform(sizeX - 1, sizeZ - 1, rotation, mirror);
        return Box.of(atX + a[0], atY, atZ + a[1], atX + b[0], atY + sizeY - 1, atZ + b[1]);
    }

    /** Vanilla StructureTemplate.transform with pivot 0: mirror first, then rotate. */
    static int[] transform(int x, int z, StructureRotation rotation, Mirror mirror) {
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> { }
        }
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[] {-z, x};
            case CLOCKWISE_180 -> new int[] {-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[] {z, -x};
            default -> new int[] {x, z};
        };
    }

    public static StructureRotation rotation(String s) {
        return switch (s) {
            case "none" -> StructureRotation.NONE;
            case "clockwise_90" -> StructureRotation.CLOCKWISE_90;
            case "clockwise_180" -> StructureRotation.CLOCKWISE_180;
            case "counterclockwise_90" -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> throw Args.invalid("Unknown rotation: " + s);
        };
    }

    public static Mirror mirror(String s) {
        return switch (s) {
            case "none" -> Mirror.NONE;
            case "left_right" -> Mirror.LEFT_RIGHT;
            case "front_back" -> Mirror.FRONT_BACK;
            default -> throw Args.invalid("Unknown mirror: " + s);
        };
    }
}
