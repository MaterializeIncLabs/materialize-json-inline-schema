package com.materialize.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopicConfigTest {
    private JsonNode testSchema;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String schemaJson = """
                {
                  "type": "struct",
                  "fields": [],
                  "name": "test.schema"
                }
                """;
        testSchema = mapper.readTree(schemaJson);
    }

    @Test
    void testValidConstruction() {
        TopicConfig config = new TopicConfig("input-topic", "output-topic", testSchema);

        assertEquals("input-topic", config.getInputTopic());
        assertEquals("output-topic", config.getOutputTopic());
        assertEquals(testSchema, config.getSchemaNode());
    }

    @Test
    void testNullInputTopic() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopicConfig(null, "output-topic", testSchema);
        });
    }

    @Test
    void testEmptyInputTopic() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopicConfig("", "output-topic", testSchema);
        });
    }

    @Test
    void testNullOutputTopic() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopicConfig("input-topic", null, testSchema);
        });
    }

    @Test
    void testEmptyOutputTopic() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopicConfig("input-topic", "", testSchema);
        });
    }

    @Test
    void testNullSchema() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TopicConfig("input-topic", "output-topic", null);
        });
    }

    @Test
    void testToString() {
        TopicConfig config = new TopicConfig("input-topic", "output-topic", testSchema);
        String str = config.toString();

        assertTrue(str.contains("input-topic"));
        assertTrue(str.contains("output-topic"));
    }
}
