#!/usr/bin/env python3
"""
Generate Kafka Connect schema configurations from Materialize sinks.

This script queries Materialize's system catalog to discover Kafka sinks and their
underlying schema (columns, types, nullability), then generates schema configurations
for the JSON Schema Attacher application.
"""

import argparse
import json
import logging
import re
import sys
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass

try:
    import psycopg2
    from psycopg2 import extras
except ImportError:
    print("Error: psycopg2 is not installed. Install with: pip install psycopg2-binary", file=sys.stderr)
    sys.exit(1)


# Configure logging
logging.basicConfig(level=logging.INFO, format='%(levelname)s: %(message)s')
logger = logging.getLogger(__name__)


@dataclass
class Column:
    """Represents a database column with its properties."""
    name: str
    type: str
    nullable: bool
    position: int


@dataclass
class Sink:
    """Represents a Materialize sink with its schema."""
    name: str
    topic: str
    source_object: str
    columns: List[Column]


class TypeMapper:
    """Maps Materialize types to Kafka Connect schema types."""

    # Basic type mappings
    TYPE_MAP = {
        'bigint': 'int64',
        'integer': 'int32',
        'int': 'int32',
        'smallint': 'int32',
        'real': 'float32',
        'double precision': 'float64',
        'double': 'float64',
        'numeric': 'double',  # Simplified mapping; could use bytes with Decimal logical type
        'decimal': 'double',
        'text': 'string',
        'varchar': 'string',
        'char': 'string',
        'character': 'string',
        'character varying': 'string',
        'boolean': 'boolean',
        'bool': 'boolean',
        'bytea': 'bytes',
    }

    # Temporal types that need logical type annotations
    TEMPORAL_TYPES = {
        'timestamp': ('int64', 'org.apache.kafka.connect.data.Timestamp'),
        'timestamp with time zone': ('int64', 'org.apache.kafka.connect.data.Timestamp'),
        'timestamp without time zone': ('int64', 'org.apache.kafka.connect.data.Timestamp'),
        'date': ('int32', 'org.apache.kafka.connect.data.Date'),
        'time': ('int32', 'org.apache.kafka.connect.data.Time'),
        'time with time zone': ('int32', 'org.apache.kafka.connect.data.Time'),
        'time without time zone': ('int32', 'org.apache.kafka.connect.data.Time'),
    }

    @classmethod
    def map_type(cls, materialize_type: str) -> Tuple[str, Optional[str], Optional[Dict]]:
        """
        Map a Materialize type to Kafka Connect type.

        Returns:
            Tuple of (kafka_connect_type, logical_type_name, additional_properties)
        """
        # Normalize type string
        normalized_type = materialize_type.lower().strip()

        # Check for temporal types first (they need logical types)
        if normalized_type in cls.TEMPORAL_TYPES:
            base_type, logical_type = cls.TEMPORAL_TYPES[normalized_type]
            return base_type, logical_type, {'version': 1}

        # Check basic types
        if normalized_type in cls.TYPE_MAP:
            return cls.TYPE_MAP[normalized_type], None, None

        # Handle array types
        if normalized_type.endswith('[]'):
            logger.warning(f"Array type '{materialize_type}' detected. Arrays are not fully supported yet. Using 'string' as fallback.")
            return 'string', None, None

        # Handle JSONB
        if 'json' in normalized_type:
            logger.warning(f"JSON type '{materialize_type}' detected. Using 'string' representation.")
            return 'string', None, None

        # Unknown type - default to string with warning
        logger.warning(f"Unknown type '{materialize_type}'. Defaulting to 'string'.")
        return 'string', None, None


