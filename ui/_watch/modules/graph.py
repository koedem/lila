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
deps = {}


def walk(p: Path):
    files = Node(p)
    for name in deps.keys():
        for dep in deps[name]:
            print(f"{name} -> {dep}")


careabout = [".ts", ".json", ".mjs", ".tsconfig", ".scss"]

ignore = [
    "build",
    "@build",
    "@types",
    "_watch",
]
rollupForYamlRe = re.compile(r"rollupProject\((\{.+})\);", re.DOTALL)
rollupExcludePluginsYamlRe = re.compile(
    r"(?m)^    plugins: \[$.+^    ],$", re.DOTALL
)


class Node:
    def __init__(self, node: Path):
        self.node = node
        if node.is_dir():
            self.files = {}
            for file in node.iterdir():
                if file.is_dir() and file.name not in ignore:
                    self.files[file.name] = Node(file)
                elif file.suffix == ".mjs":
                    self.rollup_config(file)
                elif file.name == "package.json":
                    self.package_json(file)

    def package_json(self, file: Path):
        dep_json = json.loads(file.read_text())
        # print(dep_json)
        try:
            deps[file.parent.name] = dep_json["dependencies"]
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
                print(
                    f"Broken watch:  Error parsing rollup config:  {file.as_posix()}"
                )


#                        print(json)
