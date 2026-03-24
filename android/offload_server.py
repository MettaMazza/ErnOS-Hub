#!/usr/bin/env python3
"""
offload_server.py — ErnOS PC/Mac Inference Offload Server

Runs a WebSocket server that accepts inference requests from the ErnOS Android
app and streams token responses back using llama-cpp-python.

Requirements:
    pip install llama-cpp-python websockets

Usage:
    python offload_server.py --model /path/to/model.gguf [--host 0.0.0.0] [--port 8765] [--secret <token>]

SECURITY NOTE:
    This server exposes bash_execute/terminal_read/finder_open which allow
    arbitrary command execution on the host.  Always set --secret to a strong
    random token and never expose port 8765 to the public internet.
    Generate a token: python3 -c "import secrets; print(secrets.token_hex(32))"

The server binds on all interfaces by default so that Android devices on the
same Wi-Fi network can reach it.  To restrict to localhost only, pass
--host 127.0.0.1.

Protocol:
    First frame (client → server) MUST be an auth challenge if --secret is set:
        {"type": "auth", "token": "<secret>"}
    Server replies:
        {"type": "auth_ok"}   on success
        {"type": "error", "message": "Unauthorized"} + close on failure

    Generate request:
        {"type": "generate", "prompt": "...", "max_tokens": 512, "temperature": 0.7, "top_p": 0.9}

    Token frame (server → client, repeated):
        {"type": "token", "text": "..."}

    Done frame (server → client):
        {"type": "done"}

    Error frame (server → client):
        {"type": "error", "message": "..."}

    Ping (client → server):
        {"type": "ping"}

    Pong (server → client):
        {"type": "pong"}
"""

import argparse
import asyncio
import json
import logging
import sys

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("offload_server")


def load_llama(model_path: str, n_ctx: int, n_gpu_layers: int):
    """Load the llama-cpp-python model and return the Llama instance."""
    try:
        from llama_cpp import Llama  # type: ignore
    except ImportError:
        log.error(
            "llama-cpp-python not installed. Run: pip install llama-cpp-python"
        )
        sys.exit(1)

    log.info(f"Loading model: {model_path}")
    log.info(f"  context window : {n_ctx}")
    log.info(f"  GPU layers     : {n_gpu_layers}")

    llm = Llama(
        model_path=model_path,
        n_ctx=n_ctx,
        n_gpu_layers=n_gpu_layers,
        verbose=False,
    )
    log.info("Model loaded successfully")
    return llm


async def handle_connection(websocket, llm, secret: str):
    """Handle a single WebSocket client connection."""
    remote = websocket.remote_address
    log.info(f"Client connected: {remote}")

    # ── Authentication ──────────────────────────────────────────────────────
    if secret:
        try:
            raw_auth = await asyncio.wait_for(websocket.recv(), timeout=10.0)
            frame = json.loads(raw_auth)
        except (asyncio.TimeoutError, json.JSONDecodeError, Exception) as e:
            log.warning(f"Auth failed ({remote}): {e}")
            try:
                await websocket.send(json.dumps({"type": "error", "message": "Unauthorized"}))
                await websocket.close(1008, "Unauthorized")
            except Exception:
                pass
            return

        provided = frame.get("token", "")
        if frame.get("type") != "auth" or not _constant_time_eq(provided, secret):
            log.warning(f"Auth rejected ({remote}): wrong or missing token")
            try:
                await websocket.send(json.dumps({"type": "error", "message": "Unauthorized"}))
                await websocket.close(1008, "Unauthorized")
            except Exception:
                pass
            return

        await websocket.send(json.dumps({"type": "auth_ok"}))
        log.info(f"Client authenticated: {remote}")

    # ── Main message loop ───────────────────────────────────────────────────
    try:
        async for raw_message in websocket:
            try:
                frame = json.loads(raw_message)
            except json.JSONDecodeError:
                await websocket.send(json.dumps({
                    "type": "error",
                    "message": "Invalid JSON frame",
                }))
                continue

            frame_type = frame.get("type", "")

            if frame_type == "ping":
                await websocket.send(json.dumps({"type": "pong"}))
                continue

            if frame_type == "generate":
                prompt = frame.get("prompt", "")
                if prompt.startswith("__BASH__:"):
                    await handle_bash(websocket, prompt[len("__BASH__:"):])
                elif prompt.startswith("__READ__:"):
                    await handle_read_file(websocket, prompt[len("__READ__:"):])
                elif prompt.startswith("__FINDER__:"):
                    await handle_finder_open(websocket, prompt[len("__FINDER__:"):])
                else:
                    await handle_generate(websocket, llm, frame)
                continue

            await websocket.send(json.dumps({
                "type": "error",
                "message": f"Unknown frame type: {frame_type}",
            }))

    except Exception as e:
        log.warning(f"Connection error ({remote}): {e}")
    finally:
        log.info(f"Client disconnected: {remote}")


