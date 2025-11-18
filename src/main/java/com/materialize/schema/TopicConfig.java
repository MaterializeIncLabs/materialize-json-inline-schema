package com.materialize.schema;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Configuration for a single topic transformation.
 * Specifies input topic, output topic, and the schema to attach.
 */
public class TopicConfig {
    private final String inputTopic;
    private final String outputTopic;
    private final JsonNode schemaNode;

    public TopicConfig(String inputTopic, String outputTopic, JsonNode schemaNode) {
        if (inputTopic == null || inputTopic.isEmpty()) {
            throw new IllegalArgumentException("inputTopic cannot be null or empty");
        }
        if (outputTopic == null || outputTopic.isEmpty()) {
            throw new IllegalArgumentException("outputTopic cannot be null or empty");
        }
        if (schemaNode == null) {
            throw new IllegalArgumentException("schemaNode cannot be null");
        }

        this.inputTopic = inputTopic;
        this.outputTopic = outputTopic;
        this.schemaNode = schemaNode;
    }

    public String getInputTopic() {
        return inputTopic;
    }

    public String getOutputTopic() {
        return outputTopic;
    }

    public JsonNode getSchemaNode() {
        return schemaNode;
    }

    @Override
    public String toString() {
        return "TopicConfig{" +
                "inputTopic='" + inputTopic + '\'' +
                ", outputTopic='" + outputTopic + '\'' +
                '}';
    }
}
