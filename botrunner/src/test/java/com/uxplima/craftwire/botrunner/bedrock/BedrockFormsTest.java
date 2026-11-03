package com.uxplima.craftwire.botrunner.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.bedrock.packet.UiPackets;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Server forms as gui_read shows them and the ModalFormResponse data dialog_answer sends back, in the shapes the
 * Bedrock client uses (Geyser and form plugins parse exactly these: an index, true/false, or a value array).
 */
class BedrockFormsTest {
    private static BedrockForms showing(String json) {
        BedrockForms f = new BedrockForms();
        f.form(new UiPackets.Form(7, json, false));
        return f;
    }

    private static final String SIMPLE = "{type:'form',title:'§6Shop',content:'Pick one',buttons:[{text:'§aBuy'},{text:'Sell'}]}";
    private static final String MODAL = "{type:'modal',title:'Sure?',content:'Really?',button1:'Yes',button2:'No'}";
    private static final String CUSTOM = """
            {type:'custom_form',title:'Settings',content:[
              {type:'label',text:'Pick your options'},
              {type:'input',text:'Name',default:'Steve',placeholder:'your name'},
              {type:'toggle',text:'PvP',default:true},
              {type:'slider',text:'Volume',min:0,max:10,step:1,default:5},
              {type:'dropdown',text:'Team',options:['§cRed','§9Blue'],default:0},
              {type:'step_slider',text:'Level',steps:['Easy','Hard'],default:1}
            ]}""";

    @Test
    void aSimpleFormListsItsButtonsWithoutColourCodes() {
        JsonObject dialog = showing(SIMPLE).json().getAsJsonObject("dialog");
        assertEquals("Shop", dialog.get("title").getAsString());
        assertEquals("Buy", dialog.getAsJsonArray("buttons").get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(2, dialog.getAsJsonArray("buttons").size());
    }

    @Test
    void aSimpleFormIsAnsweredWithTheButtonIndex() {
        assertEquals("1", showing(SIMPLE).answer("sell", Map.of()).json());
        assertEquals("0", showing(SIMPLE).answer("0", Map.of()).json());
        assertEquals("BUTTON_NOT_FOUND", assertThrows(AgentError.class, () -> showing(SIMPLE).answer("Trade", Map.of())).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> showing(SIMPLE).answer(null, Map.of())).code());
    }

    @Test
    void aModalIsAnsweredTrueForItsFirstButton() {
        assertEquals("true", showing(MODAL).answer("Yes", Map.of()).json());
        assertEquals("false", showing(MODAL).answer("No", Map.of()).json());
    }

    @Test
    void aCustomFormShowsLabelsAsBodyAndTheRestAsInputs() {
        JsonObject dialog = showing(CUSTOM).json().getAsJsonObject("dialog");
        assertEquals("Pick your options", dialog.getAsJsonArray("body").get(0).getAsString());
        JsonArray inputs = dialog.getAsJsonArray("inputs");
        assertEquals(5, inputs.size());
        JsonObject team = inputs.get(3).getAsJsonObject();
        assertEquals("4", team.get("key").getAsString(), "inputs are keyed by their element index");
        assertEquals("option", team.get("type").getAsString());
        assertEquals(List.of("Red", "Blue"), team.getAsJsonArray("options").asList().stream().map(e -> e.getAsString()).toList());
    }

    @Test
    void aCustomFormAnswerHasOneValuePerElementAndKeepsDefaults() {
        BedrockForms f = showing(CUSTOM);
        BedrockForms.Answer a = f.answer(null, Map.of("1", "Alex", "4", "blue", "3", "7.5"));
        // label → null, then input, toggle (default), slider, dropdown index by option text, step slider (default)
        assertEquals(JsonParser.parseString("[null,\"Alex\",true,7.5,1,1]"), JsonParser.parseString(a.json()));
        assertEquals(7, a.form().id());
        assertFalse(f.open(), "answering closes the form");
    }

    @Test
    void botFormValuesFillTheInputsInOrderSkippingLabels() {
        BedrockForms f = showing(CUSTOM);
        Map<String, String> inputs = f.inputs(List.of("Alex", "false"));
        assertEquals(Map.of("1", "Alex", "2", "false"), inputs);
        assertEquals(JsonParser.parseString("[null,\"Alex\",false,5,0,1]"), JsonParser.parseString(f.answer(null, inputs).json()));
    }

    @Test
    void aValueThatFitsNoInputIsRefused() {
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> showing(CUSTOM).answer(null, Map.of("3", "loud"))).code());
    }

    @Test
    void anNpcDialogueIsAnsweredWithItsButtonIndex() {
        BedrockForms f = new BedrockForms();
        f.npc(new UiPackets.NpcDialogue(1, 0, "Hello traveller", "", "§eGuide", "[{button_name:'Quests'},{button_name:'Bye'}]"));
        JsonObject dialog = f.json().getAsJsonObject("dialog");
        assertEquals("Guide", dialog.get("title").getAsString());
        assertEquals(1, f.answer("Bye", Map.of()).npcButton());
        assertFalse(f.open());
    }

    @Test
    void closingSendsAResponseWithoutData() {
        BedrockForms f = showing(SIMPLE);
        BedrockForms.Answer a = f.close();
        assertNull(a.json());
        assertEquals(7, a.form().id());
        assertNull(f.json());
        assertEquals("NO_DIALOG", assertThrows(AgentError.class, () -> f.answer("0", Map.of())).code());
    }

    @Test
    void aFormWhoseJsonIsBrokenIsNotShown() {
        assertFalse(showing("{not json").open());
    }
}
