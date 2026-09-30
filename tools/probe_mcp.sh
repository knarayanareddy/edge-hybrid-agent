#!/usr/bin/env bash
# Performs a real MCP `initialize` handshake against a remote endpoint.
#
# A bare GET returning 405/406 only proves an HTTP server is there. This sends the
# actual JSON-RPC initialize request, so a 200 with a JSON-RPC result proves the
# endpoint speaks MCP.
#
# Usage: probe_mcp.sh <url>
set -uo pipefail

URL="${1:-}"
[ -z "$URL" ] && { echo "usage: $0 <mcp-url>"; exit 2; }

host=$(echo "$URL" | sed -E 's#^https?://([^/]+).*#\1#')
ip=$(nslookup "$host" 2>/dev/null | awk '/^Address: /{print $2}' | tail -1)
[ -z "$ip" ] && { echo "$host -> NO_DNS"; exit 3; }

payload='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"edge-hybrid-probe","version":"1.0"}}}'

body=$(curl -s --max-time 20 --resolve "$host:443:$ip" \
  -X POST "$URL" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-03-26' \
  --data "$payload" 2>&1)

code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 --resolve "$host:443:$ip" \
  -X POST "$URL" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data "$payload" 2>/dev/null)

# SSE responses prefix the JSON with "data: "
clean=$(printf '%s' "$body" | sed -n 's/^data: //p' | head -1)
[ -z "$clean" ] && clean="$body"

if printf '%s' "$clean" | grep -q '"result"'; then
  name=$(printf '%s' "$clean" | sed -n 's/.*"serverInfo":{"name":"\([^"]*\)".*/\1/p')
  echo "$host -> MCP_OK http=$code server=${name:-unknown}"
  exit 0
elif printf '%s' "$clean" | grep -q '"error"'; then
  echo "$host -> MCP_ERROR http=$code $(printf '%s' "$clean" | head -c 160)"
  exit 1
else
  echo "$host -> MCP_UNKNOWN http=$code $(printf '%s' "$clean" | head -c 160)"
  exit 1
fi