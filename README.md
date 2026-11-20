# Duck Physical Therapy Clinic

Quarkus 3 application (JDK 21, port **8082**, package `eu.hansolo.duckphysicaltherapyclinic`) with a web UI, a REST API and an MCP server that speaks the stateless MCP protocol (spec **2026-07-28**) through `quarkus-mcp-server` 2.0.x. Older stateful MCP clients are served on the same endpoint.

## Run

```bash
docker compose up --build      # then use http://localhost:8082
# or without Docker (JDK 21 + Maven):
mvn quarkus:dev
```

Open <http://localhost:8082> for the web UI.

## Web UI

Plain HTML, CSS and JavaScript in `src/main/resources/META-INF/resources` (no build step, no external fonts or CDNs). It uses the `/api` endpoints:

- **Book a session**: owner, duck, treatment, therapist, date and time, with a live summary and the same booking rules as the API.
- **Appointments**: find by owner, by duck or by appointment number, optionally including cancelled ones.
- **Treatments**: the treatment list with duration and price, plus the therapists.

The H2 database lives in the `clinic-data` volume (`/data` in the container) and is seeded on first start with 3 owners, 6 ducks, 2 therapists and 6 treatments. Two ducks (Sir Quacksalot and Duchess), their owner Anna Keller and the therapists Dr. Dewey Quackenbush and Dr. Mabel Mallard come from the duck insurance, the other owners and ducks are new. To re-seed an existing database run `docker compose down -v` (needed once if you started the all-animals version, the tables changed).

## Data model (fields taken from the duck insurance)

| Table       | Fields                                                              | Origin in duck insurance |
|-------------|---------------------------------------------------------------------|--------------------------|
| Owner       | firstName, lastName, email, birthDate, city                         | Customer                 |
| Duck        | name, breed, birthDate, ownerId                                     | Duck                     |
| Therapist   | name, specialty, city                                               | Vet                      |
| Treatment   | name, description, durationMinutes, price                           | new                      |
| Appointment | duckId, ownerId, therapistId, treatmentId, startTime, endTime, status, notes, createdAt | new (shaped like Claim) |

## MCP tools (`POST /mcp`)

`list_treatments`, `list_therapists`, `list_owners`, `list_ducks`, `book_appointment`, `get_appointment`, `list_appointments_for_duck`, `list_appointments_for_owner`

Booking rules: Monday to Friday, 08:00-18:00 local clinic time, treatment must end by closing time, start in the future, no double booking of therapist or duck. Violations come back as MCP tool errors with a readable message.

A stateless call is one self-contained POST, no `initialize`, no session id:

```bash
curl -s http://localhost:8082/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: list_treatments' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_treatments","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"curl","version":"1"}}}}'
```

Claude Code: `claude mcp add --transport http duck-clinic http://localhost:8082/mcp`

## REST API

`GET /api/treatments`, `/api/therapists`, `/api/owners`, `/api/ducks?ownerId=`, `POST /api/appointments`, `GET /api/appointments/{id}`, `GET /api/ducks/{id}/appointments`, `GET /api/owners/{id}/appointments` (`?includeCancelled=true`).

```bash
curl -s localhost:8082/api/appointments -H 'Content-Type: application/json' \
  -d '{"duckId":1,"treatmentId":1,"therapistId":1,"startTime":"2026-12-07T10:00"}'
```

## Notes

- Not built yet: the code was written without access to Maven Central. If the first `mvn package` complains, the likely suspects are the versions in `pom.xml` (`quarkus.platform.version`, `quarkus-mcp-server.version`).
- No authentication; put it behind a gateway before exposing it.
- Two simultaneous bookings of the same slot could both pass the overlap check; add a unique constraint or locking if that matters.


## Agent-to-agent demo

This application has an agent that takes part in a referral across DuckClinic, the Duck Physical Therapy Clinic and DuckInsurance. The design, tools, policies and limits are in `README-agents.md` of the DuckInsurance project (copied here as `README-agents.md`). Model settings: copy `llm.env.example` to `llm.env`.
