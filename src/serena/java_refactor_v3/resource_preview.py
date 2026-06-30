"""Planned V3 resource-preview public module."""

from serena.java_refactor_v3.resource_spi_client import ResourceSpiClient


class ResourcePreviewClient(ResourceSpiClient):
    """Plan-named adapter over the consolidated resource SPI client."""


__all__ = ["ResourcePreviewClient", "ResourceSpiClient"]
