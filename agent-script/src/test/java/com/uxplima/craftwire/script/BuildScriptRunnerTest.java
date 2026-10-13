package com.uxplima.craftwire.script;

import static org.junit.jupiter.api.Assertions.*;
import com.uxplima.craftwire.build.BuildContext;
import com.uxplima.craftwire.build.BuildError;
import com.uxplima.craftwire.build.FakePalette;
import com.uxplima.craftwire.build.Transform;
import com.uxplima.craftwire.build.WorldReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class BuildScriptRunnerTest {
    static final BuildScriptRunner runner = new BuildScriptRunner(BuildScriptRunnerTest.class.getClassLoader());
    final FakePalette pal = new FakePalette();
    final WorldReader air = new WorldReader() {
        @Override public int read(int x, int y, int z) { return 0; }
        @Override public int ground(int x, int z) { return 0; }
    };

    @AfterAll
    static void close() { runner.close(); }

    BuildContext ctx() { return new BuildContext(pal, air, Transform.IDENTITY, 100_000, 1); }

    BuildScriptRunner.Result run(String code, String only) {
        return runner.run(code, "t.build.js", only, "main", ctx(), 5_000);
    }

    @Test
    void topLevelCodeIsOnePart() {
        BuildScriptRunner.Result r = run("fill(0,0,0,1,1,1,'stone')", null);
        assertEquals(1, r.parts().size());
        assertEquals("main", r.parts().get(0).name());
        assertEquals(8, r.parts().get(0).buffer().size());
    }

    @Test
    void declaredPartsRunSeparately() {
        String code = "part('a', () => set(0,0,0,'stone'));\npart('b', () => fill(0,0,0,2,0,0,'glass'));";
        BuildScriptRunner.Result all = run(code, null);
        assertEquals(java.util.List.of("a", "b"), all.parts().stream().map(BuildScriptRunner.Part::name).toList());
        assertEquals(3, all.parts().get(1).buffer().size());
        assertEquals(1, run(code, "a").parts().size());
    }

    @Test
    void unknownPartListsTheParts() {
        BuildError e = assertThrows(BuildError.class, () -> run("part('a', () => {})", "zzz"));
        assertEquals("BAD_ARGUMENT", e.code());
        assertTrue(e.getMessage().contains("a"));
    }

    @Test
    void blocksOutsidePartsAreAnError() {
        BuildError e = assertThrows(BuildError.class, () -> run("set(0,0,0,'stone'); part('a', () => {})", null));
        assertEquals("SCRIPT_ERROR", e.code());
    }

    @Test
    void unknownBlockIsBadBlockWithSuggestion() {
        BuildError e = assertThrows(BuildError.class, () -> run("set(0,0,0,'stone_brick')", null));
        assertEquals("BAD_BLOCK", e.code());
        assertTrue(e.getMessage().contains("minecraft:stone_bricks"), e.getMessage());
    }

    @Test
    void scriptErrorNamesTheLine() {
        BuildError e = assertThrows(BuildError.class, () -> run("set(0,0,0,'stone');\nnope();", null));
        assertEquals("SCRIPT_ERROR", e.code());
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void infiniteLoopTimesOut() {
        BuildError e = assertThrows(BuildError.class, () -> runner.run("while (true) {}", "t.build.js", null, "main", ctx(), 300));
        assertEquals("TIMEOUT", e.code());
    }

    @Test
    void scriptsCannotReachJava() {
        BuildError e = assertThrows(BuildError.class, () -> run("Java.type('java.io.File')", null));
        assertEquals("SCRIPT_ERROR", e.code());
    }

    @Test
    void scriptsCannotReachJavaThroughTheHostObject() {
        // allow-build without allow-eval must not give a way to run arbitrary Java.
        for (String escape : new String[] {
                "__cw.getClass().forName('java.lang.Runtime')",
                "__cw.getClass().getClassLoader()",
        }) {
            assertThrows(BuildError.class, () -> run(escape + "; set(0,0,0,'stone')", null), escape);
        }
    }

    @Test
    void numberAndStringArraysStillCrossIntoTheApi() {
        // The narrowed host access must keep converting JS arrays for polygon (int[]) and gradient (String[]).
        BuildScriptRunner.Result r = run("polygon([[0,0],[4,0],[0,4]], 0, 1, 'stone'); fill(0,1,0,0,5,0, gradient('y', 1, 5, ['stone','dirt']))", null);
        assertTrue(r.parts().get(0).buffer().size() > 5);
    }

    @Test
    void tooManyBlocksIsTooLarge() {
        BuildContext small = new BuildContext(pal, air, Transform.IDENTITY, 10, 1);
        BuildError e = assertThrows(BuildError.class, () -> runner.run("fill(0,0,0,9,9,9,'stone')", "t.build.js", null, "main", small, 5_000));
        assertEquals("TOO_LARGE", e.code());
    }

    @Test
    void rotateTurnsStairs() {
        BuildScriptRunner.Result r = run("rotate(1, () => set(0,0,-1,'oak_stairs[facing=north]'))", null);
        assertEquals("minecraft:oak_stairs[facing=east]", pal.name(r.parts().get(0).buffer().get(1, 0, 0)));
    }

    @Test
    void functionsMixesAndLayersWork() {
        String code = """
            set(0,0,0, (x,y,z) => 'gold_block');
            fill(0,1,0,9,1,9, mix({stone: 3, cobblestone: 1}));
            layers(0,2,0, {s: 'stone', g: 'glass'}, ['sg/g.']);
            roof({x1:0, z1:0, x2:4, z2:4, y:5, type:'hip', stairs:'spruce_stairs'});
            symmetric('x', () => set(3,9,0,'bricks'));
            repeat(3, [2,0,0], (i) => set(0,10,0,'dirt'));
            print('done', 1);
            """;
        BuildScriptRunner.Result r = run(code, null);
        var b = r.parts().get(0).buffer();
        assertEquals("minecraft:gold_block", pal.name(b.get(0, 0, 0)));
        assertEquals("minecraft:glass", pal.name(b.get(1, 2, 0)));
        assertEquals(pal.air(), b.get(1, 2, 1));
        assertEquals("minecraft:bricks", pal.name(b.get(-3, 9, 0)));
        assertEquals("minecraft:dirt", pal.name(b.get(4, 10, 0)));
        assertEquals("done 1\n", r.output());
    }

    @Test
    void sameSeedSameBlocks() {
        String code = "fill(0,0,0,15,0,15, mix({stone: 1, cobblestone: 1}))";
        assertEquals(snapshot(run(code, null)), snapshot(run(code, null)));
    }

    static String snapshot(BuildScriptRunner.Result r) {
        StringBuilder s = new StringBuilder();
        r.parts().get(0).buffer().blocks().forEach((k, v) -> s.append(k).append('=').append(v).append(';'));
        return s.toString();
    }
}
