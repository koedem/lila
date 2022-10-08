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
touch_map = {}
module_filter = [
    "lichess_pgn_viewer",
    "chess.js",
    "chessground",
    "chessops",
    "mithril",
]
modules = {}
module_build = []


def walk(p: Path):
    files = ModuleParser(p)
    for name in modules.keys():
        for dep in modules[name]["deps"]:
            # print(f"{name} -> {dep}")
            pass


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


class ModuleParser:
    def __init__(self, node: Path):
        self.node = node
        for file in node.iterdir():
            if (
                file.is_dir()
                and file.name[0] != "."
                and file.name not in ignore
            ):
                module_filter.append(file.name)
                # print(file.name)
        self.build_graph(node)

    def build_graph(self, file: Path):
        if file.name == "rollup.config.mjs":
            self.rollup_config(file)
        elif file.name == "package.json":
            self.package_json(file)
        elif file.suffix == ".ts":
            self.module_src(file)
        elif file.is_dir() and file.name[0] != "." and file.name not in ignore:
            for f in sorted(file.iterdir()):
                self.build_graph(f)

    def build_module(self, module: str):
        pass

    def module_src(self, file: Path):
        relpath = e.rel(file)
        module = relpath.parts[0]
        touch_map[relpath] = module
        # print(module)

    def package_json(self, file: Path):
        package = json.loads(file.read_text())
        deps = []
        if "dependencies" in package:
            deps = package["dependencies"]
        modname = file.parent.name
        print(f"{modname} -> {deps}")
        modules[modname] = {}.setdefault()
        module = modules[modname]
        try:
            self.module_scripts(module, package["scripts"])
            self.module_triggers(
                module, filter(lambda d: d not in module_filter, deps)
            )
            # if e.force:
            #     for v in build.values():
            #         v.remove("--incremental")
            for script in module["build"].keys():
                print(f"{modname}: {script} -> {module['build'][script]}")
            module["cwd"] = file.parent
            module["triggers"] = []

        except BaseException as err:
            print(f"[{modname}] package.json error: {err}")

    def module_triggers(self, module, deps):
        module["deps"] = deps
        for depname in module["deps"]:
            if not depname in module:
                module[depname] = {}
            dep = module[depname]
            if not triggers in dep:
                dep["triggers"] = []
            dep["triggers"].append(modname)
            # modules[dep]

        pass

    def module_scripts(self, module, build):
        build = package["scripts"]
        module["build"] = {}
        # for script in filter(
        #     lambda s: s not in ["prod", "plugin-prod"], build.keys()
        # ):
        if not "dev" in build:
            return
        script = "dev"
        buildlist = module["build"][script] = []
        for cmd in re.split(r" && ", build[script]):
            cmdlist = list(
                filter(
                    lambda arg: arg != "$npm_execpath",
                    cmd.strip().split(),
                )
            )
            if cmdlist[0] == "rollup":
                cmdlist.append("--config-all")
            if cmdlist[0] in ["run", "rollup"]:
                cmdlist.insert(0, "yarn")
            elif cmdlist[0] == "tsc" and not "--incremental" in cmdlist:
                # if this is ok, it should be done in package json not here.  temp hack
                cmdlist.append("--incremental")
            buildlist.append(cmdlist)
        module["build"][script] = buildlist
        # print(buildlist)

    def rollup_config(self, file: Path):
        # rollup.config.mjs is after package.json alphabetically,
        # so we should already have our module def built
        txt = file.read_text()
        exclude = rollupExcludePluginsYamlRe.search(txt)
        if exclude != None:
            txt = txt[: exclude.start()] + txt[exclude.end() :]
            # print(txt)
        match = rollupForYamlRe.search(txt)
        if match == None:
            return

        try:
            rollup = yaml.load(match.group(1), Loader=Loader)
            for target in rollup.keys():
                desc = rollup[target]
                dest = desc["output"]
                src = desc["input"]
                # name = desc["name"]

            rollups.append(yaml.load(match.group(1), Loader=Loader))
        except BaseException as err:
            print(f"[{modname}] rollup.config.mjs error: {err}")
