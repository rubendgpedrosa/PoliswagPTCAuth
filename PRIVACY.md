# Poliswag Passport — Privacy Policy

Poliswag Passport is a self-hosted helper. It runs entirely on the user's own
machine and talks only to the user's own local helper process and to the
official Pokémon Trainer Club login site.

## What it handles

- **Authentication information** (Pokémon Trainer Club username and password):
  received from the user's own Dragonite instance via the local helper, used
  **only** to perform the login on `https://access.pokemon.com`, and the
  resulting login code is returned to the local helper.

## What it does NOT do

- It does **not** store credentials or login codes.
- It does **not** send any data to the developer.
- It does **not** send any data to third parties or analytics services.
- It contacts no remote servers other than the official Pokémon login site; all
  helper communication is to `127.0.0.1` (the user's own machine).

## Contact

Questions: open an issue on the project repository.
