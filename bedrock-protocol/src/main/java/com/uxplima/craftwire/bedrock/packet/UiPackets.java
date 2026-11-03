package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** What the player reads: chat and system text, command output, titles, boss bars, scoreboards, forms, NPC dialogue. */
public final class UiPackets {
    /** Text types: 1 chat, 7 whisper, 8 announcement carry a source; 2-4 are translations with parameters. */
    public record Text(int type, String source, String message, List<String> parameters, boolean translate) implements Clientbound {}

    public record CommandOutput(boolean success, List<String> messages) implements Clientbound {}

    /** SetTitle actions: 0 clear, 1 reset, 2 title, 3 subtitle, 4 action bar, 5 durations. */
    public record Title(int action, String text, int fadeIn, int stay, int fadeOut) implements Clientbound {}

    /** BossEvent: 0 show, 2 hide, 4 health, 5 title, 6 appearance. */
    public record BossEvent(long bossId, int event, String title, float health, int colour) implements Clientbound {}

    public record DisplayObjective(String slot, String objective, String displayName, int sortOrder) implements Clientbound {}

    public record RemoveObjective(String objective) implements Clientbound {}

    /** One score line: `kind` 0 remove, 1 player, 2 entity, 3 fake player (a sidebar line, `name` its text). */
    public record ScoreEntry(int kind, long entryId, String objective, int score, String name) {}

    public record SetScore(List<ScoreEntry> entries) implements Clientbound {}

    /** ModalFormRequest and ServerSettingsResponse: a form's JSON, answered with ModalFormResponse by `id`. */
    public record Form(int id, String json, boolean settings) implements Clientbound {}

    public record CloseForms() implements Clientbound {}

    /** NPCDialogue: action 0 opens the dialogue, 1 closes it; `actionsJson` lists its buttons. */
    public record NpcDialogue(long entityId, int action, String dialogue, String scene, String npcName, String actionsJson) implements Clientbound {}

    /** NetworkStackLatency: when `needsResponse`, the client echoes the timestamp back at once. */
    public record Latency(long timestamp, boolean needsResponse) implements Clientbound {}

    private UiPackets() {}

    static Clientbound decode(int id, ByteBuf b) {
        return switch (id) {
            case PacketIds.TEXT -> text(b);
            case PacketIds.COMMAND_OUTPUT -> commandOutput(b);
            case PacketIds.SET_TITLE -> new Title(Bin.readVarInt(b), Bin.readString(b), Bin.readVarInt(b), Bin.readVarInt(b), Bin.readVarInt(b));
            case PacketIds.BOSS_EVENT -> {
                long boss = Bin.readVarLong(b);
                int event = b.readUnsignedByte();
                String title = Bin.readString(b);
                Bin.readString(b);
                yield new BossEvent(boss, event, title, b.readFloatLE(), b.readUnsignedByte());
            }
            case PacketIds.SET_DISPLAY_OBJECTIVE -> {
                String slot = Bin.readString(b);
                String objective = Bin.readString(b);
                String display = Bin.readString(b);
                Bin.readString(b);
                yield new DisplayObjective(slot, objective, display, Bin.readVarInt(b));
            }
            case PacketIds.REMOVE_OBJECTIVE -> new RemoveObjective(Bin.readString(b));
            case PacketIds.SET_SCORE -> setScore(b);
            case PacketIds.MODAL_FORM_REQUEST -> new Form(Bin.readVarUInt(b), new String(Bin.readBytes(b), StandardCharsets.UTF_8), false);
            case PacketIds.SERVER_SETTINGS_RESPONSE -> new Form(Bin.readVarUInt(b), new String(Bin.readBytes(b), StandardCharsets.UTF_8), true);
            case PacketIds.CLIENT_BOUND_CLOSE_FORM -> new CloseForms();
            case PacketIds.NPC_DIALOGUE -> new NpcDialogue(b.readLongLE(), Bin.readVarInt(b), Bin.readString(b), Bin.readString(b),
                    Bin.readString(b), Bin.readString(b));
            case PacketIds.NETWORK_STACK_LATENCY -> new Latency(b.readLongLE(), Bin.readBool(b));
            default -> null;
        };
    }

    private static Text text(ByteBuf b) {
        boolean translate = Bin.readBool(b);
        b.skipBytes(1);
        int type = b.readUnsignedByte();
        String source = null;
        List<String> params = List.of();
        String message;
        switch (type) {
            case 1, 7, 8 -> {
                source = Bin.readString(b);
                message = Bin.readString(b);
            }
            case 2, 3, 4 -> {
                message = Bin.readString(b);
                params = strings(b);
            }
            default -> message = Bin.readString(b);
        }
        return new Text(type, source, message, params, translate);
    }

    private static CommandOutput commandOutput(ByteBuf b) {
        Bin.readString(b);
        Bin.readUuid(b);
        Bin.readString(b);
        b.skipBytes(8);
        Bin.readString(b);
        int successes = b.readIntLE();
        int n = Bin.readVarUInt(b);
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String m = Bin.readString(b);
            b.skipBytes(1);
            List<String> params = strings(b);
            messages.add(params.isEmpty() ? m : m + " " + String.join(" ", params));
        }
        return new CommandOutput(successes > 0, messages);
    }

    private static SetScore setScore(ByteBuf b) {
        int n = Bin.readVarUInt(b);
        List<ScoreEntry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int kind = Bin.readVarUInt(b);
            Bin.readString(b);
            long entry = Bin.readVarLong(b);
            switch (kind) {
                case 0 -> out.add(new ScoreEntry(0, entry, Bin.readBool(b) ? Bin.readString(b) : "", 0, null));
                case 1, 2 -> {
                    String objective = Bin.readString(b);
                    int score = b.readIntLE();
                    long entity = Bin.readVarLong(b);
                    out.add(new ScoreEntry(kind, entry, objective, score, Long.toString(entity)));
                }
                default -> {
                    String objective = Bin.readString(b);
                    int score = b.readIntLE();
                    out.add(new ScoreEntry(3, entry, objective, score, Bin.readString(b)));
                }
            }
        }
        return new SetScore(out);
    }

    static List<String> strings(ByteBuf b) {
        int n = Bin.readVarUInt(b);
        List<String> out = new ArrayList<>(Math.min(n, 64));
        for (int i = 0; i < n; i++) out.add(Bin.readString(b));
        return out;
    }
}
