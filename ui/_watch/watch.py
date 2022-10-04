#!/usr/bin/env python3
import sys
import time
import logging
import argparse
from modules.env import e
from watchdog.observers import Observer
from watchdog.events import LoggingEventHandler, PatternMatchingEventHandler
import modules.graph as graph
import modules.util as util


def main():
    e.set_args(_get_args())
    graph.walk(e.src_path)
    print(e.src_path)


"""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    path = "/Users/gamblej/ws/lichess/lila-local/node_modules"  # e.src_path
    event_handler = PatternMatchingEventHandler()
    observer = Observer()
    observer.schedule(event_handler, path, recursive=True)
    observer.start()
    try:
        while True:
            time.sleep(1)
    finally:
        observer.stop()
        observer.join()
"""

node_modules_watch = [
    "lichess_pgn_viewer",
    "chess.js",
    "chessground",
    "chessops",
    "mithril",
]


class UpdateHandler(PatternMatchingEventHandler):
    def __init__(self):
        super(UpdateHandler, self).__init__(
            patterns=[".*", "app", "modules", ""]
        )


def _get_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="""
            description
        """
    )
    parser.add_argument(
        "--1",
        "-1",
        help="""
            help
        """,
        default="",
    )
    parser.add_argument(
        "--2",
        "-2",
        type=int,
        help="""
            help
        """,
        default=0,
        choices=[0, 1, 2],
    )
    parser.add_argument(
        "--bool",
        "-b",
        action="store_true",
        help="""
        """,
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--option1",
        type=str,
        help="""
            help
        """,
    )
    group.add_argument(
        "--option2",
        type=str,
        help="""
            help
        """,
    )
    return parser.parse_args()


if __name__ == "__main__":
    main()