class MaterializeSchemaExtractor:
    """Extracts schema information from Materialize."""

    def __init__(self, host: str, port: int, user: str, password: Optional[str], database: str):
        self.connection_params = {
            'host': host,
            'port': port,
            'user': user,
            'database': database,
        }
        if password:
            self.connection_params['password'] = password

    def connect(self):
        """Establish connection to Materialize."""
        try:
            return psycopg2.connect(**self.connection_params)
        except psycopg2.Error as e:
            logger.error(f"Failed to connect to Materialize: {e}")
            raise

    def get_sinks(self, sink_names: Optional[List[str]] = None) -> List[Sink]:
        """
        Query all Kafka sinks and their schemas.

        Args:
            sink_names: Optional list of specific sink names to query

        Returns:
            List of Sink objects with column information
        """
        with self.connect() as conn:
            with conn.cursor(cursor_factory=extras.DictCursor) as cur:
                # Query to get sink information and their source objects
                sink_query = """
                SELECT
                    s.name AS sink_name,
                    s.create_sql
                FROM mz_catalog.mz_sinks s
                WHERE s.type = 'kafka'
                """

                if sink_names:
                    placeholders = ','.join(['%s'] * len(sink_names))
                    sink_query += f" AND s.name IN ({placeholders})"
                    cur.execute(sink_query, sink_names)
                else:
                    cur.execute(sink_query)

                sink_rows = cur.fetchall()

                if not sink_rows:
                    logger.warning("No Kafka sinks found")
                    return []

                sinks = []
                for row in sink_rows:
                    sink_name = row['sink_name']
                    create_sql = row['create_sql']

                    # Extract topic name and source object from CREATE SINK statement
                    topic, source_object = self._parse_create_sink(create_sql)

                    if not source_object:
                        logger.warning(f"Could not parse source object for sink '{sink_name}'")
                        continue

                    # Get columns for the source object
                    columns = self._get_columns(cur, source_object)

                    if not columns:
                        logger.warning(f"No columns found for sink '{sink_name}' (source: {source_object})")
                        continue

                    sinks.append(Sink(
                        name=sink_name,
                        topic=topic,
                        source_object=source_object,
                        columns=columns
                    ))

                return sinks

    def _parse_create_sink(self, create_sql: str) -> Tuple[str, Optional[str]]:
        """
        Parse CREATE SINK statement to extract topic name and source object.

        Returns:
            Tuple of (topic_name, source_object_name)
        """
        # Extract topic name from TOPIC = 'topic-name'
        topic_match = re.search(r"TOPIC\s*=\s*'([^']+)'", create_sql, re.IGNORECASE)
        topic = topic_match.group(1) if topic_match else None

        # Extract source object from FROM [id AS "schema"."object"]
        # Example: FROM [u76 AS "materialize"."public"."users_processed"]
        source_match = re.search(r'FROM\s+\[[^\s]+\s+AS\s+"[^"]+"\."[^"]+"\."([^"]+)"\]', create_sql)
        source_object = source_match.group(1) if source_match else None

        return topic, source_object

    def _get_columns(self, cur, object_name: str) -> List[Column]:
        """
        Get columns for a materialized view or table.

        Args:
            cur: Database cursor
            object_name: Name of the object (view/table)

        Returns:
            List of Column objects
        """
        # Try materialized views first
        query = """
        SELECT
            c.name AS column_name,
            c.type AS column_type,
            c.nullable,
            c.position
        FROM mz_catalog.mz_materialized_views v
        JOIN mz_catalog.mz_columns c ON v.id = c.id
        WHERE v.name = %s
        ORDER BY c.position
        """

        cur.execute(query, (object_name,))
        rows = cur.fetchall()

        # If no results, try tables
        if not rows:
            query = """
            SELECT
                c.name AS column_name,
                c.type AS column_type,
                c.nullable,
                c.position
            FROM mz_catalog.mz_tables t
            JOIN mz_catalog.mz_columns c ON t.id = c.id
            WHERE t.name = %s
            ORDER BY c.position
            """
            cur.execute(query, (object_name,))
            rows = cur.fetchall()

        columns = []
        for row in rows:
            columns.append(Column(
                name=row['column_name'],
                type=row['column_type'],
                nullable=row['nullable'],
                position=row['position']
            ))

        return columns


class SchemaGenerator:
    """Generates Kafka Connect schema JSON from Materialize schema."""

    def __init__(self, schema_namespace: str = 'materialize'):
        self.schema_namespace = schema_namespace
        self.type_mapper = TypeMapper()

    def generate_schema(self, sink: Sink) -> Dict:
        """
        Generate Kafka Connect schema JSON for a sink.

        Args:
            sink: Sink object with column information

        Returns:
            Schema dictionary
        """
        fields = []

        for column in sink.columns:
            kafka_type, logical_type, extra_props = self.type_mapper.map_type(column.type)

            field = {
                'type': kafka_type,
                'optional': column.nullable,
                'field': column.name
            }

            # Add logical type if present
            if logical_type:
                field['name'] = logical_type
                if extra_props:
                    field.update(extra_props)

            fields.append(field)

        schema = {
            'type': 'struct',
            'fields': fields,
            'optional': False,
            'name': f'{self.schema_namespace}.{sink.source_object}'
        }

        return schema


