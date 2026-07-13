"""Confluent-wire Avro decoding via Schema Registry (Phase-9 P2).

Kafka message values produced by the Debezium AvroConverter are framed as:
    [0x00 magic][4-byte big-endian schema id][avro binary payload]
This decoder fetches the writer schema by id from the Schema Registry and decodes
the payload with fastavro. Schemas are cached by id.
"""

import io
import json
import struct

import fastavro
import httpx


class AvroRegistryDecoder:
    def __init__(self, registry_url: str, auth: tuple[str, str] | None = None):
        self._url = registry_url.rstrip("/")
        self._auth = auth if auth and auth[0] else None
        self._cache: dict[int, dict] = {}

    async def _schema(self, schema_id: int) -> dict:
        if schema_id not in self._cache:
            async with httpx.AsyncClient(timeout=10, auth=self._auth) as client:
                resp = await client.get(f"{self._url}/schemas/ids/{schema_id}")
                resp.raise_for_status()
                self._cache[schema_id] = fastavro.parse_schema(json.loads(resp.json()["schema"]))
        return self._cache[schema_id]

    async def decode(self, data: bytes) -> dict:
        if not data or data[0] != 0:
            raise ValueError("not a Confluent-framed Avro message (bad magic byte)")
        schema_id = struct.unpack(">I", data[1:5])[0]
        schema = await self._schema(schema_id)
        return fastavro.schemaless_reader(io.BytesIO(data[5:]), schema)
