package com.materialize.schema;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

/**
 * Kafka Streams application that attaches inline JSON schemas to messages.
 * Reads from configured input topics and writes to output topics with schema attached.
 */
public class SchemaAttacherApp {
    private static final Logger logger = LoggerFactory.getLogger(SchemaAttacherApp.class);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: SchemaAttacherApp <path-to-properties-file>");
            System.exit(1);
        }

        Properties props = loadProperties(args[0]);

        Topology topology = buildTopology(props);
        logger.info("Topology:\n{}", topology.describe());

        KafkaStreams streams = new KafkaStreams(topology, props);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            streams.close();
        }));

        logger.info("Starting Kafka Streams application...");
        streams.start();
    }

    /**
     * Load properties from file.
     */
    static Properties loadProperties(String path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            props.load(in);
        }
        logger.info("Loaded configuration from {}", path);
        return props;
    }

    /**
     * Build the Kafka Streams topology.
     */
    static Topology buildTopology(Properties props) throws IOException {
        StreamsBuilder builder = new StreamsBuilder();
        ConfigParser parser = new ConfigParser();
        SchemaWrapper wrapper = new SchemaWrapper();

        List<TopicConfig> topicConfigs = parser.parseTopicConfigs(props);

        for (TopicConfig config : topicConfigs) {
            attachSchemaForTopic(builder, config, wrapper);
        }

        return builder.build();
    }

    /**
     * Create a stream for a single topic that attaches schema to each message.
     * Tombstones (null values) are preserved and passed through for delete operations.
     */
    private static void attachSchemaForTopic(StreamsBuilder builder, TopicConfig config, SchemaWrapper wrapper) {
        logger.info("Setting up stream: {} -> {} (tombstones will be preserved)",
                    config.getInputTopic(), config.getOutputTopic());

        builder.stream(
                    config.getInputTopic(),
                    Consumed.with(Serdes.String(), Serdes.String())
               )
               .peek((key, value) -> {
                   if (value == null) {
                       logger.info("Tombstone detected for key: {} - propagating delete", key);
                   }
               })
               .mapValues(value -> wrapper.wrapWithSchemaOrPassThrough(value, config.getSchemaNode()))
               .to(config.getOutputTopic(), Produced.with(Serdes.String(), Serdes.String()));
    }
}
