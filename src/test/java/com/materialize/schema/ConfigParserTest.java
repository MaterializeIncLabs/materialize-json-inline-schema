package com.materialize.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigParserTest {
    private ConfigParser parser;

    @BeforeEach
    void setUp() {
        parser = new ConfigParser();
    }

    @Test
    void testParseTopicConfigs_ValidConfiguration() throws IOException {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "localhost:9092");
        props.setProperty("application.id", "test-app");

        String schema = """
                {
                  "type": "struct",
                  "fields": [
                    {"type": "int64", "optional": false, "field": "id"},
                    {"type": "string", "optional": false, "field": "name"}
                  ],
                  "optional": false,
                  "name": "test.users"
                }
                """;

        props.setProperty("schema.topic.users-input", schema);
        props.setProperty("output.topic.users-input", "users-output");

        List<TopicConfig> configs = parser.parseTopicConfigs(props);

        assertEquals(1, configs.size());
        TopicConfig config = configs.get(0);
        assertEquals("users-input", config.getInputTopic());
        assertEquals("users-output", config.getOutputTopic());
        assertNotNull(config.getSchemaNode());
    }

    @Test
    void testParseTopicConfigs_MultipleTopics() throws IOException {
        Properties props = new Properties();

        String schema1 = "{\"type\": \"struct\", \"fields\": [], \"name\": \"schema1\"}";
        String schema2 = "{\"type\": \"struct\", \"fields\": [], \"name\": \"schema2\"}";

        props.setProperty("schema.topic.topic1", schema1);
        props.setProperty("output.topic.topic1", "output1");

        props.setProperty("schema.topic.topic2", schema2);
        props.setProperty("output.topic.topic2", "output2");

        List<TopicConfig> configs = parser.parseTopicConfigs(props);

        assertEquals(2, configs.size());
    }

    @Test
    void testParseTopicConfigs_MissingOutputTopic() {
        Properties props = new Properties();
        String schema = "{\"type\": \"struct\", \"fields\": [], \"name\": \"test\"}";

        props.setProperty("schema.topic.input-topic", schema);
        // Missing output.topic.input-topic

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            parser.parseTopicConfigs(props);
        });

        assertTrue(exception.getMessage().contains("Missing config"));
        assertTrue(exception.getMessage().contains("output.topic.input-topic"));
    }

    @Test
    void testParseTopicConfigs_NoSchemaTopics() {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "localhost:9092");
        props.setProperty("application.id", "test-app");
        // No schema.topic.* properties

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            parser.parseTopicConfigs(props);
        });

        assertTrue(exception.getMessage().contains("No schema.topic.* configuration entries found"));
    }

    @Test
    void testParseTopicConfigs_InvalidSchemaJson() {
        Properties props = new Properties();
        props.setProperty("schema.topic.test-topic", "not valid json {");
        props.setProperty("output.topic.test-topic", "output-topic");

        assertThrows(IOException.class, () -> {
            parser.parseTopicConfigs(props);
        });
    }

    @Test
    void testParseTopicConfigs_IgnoresNonSchemaProperties() throws IOException {
        Properties props = new Properties();

        String schema = "{\"type\": \"struct\", \"fields\": [], \"name\": \"test\"}";
        props.setProperty("schema.topic.test", schema);
        props.setProperty("output.topic.test", "output");

        // These should be ignored
        props.setProperty("bootstrap.servers", "localhost:9092");
        props.setProperty("some.other.property", "value");
        props.setProperty("topic.something", "ignored");

        List<TopicConfig> configs = parser.parseTopicConfigs(props);

        assertEquals(1, configs.size());
    }
}
