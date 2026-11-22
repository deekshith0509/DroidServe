# HTTP API

All endpoints are served by the phone on the LAN. Authentication, when enabled, uses HTTP Basic
Auth with a session cookie or a per-link token.

| Endpoint | Method | Description |
|---|---|---|
| `/api/info` | GET | Server facts (name, IP, port, auth status, API version) |
| `/api/links` | GET | Current streaming-links list `{version, links[]}` |
| `/api/links/add` | GET | Add/resolve a URL |
| `/api/links/remove` | GET | Remove a link by id |
| `/api/links/clear` | GET | Clear all links |
| `/api/links/refresh` | GET | Re-fetch and replace a playlist group |
| `/api/links/export.m3u` | GET | Export all links as M3U |
| `/api/links/poll` | GET | Long-poll for link changes |
| `/api/links/import` | POST | Bulk import an M3U body or URL |
| `/api/links/import/preview` | GET | Preview an M3U URL (count + samples) |
| `/api/remote/*` | GET/POST | TV now-playing and remote commands |

Responses are JSON unless noted. CORS headers are included on all responses.
EOF