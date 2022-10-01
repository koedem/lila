#!/usr/bin/env python3
import argparse
from modules.env import e
import modules.dir as dir
import modules.util as util


def main():
    e.set_args(_get_args())
    dir.walk(e.src_path)


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
