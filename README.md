# Pano Market

Store plugin for [Pano](https://panomc.com): list, manage and sell products on your website, with a cart,
checkout, credits, orders and invoices, plus delivery to your Minecraft servers.

## Payment gateways and shipping carriers

The market plugin itself ships no payment gateway and no shipping carrier. Gateways (Stripe, ...) and carriers are
separate Pano plugins built against the market API jar (`pano-plugin-market-api-<version>.jar`, attached to every
release). Install the ones you need next to this plugin.

## Minecraft-side component (required for server deliveries)

Deliveries to a game server (commands, ranks, items) are executed by a small component on the Minecraft server. It is
required whenever a product is delivered to a server, and it must match the market version.

- Spigot / Paper / Folia, BungeeCord, Velocity: the component is inside the market jar. Download it from the panel
  (Market > Minecraft component) and drop it into the server's `plugins` folder.
- Fabric: a separate jar, `pano-plugin-market-fabric-<version>.jar`, attached to every GitHub release (also
  downloadable from the panel).

## Build

Needs JDK 21, Bun and the Gradle wrapper. `./gradlew build -Pfabric` builds the plugin jar (`build/libs`), the API jar
(`build/api`) and the Fabric jar (`build/mc`). The database test tier needs MariaDB (`PANO_IT_MARIADB`).

Releases are produced by CI (semantic-release) on `dev` (prerelease, dev store) and `main` (production store).
