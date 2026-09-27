"""Ask Gemini about your Starchart data, using Starchart's MCP server as tools.

Setup:
    pip install -r examples/requirements.txt
    export GEMINI_API_KEY=...          # from https://aistudio.google.com/apikey
    export STARCHART_TOKEN=sc_...      # starchart.fly.dev -> API tokens -> Create token

Run:
    python examples/gemini_starchart.py "How am I going on my goals this fortnight?"
    python examples/gemini_starchart.py --check   # Starchart only: list tools + call get_overview

Env overrides: STARCHART_URL (default https://starchart.fly.dev/mcp),
GEMINI_MODEL (default gemini-3.8-flash).
"""

import asyncio
import json
import os
import sys

from google import genai
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared._httpx_utils import create_mcp_http_client

STARCHART_URL = os.environ.get("STARCHART_URL", "https://starchart.fly.dev/mcp")
MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.8-flash")


async def run(prompt: str | None) -> None:
    token = os.environ.get("STARCHART_TOKEN")
    if not token:
        sys.exit("Set STARCHART_TOKEN (create one on the Starchart home page).")

    http = create_mcp_http_client(headers={"Authorization": f"Bearer {token}"})
    async with http, streamable_http_client(STARCHART_URL, http_client=http) as streams:
        read, write = streams[0], streams[1]
        async with ClientSession(read, write) as session:
            await session.initialize()

            if prompt is None:
                tools = await session.list_tools()
                print("Tools:", ", ".join(t.name for t in tools.tools))
                result = await session.call_tool("get_overview", {})
                print(json.dumps(json.loads(result.content[0].text), indent=2, ensure_ascii=False))
                return

            # The SDK lists the session's tools, lets Gemini call them, and
            # feeds results back until it has an answer.
            client = genai.Client()
            response = await client.aio.models.generate_content(
                model=MODEL,
                contents=prompt,
                config=genai.types.GenerateContentConfig(
                    system_instruction=(
                        "You help the user with their habit goals and whereabouts using the Starchart tools. "
                        "Call get_overview first when unsure. Be brief and encouraging."
                    ),
                    tools=[session],
                ),
            )
            print(response.text)


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    asyncio.run(run(None if args == ["--check"] else " ".join(args)))
