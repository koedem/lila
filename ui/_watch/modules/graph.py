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

module_filter = [
    "lichess_pgn_viewer",
    "chess.js",
    "chessground",
    "chessops",
    "mithril",
]
deps = {}


def walk(p: Path):
    files = GraphBuilder(p)
    for name in deps.keys():
        for dep in deps[name]:
            print(f"{name} -> {dep}")


careabout = [".ts", ".json", ".mjs", ".tsconfig", ".scss"]

ignore = [
    "build",
    "@build",
    "@types",
    "_watch",
    "node_modules",
]
rollupForYamlRe = re.compile(r"rollupProject\((\{.+})\);", re.DOTALL)
rollupExcludePluginsYamlRe = re.compile(
    r"(?m)^    plugins: \[$.+^    ],$", re.DOTALL
)


class GraphBuilder:
    def __init__(self, node: Path):
        self.node = node
        for file in node.iterdir():
            if (
                file.is_dir()
                and file.name[0] != "."
                and file.name not in ignore
            ):
                module_filter.append(file.name)
                print(file.name)
        self.build(node)

    def build(self, file: Path):
        if file.suffix == ".mjs":
            self.rollup_config(file)
        elif file.name == "package.json":
            self.package_json(file)
        elif file.suffix == ".ts":
            self.module_src(file)
        elif file.is_dir():
            for f in file.iterdir():
                self.build(f)

    def module_src(self, file: Path):
        module = file.relative_to(e.src_path).parts[0]
        print(module)

    def package_json(self, file: Path):
        dep_json = json.loads(file.read_text())
        # print(dep_json)
        try:
            deps = dep_json["dependencies"]
            deps[file.parent.name] = filter(
                lambda dep: dep in module_filter, deps
            )

        except BaseException:
            print(f"{file.as_posix()}")

    def rollup_config(self, file: Path):
        txt = file.read_text()
        exclude = rollupExcludePluginsYamlRe.search(txt)
        if exclude != None:
            txt = txt[: exclude.start()] + txt[exclude.end() :]
            # print(txt)
        match = rollupForYamlRe.search(txt)
        if match != None:
            try:
                rollups.append(yaml.load(match.group(1), Loader=Loader))
            except BaseException:
                print(f"Error parsing:  {file.as_posix()}")


#                        print(json)
