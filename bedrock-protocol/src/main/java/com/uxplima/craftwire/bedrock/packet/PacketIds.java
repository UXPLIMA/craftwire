package com.uxplima.craftwire.bedrock.packet;

/**
 * Packet ids of the Bedrock protocol a bot uses, table-like data checked against gophertunnel's id list and pmmp's
 * protocol_info. They are the same in every {@link BedrockVersion} (1.26.60 only appended ids). Only the packets
 * the bot reads or sends are listed; the rest are skipped by length inside their batch.
 */
public final class PacketIds {
    static final int LOGIN = 1;
    static final int PLAY_STATUS = 2;
    static final int SERVER_TO_CLIENT_HANDSHAKE = 3;
    static final int CLIENT_TO_SERVER_HANDSHAKE = 4;
    static final int DISCONNECT = 5;
    static final int RESOURCE_PACKS_INFO = 6;
    static final int RESOURCE_PACK_STACK = 7;
    static final int RESOURCE_PACK_CLIENT_RESPONSE = 8;
    static final int TEXT = 9;
    static final int SET_TIME = 10;
    static final int START_GAME = 11;
    static final int ADD_PLAYER = 12;
    static final int ADD_ACTOR = 13;
    static final int REMOVE_ACTOR = 14;
    static final int MOVE_ACTOR_ABSOLUTE = 18;
    static final int MOVE_PLAYER = 19;
    static final int UPDATE_BLOCK = 21;
    static final int UPDATE_ATTRIBUTES = 29;
    static final int INVENTORY_TRANSACTION = 30;
    static final int MOB_EQUIPMENT = 31;
    static final int INTERACT = 33;
    static final int PLAYER_ACTION = 36;
    static final int SET_ACTOR_MOTION = 40;
    static final int SET_HEALTH = 42;
    static final int ANIMATE = 44;
    static final int RESPAWN = 45;
    static final int CONTAINER_OPEN = 46;
    static final int CONTAINER_CLOSE = 47;
    static final int INVENTORY_CONTENT = 49;
    static final int INVENTORY_SLOT = 50;
    static final int CONTAINER_SET_DATA = 51;
    static final int BLOCK_ACTOR_DATA = 56;
    static final int LEVEL_CHUNK = 58;
    static final int CHANGE_DIMENSION = 61;
    static final int SET_PLAYER_GAME_TYPE = 62;
    static final int PLAYER_LIST = 63;
    static final int REQUEST_CHUNK_RADIUS = 69;
    static final int CHUNK_RADIUS_UPDATED = 70;
    static final int BOSS_EVENT = 74;
    static final int COMMAND_REQUEST = 77;
    static final int COMMAND_OUTPUT = 79;
    static final int RESOURCE_PACK_DATA_INFO = 82;
    static final int RESOURCE_PACK_CHUNK_DATA = 83;
    static final int RESOURCE_PACK_CHUNK_REQUEST = 84;
    static final int TRANSFER = 85;
    static final int SET_TITLE = 88;
    static final int NPC_REQUEST = 98;
    static final int MODAL_FORM_REQUEST = 100;
    static final int MODAL_FORM_RESPONSE = 101;
    static final int SERVER_SETTINGS_REQUEST = 102;
    static final int SERVER_SETTINGS_RESPONSE = 103;
    static final int REMOVE_OBJECTIVE = 106;
    static final int SET_DISPLAY_OBJECTIVE = 107;
    static final int SET_SCORE = 108;
    static final int MOVE_ACTOR_DELTA = 111;
    static final int SET_LOCAL_PLAYER_AS_INITIALISED = 113;
    static final int NETWORK_STACK_LATENCY = 115;
    static final int NETWORK_CHUNK_PUBLISHER_UPDATE = 121;
    static final int CLIENT_CACHE_STATUS = 129;
    static final int NETWORK_SETTINGS = 143;
    static final int PLAYER_AUTH_INPUT = 144;
    static final int ITEM_STACK_REQUEST = 147;
    static final int ITEM_STACK_RESPONSE = 148;
    static final int CORRECT_PLAYER_MOVE_PREDICTION = 161;
    static final int ITEM_REGISTRY = 162;
    static final int NPC_DIALOGUE = 169;
    static final int SUB_CHUNK = 174;
    static final int SUB_CHUNK_REQUEST = 175;
    static final int REQUEST_NETWORK_SETTINGS = 193;
    static final int CLIENT_BOUND_CLOSE_FORM = 310;
    static final int SERVER_BOUND_LOADING_SCREEN = 312;

    private PacketIds() {}
}
