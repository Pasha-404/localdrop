# localdrop-protocol

Shared LocalDrop network contract module.

This module contains the protocol-level classes that can be reused by multiple platforms:

- discovery payloads
- device info model
- TCP transfer message framing and payload models
- shared protocol constants

The goal is to keep Android and Windows implementations aligned on one source of truth for LAN discovery and file-transfer messages.