class OutputFormatter:
    """Formats generated schemas for different output formats."""

    @staticmethod
    def format_properties(sinks: List[Sink], schemas: List[Dict], output_suffix: str = '-withschema') -> str:
        """
        Format schemas as Java properties file format.

        Args:
            sinks: List of Sink objects
            schemas: List of schema dictionaries (corresponding to sinks)
            output_suffix: Suffix to add to output topic names

        Returns:
            Properties file content as string
        """
        lines = []
        lines.append("# Generated schema configurations")
        lines.append("# Materialize JSON Schema Attacher")
        lines.append("")

        for sink, schema in zip(sinks, schemas):
            input_topic = sink.topic
            output_topic = input_topic.replace('-noschema', '') + output_suffix

            # Compact JSON on single line
            schema_json = json.dumps(schema, separators=(',', ': '))

            lines.append(f"# Sink: {sink.name} (from {sink.source_object})")
            lines.append(f"schema.topic.{input_topic}={schema_json}")
            lines.append(f"output.topic.{input_topic}={output_topic}")
            lines.append("")

        return '\n'.join(lines)

    @staticmethod
    def format_json(sinks: List[Sink], schemas: List[Dict], output_suffix: str = '-withschema') -> str:
        """
        Format schemas as JSON format.

        Args:
            sinks: List of Sink objects
            schemas: List of schema dictionaries (corresponding to sinks)
            output_suffix: Suffix to add to output topic names

        Returns:
            JSON string
        """
        output = {}

        for sink, schema in zip(sinks, schemas):
            input_topic = sink.topic
            output_topic = input_topic.replace('-noschema', '') + output_suffix

            output[sink.name] = {
                'input_topic': input_topic,
                'output_topic': output_topic,
                'source_object': sink.source_object,
                'schema': schema
            }

        return json.dumps(output, indent=2)


def main():
    parser = argparse.ArgumentParser(
        description='Generate Kafka Connect schema configurations from Materialize sinks',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Generate schemas for all sinks
  python generate_schemas.py --host localhost --port 6875

  # Generate for specific sinks
  python generate_schemas.py --sink users_json_sink --sink orders_json_sink

  # Output in JSON format
  python generate_schemas.py --format json --output schemas.json

  # Use environment variables for connection
  export MATERIALIZE_HOST=mz.example.com
  export MATERIALIZE_USER=myuser
  export MATERIALIZE_PASSWORD=mypassword
  python generate_schemas.py
        """
    )

    # Connection options
    parser.add_argument('--host', default='localhost',
                       help='Materialize host (default: localhost, env: MATERIALIZE_HOST)')
    parser.add_argument('--port', type=int, default=6875,
                       help='Materialize port (default: 6875, env: MATERIALIZE_PORT)')
    parser.add_argument('--user', default='materialize',
                       help='Materialize user (default: materialize, env: MATERIALIZE_USER)')
    parser.add_argument('--password',
                       help='Materialize password (env: MATERIALIZE_PASSWORD)')
    parser.add_argument('--database', default='materialize',
                       help='Materialize database (default: materialize, env: MATERIALIZE_DATABASE)')

    # Sink selection
    parser.add_argument('--sink', action='append', dest='sinks',
                       help='Specific sink name (can be repeated for multiple sinks)')
    parser.add_argument('--all-sinks', action='store_true', default=True,
                       help='Process all Kafka sinks (default: True)')

    # Output options
    parser.add_argument('--format', choices=['properties', 'json'], default='properties',
                       help='Output format (default: properties)')
    parser.add_argument('--output', '-o',
                       help='Output file (default: stdout)')

    # Schema options
    parser.add_argument('--schema-namespace', default='materialize',
                       help='Schema namespace prefix (default: materialize)')
    parser.add_argument('--output-suffix', default='-withschema',
                       help='Suffix for output topics (default: -withschema)')

    # Other options
    parser.add_argument('--verbose', '-v', action='store_true',
                       help='Enable verbose logging')

    args = parser.parse_args()

    # Configure logging
    if args.verbose:
        logger.setLevel(logging.DEBUG)

    # Get connection parameters from environment if not provided
    import os
    host = os.getenv('MATERIALIZE_HOST', args.host)
    port = int(os.getenv('MATERIALIZE_PORT', args.port))
    user = os.getenv('MATERIALIZE_USER', args.user)
    password = os.getenv('MATERIALIZE_PASSWORD', args.password)
    database = os.getenv('MATERIALIZE_DATABASE', args.database)

    try:
        # Extract schema from Materialize
        logger.info(f"Connecting to Materialize at {host}:{port}...")
        extractor = MaterializeSchemaExtractor(host, port, user, password, database)

        logger.info("Querying Kafka sinks...")
        sinks = extractor.get_sinks(sink_names=args.sinks)

        if not sinks:
            logger.error("No sinks found")
            return 1

        logger.info(f"Found {len(sinks)} sink(s)")

        # Generate schemas
        generator = SchemaGenerator(schema_namespace=args.schema_namespace)
        schemas = []

        for sink in sinks:
            logger.info(f"Generating schema for sink '{sink.name}' (topic: {sink.topic})")
            schema = generator.generate_schema(sink)
            schemas.append(schema)

        # Format output
        formatter = OutputFormatter()
        if args.format == 'properties':
            output = formatter.format_properties(sinks, schemas, args.output_suffix)
        else:  # json
            output = formatter.format_json(sinks, schemas, args.output_suffix)

        # Write output
        if args.output:
            with open(args.output, 'w') as f:
                f.write(output)
            logger.info(f"Schema configurations written to {args.output}")
        else:
            print(output)

        logger.info("Schema generation completed successfully")
        return 0

    except Exception as e:
        logger.error(f"Error: {e}")
        if args.verbose:
            import traceback
            traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
