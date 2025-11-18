package com.materialize.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaWrapperTest {
    private SchemaWrapper wrapper;
    private ObjectMapper mapper;
    private JsonNode testSchema;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        wrapper = new SchemaWrapper(mapper);

        String schemaJson = """
                {
                  "type": "struct",
                  "fields": [
                    {"type": "int64", "optional": false, "field": "id"},
                    {"type": "string", "optional": false, "field": "name"}
                  ],
                  "optional": false,
                  "name": "test.schema"
                }
                """;
        testSchema = mapper.readTree(schemaJson);
    }

    @Test
    void testWrapWithSchema_ValidJson() throws JsonProcessingException {
        String input = """
                {"id": 123, "name": "Alice"}
                """;

        String result = wrapper.wrapWithSchema(input, testSchema);

        assertNotNull(result);
        JsonNode resultNode = mapper.readTree(result);

        assertTrue(resultNode.has("schema"));
        assertTrue(resultNode.has("payload"));

        JsonNode payload = resultNode.get("payload");
        assertEquals(123, payload.get("id").asInt());
        assertEquals("Alice", payload.get("name").asText());

        JsonNode schema = resultNode.get("schema");
        assertEquals("struct", schema.get("type").asText());
    }

    @Test
    void testWrapWithSchema_NullInput() throws JsonProcessingException {
        String result = wrapper.wrapWithSchema(null, testSchema);
        assertNull(result);
    }

    @Test
    void testWrapWithSchema_InvalidJson() {
        String invalidJson = "not valid json {";

        assertThrows(JsonProcessingException.class, () -> {
            wrapper.wrapWithSchema(invalidJson, testSchema);
        });
    }

    @Test
    void testWrapWithSchemaOrPassThrough_ValidJson() {
        String input = """
                {"id": 456, "name": "Bob"}
                """;

        String result = wrapper.wrapWithSchemaOrPassThrough(input, testSchema);

        assertNotNull(result);
        assertTrue(result.contains("schema"));
        assertTrue(result.contains("payload"));
    }

    @Test
    void testWrapWithSchemaOrPassThrough_InvalidJson() {
        String invalidJson = "not valid json {";

        String result = wrapper.wrapWithSchemaOrPassThrough(invalidJson, testSchema);

        // Should pass through unchanged
        assertEquals(invalidJson, result);
    }

    @Test
    void testWrapWithSchemaOrPassThrough_NullInput() {
        String result = wrapper.wrapWithSchemaOrPassThrough(null, testSchema);
        assertNull(result);
    }

    @Test
    void testWrapWithSchema_EmptyObject() throws JsonProcessingException {
        String input = "{}";

        String result = wrapper.wrapWithSchema(input, testSchema);

        assertNotNull(result);
        JsonNode resultNode = mapper.readTree(result);

        assertTrue(resultNode.has("schema"));
        assertTrue(resultNode.has("payload"));

        JsonNode payload = resultNode.get("payload");
        assertTrue(payload.isObject());
        assertEquals(0, payload.size());
    }

    @Test
    void testWrapWithSchema_NestedObjects() throws JsonProcessingException {
        String input = """
                {
                  "id": 789,
                  "name": "Charlie",
                  "metadata": {
                    "created": "2024-01-01",
                    "tags": ["tag1", "tag2"]
                  }
                }
                """;

        String result = wrapper.wrapWithSchema(input, testSchema);

        assertNotNull(result);
        JsonNode resultNode = mapper.readTree(result);

        JsonNode payload = resultNode.get("payload");
        assertTrue(payload.has("metadata"));
        assertTrue(payload.get("metadata").isObject());
        assertTrue(payload.get("metadata").get("tags").isArray());
    }
}
