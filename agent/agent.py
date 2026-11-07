"""Minimal ACP agent for JetBrains Air.

Install:  uv add agent-client-protocol   (or: pip install agent-client-protocol)
Run:      Air launches this over stdio via ~/.jetbrains/acp.json — you don't run it yourself.

IMPORTANT: stdout is the JSON-RPC channel. Never print() to stdout — log to stderr.
"""

import asyncio
import logging
import sys
from typing import Any
from uuid import uuid4

from acp import (
    PROTOCOL_VERSION,
    Agent,
    InitializeResponse,
    NewSessionResponse,
    PromptResponse,
    run_agent,
    text_block,
    update_agent_message,
)
from acp.interfaces import Client

logging.basicConfig(stream=sys.stderr, level=logging.INFO)
log = logging.getLogger("my-agent")


class Session:
    def __init__(self, cwd: str, mcp_servers: list[Any]) -> None:
        self.cwd = cwd                  # project root Air opened the task in
        self.mcp_servers = mcp_servers  # IDE tools if use_idea_mcp=true in acp.json
        self.history: list[dict[str, str]] = []
        self.cancelled = False


class MyAgent(Agent):
    _conn: Client

    def __init__(self) -> None:
        self.sessions: dict[str, Session] = {}

    def on_connect(self, conn: Client) -> None:
        self._conn = conn

    # 1. Handshake: Air tells you its capabilities (fs, terminal), you reply with yours.
    async def initialize(self, protocol_version: int, client_capabilities=None,
                         client_info=None, **kwargs: Any) -> InitializeResponse:
        log.info("client=%s caps=%s", client_info, client_capabilities)
        return InitializeResponse(protocol_version=PROTOCOL_VERSION)

    # 2. One session per Air task/chat.
    async def new_session(self, cwd: str, mcp_servers: list[Any],
                          **kwargs: Any) -> NewSessionResponse:
        session_id = uuid4().hex
        self.sessions[session_id] = Session(cwd, mcp_servers)
        log.info("new session %s in %s (%d MCP servers)", session_id, cwd, len(mcp_servers))
        return NewSessionResponse(session_id=session_id)

    # 3. A user turn. Stream updates back, then return a stop reason.
    async def prompt(self, prompt: list[Any], session_id: str,
                     **kwargs: Any) -> PromptResponse:
        session = self.sessions[session_id]
        session.cancelled = False

        user_text = "\n".join(b.text for b in prompt if getattr(b, "text", None))
        session.history.append({"role": "user", "content": user_text})

        # --- Replace this with your actual agent loop (call an LLM, run tools, etc.) ---
        reply = f"You said: {user_text}\n(working dir: {session.cwd})"
        for word in reply.split(" "):
            if session.cancelled:
                return PromptResponse(stop_reason="cancelled")
            await self._conn.session_update(
                session_id=session_id,
                update=update_agent_message(text_block(word + " ")),
            )
            await asyncio.sleep(0.02)  # fake streaming
        # -------------------------------------------------------------------------------

        session.history.append({"role": "assistant", "content": reply})
        return PromptResponse(stop_reason="end_turn")

    # 4. User hit stop in Air.
    async def cancel(self, session_id: str, **kwargs: Any) -> None:
        if session_id in self.sessions:
            self.sessions[session_id].cancelled = True


async def main() -> None:
    await run_agent(MyAgent())


if __name__ == "__main__":
    asyncio.run(main())
