import random
import os
import subprocess
import base64
import re
import argparse
import json
import yaml
import modules.util as util
from modules.env import e
from datetime import datetime
from pathlib import Path
from yaml import Loader


flatmap = {}
rollups = []


def walk(p: Path):
    files = Node(p)
    for r in rollups:
        print(r)


careabout = [".ts", ".json", ".mjs", ".tsconfig", ".scss"]

ignore = [
    "build",
    "@build",
    "@types",
    "_watch",
]
rollupJsonRe = re.compile(r"rollupProject\((\{.+})\);", re.DOTALL)


class Node:
    def __init__(self, node: Path):
        self.node = node
        if node.is_dir():
            self.files = {}
            for file in node.iterdir():
                if file.is_dir() and file.name not in ignore:
                    self.files[file.name] = Node(file)
                elif file.suffix == ".mjs":
                    # print(file.read_text())
                    match = rollupJsonRe.search(file.read_text())
                    if match != None:
                        try:
                            rollups.append(
                                yaml.load(match.group(1), Loader=Loader)
                            )
                        except BaseException:
                            print("ohnoes")


#                        print(json)