def _constant_time_eq(a: str, b: str) -> bool:
    """Constant-time string comparison to prevent timing attacks."""
    import hmac
    return hmac.compare_digest(a.encode(), b.encode())


async def handle_generate(websocket, llm, frame: dict):
    """Stream token generation for a generate request."""
    prompt      = frame.get("prompt", "")
    max_tokens  = int(frame.get("max_tokens", 512))
    temperature = float(frame.get("temperature", 0.7))
    top_p       = float(frame.get("top_p", 0.9))

    if not prompt:
        await websocket.send(json.dumps({
            "type": "error",
            "message": "Empty prompt",
        }))
        return

    log.info(f"Generating ({len(prompt)} chars prompt, max_tokens={max_tokens})")

    try:
        loop = asyncio.get_event_loop()
        token_queue: asyncio.Queue = asyncio.Queue()

        def _generate():
            try:
                for chunk in llm(
                    prompt,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    top_p=top_p,
                    stream=True,
                    stop=["<|im_end|>", "</s>"],
                ):
                    text = chunk["choices"][0]["text"]
                    if text:
                        loop.call_soon_threadsafe(token_queue.put_nowait, text)
            except Exception as exc:
                loop.call_soon_threadsafe(
                    token_queue.put_nowait, {"__error__": str(exc)}
                )
            finally:
                loop.call_soon_threadsafe(token_queue.put_nowait, None)

        await loop.run_in_executor(None, _generate)

        total_tokens = 0
        while True:
            item = await token_queue.get()
            if item is None:
                break
            if isinstance(item, dict) and "__error__" in item:
                await websocket.send(json.dumps({
                    "type":    "error",
                    "message": item["__error__"],
                }))
                return
            await websocket.send(json.dumps({"type": "token", "text": item}))
            total_tokens += 1

        await websocket.send(json.dumps({"type": "done"}))
        log.info(f"Generation complete — {total_tokens} tokens sent")

    except Exception as e:
        log.error(f"handle_generate error: {e}", exc_info=True)
        try:
            await websocket.send(json.dumps({
                "type":    "error",
                "message": str(e),
            }))
        except Exception:
            pass


async def handle_bash(websocket, command: str):
    """Execute a shell command on the host and stream stdout/stderr back."""
    import subprocess

    if not command.strip():
        await websocket.send(json.dumps({"type": "error", "message": "Empty bash command"}))
        return

    log.info(f"bash_execute: {command!r}")
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
        output = result.stdout + result.stderr
        if not output:
            output = f"(exit code {result.returncode}, no output)"
        await websocket.send(json.dumps({"type": "token", "text": output}))
        await websocket.send(json.dumps({"type": "done"}))
    except subprocess.TimeoutExpired:
        await websocket.send(json.dumps({"type": "error", "message": "Command timed out after 30s"}))
    except Exception as e:
        await websocket.send(json.dumps({"type": "error", "message": str(e)}))


