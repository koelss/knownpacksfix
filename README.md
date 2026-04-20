# KnownPacksFix

A lightweight compatibility plugin that fixes Velocity proxy disconnects caused by the “KnownPacksPacket / too many known packs” error during the Minecraft 1.21+ configuration phase.

This issue typically appears in heavily modded server environments using a Velocity proxy.

What it does
Prevents disconnects caused by excessive known pack data during login
Improves stability when connecting through Velocity
Works as a protocol-level compatibility workaround

# Compatibility

NeoForge servers (primary testing target)
May work with Forge / Fabric setups depending on environment
Minecraft 1.21.x
Requires Velocity proxy environment to be relevant
Building from source

# This project uses Maven.

Requirements
Java JDK 21 or newer
Apache Maven installed and added to PATH

# Download source

Download the project as a ZIP file from GitHub
Extract it to a folder.
Open a terminal inside that folder and
Run:
mvn clean package

# The compiled plugin .jar will be located in:

/target

# Installation

Build or download the .jar file. Place it into your server’s plugins folder and then restart the proxy.

# License

This project is open source under the MIT License.

You are free to:

Use it, modify it, fork it, redistribute it

Credit is appreciated but not required.
