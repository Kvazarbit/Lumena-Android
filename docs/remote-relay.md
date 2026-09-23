# Lumena Remote Relay v1

This relay gives an authenticated ChatGPT session a narrow path to Lumena's
local public-web tools without exposing the Android device on Wi-Fi or opening
an inbound Internet port.

## Architecture

ChatGPT with GitHub access -> private GitHub mailbox issue -> Termux relay ->
127.0.0.1:8765 bridge -> public-web tool -> result comment -> ChatGPT.

The existing Termux bridge remains loopback-only.

## Authority boundary

Remote v1 accepts only:

- web.search
- web.read
- http.get
- http.json
- image.search

It deliberately rejects file/system/Git inspection, inspect.batch, Python,
Ollama execution, workspace writes and every mutating tool. This is a separate
authority boundary from the broader local ToolRegistry.

The mailbox repository must be private. Startup fails closed if GitHub reports
that the configured repository is public.

Only comments authored by the configured GitHub login are considered.
Completed request IDs are persisted and cannot execute again.

## Setup

1. Create a dedicated private GitHub repository, for example
   Kvazarbit/Lumena-Relay.
2. Create one open issue in that private repository, for example issue 1.
3. Create a fine-grained GitHub token restricted to that one private repository
   with Metadata: read and Issues: read/write.
4. Make sure the normal Lumena bridge is installed once:

       bash termux/install_bridge.sh

5. Configure the relay from Termux. The token is requested interactively and is
   not placed in shell history:

       python ~/Lumena-Android/termux/remote_relay.py --configure \
         --repo Kvazarbit/Lumena-Relay --issue 1 --author Kvazarbit

6. Start it:

       python ~/Lumena-Android/termux/remote_relay.py --daemon

Check configuration:

       python ~/Lumena-Android/termux/remote_relay.py --status

For a foreground one-shot poll:

       python ~/Lumena-Android/termux/remote_relay.py --once

Secrets are stored in ~/.lumena with owner-only permissions. Never commit the
GitHub token or bridge token.

## Wire protocol

A request is one issue comment:

    LUMENA_REMOTE_REQUEST_V1
    {"requestId":"search-001","tool":"web.search","args":{"query":"EPYC 7402P DDR4 ECC","limit":5}}

A result is returned as another issue comment:

    LUMENA_REMOTE_RESULT_V1
    {"version":1,"status":"tool_result","requestId":"search-001",...}

Unknown fields, nested argument objects, invalid request IDs and non-approved
tools are rejected before the local bridge is called.

## First-run safety

Configuration snapshots the newest existing mailbox comment. Therefore old
comments cannot suddenly execute after first install, reinstall or state
initialization.

## Why a private outbound relay instead of a public HTTP tunnel

The phone makes only outbound HTTPS requests to GitHub. There is no inbound
port to scan, no public bridge bearer token, and the local bridge can keep its
127.0.0.1-only invariant. GitHub provides authenticated authorship and an
auditable request/result log.

A later relay version may add locally approved executable/mutating requests,
but those must pass an explicit on-device approval layer instead of expanding
the v1 remote allow-list.