async def handle_read_file(websocket, path: str):
    """Read a file on the host and stream its content back."""
    import os

    log.info(f"terminal_read: {path!r}")
    try:
        path = os.path.expanduser(path.strip())
        if not os.path.exists(path):
            await websocket.send(json.dumps({"type": "error", "message": f"File not found: {path}"}))
            return
        if os.path.isdir(path):
            entries = os.listdir(path)
            content = "\n".join(
                f"{'[DIR]' if os.path.isdir(os.path.join(path, e)) else '[FILE]'} {e}"
                for e in sorted(entries)
            )
        else:
            if os.path.getsize(path) > 2 * 1024 * 1024:
                await websocket.send(json.dumps({"type": "error", "message": "File too large (>2 MB)"}))
                return
            with open(path, "r", errors="replace") as f:
                content = f.read()
        await websocket.send(json.dumps({"type": "token", "text": content}))
        await websocket.send(json.dumps({"type": "done"}))
    except Exception as e:
        await websocket.send(json.dumps({"type": "error", "message": str(e)}))


async def handle_finder_open(websocket, path: str):
    """Reveal a path in macOS Finder or Windows Explorer on the host."""
    import os, subprocess, sys as _sys

    log.info(f"finder_open: {path!r}")
    try:
        path = os.path.expanduser(path.strip())
        if _sys.platform == "darwin":
            subprocess.Popen(["open", "-R", path])
        elif _sys.platform.startswith("linux"):
            subprocess.Popen(["xdg-open", os.path.dirname(path) if os.path.isfile(path) else path])
        elif _sys.platform == "win32":
            subprocess.Popen(["explorer", "/select,", path])
        else:
            await websocket.send(json.dumps({"type": "error", "message": f"Unsupported platform: {_sys.platform}"}))
            return
        await websocket.send(json.dumps({"type": "token", "text": f"Opened Finder for: {path}"}))
        await websocket.send(json.dumps({"type": "done"}))
    except Exception as e:
        await websocket.send(json.dumps({"type": "error", "message": str(e)}))


async def main(args):
    try:
        import websockets  # type: ignore
    except ImportError:
        log.error("websockets not installed. Run: pip install websockets")
        sys.exit(1)

    llm = load_llama(args.model, n_ctx=args.ctx, n_gpu_layers=args.gpu_layers)

    secret = args.secret or ""
    if secret:
        log.info("Auth ENABLED — clients must send {type:auth, token:<secret>} as first frame")
    else:
        log.warning(
            "Auth DISABLED — server is open to anyone on the network. "
            "Pass --secret <token> to enable authentication."
        )

    log.info(f"Starting offload server on {args.host}:{args.port}")
    log.info("Android app can connect at:")
    log.info(f"  ws://<this-machine-ip>:{args.port}/offload")

    async with websockets.serve(
        lambda ws: handle_connection(ws, llm, secret),
        args.host,
        args.port,
        ping_interval=20,
        ping_timeout=30,
        max_size=10 * 1024 * 1024,
    ):
        log.info("Server ready — waiting for connections")
        await asyncio.Future()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="ErnOS inference offload server (Mac/PC companion)"
    )
    parser.add_argument(
        "--model",
        required=True,
        help="Path to the GGUF model file (same model as on Android)",
    )
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="Bind address (default: 0.0.0.0 — all interfaces)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8765,
        help="WebSocket port (default: 8765)",
    )
    parser.add_argument(
        "--secret",
        default="",
        help=(
            "Shared secret token for client authentication. "
            "Generate with: python3 -c \"import secrets; print(secrets.token_hex(32))\""
        ),
    )
    parser.add_argument(
        "--ctx",
        type=int,
        default=8192,
        help="Context window in tokens (default: 8192)",
    )
    parser.add_argument(
        "--gpu-layers",
        type=int,
        default=-1,
        dest="gpu_layers",
        help="Number of layers to offload to GPU (-1 = all, 0 = CPU only)",
    )
    asyncio.run(main(parser.parse_args()))
