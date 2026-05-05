#!/usr/bin/env python3
"""
remote_control.py — Envoie une tâche à Claude Code sur le projet Podmix.

Usage:
    python remote_control.py "build et installe l'app"
    python remote_control.py "corrige l'erreur dans AddRadioScreen.kt"
    echo "ajoute un log dans PodcastsScreen" | python remote_control.py
"""

import sys
import os
import anyio
from claude_agent_sdk import (
    query,
    ClaudeAgentOptions,
    ResultMessage,
    AssistantMessage,
    TextBlock,
    CLINotFoundError,
    CLIConnectionError,
)

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))

SYSTEM_PROMPT = """Tu travailles sur le projet Android Kotlin Podmix (C:/APP/Podmix).
Stack : Jetpack Compose, Hilt, Room, Kotlin Coroutines, Coil.
Pour builder et installer : ./gradlew installDebug --no-daemon
Le téléphone Samsung SM-S916B est connecté en USB avec ADB.
Sois concis et direct. Exécute la tâche sans demander de confirmation sauf si vraiment nécessaire."""


async def run(task: str) -> None:
    print(f"\n▶ Tâche : {task}\n{'─' * 60}")

    async for message in query(
        prompt=task,
        options=ClaudeAgentOptions(
            cwd=PROJECT_DIR,
            allowed_tools=["Read", "Edit", "Write", "Bash", "Glob", "Grep"],
            permission_mode="bypassPermissions",
            system_prompt=SYSTEM_PROMPT,
            max_turns=30,
        ),
    ):
        if isinstance(message, AssistantMessage):
            for block in message.content:
                if isinstance(block, TextBlock) and block.text.strip():
                    print(block.text, flush=True)

        elif isinstance(message, ResultMessage):
            print(f"\n{'─' * 60}")
            print(f"✓ Terminé  (stop: {message.stop_reason})")


def main() -> None:
    # Tâche depuis args CLI ou stdin
    if len(sys.argv) > 1:
        task = " ".join(sys.argv[1:])
    elif not sys.stdin.isatty():
        task = sys.stdin.read().strip()
    else:
        print("Usage: python remote_control.py \"<tâche>\"")
        print('       echo "<tâche>" | python remote_control.py')
        sys.exit(1)

    if not task:
        print("Erreur : tâche vide.")
        sys.exit(1)

    try:
        anyio.run(run, task)
    except CLINotFoundError:
        print("✗ Claude Code CLI introuvable. Installe : pip install claude-agent-sdk")
        sys.exit(1)
    except CLIConnectionError as e:
        print(f"✗ Erreur de connexion : {e}")
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n⚠ Interrompu.")
        sys.exit(0)


if __name__ == "__main__":
    main()
